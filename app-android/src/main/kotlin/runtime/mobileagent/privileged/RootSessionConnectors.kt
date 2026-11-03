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
import runtime.mobileagent.domain.RootServiceTerminalOutcome
import runtime.mobileagent.shizuku.ShizukuAuthorityState
import runtime.mobileagent.skills.tooling.ShellExecRequest
import runtime.mobileagent.skills.tooling.ShellExecutionStatus

data class RootAcquiredSession(
    val identity: PrivilegedSessionIdentity,
    val caller: RootCallerObservation,
    val transport: RootChannelTransport? = null,
    val controlPlane: RootControlPlaneBoundary? = null,
    /** Token of the acquisition attempt that produced this session (see [isAcquisitionCurrent]). */
    val attemptToken: Long = 0L,
)

interface RootSessionConnector {
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

data class RootShellRunOutcome(
    val status: ShellExecutionStatus,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val terminalOutcome: RootServiceTerminalOutcome,
    val requiresDegradedEpoch: Boolean = false,
) {
    override fun toString(): String =
        "RootShellRunOutcome(status=$status,exitCode=$exitCode,terminalOutcome=$terminalOutcome,redacted=true)"
}

interface RootShellCommandRunner {
    fun runCommand(
        authority: Authority,
        request: ShellExecRequest,
    ): RootShellRunOutcome

    fun cancel(requestId: String): Boolean

    /**
     * Terminates every in-flight process group and returns the request ids whose whole group
     * (and observed escapees) were confirmed gone within the budget. Unconfirmed ids stay
     * unconfirmed so the forced shutdown reports `UNKNOWN_OUTCOME`/`DEGRADED`.
     */
    fun terminateAll(deadlineMs: Long): Set<String> = emptySet()

    fun activeRequestIds(): Set<String> = emptySet()

    fun unconfirmedRequestIds(): Set<String> = emptySet()
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
    private class AcquireAttempt(val generation: Long) {
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
    ): RootAcquiredSession? {
        if (connectorClosed.get()) return null
        val attempt = AcquireAttempt(attemptCounter.incrementAndGet())
        val superseded = synchronized(stateLock) {
            if (connectorClosed.get()) return null
            activeAttempts.put(authority, attempt)
        }
        superseded?.let { stale ->
            stale.cancelled = true
            runCatching { stale.transport?.close() }
        }
        lastAcquireFailureReason = null
        // Explicit foreground authorization gates this call (coordinator.requestSessionFromForeground);
        // factories are test seams only. Identity failures still reject below.
        val bootstrap = observeVerifiedHostBootstrap(authority, identityEpoch, appInstanceId)
            ?: return null.also {
                if (lastAcquireFailureReason == null) noteFailure(authority, attempt, "bootstrap_null")
            }
        val runtime = channel(authority)
        val acquired = when (authority) {
            Authority.ROOT_DIRECT -> connectRootDirectService(authority, bootstrap, runtime, attempt)
            Authority.SHIZUKU_ROOT -> connectShizukuRootService(authority, bootstrap, shizukuState, runtime, attempt)
            else -> null
        } ?: return null
        // Ownership stays with `attempt` until the coordinator completes or aborts it.
        return acquired.copy(attemptToken = attempt.generation)
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
        runCatching { attempt.transport?.close() }
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
        runCatching { attempt.transport?.close() }
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
        attempt?.let { runCatching { it.transport?.close() } }
        val runtime = channels.remove(authority) ?: return
        runtime.bindGuard.unbindAndInvalidate()
        runtime.transport?.let { runCatching { it.close() } }
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
        val launch = RootServiceLaunchIdentity(
            appUid = req.appUid,
            androidUserId = req.androidUserId,
            packageName = req.packageName,
            identityEpoch = req.identityEpoch,
        )
        // Foreground acquisition binds the provider first: the pinned `su` path must identify
        // itself as a supported MagiskSU, otherwise no service is launched at all.
        val provider = if (directTransportFactory == null) {
            RootDirectProviderProbe.resolve(isCancelled = { !stillOwned(Authority.ROOT_DIRECT, attempt) })
                ?: return null.also {
                    noteFailure(Authority.ROOT_DIRECT, attempt, "unsupported_root_provider")
                }
        } else {
            null
        }
        // A cancellation that landed while probing must not launch `su` afterwards.
        if (!stillOwned(Authority.ROOT_DIRECT, attempt)) return null
        val transport = (directTransportFactory?.invoke(bootstrap.apkPath, bootstrap.caller.callingUid)
            ?: SuRootDaemonTransport.startSuProcess(bootstrap.apkPath, launch, provider!!.path))
            ?: return null.also { noteFailure(Authority.ROOT_DIRECT, attempt, "direct_transport_null") }
        attempt.transport = transport
        // A cancellation that landed before the transport existed must win here, before any RPC.
        if (!stillOwned(authority, attempt)) {
            runCatching { transport.close() }
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
            runCatching { transport.close() }
            return null
        }
        return completeTransportHandshake(Authority.SHIZUKU_ROOT, bootstrap, transport, runtime, attempt)
    }

    /** A caller-initiated cancellation is the authoritative reason; a later rejection must not mask it. */
    private fun noteFailure(reason: String) {
        if (lastAcquireFailureReason != ACQUISITION_CANCELLED) lastAcquireFailureReason = reason
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
            runCatching { transport.close() }
            return null
        }
        val identity = response?.identity
        val valid = response?.accepted == true && identity != null &&
            RootShizukuBridgePolicy.evaluateRootHandshake(bootstrap.expectation, identity) is RootBridgeGateDecision.Allowed
        if (!valid) {
            noteFailure(authority, attempt, "handshake_rejected:accepted=${response?.accepted}:reason=${response?.errorReason}:hasIdentity=${identity != null}")
            runCatching { transport.close() }
            return null
        }
        // Atomic publish with cancel/close: the decision and the channel assignment happen under
        // one short lock; the transport is closed outside it when the attempt already lost.
        val published = synchronized(stateLock) {
            if (attempt.cancelled || connectorClosed.get() || activeAttempts[authority] !== attempt) {
                false
            } else {
                runtime.transport = transport
                true
            }
        }
        if (!published) {
            runCatching { transport.close() }
            return null
        }
        return RootAcquiredSession(
            identity = identity,
            caller = bootstrap.caller,
            transport = transport,
        )
    }

    companion object {
        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }
}

/**
 * Direct `UID 0` service shell entry backed by [RootSupervisedShellRunner] (`PR_SET_CHILD_SUBREAPER`
 * + `PR_SET_PDEATHSIG` + `setsid` + `waitpid(-1)` until `ECHILD`).
 *
 * When constructed with only an unproven sampled [RootShellGroupRunner] or when the kernel/runtime
 * cannot prove subreaper support prior to spawn, [runCommand] fails closed before spawning any
 * process (`status = FAILED`, `requiresDegradedEpoch = false`).
 */
class Uid0ProcessGroupShellRunner internal constructor(
    private val groupRunner: RootShellGroupRunner,
    private val supervisedRunner: RootSupervisedShellRunner? = null,
) : RootShellCommandRunner {
    constructor() : this(
        groupRunner = RootShellGroupRunner(),
        supervisedRunner = RootSupervisedShellRunner(),
    )

    internal constructor(supervisedRunner: RootSupervisedShellRunner) : this(
        groupRunner = RootShellGroupRunner(),
        supervisedRunner = supervisedRunner,
    )

    override fun runCommand(
        authority: Authority,
        request: ShellExecRequest,
    ): RootShellRunOutcome {
        if (authority != Authority.ROOT_DIRECT && authority != Authority.SHIZUKU_ROOT) {
            return RootShellRunOutcome(
                status = ShellExecutionStatus.FAILED,
                terminalOutcome = RootServiceTerminalOutcome.FAILED,
                requiresDegradedEpoch = false,
            )
        }
        val supervisor = supervisedRunner ?: return RootShellRunOutcome(
            status = ShellExecutionStatus.FAILED,
            terminalOutcome = RootServiceTerminalOutcome.FAILED,
            requiresDegradedEpoch = false,
        )
        return supervisor.execute(request)
    }

    override fun cancel(requestId: String): Boolean =
        supervisedRunner?.cancel(requestId) ?: groupRunner.cancel(requestId)

    override fun terminateAll(deadlineMs: Long): Set<String> =
        supervisedRunner?.terminateAll(deadlineMs) ?: groupRunner.terminateAll(deadlineMs)

    override fun activeRequestIds(): Set<String> =
        supervisedRunner?.activeRequestIds() ?: groupRunner.activeRequestIds()

    override fun unconfirmedRequestIds(): Set<String> =
        supervisedRunner?.unconfirmedRequestIds() ?: groupRunner.unconfirmedRequestIds()
}

/**
 * [Authority.ROOT_DIRECT]-only `su` process-group shell runner.
 * Rejects any non-[Authority.ROOT_DIRECT] authority without starting `su`.
 */
class SuProcessGroupShellRunner internal constructor(
    private val groupRunner: RootShellGroupRunner,
) : RootShellCommandRunner {
    constructor() : this(RootShellGroupRunner())

    override fun runCommand(
        authority: Authority,
        request: ShellExecRequest,
    ): RootShellRunOutcome {
        if (authority != Authority.ROOT_DIRECT) {
            return RootShellRunOutcome(
                status = ShellExecutionStatus.FAILED,
                terminalOutcome = RootServiceTerminalOutcome.FAILED,
            )
        }
        val builder = ProcessBuilder(
            "su",
            "-c",
            "exec setsid sh -c \"\$1\"",
            "sh",
            request.command,
        )
        return groupRunner.execute(builder, request)
    }

    override fun cancel(requestId: String): Boolean = groupRunner.cancel(requestId)

    override fun terminateAll(deadlineMs: Long): Set<String> = groupRunner.terminateAll(deadlineMs)

    override fun activeRequestIds(): Set<String> = groupRunner.activeRequestIds()

    override fun unconfirmedRequestIds(): Set<String> = groupRunner.unconfirmedRequestIds()
}
