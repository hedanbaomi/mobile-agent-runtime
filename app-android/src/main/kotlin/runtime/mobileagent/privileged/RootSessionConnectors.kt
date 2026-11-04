// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RootCallerObservation
import runtime.mobileagent.domain.RootControlPlaneBoundary
import runtime.mobileagent.domain.RootKernelBootIdentity
import runtime.mobileagent.domain.isRootAuthority
import runtime.mobileagent.shizuku.ShizukuAuthorityState

data class RootAcquiredSession(
    val identity: PrivilegedSessionIdentity,
    val caller: RootCallerObservation,
    val transport: RootChannelTransport? = null,
    val controlPlane: RootControlPlaneBoundary? = null,
    /** Token of the acquisition attempt that produced this session (see [isAcquisitionCurrent]). */
    val attemptToken: Long = 0L,
    /** Accepted only from the identity-verified service response, never from caller settings. */
    val kernelBootId: String? = null,
    val kernelElapsedRealtimeMs: Long? = null,
)

/** A completed, already-closed foreground probe; it conveys no dispatchable transport. */
class RootBootObservation internal constructor(
    val identity: PrivilegedSessionIdentity,
    val kernelBootId: String,
) {
    init {
        require(identity.authority.isRootAuthority && identity.serviceUid == 0 && identity.privilegeTier == PrivilegeTier.ROOT)
        require(RootKernelBootIdentity.isValid(kernelBootId))
    }
}

interface RootSessionConnector {
    /** Explicit recovery-only observation; unsupported/old connectors provide no boot proof. */
    fun probeBootIdentity(
        authority: Authority,
        identityEpoch: Long,
        appInstanceId: String,
        shizukuState: ShizukuAuthorityState?,
    ): RootBootObservation? = null
    /**
     * True while the acquisition identified by [attemptToken] is still current (not cancelled and
     * not superseded). The coordinator checks this immediately before it publishes the session, so
     * a cancellation that races the handshake can never install a transport.
     */
    fun isAcquisitionCurrent(authority: Authority, attemptToken: Long): Boolean = true

    /**
     * Takes ownership of a completed acquisition. Returns false when it was cancelled, closed or
     * superseded while the coordinator was attaching; the caller must then drop the transport.
     */
    fun completeAcquisition(authority: Authority, attemptToken: Long): Boolean = true

    /** Releases an acquisition the coordinator refused to attach. */
    fun abortAcquisition(authority: Authority, attemptToken: Long) {}

    /** Cancels an acquisition that is still waiting for the platform decision. */
    fun cancelPendingAcquisition(authority: Authority): Boolean = false

    fun tryAcquireSession(
        authority: Authority,
        identityEpoch: Long,
        appInstanceId: String,
        shizukuState: ShizukuAuthorityState?,
    ): RootAcquiredSession?
}

/**
 * Production [RootSessionConnector] that connects to a persistent controlled `UID 0` service:
 * - [Authority.ROOT_DIRECT]: spawns [RootPrivilegedProcessMain] via [SuRootDaemonTransport]
 * - [Authority.SHIZUKU_ROOT]: binds [RootShizukuUserService] via [ShizukuRootBinderTransport]
 * and completes a verified protocol v4 handshake inside the `UID 0` service.
 *
 * Each Root channel owns an independent bind generation and transport. A disconnect, rebind or
 * failure on one channel can never invalidate or close the other channel's service.
 */
private const val ACQUISITION_CANCELLED = "acquisition_cancelled"


class AndroidRootSessionConnector(
    private val context: Context,
    private val directTransportFactory: ((String, Int) -> RootChannelTransport?)? = null,
    private val shizukuTransportFactory: ((Context, RootBindGenerationGuard) -> RootChannelTransport?)? = null,
) : RootSessionConnector {
    private class ChannelRuntime {
        val bindGuard = RootBindGenerationGuard()

        @Volatile
        var transport: RootChannelTransport? = null
    }

    private val channels = ConcurrentHashMap<Authority, ChannelRuntime>()

    /**
     * One in-flight acquisition per authority.
     *
     * Ownership is explicit: an attempt stays registered from `tryAcquireSession` until the
     * coordinator takes it over ([completeAcquisition]), aborts it, or it is cancelled/closed.
     * Every "is this attempt still current" decision runs under [stateLock]; RPC calls and
     * transport closes never run inside that lock.
     */
    private class AcquireAttempt(val generation: Long, val probeOnly: Boolean) {
        val transportClosed = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile
        var cancelled: Boolean = false

        @Volatile
        var transport: RootChannelTransport? = null
    }

    private val attemptCounter = java.util.concurrent.atomic.AtomicLong(0L)
    private val activeAttempts = ConcurrentHashMap<Authority, AcquireAttempt>()
    private val connectorClosed = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Short ordering lock for cancel/close/publish. Never held across RPC or close(2). */
    private val stateLock = Any()

    @Volatile
    var lastAcquireFailureReason: String? = null
        private set

    private data class VerifiedHostBootstrap(
        val expectation: PinnedAppBootstrapExpectation,
        val caller: RootCallerObservation,
        val handshakeRequest: RootServiceHandshakeRequest,
        val apkPath: String,
    )

    override fun tryAcquireSession(
        authority: Authority,
        identityEpoch: Long,
        appInstanceId: String,
        shizukuState: ShizukuAuthorityState?,
    ): RootAcquiredSession? = acquireSession(authority, identityEpoch, appInstanceId, shizukuState, probeOnly = false)

    override fun probeBootIdentity(
        authority: Authority,
        identityEpoch: Long,
        appInstanceId: String,
        shizukuState: ShizukuAuthorityState?,
    ): RootBootObservation? {
        val acquired = acquireSession(authority, identityEpoch, appInstanceId, shizukuState, probeOnly = true) ?: return null
        val bootId = acquired.kernelBootId ?: return null
        return RootBootObservation(acquired.identity, bootId)
    }

    private fun acquireSession(
        authority: Authority,
        identityEpoch: Long,
        appInstanceId: String,
        shizukuState: ShizukuAuthorityState?,
        probeOnly: Boolean,
    ): RootAcquiredSession? {
        if (connectorClosed.get()) return null
        val attempt = AcquireAttempt(attemptCounter.incrementAndGet(), probeOnly)
        val superseded = synchronized(stateLock) {
            if (connectorClosed.get()) return null
            activeAttempts.put(authority, attempt)
        }
        superseded?.let { stale ->
            stale.cancelled = true
            closeAttemptTransport(stale)
        }
        var runtime: ChannelRuntime? = null
        var retained = false
        try {
            lastAcquireFailureReason = null
            // Only explicit foreground acquisition/recovery invokes this path.
            val bootstrap = observeVerifiedHostBootstrap(authority, identityEpoch, appInstanceId)
                ?: return null.also {
                    if (lastAcquireFailureReason == null) noteFailure(authority, attempt, "bootstrap_null")
                }
            // A probe cannot replace/publish a normal channel or invalidate its Binder guard.
            val channelRuntime = if (probeOnly) ChannelRuntime() else channel(authority)
            runtime = channelRuntime
            val acquired = when (authority) {
                Authority.ROOT_DIRECT -> connectRootDirectService(authority, bootstrap, channelRuntime, attempt)
                Authority.SHIZUKU_ROOT -> connectShizukuRootService(authority, bootstrap, shizukuState, channelRuntime, attempt)
                else -> null
            } ?: return null
            // Normal ownership remains with the attempt until the coordinator takes it over.
            if (!stillOwned(authority, attempt)) return null
            retained = !probeOnly
            return acquired.copy(attemptToken = attempt.generation)
        } finally {
            if (!retained) {
                runCatching { abortAcquisition(authority, attempt.generation) }
                // A newer attempt may already have removed this attempt from the map.
                closeAttemptTransport(attempt)
                if (probeOnly) runCatching { runtime?.bindGuard?.unbindAndInvalidate() }
            }
        }
    }

    private fun closeAttemptTransport(attempt: AcquireAttempt) {
        val transport = attempt.transport ?: return
        if (attempt.transportClosed.compareAndSet(false, true)) runCatching { transport.close() }
    }

    /** Records a failure only for the attempt that still owns this authority. */
    private fun noteFailure(authority: Authority, attempt: AcquireAttempt, reason: String) {
        synchronized(stateLock) {
            if (!attempt.cancelled && activeAttempts[authority] === attempt) {
                lastAcquireFailureReason = reason
            }
        }
    }

    private fun stillOwned(authority: Authority, attempt: AcquireAttempt): Boolean = synchronized(stateLock) {
        !attempt.cancelled && !connectorClosed.get() && activeAttempts[authority] === attempt
    }

    override fun isAcquisitionCurrent(authority: Authority, attemptToken: Long): Boolean = synchronized(stateLock) {
        val attempt = activeAttempts[authority] ?: return@synchronized false
        attempt.generation == attemptToken && !attempt.cancelled && !connectorClosed.get()
    }

    /**
     * Transfers ownership of a completed acquisition to the coordinator. Returns false when the
     * attempt was cancelled, superseded or the connector was closed in the meantime.
     */
    override fun completeAcquisition(authority: Authority, attemptToken: Long): Boolean = synchronized(stateLock) {
        val attempt = activeAttempts[authority] ?: return@synchronized false
        if (attempt.generation != attemptToken || attempt.cancelled || connectorClosed.get()) {
            return@synchronized false
        }
        activeAttempts.remove(authority, attempt)
        true
    }

    /** Releases an acquisition the coordinator refused to attach (identity/confinement failure). */
    override fun abortAcquisition(authority: Authority, attemptToken: Long) {
        val attempt = synchronized(stateLock) {
            val current = activeAttempts[authority] ?: return
            if (current.generation != attemptToken) return
            activeAttempts.remove(authority, current)
            current.cancelled = true
            current
        }
        closeAttemptTransport(attempt)
    }

    internal fun observeVerifiedHostCaller(
        authority: Authority,
        identityEpoch: Long,
        appInstanceId: String,
    ): RootCallerObservation? = observeVerifiedHostBootstrap(authority, identityEpoch, appInstanceId)?.caller

    /**
     * Cancels a direct `su` acquisition that is still waiting for the manager's decision.
     *
     * Closing the transport destroys the launched `su` child, so a late "allow" cannot attach a
     * new UID0 service or revive this attempt. Returns false when no acquisition is in flight.
     */
    override fun cancelPendingAcquisition(authority: Authority): Boolean {
        val attempt = synchronized(stateLock) {
            val current = activeAttempts[authority] ?: return false
            activeAttempts.remove(authority, current)
            current.cancelled = true
            lastAcquireFailureReason = ACQUISITION_CANCELLED
            current
        }
        closeAttemptTransport(attempt)
        return true
    }

    /** Per-channel transport, exposed for reconnect/degrade decisions and deterministic tests. */
    fun activeTransport(authority: Authority): RootChannelTransport? = channels[authority]?.transport

    fun closeChannel(authority: Authority) {
        val attempt = synchronized(stateLock) {
            val current = activeAttempts.remove(authority) ?: return@synchronized null
            current.cancelled = true
            current
        }
        attempt?.let(::closeAttemptTransport)
        val runtime = channels.remove(authority) ?: return
        runtime.bindGuard.unbindAndInvalidate()
        runtime.transport?.takeUnless { it === attempt?.transport }?.let { runCatching { it.close() } }
    }

    fun close() {
        if (!connectorClosed.compareAndSet(false, true)) return
        listOf(Authority.ROOT_DIRECT, Authority.SHIZUKU_ROOT).forEach(::closeChannel)
    }

    private fun channel(authority: Authority): ChannelRuntime =
        channels.computeIfAbsent(authority) { ChannelRuntime() }

    private fun observeVerifiedHostBootstrap(
        authority: Authority,
        identityEpoch: Long,
        appInstanceId: String,
    ): VerifiedHostBootstrap? = runCatching {
        val pkg = context.packageName
        val uid = Process.myUid()
        val userId = uid / 100_000
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageInfo(pkg, flags)
        @Suppress("DEPRECATION")
        val signer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            val s = info.signingInfo ?: return@runCatching null
            if (s.hasMultipleSigners()) return@runCatching null
            s.apkContentsSigners?.singleOrNull()
        } else {
            info.signatures?.singleOrNull()
        } ?: return@runCatching null
        val signingSha256 = sha256Hex(signer.toByteArray())
        val codeFile = File(context.packageCodePath).canonicalFile
        val apkSha256 = PrivilegedIdentityVerifier.cachedFileSha256Hex(codeFile)
        val epoch = identityEpoch.coerceAtLeast(1L)
        val expectation = PinnedAppBootstrapExpectation(
            expectedAuthority = authority,
            expectedAppUid = uid,
            expectedUserId = userId,
            expectedPackageName = pkg,
            expectedSigningSha256 = signingSha256,
            expectedApkSha256 = apkSha256,
            expectedAppInstanceId = appInstanceId,
            expectedIdentityEpoch = epoch,
        )
        val verification = PrivilegedIdentityVerifier.observeAndVerifyFromOs(
            pinnedContext = context,
            callingUid = uid,
            observedServiceUid = PrivilegeTier.ROOT.uid,
            expectation = expectation,
        )
        if (verification !is PrivilegedIdentityVerificationResult.Verified) {
            lastAcquireFailureReason = "host_verify_denied:${(verification as? PrivilegedIdentityVerificationResult.Denied)?.reason}"
            return@runCatching null
        }
        val ceDir = (context.dataDir ?: context.filesDir?.parentFile ?: return@runCatching null)
        val deDir = (context.createDeviceProtectedStorageContext()?.dataDir ?: ceDir)
        val rawCePosix = AndroidRootPosixFileDriver.toPosixAbsolutePath(ceDir.toPath())
        val cePosix = if (userId == 0 && rawCePosix == "/data/data/$pkg") "/data/user/0/$pkg" else rawCePosix
        val dePosix = AndroidRootPosixFileDriver.toPosixAbsolutePath(deDir.toPath())
        val caller = RootCallerObservation(
            callingUid = uid,
            observedServiceUid = PrivilegeTier.ROOT.uid,
            packageName = pkg,
            androidUserId = userId,
            signingSha256 = signingSha256,
            binderAlive = true,
        )
        val req = RootServiceHandshakeRequest(
            authority = authority,
            identityEpoch = epoch,
            appUid = uid,
            androidUserId = userId,
            appInstanceId = appInstanceId,
            packageName = pkg,
            signingSha256 = signingSha256,
            apkSha256 = apkSha256,
            ceDataDir = cePosix,
            deDataDir = dePosix,
        )
        VerifiedHostBootstrap(expectation, caller, req, codeFile.path)
    }.onFailure { err ->
        lastAcquireFailureReason = "bootstrap_exc:${err::class.simpleName}:${err.message}"
    }.getOrNull()

    private fun connectRootDirectService(
        authority: Authority,
        bootstrap: VerifiedHostBootstrap,
        runtime: ChannelRuntime,
        attempt: AcquireAttempt,
    ): RootAcquiredSession? {
        val req = bootstrap.handshakeRequest
        // Provider metadata only selects optional observation; UID0 is verified by handshake.
        val provider = if (directTransportFactory == null) {
            RootDirectProviderProbe.resolve(isCancelled = { !stillOwned(Authority.ROOT_DIRECT, attempt) })
                ?: return null.also {
                    noteFailure(Authority.ROOT_DIRECT, attempt, "su_not_found")
                }
        } else {
            null
        }
        val launch = RootServiceLaunchIdentity(req.appUid, req.androidUserId, req.packageName,
            req.identityEpoch, observeMagiskPolicy = provider?.observeMagiskPolicy == true)
        // A cancellation that landed while probing must not launch `su` afterwards.
        if (!stillOwned(Authority.ROOT_DIRECT, attempt)) return null
        val transport = (directTransportFactory?.invoke(bootstrap.apkPath, bootstrap.caller.callingUid)
            ?: SuRootDaemonTransport.startSuProcess(bootstrap.apkPath, launch, provider!!.path))
            ?: return null.also { noteFailure(Authority.ROOT_DIRECT, attempt, "direct_transport_null") }
        attempt.transport = transport
        // A cancellation that landed before the transport existed must win here, before any RPC.
        if (!stillOwned(authority, attempt)) {
            closeAttemptTransport(attempt)
            return null
        }
        return completeTransportHandshake(authority, bootstrap, transport, runtime, attempt)
    }

    private fun connectShizukuRootService(
        authority: Authority,
        bootstrap: VerifiedHostBootstrap,
        shizukuState: ShizukuAuthorityState?,
        runtime: ChannelRuntime,
        attempt: AcquireAttempt,
    ): RootAcquiredSession? {
        val state = shizukuState ?: return null.also { lastAcquireFailureReason = "shizuku_state_null" }
        val decision = RootShizukuBridgePolicy.evaluateRootShizukuServer(
            selectedAuthority = Authority.SHIZUKU_ROOT,
            binderAlive = state.binderAlive,
            preV11 = state.preV11,
            permissionGranted = state.permissionGranted,
            serverUid = state.serverUid,
            serverVersion = state.apiVersion,
            sessionDegraded = false,
        )
        if (decision !is RootBridgeGateDecision.Allowed || (state.userServiceUid != null && state.userServiceUid != 0)) {
            lastAcquireFailureReason = "shizuku_gate_denied:$decision:userServiceUid=${state.userServiceUid}"
            return null
        }
        val transport = (shizukuTransportFactory?.invoke(context, runtime.bindGuard)
            ?: ShizukuRootBinderTransport.bindLiveShizukuRootService(context, runtime.bindGuard))
            ?: return null.also { noteFailure(Authority.SHIZUKU_ROOT, attempt, "shizuku_bind_null") }
        attempt.transport = transport
        if (!stillOwned(Authority.SHIZUKU_ROOT, attempt)) {
            closeAttemptTransport(attempt)
            return null
        }
        return completeTransportHandshake(Authority.SHIZUKU_ROOT, bootstrap, transport, runtime, attempt)
    }

    private fun completeTransportHandshake(
        authority: Authority,
        bootstrap: VerifiedHostBootstrap,
        transport: RootChannelTransport,
        runtime: ChannelRuntime,
        attempt: AcquireAttempt,
    ): RootAcquiredSession? {
        val startedNanos = System.nanoTime()
        val response = transport.handshake(bootstrap.handshakeRequest)
        if (response == null && transport is SuRootDaemonTransport && transport.lastCallTimedOut) {
            val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L
            noteFailure(authority, attempt, "handshake_timeout:${elapsedMs}ms")
            closeAttemptTransport(attempt)
            return null
        }
        val identity = response?.identity
        val valid = response?.accepted == true && identity != null &&
            RootShizukuBridgePolicy.evaluateRootHandshake(bootstrap.expectation, identity) is RootBridgeGateDecision.Allowed
        if (!valid) {
            noteFailure(authority, attempt, "handshake_rejected:accepted=${response?.accepted}:reason=${response?.errorReason}:hasIdentity=${identity != null}")
            closeAttemptTransport(attempt)
            return null
        }
        // Atomic publish with cancel/close: the decision and the channel assignment happen under
        // one short lock; the transport is closed outside it when the attempt already lost.
        var replaced: RootChannelTransport? = null
        val published = synchronized(stateLock) {
            if (attempt.cancelled || connectorClosed.get() || activeAttempts[authority] !== attempt) {
                false
            } else {
                if (!attempt.probeOnly) {
                    replaced = runtime.transport?.takeUnless { it === transport }
                    runtime.transport = transport
                }
                true
            }
        }
        // Never block cancellation/generation publication on the old transport's shutdown.
        runCatching { replaced?.close() }
        if (!published) {
            closeAttemptTransport(attempt)
            return null
        }
        return RootAcquiredSession(
            identity = identity,
            caller = bootstrap.caller,
            transport = transport,
            kernelBootId = response?.kernelBootId?.takeIf(RootKernelBootIdentity::isValid),
            kernelElapsedRealtimeMs = response?.kernelElapsedRealtimeMs?.takeIf { it > 0L },
        )
    }

    companion object {
        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }
}
