// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Process
import androidx.annotation.Keep
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import rikka.shizuku.Shizuku
import runtime.mobileagent.BuildConfig
import runtime.mobileagent.domain.RootQuiescenceQueryRequest
import runtime.mobileagent.domain.RootRemoteQuiescenceSnapshot

interface RootChannelTransport : AutoCloseable {
    /** Transport liveness only; never substitutes for the authenticated handshake. */
    fun isAlive(): Boolean = true
    fun handshake(request: RootServiceHandshakeRequest): RootServiceHandshakeResponse?

    /**
     * Non-interactive manager-authorization observation for the already-authorized service. Returns
     * the raw JSON answer (identity-bound); transports without a UID0 service return null.
     */
    fun queryManagerAuthorization(requestJson: String): String? = null

    /**
     * Raw workspace-control envelope (`REATTACH` when `phase` is absent, otherwise
     * `PROPOSE/STAGE/CONFIRM/RELEASE`). Transports forward the JSON unchanged and strictly
     * decode the response; unknown fields or a malformed payload fail closed (`null`).
     */
    fun bindWorkspace(requestJson: String): RootServiceBindWorkspaceResponse? = null

    fun bindWorkspace(request: RootServiceBindWorkspaceRequest): RootServiceBindWorkspaceResponse? =
        bindWorkspace(RootPrivilegedServiceEngine.json.encodeToString(request))

    fun submitEnvelope(
        controlSequence: Long,
        rawEnvelopeJson: String,
    ): RootServiceExecuteResponse?
    fun executeAccepted(
        requestId: String,
        payloadBytes: ByteArray = ByteArray(0),
    ): RootServiceExecuteResponse?
    fun submitAndExecute(
        controlSequence: Long,
        rawEnvelopeJson: String,
        payloadBytes: ByteArray = ByteArray(0),
    ): RootServiceExecuteResponse?
    fun cancelCommand(requestId: String): Boolean
    fun cancelInvocation(request: RootCancelInvocationRequest): Boolean = false
    fun queryQuiescence(request: RootQuiescenceQueryRequest): RootRemoteQuiescenceSnapshot? = null
    fun revokeAndShutdown(controlSequence: Long, reason: String): String
}

class EngineRootChannelTransport(
    val engine: RootPrivilegedServiceEngine,
    private val callingUid: Int,
    private val callingPid: Int = -1,
    private val controlTimeoutMs: Long = 5_000L,
    private val executeTimeoutMs: Long = runtime.mobileagent.domain.RootExecutionBudget.MAX_TIMEOUT_MS + 10_000L,
    private val closeTimeoutMs: Long = 1_000L,
) : RootChannelTransport {
    private val callGate = RootBoundedCallGate("mar-engine-root-rpc", controlTimeoutMs)

    override fun handshake(request: RootServiceHandshakeRequest): RootServiceHandshakeResponse? =
        callGate.callBounded(controlTimeoutMs) {
            val raw = engine.handshake(RootPrivilegedServiceEngine.json.encodeToString(request), callingUid, callingPid)
            RootPrivilegedServiceEngine.json.decodeFromString<RootServiceHandshakeResponse>(raw)
        }

    override fun queryManagerAuthorization(requestJson: String): String? =
        callGate.callBounded(controlTimeoutMs) {
            engine.queryManagerAuthorization(requestJson, callingUid)
        }

    override fun bindWorkspace(requestJson: String): RootServiceBindWorkspaceResponse? =
        callGate.callBounded(controlTimeoutMs) {
            val raw = engine.bindWorkspace(requestJson, callingUid)
            RootPrivilegedServiceEngine.json.decodeFromString<RootServiceBindWorkspaceResponse>(raw)
        }

    override fun submitEnvelope(
        controlSequence: Long,
        rawEnvelopeJson: String,
    ): RootServiceExecuteResponse? = callGate.callBounded(controlTimeoutMs) {
        val raw = engine.submitEnvelope(controlSequence, rawEnvelopeJson, callingUid)
        RootPrivilegedServiceEngine.json.decodeFromString<RootServiceExecuteResponse>(raw)
    }

    override fun executeAccepted(
        requestId: String,
        payloadBytes: ByteArray,
    ): RootServiceExecuteResponse? = callGate.callBounded(executeTimeoutMs) {
        val raw = engine.executeAccepted(requestId, payloadBytes, callingUid)
        RootPrivilegedServiceEngine.json.decodeFromString<RootServiceExecuteResponse>(raw)
    }

    override fun submitAndExecute(
        controlSequence: Long,
        rawEnvelopeJson: String,
        payloadBytes: ByteArray,
    ): RootServiceExecuteResponse? = callGate.callBounded(executeTimeoutMs) {
        val raw = engine.submitAndExecute(controlSequence, rawEnvelopeJson, payloadBytes, callingUid)
        RootPrivilegedServiceEngine.json.decodeFromString<RootServiceExecuteResponse>(raw)
    }

    override fun cancelCommand(requestId: String): Boolean =
        callGate.callBounded(controlTimeoutMs) { engine.cancelCommand(requestId, callingUid) } ?: false

    override fun cancelInvocation(request: RootCancelInvocationRequest): Boolean =
        callGate.callBounded(controlTimeoutMs) {
            engine.cancelInvocation(RootPrivilegedServiceEngine.json.encodeToString(request), callingUid)
        } ?: false

    override fun queryQuiescence(request: RootQuiescenceQueryRequest): RootRemoteQuiescenceSnapshot? =
        callGate.callBounded(controlTimeoutMs) {
            val raw = engine.queryQuiescence(RootPrivilegedServiceEngine.json.encodeToString(request), callingUid)
            RootPrivilegedServiceEngine.json.decodeFromString<RootRemoteQuiescenceSnapshot>(raw)
        }

    override fun revokeAndShutdown(controlSequence: Long, reason: String): String =
        callGate.callBounded(controlTimeoutMs) {
            engine.revokeAndShutdown(controlSequence, reason, callingUid)
        } ?: "DISCONNECTED"

    override fun close() {
        callGate.closeBounded(closeTimeoutMs, { engine.close() })
    }
}

/**
 * Binder IPC transport for [runtime.mobileagent.domain.Authority.SHIZUKU_ROOT].
 * Routes all Root file and shell operations over [IRootPrivilegedService] into [RootShizukuUserService]
 * running in Shizuku's `UID 0` process, never invoking `su`.
 */
class ShizukuRootBinderTransport(
    private val service: IRootPrivilegedService,
    private val controlTimeoutMs: Long = 5_000L,
    private val executeTimeoutMs: Long = runtime.mobileagent.domain.RootExecutionBudget.MAX_TIMEOUT_MS + 10_000L,
    private val closeTimeoutMs: Long = 1_000L,
    private val onUnbind: () -> Unit = {},
) : RootChannelTransport {
    private val callGate = RootBoundedCallGate("mar-shizuku-root-rpc", controlTimeoutMs)
    override fun isAlive(): Boolean = !callGate.isClosed && service.asBinder().isBinderAlive

    override fun handshake(request: RootServiceHandshakeRequest): RootServiceHandshakeResponse? =
        callGate.callBounded(controlTimeoutMs) {
            val raw = service.handshake(RootPrivilegedServiceEngine.json.encodeToString(request))
            RootPrivilegedServiceEngine.json.decodeFromString<RootServiceHandshakeResponse>(raw)
        }

    override fun bindWorkspace(requestJson: String): RootServiceBindWorkspaceResponse? =
        callGate.callBounded(controlTimeoutMs) {
            val raw = service.bindWorkspace(requestJson)
            RootPrivilegedServiceEngine.json.decodeFromString<RootServiceBindWorkspaceResponse>(raw)
        }

    override fun submitEnvelope(
        controlSequence: Long,
        rawEnvelopeJson: String,
    ): RootServiceExecuteResponse? = callGate.callBounded(controlTimeoutMs) {
        val raw = service.submitEnvelope(controlSequence, rawEnvelopeJson)
        RootPrivilegedServiceEngine.json.decodeFromString<RootServiceExecuteResponse>(raw)
    }

    override fun executeAccepted(
        requestId: String,
        payloadBytes: ByteArray,
    ): RootServiceExecuteResponse? = callGate.callBounded(executeTimeoutMs) {
        val raw = service.executeAccepted(requestId, payloadBytes)
        RootPrivilegedServiceEngine.json.decodeFromString<RootServiceExecuteResponse>(raw)
    }

    override fun submitAndExecute(
        controlSequence: Long,
        rawEnvelopeJson: String,
        payloadBytes: ByteArray,
    ): RootServiceExecuteResponse? = callGate.callBounded(executeTimeoutMs) {
        val raw = service.submitAndExecute(controlSequence, rawEnvelopeJson, payloadBytes)
        RootPrivilegedServiceEngine.json.decodeFromString<RootServiceExecuteResponse>(raw)
    }

    override fun cancelCommand(requestId: String): Boolean =
        callGate.callBounded(controlTimeoutMs) { service.cancelCommand(requestId) } ?: false

    override fun cancelInvocation(request: RootCancelInvocationRequest): Boolean =
        callGate.callBounded(controlTimeoutMs) {
            service.cancelInvocation(RootPrivilegedServiceEngine.json.encodeToString(request))
        } ?: false

    override fun queryQuiescence(request: RootQuiescenceQueryRequest): RootRemoteQuiescenceSnapshot? =
        callGate.callBounded(controlTimeoutMs) {
            val raw = service.queryQuiescence(RootPrivilegedServiceEngine.json.encodeToString(request))
            RootPrivilegedServiceEngine.json.decodeFromString<RootRemoteQuiescenceSnapshot>(raw)
        }

    override fun revokeAndShutdown(controlSequence: Long, reason: String): String =
        callGate.callBounded(controlTimeoutMs) { service.revokeAndShutdown(controlSequence, reason) } ?: "DISCONNECTED"

    override fun close() {
        callGate.closeBounded(
            closeTimeoutMs,
            { service.destroy() },
            { onUnbind() },
        )
    }

    companion object {
        fun bindLiveShizukuRootService(
            context: Context,
            bindGuard: RootBindGenerationGuard,
            timeoutMs: Long = 10_000L,
        ): ShizukuRootBinderTransport? = runCatching {
            val generation = bindGuard.beginBind()
            val latch = CountDownLatch(1)
            var boundService: IRootPrivilegedService? = null
            val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, RootShizukuUserService::class.java.name))
                .daemon(false)
                .processNameSuffix(RootShizukuBridgePolicy.ROOT_USER_SERVICE_TAG)
                .debuggable(false)
                .version(6)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder != null && binder.isBinderAlive) {
                        boundService = IRootPrivilegedService.Stub.asInterface(binder)
                    }
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    bindGuard.acceptDisconnectOrDeath(generation)
                }
            }
            val bindInitiated = RootBoundedCallGate.runBoundedOnce("mar-shizuku-bind", timeoutMs) {
                Shizuku.bindUserService(args, connection)
                true
            } ?: false
            if (bindInitiated) {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            }
            val svc = boundService ?: run {
                bindGuard.acceptDisconnectOrDeath(generation)
                RootBoundedCallGate.runBoundedOnce("mar-shizuku-unbind", 1_000L) {
                    runCatching { Shizuku.unbindUserService(args, connection, false) }
                    runCatching { Shizuku.unbindUserService(args, connection, true) }
                }
                return@runCatching null
            }
            ShizukuRootBinderTransport(svc) {
                bindGuard.acceptDisconnectOrDeath(generation)
                runCatching { Shizuku.unbindUserService(args, connection, false) }
                runCatching { Shizuku.unbindUserService(args, connection, true) }
            }
        }.getOrNull()
    }
}

/**
 * Shizuku `UID 0` UserService endpoint for [runtime.mobileagent.domain.Authority.SHIZUKU_ROOT].
 */
@Keep
class RootShizukuUserService(context: Context?) : IRootPrivilegedService.Stub() {
    private val osFacts: RootOsIdentityFacts? =
        context?.let { AndroidRootOsIdentityFacts.userServiceFacts(it) }
            ?: AndroidRootOsIdentityFacts.systemProcessFacts()

    @Volatile
    private var lastBootstrapRejection: String? = null

    private val engine = RootPrivilegedServiceEngine(
        onStopService = { runCatching { exitProcess(0) } },
        hostBootstrapProvider = { callingUid -> pinnedHostBootstrap(callingUid) },
    ).also { it.armLifecycleWatchdog() }

    @Keep
    constructor() : this(null)

    /**
     * Fixed application id resolved against the OS; the first caller's uid never establishes
     * identity. A uid/package that does not resolve back to this installed package is rejected.
     */
    private fun pinnedHostBootstrap(callingUid: Int): RootServiceHostBootstrap? {
        val userId = if (callingUid > 0) callingUid / 100_000 else 0
        val primary = osFacts ?: AndroidRootOsIdentityFacts.systemProcessFacts()
        if (primary == null) {
            lastBootstrapRejection = "facts_null"
            return null
        }
        val firstObs = RootServiceBootstrap.observeFixedHost(BuildConfig.APPLICATION_ID, userId, primary)
        if (firstObs is RootBootstrapObservation.Pinned) return firstObs.bootstrap
        val fallbackFacts = AndroidRootOsIdentityFacts.systemProcessFacts()
        if (fallbackFacts != null && fallbackFacts !== primary) {
            val secondObs = RootServiceBootstrap.observeFixedHost(BuildConfig.APPLICATION_ID, userId, fallbackFacts)
            if (secondObs is RootBootstrapObservation.Pinned) return secondObs.bootstrap
        }
        lastBootstrapRejection = (firstObs as? RootBootstrapObservation.Rejected)?.reason ?: "unknown"
        return null
    }

    override fun handshake(requestJson: String): String {
        val raw = engine.handshake(requestJson, Binder.getCallingUid(), Binder.getCallingPid())
        val rej = lastBootstrapRejection
        return if (rej != null && raw.contains("\"trusted_bootstrap_required\"")) {
            raw.replace("\"trusted_bootstrap_required\"", "\"trusted_bootstrap_required:$rej\"")
        } else {
            raw
        }
    }

    override fun bindWorkspace(requestJson: String): String =
        engine.bindWorkspace(requestJson, Binder.getCallingUid())

    override fun submitEnvelope(controlSequence: Long, rawEnvelopeJson: String): String =
        engine.submitEnvelope(controlSequence, rawEnvelopeJson, Binder.getCallingUid())

    override fun executeAccepted(requestId: String, payloadBytes: ByteArray?): String =
        engine.executeAccepted(requestId, payloadBytes ?: ByteArray(0), Binder.getCallingUid())

    override fun submitAndExecute(
        controlSequence: Long,
        rawEnvelopeJson: String,
        payloadBytes: ByteArray?,
    ): String = engine.submitAndExecute(
        controlSequence = controlSequence,
        rawEnvelopeJson = rawEnvelopeJson,
        payloadBytes = payloadBytes ?: ByteArray(0),
        observedCallingUid = Binder.getCallingUid(),
    )

    override fun cancelCommand(requestId: String): Boolean =
        engine.cancelCommand(requestId, Binder.getCallingUid())

    override fun cancelInvocation(requestJson: String): Boolean =
        engine.cancelInvocation(requestJson, Binder.getCallingUid())

    override fun queryQuiescence(requestJson: String): String =
        engine.queryQuiescence(requestJson, Binder.getCallingUid())

    override fun revokeAndShutdown(controlSequence: Long, reason: String): String =
        engine.revokeAndShutdown(controlSequence, reason, Binder.getCallingUid())

    override fun destroy() {
        engine.close()
        exitProcess(0)
    }
}

@Serializable
data class RootPipeRpcFrame(
    val rpcId: Long = 0L,
    val method: String,
    val requestJson: String = "",
    val controlSequence: Long = 0L,
    val payloadBase64: String = "",
    /** Non-secret liveness hint for the in-service lifecycle watchdog. */
    val controllerPid: Int = -1,
)

@Serializable
data class RootPipeRpcReply(
    val rpcId: Long = 0L,
    val responsePayload: String = "",
)

/**
 * Persistent non-daemon `UID 0` pipe transport for [runtime.mobileagent.domain.Authority.ROOT_DIRECT].
 */
class SuRootDaemonTransport(
    private val process: java.lang.Process? = null,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val controlTimeoutMs: Long = 5_000L,
    private val executeTimeoutMs: Long = runtime.mobileagent.domain.RootExecutionBudget.MAX_TIMEOUT_MS + 10_000L,
    private val writeTimeoutMs: Long = 2_000L,
    private val closeTimeoutMs: Long = 1_000L,
) : RootChannelTransport {
    private val nextRpcId = AtomicLong(0L)
    private val pendingReplies = ConcurrentHashMap<Long, CompletableFuture<String?>>()

    /**
     * True when the most recent bounded RPC ran out of its budget (as opposed to the peer closing,
     * the write failing, or a protocol error). The connector uses this to report a real timeout
     * reason instead of a generic rejection.
     */
    @Volatile
    internal var lastCallTimedOut: Boolean = false
        private set
    private val writeLock = Any()
    private val writeGate = RootBoundedCallGate("mar-root-pipe-writer", writeTimeoutMs)
    @Volatile private var peerClosed = false
    override fun isAlive(): Boolean = !peerClosed && !writeGate.isClosed && (process?.isAlive != false)

    init {
        Thread({ readLoop() }, "mar-root-pipe-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop() {
        try {
            while (true) {
                val len = input.readInt()
                if (len !in 1..MAX_FRAME_BYTES) break
                val buf = ByteArray(len)
                input.readFully(buf)
                val raw = String(buf, StandardCharsets.UTF_8)
                val reply = RootPrivilegedServiceEngine.json.decodeFromString<RootPipeRpcReply>(raw)
                pendingReplies.remove(reply.rpcId)?.complete(reply.responsePayload)
            }
        } catch (_: Throwable) {
            // Stream closed.
        } finally {
            peerClosed = true
            pendingReplies.keys.toList().forEach { id -> pendingReplies.remove(id)?.complete(null) }
        }
    }

    private fun callRpc(frame: RootPipeRpcFrame, timeoutMs: Long = controlTimeoutMs): String? = runCatching {
        lastCallTimedOut = false
        if (writeGate.isClosed || (process != null && !process.isAlive)) return null
        val id = nextRpcId.incrementAndGet()
        val future = CompletableFuture<String?>()
        pendingReplies[id] = future
        if (writeGate.isClosed) {
            pendingReplies.remove(id)
            return null
        }
        try {
            val numbered = frame.copy(rpcId = id)
            val encoded = RootPrivilegedServiceEngine.json.encodeToString(numbered).toByteArray(StandardCharsets.UTF_8)
            val wrote = writeGate.callBounded(minOf(timeoutMs, writeTimeoutMs)) {
                synchronized(writeLock) {
                    output.writeInt(encoded.size)
                    output.write(encoded)
                    output.flush()
                    true
                }
            }
            if (wrote != true) return null
            try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (timeout: java.util.concurrent.TimeoutException) {
                lastCallTimedOut = true
                throw timeout
            }
        } finally {
            pendingReplies.remove(id)
        }
    }.getOrNull()

    override fun queryManagerAuthorization(requestJson: String): String? =
        callRpc(RootPipeRpcFrame(method = "queryManagerAuthorization", requestJson = requestJson))

    override fun handshake(request: RootServiceHandshakeRequest): RootServiceHandshakeResponse? = runCatching {
        val raw = callRpc(
            RootPipeRpcFrame(
                method = "handshake",
                requestJson = RootPrivilegedServiceEngine.json.encodeToString(request),
                // Only a real `su` child is a monitorable controller; an attached in-memory
                // pipe (unit test seam) has no process and must not arm liveness monitoring.
                controllerPid = if (process != null) Process.myPid() else -1,
            ),
        ) ?: return null
        RootPrivilegedServiceEngine.json.decodeFromString<RootServiceHandshakeResponse>(raw)
    }.getOrNull()

    override fun bindWorkspace(requestJson: String): RootServiceBindWorkspaceResponse? = runCatching {
        val raw = callRpc(
            RootPipeRpcFrame(
                method = "bindWorkspace",
                requestJson = requestJson,
            ),
        ) ?: return null
        RootPrivilegedServiceEngine.json.decodeFromString<RootServiceBindWorkspaceResponse>(raw)
    }.getOrNull()

    override fun submitEnvelope(
        controlSequence: Long,
        rawEnvelopeJson: String,
    ): RootServiceExecuteResponse? = runCatching {
        val raw = callRpc(
            RootPipeRpcFrame(
                method = "submitEnvelope",
                requestJson = rawEnvelopeJson,
                controlSequence = controlSequence,
            ),
        ) ?: return null
        RootPrivilegedServiceEngine.json.decodeFromString<RootServiceExecuteResponse>(raw)
    }.getOrNull()

    override fun executeAccepted(
        requestId: String,
        payloadBytes: ByteArray,
    ): RootServiceExecuteResponse? = runCatching {
        val b64 = if (payloadBytes.isEmpty()) "" else Base64.getEncoder().encodeToString(payloadBytes)
        val raw = callRpc(
            RootPipeRpcFrame(
                method = "executeAccepted",
                requestJson = requestId,
                payloadBase64 = b64,
            ),
            timeoutMs = executeTimeoutMs,
        ) ?: return null
        RootPrivilegedServiceEngine.json.decodeFromString<RootServiceExecuteResponse>(raw)
    }.getOrNull()

    override fun submitAndExecute(
        controlSequence: Long,
        rawEnvelopeJson: String,
        payloadBytes: ByteArray,
    ): RootServiceExecuteResponse? = runCatching {
        val b64 = if (payloadBytes.isEmpty()) "" else Base64.getEncoder().encodeToString(payloadBytes)
        val raw = callRpc(
            RootPipeRpcFrame(
                method = "submitAndExecute",
                requestJson = rawEnvelopeJson,
                controlSequence = controlSequence,
                payloadBase64 = b64,
            ),
            timeoutMs = executeTimeoutMs,
        ) ?: return null
        RootPrivilegedServiceEngine.json.decodeFromString<RootServiceExecuteResponse>(raw)
    }.getOrNull()

    override fun cancelCommand(requestId: String): Boolean {
        val raw = callRpc(RootPipeRpcFrame(method = "cancelCommand", requestJson = requestId), timeoutMs = controlTimeoutMs)
        return raw == "true"
    }

    override fun cancelInvocation(request: RootCancelInvocationRequest): Boolean =
        callRpc(RootPipeRpcFrame(method = "cancelInvocation",
            requestJson = RootPrivilegedServiceEngine.json.encodeToString(request)), timeoutMs = controlTimeoutMs) == "true"

    override fun queryQuiescence(request: RootQuiescenceQueryRequest): RootRemoteQuiescenceSnapshot? = runCatching {
        val raw = callRpc(
            RootPipeRpcFrame(
                method = "queryQuiescence",
                requestJson = RootPrivilegedServiceEngine.json.encodeToString(request),
            ),
            timeoutMs = controlTimeoutMs,
        ) ?: return null
        RootPrivilegedServiceEngine.json.decodeFromString<RootRemoteQuiescenceSnapshot>(raw)
    }.getOrNull()

    override fun revokeAndShutdown(controlSequence: Long, reason: String): String =
        callRpc(
            RootPipeRpcFrame(
                method = "revokeAndShutdown",
                requestJson = reason,
                controlSequence = controlSequence,
            ),
            timeoutMs = controlTimeoutMs,
        ) ?: "DISCONNECTED"

    override fun close() {
        try {
            writeGate.closeBounded(
                closeTimeoutMs,
                { process?.destroyForcibly() },
                { output.close() },
                { input.close() },
            )
        } finally {
            runCatching { process?.destroyForcibly() }
            pendingReplies.keys.toList().forEach { id -> pendingReplies.remove(id)?.complete(null) }
        }
    }

    companion object {
        private const val MAX_FRAME_BYTES = 1_048_576

        fun startSuProcess(
            apkPath: String,
            identity: RootServiceLaunchIdentity,
            suPath: String = "su",
        ): SuRootDaemonTransport? = runCatching {
            // Only non-secret launch identity is placed in argv; challenges/session/authorization
            // proofs travel exclusively over the verified stdin/stdout channel.
            val args = identity.toArgs().joinToString(" ") { "'$it'" }
            val cmd = "CLASSPATH='$apkPath' exec /system/bin/app_process /system/bin " +
                "${RootPrivilegedProcessMain::class.java.name} $args"
            // Pinned to the provider path the foreground probe verified: never a PATH lookup.
            val proc = ProcessBuilder(suPath, "-c", cmd)
                .redirectError(java.io.File("/dev/null"))
                .start()
            SuRootDaemonTransport(
                process = proc,
                input = DataInputStream(proc.inputStream),
                output = DataOutputStream(proc.outputStream),
            )
        }.getOrNull()
    }
}

/**
 * Controlled `UID 0` process main for [runtime.mobileagent.domain.Authority.ROOT_DIRECT].
 * Exits immediately when parent pipe closes or session is revoked.
 */
object RootPrivilegedProcessMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val launch = RootServiceLaunchIdentity.parse(args.toList()) ?: exitProcess(1)
        if (Process.myUid() != 0) exitProcess(1)
        if (launch.packageName != BuildConfig.APPLICATION_ID) exitProcess(1)
        val facts = AndroidRootOsIdentityFacts.systemProcessFacts() ?: exitProcess(1)
        val pinned = (RootServiceBootstrap.observe(launch, facts) as? RootBootstrapObservation.Pinned)
            ?.bootstrap ?: exitProcess(1)
        val engine = RootPrivilegedServiceEngine(
            onStopService = { exitProcess(0) },
            managerObservationEnabled = launch.observeMagiskPolicy,
        )
        engine.pinHostBootstrap(pinned)
        engine.armLifecycleWatchdog()
        serveStreamLoop(engine, DataInputStream(System.`in`), DataOutputStream(System.out), launch.appUid)
    }

    internal fun serveStreamLoop(
        engine: RootPrivilegedServiceEngine,
        input: DataInputStream,
        output: DataOutputStream,
        parentUid: Int,
    ) {
        val writeLock = Any()
        val workerPool = Executors.newCachedThreadPool()
        fun sendReply(rpcId: Long, payload: String) {
            val replyBytes = RootPrivilegedServiceEngine.json.encodeToString(
                RootPipeRpcReply(rpcId = rpcId, responsePayload = payload),
            ).toByteArray(StandardCharsets.UTF_8)
            synchronized(writeLock) {
                output.writeInt(replyBytes.size)
                output.write(replyBytes)
                output.flush()
            }
        }
        try {
            while (true) {
                val len = try {
                    input.readInt()
                } catch (_: Throwable) {
                    break
                }
                if (len !in 1..1_048_576) break
                val buf = ByteArray(len)
                input.readFully(buf)
                val frame = RootPrivilegedServiceEngine.json.decodeFromString<RootPipeRpcFrame>(
                    String(buf, StandardCharsets.UTF_8),
                )
                if (frame.method == "executeAccepted" || frame.method == "submitAndExecute") {
                    workerPool.submit {
                        val reply = dispatchFrame(engine, frame, parentUid)
                        runCatching { sendReply(frame.rpcId, reply) }
                    }
                } else {
                    val reply = dispatchFrame(engine, frame, parentUid)
                    sendReply(frame.rpcId, reply)
                }
            }
        } finally {
            workerPool.shutdownNow()
            engine.close()
        }
    }

    internal fun dispatchFrame(
        engine: RootPrivilegedServiceEngine,
        frame: RootPipeRpcFrame,
        callingUid: Int,
    ): String = when (frame.method) {
        "handshake" -> engine.handshake(frame.requestJson, callingUid, frame.controllerPid)
        "bindWorkspace" -> engine.bindWorkspace(frame.requestJson, callingUid)
        "submitEnvelope" -> engine.submitEnvelope(frame.controlSequence, frame.requestJson, callingUid)
        "executeAccepted" -> {
            val payload = if (frame.payloadBase64.isEmpty()) {
                ByteArray(0)
            } else {
                Base64.getDecoder().decode(frame.payloadBase64)
            }
            engine.executeAccepted(frame.requestJson, payload, callingUid)
        }
        "submitAndExecute" -> {
            val payload = if (frame.payloadBase64.isEmpty()) {
                ByteArray(0)
            } else {
                Base64.getDecoder().decode(frame.payloadBase64)
            }
            engine.submitAndExecute(frame.controlSequence, frame.requestJson, payload, callingUid)
        }
        "cancelCommand" -> engine.cancelCommand(frame.requestJson, callingUid).toString()
        "cancelInvocation" -> engine.cancelInvocation(frame.requestJson, callingUid).toString()
        "queryQuiescence" -> engine.queryQuiescence(frame.requestJson, callingUid)
        "queryManagerAuthorization" -> engine.queryManagerAuthorization(frame.requestJson, callingUid)
        "revokeAndShutdown" -> engine.revokeAndShutdown(frame.controlSequence, frame.requestJson, callingUid)
        else -> "UNKNOWN_METHOD"
    }
}
