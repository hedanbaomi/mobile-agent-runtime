// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.skills.ToolExecutor
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Observable, fail-closed authority bridge for the optional third-party
 * Shizuku service.
 *
 * The class never starts or configures Shizuku.  A foreground UI must call
 * [requestPermission] after explaining the scope to the user.  A separate
 * explicit [bindUserService] call is required before tools become visible.
 */
class ShizukuAuthorityBridge(
    context: Context,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val _state = MutableStateFlow(initialState())
    private var started = true
    private var userService: IShizukuCommandService? = null
    private var userServiceBinder: IBinder? = null
    private var userServiceUid: Int? = null
    private var userServiceProtocolVersion: Int? = null
    private var userServiceSessionId: String? = null
    private var userServiceCallerUid: Int? = null
    /** Non-null while one asynchronous Shizuku bind is awaiting its ServiceConnection callback. */
    private var userServiceBindStartedAtElapsedMs: Long? = null
    /** Last verified grant; binder death changes availability, not user consent. */
    @Volatile private var lastKnownPermissionGranted = false
    private var permissionListenerRegistered = false
    private val shellReadPool: ExecutorService = Executors.newCachedThreadPool()
    private val shellCallLock = Any()
    private val usedShellCallIds = linkedSetOf<String>()

    private val stateListeners = CopyOnWriteArraySet<(ShizukuAuthorityState) -> Unit>()
    private val permissionListeners = CopyOnWriteArraySet<(ShizukuPermissionResult) -> Unit>()

    /** Stable observable state for UI and deterministic broker preflight. */
    val state: StateFlow<ShizukuAuthorityState> = _state.asStateFlow()

    private val binderReceivedListener = rikka.shizuku.Shizuku.OnBinderReceivedListener {
        val current = refresh()
        // A persisted Shizuku grant is durable user consent. Binder delivery may happen after
        // Application.onCreate(), so reconnect the typed service here without requesting anything.
        if (current.permissionGranted) bindUserService()
    }
    private val binderDeadListener = rikka.shizuku.Shizuku.OnBinderDeadListener {
        synchronized(lock) {
            clearUserServiceLocked()
        }
        refresh()
    }
    private val permissionResultListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        val result = ShizukuPermissionResult(
            requestCode = requestCode,
            granted = grantResult == PackageManager.PERMISSION_GRANTED,
        )
        permissionListeners.forEach { listener -> runCatching { listener(result) } }
        refresh()
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !runCatching { binder.pingBinder() }.getOrDefault(false)) {
                synchronized(lock) { clearUserServiceLocked() }
                refresh()
                return
            }
            val candidate = IShizukuCommandService.Stub.asInterface(binder)
            val handshake = runCatching { parseHandshake(candidate.getStatus()) }.getOrNull()
            synchronized(lock) {
                clearUserServiceLocked()
                userServiceBinder = binder
                userService = candidate
                userServiceUid = handshake?.serviceUid
                userServiceProtocolVersion = handshake?.protocolVersion
                userServiceSessionId = handshake?.sessionId
                userServiceCallerUid = handshake?.callerUid
                if (handshake == null || !handshake.isValid) {
                    // Keep the binder only long enough to expose an explicit
                    // unavailable state; no operation can pass dispatchReady.
                    userService = null
                } else {
                    runCatching { binder.linkToDeath(userServiceDeathRecipient, 0) }
                        .onFailure { clearUserServiceLocked() }
                }
            }
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) { clearUserServiceLocked() }
            refresh()
        }
    }

    private val userServiceDeathRecipient = IBinder.DeathRecipient {
        synchronized(lock) { clearUserServiceLocked() }
        refresh()
    }

    init {
        registerListeners()
        refresh()
    }

    /** Re-reads all live authority facts.  It never throws to the caller. */
    fun refresh(): ShizukuAuthorityState {
        val next = readState()
        _state.value = next
        stateListeners.forEach { listener -> runCatching { listener(next) } }
        return next
    }

    /**
     * Requests Shizuku permission only after a caller explicitly initiates the
     * action from a foreground consent surface.  No automatic request occurs.
     * The return value means the request was accepted or permission was already
     * granted; it does not replace the asynchronous result callback.
     */
    fun requestPermission(): Boolean = requestPermission(PERMISSION_REQUEST_CODE)

    /** Variant useful to callers that need a stable, app-owned request code. */
    fun requestPermission(requestCode: Int): Boolean {
        if (!ShizukuBridgePolicy.validPermissionRequestCode(requestCode)) return false
        val current = refresh()
        if (!current.binderAlive) return false
        if (current.permissionGranted) {
            return ShizukuBridgePolicy.evaluateServer(current.asBridgeStatus()) is ShizukuGateDecision.Allowed
        }
        return runCatching {
            Shizuku.requestPermission(requestCode)
            true
        }.getOrElse {
            refresh()
            false
        }
    }

    fun addStateListener(listener: (ShizukuAuthorityState) -> Unit) {
        stateListeners += listener
    }

    fun removeStateListener(listener: (ShizukuAuthorityState) -> Unit) {
        stateListeners -= listener
    }

    fun addPermissionResultListener(listener: (ShizukuPermissionResult) -> Unit) {
        permissionListeners += listener
    }

    fun removePermissionResultListener(listener: (ShizukuPermissionResult) -> Unit) {
        permissionListeners -= listener
    }

    /**
     * Binds the fixed UserService only after the live server authority is
     * proven.  It does not request permission or retry silently.
     */
    fun bindUserService(): Boolean {
        val current = refresh()
        if (ShizukuBridgePolicy.evaluateServer(current.asBridgeStatus()) !is ShizukuGateDecision.Allowed) {
            return false
        }
        val shouldBind = synchronized(lock) {
            if (userServiceBinder?.pingBinder() == true) {
                refresh()
                return true
            }
            val now = SystemClock.elapsedRealtime()
            val pendingSince = userServiceBindStartedAtElapsedMs
            if (pendingSince != null && now - pendingSince < USER_SERVICE_BIND_TIMEOUT_MS) {
                false
            } else {
                // Permission-result, binder-received and AppContainer initialization callbacks can
                // arrive together. Reserve the asynchronous bind before leaving the lock so they
                // cannot create parallel UserServices and strand the canonical state at CONNECTING.
                userServiceBindStartedAtElapsedMs = now
                true
            }
        }
        if (!shouldBind) return true
        return runCatching {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
            true
        }.getOrElse {
            synchronized(lock) { clearUserServiceLocked() }
            refresh()
            false
        }
    }

    /** Explicitly removes the UserService; it is not called as a fallback. */
    fun unbindUserService() {
        val shouldUnbind = synchronized(lock) {
            userServiceBinder != null || userServiceBindStartedAtElapsedMs != null
        }
        if (shouldUnbind && runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true) }
        }
        synchronized(lock) { clearUserServiceLocked() }
        refresh()
    }

    /**
     * Creates the Agent-bound ToolExecutor.  Its specs are empty until the
     * bridge is fully ready; every call and approval repeats live checks.
     */
    fun createToolExecutor(
        snapshot: AgentSnapshot,
        snapshotStillExists: () -> Boolean,
        agentStillExists: () -> Boolean,
    ): ToolExecutor = ShizukuToolExecutor(
        bridge = this,
        snapshot = snapshot,
        snapshotStillExists = snapshotStillExists,
        agentStillExists = agentStillExists,
    )

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (!started) false else {
                started = false
                true
            }
        }
        if (!shouldClose) return
        runCatching { Shizuku.removeBinderReceivedListener(binderReceivedListener) }
        runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
        if (permissionListenerRegistered) {
            runCatching { Shizuku.removeRequestPermissionResultListener(permissionResultListener) }
            permissionListenerRegistered = false
        }
        unbindUserService()
        shellReadPool.shutdownNow()
        stateListeners.clear()
        permissionListeners.clear()
    }

    internal fun dispatchList(relativePath: String): ShizukuDispatchResult =
        dispatch { service, sessionId -> service.listSession(sessionId, relativePath) }

    internal fun dispatchRead(relativePath: String, maxBytes: Int): ShizukuDispatchResult =
        dispatch { service, sessionId -> service.readSession(sessionId, relativePath, maxBytes) }

    internal fun dispatchWrite(relativePath: String, content: ByteArray, replaceExisting: Boolean): ShizukuDispatchResult =
        dispatch { service, sessionId -> service.writeSession(sessionId, relativePath, content, replaceExisting) }

    internal fun dispatchMkdir(relativePath: String): ShizukuDispatchResult =
        dispatch { service, sessionId -> service.mkdirSession(sessionId, relativePath) }

    internal fun dispatchDelete(relativePath: String): ShizukuDispatchResult =
        dispatch { service, sessionId -> service.deleteSession(sessionId, relativePath) }

    internal fun dispatchStat(relativePath: String): ShizukuDispatchResult =
        dispatch { service, sessionId -> service.statSession(sessionId, relativePath) }

    internal fun dispatchMove(
        sourcePath: String,
        destinationPath: String,
        replaceExisting: Boolean,
    ): ShizukuDispatchResult = dispatch { service, sessionId ->
        service.moveSession(sessionId, sourcePath, destinationPath, replaceExisting)
    }

    /** Opens the typed device-root browser; the result contains opaque handles only. */
    internal fun dispatchDirectoryRoot(maxEntries: Int): ShizukuDispatchResult =
        dispatch { service, sessionId -> service.openDirectoryRootSession(sessionId, maxEntries) }

    /** Browses one service-owned opaque directory handle. */
    internal fun dispatchDirectoryBrowse(handle: String, maxEntries: Int): ShizukuDispatchResult =
        dispatch { service, sessionId -> service.browseDirectorySession(sessionId, handle, maxEntries) }

    /** Attaches one service-owned opaque directory handle to the agent workspace. */
    internal fun dispatchDirectoryAttach(handle: String): ShizukuDispatchResult =
        dispatch { service, sessionId -> service.attachDirectorySession(sessionId, handle) }

    internal fun dispatchWorkspaceList(
        workspaceHandle: String,
        relativePath: String,
        maxEntries: Int,
    ): ShizukuDispatchResult = dispatch { service, sessionId ->
        service.listWorkspaceSession(sessionId, workspaceHandle, relativePath, maxEntries)
    }

    internal fun dispatchWorkspaceRead(
        workspaceHandle: String,
        relativePath: String,
        maxBytes: Int,
    ): ShizukuDispatchResult = dispatch { service, sessionId ->
        service.readWorkspaceSession(sessionId, workspaceHandle, relativePath, maxBytes)
    }

    internal fun dispatchWorkspaceWrite(
        workspaceHandle: String,
        relativePath: String,
        content: ByteArray,
        replaceExisting: Boolean,
    ): ShizukuDispatchResult = dispatch { service, sessionId ->
        service.writeWorkspaceSession(sessionId, workspaceHandle, relativePath, content, replaceExisting)
    }

    internal fun dispatchWorkspaceMkdir(
        workspaceHandle: String,
        relativePath: String,
    ): ShizukuDispatchResult = dispatch { service, sessionId ->
        service.mkdirWorkspaceSession(sessionId, workspaceHandle, relativePath)
    }

    internal fun dispatchWorkspaceDelete(
        workspaceHandle: String,
        relativePath: String,
    ): ShizukuDispatchResult = dispatch { service, sessionId ->
        service.deleteWorkspaceSession(sessionId, workspaceHandle, relativePath)
    }

    internal fun dispatchWorkspaceStat(
        workspaceHandle: String,
        relativePath: String,
    ): ShizukuDispatchResult = dispatch { service, sessionId ->
        service.statWorkspaceSession(sessionId, workspaceHandle, relativePath)
    }

    internal fun dispatchWorkspaceMove(
        workspaceHandle: String,
        sourcePath: String,
        destinationPath: String,
        replaceExisting: Boolean,
    ): ShizukuDispatchResult = dispatch { service, sessionId ->
        service.moveWorkspaceSession(sessionId, workspaceHandle, sourcePath, destinationPath, replaceExisting)
    }

    /**
     * Low-level shell entrypoint for the higher-level ShizukuShellExecutor
     * adapter.  It deliberately returns an internal result model rather than
     * an Agent ToolSpec; capability and approval remain above this bridge.
     */
    internal suspend fun executeShell(request: ShizukuShellRequest): ShizukuShellResult =
        withContext(Dispatchers.IO) { executeShellBlocking(request) }

    /** Alias kept small and generic for the upper-layer ShizukuShellExecutor adapter. */
    internal suspend fun execute(request: ShizukuShellRequest): ShizukuShellResult = executeShell(request)

    /** Best-effort cancellation for a currently executing low-level call. */
    internal suspend fun cancelShell(callId: String): Boolean = withContext(Dispatchers.IO) {
        if (callId.isBlank()) return@withContext false
        val current = refresh()
        if (!current.asBridgeStatus().dispatchReady) return@withContext false
        val (service, sessionId) = synchronized(lock) {
            val service = userService ?: return@synchronized null
            val sessionId = userServiceSessionId ?: return@synchronized null
            service to sessionId
        } ?: return@withContext false
        try {
            service.cancelShell(sessionId, callId)
        } catch (_: RemoteException) {
            synchronized(lock) { clearUserServiceLocked() }
            refresh()
            false
        } catch (_: RuntimeException) {
            synchronized(lock) { clearUserServiceLocked() }
            refresh()
            false
        }
    }

    /** Alias kept small and generic for the upper-layer adapter. */
    internal suspend fun cancel(callId: String): Boolean = cancelShell(callId)

    private fun dispatch(call: (IShizukuCommandService, String) -> String): ShizukuDispatchResult {
        val current = refresh()
        val decision = ShizukuBridgePolicy.evaluateDispatch(current.asBridgeStatus())
        if (decision is ShizukuGateDecision.Denied) return ShizukuDispatchResult.Denied(decision.reason)
        val target = synchronized(lock) {
            val service = userService?.takeIf { candidate -> runCatching { candidate.asBinder().pingBinder() }.getOrDefault(false) }
            val sessionId = userServiceSessionId
            if (service == null || sessionId.isNullOrBlank()) null else service to sessionId
        } ?: run {
            refresh()
            return ShizukuDispatchResult.Denied("Shizuku UserService is not connected")
        }
        return try {
            val payload = call(target.first, target.second)
            if (!runCatching { target.first.asBinder().pingBinder() }.getOrDefault(false)) {
                synchronized(lock) { clearUserServiceLocked() }
                refresh()
                ShizukuDispatchResult.Failed("Shizuku UserService became unavailable", unknownOutcome = true)
            } else {
                validateOperationPayload(payload)
            }
        } catch (_: RemoteException) {
            synchronized(lock) { clearUserServiceLocked() }
            refresh()
            ShizukuDispatchResult.Failed("Shizuku UserService call failed", unknownOutcome = true)
        } catch (_: RuntimeException) {
            synchronized(lock) { clearUserServiceLocked() }
            refresh()
            ShizukuDispatchResult.Failed("Shizuku UserService call failed", unknownOutcome = true)
        }
    }

    private fun executeShellBlocking(request: ShizukuShellRequest): ShizukuShellResult {
        if (request.cwd != null && !isValidShizukuShellCwdSyntax(request.cwd)) {
            return ShizukuShellResult(
                callId = request.callId,
                state = ShizukuShellResult.State.DENIED,
                errorCode = ShizukuShellLimits.INVALID_CWD,
            )
        }
        val normalized = normalizeShellRequest(request) ?: return ShizukuShellResult(
            callId = request.callId,
            state = ShizukuShellResult.State.DENIED,
            errorCode = ShizukuShellLimits.INVALID_REQUEST,
        )
        val current = refresh()
        val decision = ShizukuBridgePolicy.evaluateDispatch(current.asBridgeStatus())
        if (decision is ShizukuGateDecision.Denied) {
            return ShizukuShellResult(
                callId = normalized.callId,
                state = ShizukuShellResult.State.DENIED,
                errorCode = ShizukuShellLimits.UNAVAILABLE,
            )
        }
        val target = synchronized(lock) {
            val service = userService?.takeIf { candidate ->
                runCatching { candidate.asBinder().pingBinder() }.getOrDefault(false)
            }
            val sessionId = userServiceSessionId
            if (service == null || sessionId.isNullOrBlank()) null else service to sessionId
        } ?: return ShizukuShellResult(
            callId = normalized.callId,
            state = ShizukuShellResult.State.DENIED,
            errorCode = ShizukuShellLimits.UNAVAILABLE,
        )

        synchronized(shellCallLock) {
            if (normalized.callId in usedShellCallIds) {
                return ShizukuShellResult(
                    callId = normalized.callId,
                    state = ShizukuShellResult.State.DENIED,
                    errorCode = ShizukuShellLimits.REPLAY_DENIED,
                )
            }
            if (usedShellCallIds.size >= MAX_SHELL_CALL_IDS) {
                return ShizukuShellResult(
                    callId = normalized.callId,
                    state = ShizukuShellResult.State.DENIED,
                    errorCode = ShizukuShellLimits.CONCURRENCY_LIMIT,
                )
            }
            usedShellCallIds += normalized.callId
        }

        val response = try {
            target.first.startShell(
                target.second,
                normalized.callId,
                normalized.command,
                normalized.cwd,
                normalized.timeoutMs,
                normalized.maxStdoutBytes,
                normalized.maxStderrBytes,
            )
        } catch (_: RemoteException) {
            synchronized(lock) { clearUserServiceLocked() }
            refresh()
            return unknownShellResult(normalized.callId, ShizukuShellLimits.UNAVAILABLE)
        } catch (_: RuntimeException) {
            synchronized(lock) { clearUserServiceLocked() }
            refresh()
            return unknownShellResult(normalized.callId, ShizukuShellLimits.UNAVAILABLE)
        }

        if (!response.accepted) {
            closeResponseDescriptors(response)
            return ShizukuShellResult(
                callId = normalized.callId,
                state = ShizukuShellResult.State.DENIED,
                errorCode = response.errorCode ?: ShizukuShellLimits.EXECUTION_FAILED,
            )
        }
        val stdoutFd = response.stdoutFd
        val stderrFd = response.stderrFd
        val resultFd = response.resultFd
        if (stdoutFd == null || stderrFd == null || resultFd == null) {
            closeResponseDescriptors(response)
            return unknownShellResult(normalized.callId, ShizukuShellLimits.PROTOCOL_MISMATCH)
        }

        // The remote service keeps independent 1 MiB caps.  The upper runtime
        // must serialize both streams as one result below its own 1 MiB budget.
        val outputBudget = OutputBudget(ShizukuShellLimits.MAX_SERIALIZED_OUTPUT_BYTES)
        var stdoutFuture: Future<LocalPipeOutput>? = null
        var stderrFuture: Future<LocalPipeOutput>? = null
        var envelopeFuture: Future<ByteArray?>? = null
        try {
            stdoutFuture = shellReadPool.submit<LocalPipeOutput> {
                readPipe(stdoutFd, normalized.maxStdoutBytes, outputBudget)
            }
            stderrFuture = shellReadPool.submit<LocalPipeOutput> {
                readPipe(stderrFd, normalized.maxStderrBytes, outputBudget)
            }
            envelopeFuture = shellReadPool.submit<ByteArray?> { readEnvelope(resultFd) }
        } catch (_: RuntimeException) {
            cancelRead(*listOfNotNull(stdoutFuture, stderrFuture, envelopeFuture).toTypedArray())
            closeResponseDescriptors(response)
            return unknownShellResult(normalized.callId, ShizukuShellLimits.UNAVAILABLE)
        }
        val stdoutReader = checkNotNull(stdoutFuture)
        val stderrReader = checkNotNull(stderrFuture)
        val envelopeReader = checkNotNull(envelopeFuture)
        return try {
            // The remote runner applies the same timeout.  The grace period is
            // only for Binder/pipe delivery and does not extend shell runtime.
            val envelope = envelopeReader.get(
                normalized.timeoutMs + ShizukuShellLimits.IPC_GRACE_MS,
                TimeUnit.MILLISECONDS,
            )
            val stdout = stdoutReader.get(ShizukuShellLimits.IPC_GRACE_MS, TimeUnit.MILLISECONDS)
            val stderr = stderrReader.get(ShizukuShellLimits.IPC_GRACE_MS, TimeUnit.MILLISECONDS)
            val parsed = parseShellEnvelope(normalized.callId, envelope, stdout, stderr)
                ?: unknownShellResult(normalized.callId, ShizukuShellLimits.PROTOCOL_MISMATCH)
            // A terminal envelope is not enough to establish a safe outcome if
            // the remote UserService died during delivery.  The shell process
            // may have outlived that Binder, so keep the result non-replayable.
            if (!runCatching { target.first.asBinder().pingBinder() }.getOrDefault(false)) {
                parsed.copy(
                    state = ShizukuShellResult.State.UNKNOWN,
                    errorCode = ShizukuShellLimits.UNAVAILABLE,
                    unknownOutcome = true,
                )
            } else {
                parsed
            }
        } catch (_: TimeoutException) {
            // A local wait timeout does not prove that the remote process ended.
            // Ask it to stop, close our read ends, and surface UNKNOWN so the
            // caller cannot replay a possibly partially applied command.
            runCatching { target.first.cancelShell(target.second, normalized.callId) }
            cancelRead(stdoutReader, stderrReader, envelopeReader)
            closeResponseDescriptors(response)
            unknownShellResult(normalized.callId, ShizukuShellLimits.TIMED_OUT, timedOut = true)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { target.first.cancelShell(target.second, normalized.callId) }
            cancelRead(stdoutReader, stderrReader, envelopeReader)
            closeResponseDescriptors(response)
            unknownShellResult(normalized.callId, ShizukuShellLimits.CANCELLED, cancelled = true)
        } catch (_: RuntimeException) {
            cancelRead(stdoutReader, stderrReader, envelopeReader)
            closeResponseDescriptors(response)
            unknownShellResult(normalized.callId, ShizukuShellLimits.PROTOCOL_MISMATCH)
        } catch (_: ExecutionException) {
            cancelRead(stdoutReader, stderrReader, envelopeReader)
            closeResponseDescriptors(response)
            unknownShellResult(normalized.callId, ShizukuShellLimits.PROTOCOL_MISMATCH)
        }
    }

    private fun normalizeShellRequest(request: ShizukuShellRequest): ShizukuShellRequest? {
        if (request.callId.isBlank()) return null
        val callId = strictUtf8OrNull(request.callId) ?: return null
        if (callId.size > ShizukuShellLimits.MAX_CALL_ID_BYTES) return null
        if (request.command.isBlank()) return null
        val command = strictUtf8OrNull(request.command) ?: return null
        if (command.size > ShizukuShellLimits.MAX_COMMAND_BYTES) return null
        if (request.timeoutMs < 0L || request.maxStdoutBytes < 0 || request.maxStderrBytes < 0) return null
        val cwd = request.cwd?.let { value ->
            // Existence and directory checks belong to the UserService/shell
            // boundary.  The app side only enforces the path syntax contract.
            if (!isValidShizukuShellCwdSyntax(value)) return null
            value
        }
        return request.copy(
            cwd = cwd,
            timeoutMs = when {
                request.timeoutMs == 0L -> ShizukuShellLimits.DEFAULT_TIMEOUT_MS
                else -> minOf(request.timeoutMs, ShizukuShellLimits.MAX_TIMEOUT_MS)
            },
            maxStdoutBytes = normalizeOutputLimit(request.maxStdoutBytes),
            maxStderrBytes = normalizeOutputLimit(request.maxStderrBytes),
        )
    }

    private fun normalizeOutputLimit(requested: Int): Int = when {
        requested == 0 -> ShizukuShellLimits.DEFAULT_OUTPUT_BYTES
        else -> minOf(requested, ShizukuShellLimits.MAX_OUTPUT_BYTES)
    }

    private fun strictUtf8OrNull(value: String): ByteArray? = runCatching {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = encoder.encode(java.nio.CharBuffer.wrap(value))
        ByteArray(encoded.remaining()).also { encoded.get(it) }
    }.getOrNull()

    private fun readPipe(
        descriptor: ParcelFileDescriptor,
        maximum: Int,
        outputBudget: OutputBudget,
    ): LocalPipeOutput {
        val output = ByteArrayOutputStream(minOf(maximum, 16 * 1024))
        var truncated = false
        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = try {
                        input.read(buffer)
                    } catch (_: IOException) {
                        break
                    }
                    if (count < 0) break
                    if (count == 0) continue
                    val remaining = maximum - output.size()
                    val granted = outputBudget.claim(minOf(remaining, count))
                    if (granted > 0) output.write(buffer, 0, granted)
                    if (granted < count) truncated = true
                }
            }
        } catch (_: IOException) {
            truncated = true
        }
        return LocalPipeOutput(output.toByteArray(), truncated)
    }

    private fun readEnvelope(descriptor: ParcelFileDescriptor): ByteArray? {
        val output = ByteArrayOutputStream()
        return try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val buffer = ByteArray(4 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (output.size() + count > MAX_ENVELOPE_BYTES) return null
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        } catch (_: IOException) {
            null
        }
    }

    private fun parseShellEnvelope(
        callId: String,
        envelopeBytes: ByteArray?,
        stdout: LocalPipeOutput,
        stderr: LocalPipeOutput,
    ): ShizukuShellResult? {
        if (envelopeBytes == null) return null
        val text = runCatching {
            val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(envelopeBytes)).toString()
        }.getOrNull() ?: return null
        val objectPayload = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (objectPayload.optString("callId", "") != callId) return null
        val terminated = objectPayload.optBoolean("terminated", false)
        val remoteUnknown = objectPayload.optBoolean("unknownOutcome", false)
        val rawState = objectPayload.optString("state", "UNKNOWN")
        val remoteTimedOut = objectPayload.optBoolean("timedOut", false)
        val remoteCancelled = objectPayload.optBoolean("cancelled", false)
        val state = when (rawState) {
            "COMPLETED" -> if (terminated && !remoteUnknown) ShizukuShellResult.State.COMPLETED else ShizukuShellResult.State.UNKNOWN
            "TIMED_OUT" -> if (terminated && !remoteUnknown) ShizukuShellResult.State.TIMED_OUT else ShizukuShellResult.State.UNKNOWN
            "CANCELLED" -> if (terminated && !remoteUnknown) ShizukuShellResult.State.CANCELLED else ShizukuShellResult.State.UNKNOWN
            else -> ShizukuShellResult.State.UNKNOWN
        }
        val unknown = state == ShizukuShellResult.State.UNKNOWN || remoteUnknown || !terminated
        val exitCode = if (objectPayload.has("exitCode") && !objectPayload.isNull("exitCode")) {
            objectPayload.optInt("exitCode")
        } else {
            null
        }
        return ShizukuShellResult(
            callId = callId,
            state = state,
            exitCode = exitCode,
            stdout = stdout.bytes,
            stderr = stderr.bytes,
            stdoutTruncated = stdout.truncated || objectPayload.optBoolean("stdoutTruncated", false),
            stderrTruncated = stderr.truncated || objectPayload.optBoolean("stderrTruncated", false),
            // A conservative remote UNKNOWN may still tell us why the
            // watchdog/cancel path was entered; preserve the hint without
            // upgrading the terminal state to a replay-safe result.
            timedOut = remoteTimedOut || state == ShizukuShellResult.State.TIMED_OUT,
            cancelled = remoteCancelled || state == ShizukuShellResult.State.CANCELLED,
            durationMs = objectPayload.optLong("durationMs", -1L).takeIf { it >= 0L },
            errorCode = objectPayload.optString("errorCode", null),
            unknownOutcome = unknown,
        )
    }

    private fun unknownShellResult(
        callId: String,
        errorCode: String,
        timedOut: Boolean = false,
        cancelled: Boolean = false,
    ) = ShizukuShellResult(
        callId = callId,
        state = ShizukuShellResult.State.UNKNOWN,
        errorCode = errorCode,
        timedOut = timedOut,
        cancelled = cancelled,
        unknownOutcome = true,
    )

    private fun cancelRead(vararg futures: Future<*>) {
        futures.forEach { future -> future.cancel(true) }
    }

    private fun closeResponseDescriptors(response: ShizukuShellResponse) {
        runCatching { response.stdoutFd?.close() }
        runCatching { response.stderrFd?.close() }
        runCatching { response.resultFd?.close() }
    }

    private data class LocalPipeOutput(
        val bytes: ByteArray,
        val truncated: Boolean,
    )

    /** Atomic aggregate cap shared by the stdout/stderr reader tasks. */
    private class OutputBudget(maximum: Int) {
        private val remaining = AtomicInteger(maximum)

        fun claim(requested: Int): Int {
            if (requested <= 0) return 0
            while (true) {
                val current = remaining.get()
                if (current <= 0) return 0
                val granted = minOf(current, requested)
                if (remaining.compareAndSet(current, current - granted)) return granted
            }
        }
    }

    private fun registerListeners() {
        if (!started) return
        runCatching { Shizuku.addBinderReceivedListenerSticky(binderReceivedListener) }
        runCatching { Shizuku.addBinderDeadListener(binderDeadListener) }
        if (!permissionListenerRegistered) {
            runCatching {
                Shizuku.addRequestPermissionResultListener(permissionResultListener)
                permissionListenerRegistered = true
            }
        }
    }

    private fun validateOperationPayload(payload: String): ShizukuDispatchResult = try {
        val objectPayload = JSONObject(payload)
        if (objectPayload.optBoolean("ok", false)) {
            ShizukuDispatchResult.Success(payload)
        } else {
            ShizukuDispatchResult.Failed(
                "Shizuku operation was rejected",
                unknownOutcome = false,
                errorCode = objectPayload.optString("code", "").takeIf { it.isNotBlank() },
            )
        }
    } catch (_: RuntimeException) {
        // A malformed response after dispatch cannot establish whether a write committed.
        ShizukuDispatchResult.Failed("Shizuku operation returned an invalid response", unknownOutcome = true)
    }

    private fun readState(): ShizukuAuthorityState {
        val installedHint = isShizukuInstalled()
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) {
            return unavailable(
                installedHint,
                BINDER_UNAVAILABLE,
                permissionGranted = lastKnownPermissionGranted,
            )
        }

        val preV11 = runCatching { Shizuku.isPreV11() }.getOrDefault(true)
        if (preV11) return unavailable(installedHint, API_UNSUPPORTED, binderAlive = true, preV11 = true)

        val apiVersion = runCatching { Shizuku.getVersion() }
            .getOrNull()
            ?.takeIf { it >= 0 }
        val permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrElse { lastKnownPermissionGranted }
        lastKnownPermissionGranted = permissionGranted
        if (!permissionGranted) {
            return unavailable(
                installedHint,
                PERMISSION_REQUIRED,
                binderAlive = true,
                apiVersion = apiVersion,
                permissionGranted = false,
            )
        }
        val serverUid = runCatching { Shizuku.getUid() }.getOrNull()
        if (serverUid != ShizukuBridgePolicy.SHELL_UID) {
            return unavailable(
                installedHint,
                SERVER_UID_UNTRUSTED,
                binderAlive = true,
                permissionGranted = true,
                apiVersion = apiVersion,
                serverUid = serverUid,
            )
        }
        if (apiVersion == null || apiVersion < ShizukuBridgePolicy.MIN_SERVER_VERSION) {
            return unavailable(
                installedHint,
                SERVER_VERSION_UNSUPPORTED,
                binderAlive = true,
                permissionGranted = true,
                apiVersion = apiVersion,
                serverUid = serverUid,
            )
        }
        revalidateUserServiceHandshake()
        val serviceState = synchronized(lock) {
            val alive = userServiceBinder?.let { binder ->
                runCatching { binder.pingBinder() }.getOrDefault(false)
            } == true && userService != null
            ServiceSnapshot(
                alive = alive,
                uid = userServiceUid,
                protocolVersion = userServiceProtocolVersion,
                sessionId = userServiceSessionId,
                callerUid = userServiceCallerUid,
            )
        }
        val error = when {
            !serviceState.alive -> when {
                serviceState.uid != null && serviceState.uid != ShizukuBridgePolicy.SHELL_UID -> USER_SERVICE_UID_UNTRUSTED
                serviceState.protocolVersion != null && serviceState.protocolVersion != ShizukuBridgePolicy.USER_SERVICE_PROTOCOL_VERSION -> USER_SERVICE_PROTOCOL_UNSUPPORTED
                serviceState.callerUid != null && serviceState.callerUid != Process.myUid() -> USER_SERVICE_CALLER_UNTRUSTED
                else -> USER_SERVICE_UNAVAILABLE
            }
            serviceState.uid != ShizukuBridgePolicy.SHELL_UID -> USER_SERVICE_UID_UNTRUSTED
            serviceState.protocolVersion != ShizukuBridgePolicy.USER_SERVICE_PROTOCOL_VERSION -> USER_SERVICE_PROTOCOL_UNSUPPORTED
            serviceState.sessionId.isNullOrBlank() -> USER_SERVICE_HANDSHAKE_INVALID
            serviceState.callerUid != Process.myUid() -> USER_SERVICE_CALLER_UNTRUSTED
            else -> null
        }
        return ShizukuAuthorityState(
            installedHint = installedHint,
            binderAlive = true,
            permissionGranted = true,
            apiVersion = apiVersion,
            serverUid = serverUid,
            userServiceAlive = serviceState.alive,
            ready = serviceState.alive && error == null,
            errorCode = error,
            preV11 = false,
            userServiceUid = serviceState.uid,
            userServiceProtocolVersion = serviceState.protocolVersion,
            userServiceSessionId = serviceState.sessionId,
            userServiceCallerUid = serviceState.callerUid,
        )
    }

    private fun unavailable(
        installedHint: Boolean,
        errorCode: String,
        binderAlive: Boolean = false,
        permissionGranted: Boolean = false,
        apiVersion: Int? = null,
        serverUid: Int? = null,
        preV11: Boolean = false,
        userServiceUid: Int? = null,
        userServiceProtocolVersion: Int? = null,
        userServiceSessionId: String? = null,
        userServiceCallerUid: Int? = null,
    ) = ShizukuAuthorityState(
        installedHint = installedHint,
        binderAlive = binderAlive,
        permissionGranted = permissionGranted,
        apiVersion = apiVersion,
        serverUid = serverUid,
        userServiceAlive = false,
        ready = false,
        errorCode = errorCode,
        preV11 = preV11,
        userServiceUid = userServiceUid,
        userServiceProtocolVersion = userServiceProtocolVersion,
        userServiceSessionId = userServiceSessionId,
        userServiceCallerUid = userServiceCallerUid,
    )

    private fun initialState() = unavailable(isShizukuInstalled(), BINDER_UNAVAILABLE)

    private fun isShizukuInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(SHIZUKU_MANAGER_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** Re-checks the remote UID, protocol and caller identity before dispatch. */
    private fun revalidateUserServiceHandshake() {
        val target = synchronized(lock) {
            val binder = userServiceBinder
            val service = userService
            if (binder == null || service == null) null else binder to service
        } ?: return
        if (!runCatching { target.first.pingBinder() }.getOrDefault(false)) {
            synchronized(lock) { clearUserServiceLocked() }
            return
        }
        val handshake = runCatching { parseHandshake(target.second.getStatus()) }.getOrNull()
        synchronized(lock) {
            if (userServiceBinder !== target.first || userService !== target.second) return@synchronized
            userServiceUid = handshake?.serviceUid
            userServiceProtocolVersion = handshake?.protocolVersion
            userServiceSessionId = handshake?.sessionId
            userServiceCallerUid = handshake?.callerUid
            if (handshake == null || !handshake.isValid) {
                userService = null
            }
        }
    }

    private fun parseHandshake(payload: String): UserServiceHandshake? {
        val objectPayload = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val serviceUid = objectPayload.optIntOrNull("serviceUid")
        val protocolVersion = objectPayload.optIntOrNull("protocolVersion")
        val sessionId = objectPayload.optString("sessionId", "").takeIf { it.isNotBlank() }
        val callerUid = objectPayload.optIntOrNull("callerUid")
        val isValid = objectPayload.optBoolean("ok", false) &&
            objectPayload.optString("operation", "") == "status" &&
            serviceUid == ShizukuBridgePolicy.SHELL_UID &&
            protocolVersion == ShizukuBridgePolicy.USER_SERVICE_PROTOCOL_VERSION &&
            !sessionId.isNullOrBlank() &&
            callerUid == Process.myUid()
        return UserServiceHandshake(
            serviceUid = serviceUid,
            protocolVersion = protocolVersion,
            sessionId = sessionId,
            callerUid = callerUid,
            isValid = isValid,
        )
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else runCatching { getInt(key) }.getOrNull()

    private data class UserServiceHandshake(
        val serviceUid: Int?,
        val protocolVersion: Int?,
        val sessionId: String?,
        val callerUid: Int?,
        val isValid: Boolean,
    )

    private data class ServiceSnapshot(
        val alive: Boolean,
        val uid: Int?,
        val protocolVersion: Int?,
        val sessionId: String?,
        val callerUid: Int?,
    )

    private fun clearUserServiceLocked() {
        val binder = userServiceBinder
        if (binder != null) runCatching { binder.unlinkToDeath(userServiceDeathRecipient, 0) }
        userServiceBinder = null
        userService = null
        userServiceUid = null
        userServiceProtocolVersion = null
        userServiceSessionId = null
        userServiceCallerUid = null
        userServiceBindStartedAtElapsedMs = null
    }

    private fun ShizukuAuthorityState.asBridgeStatus() = ShizukuBridgeStatus(
        binderAlive = binderAlive,
        permissionGranted = permissionGranted,
        serverUid = serverUid,
        serverVersion = apiVersion,
        userServiceAlive = userServiceAlive,
        preV11 = preV11,
        reason = errorCode.orEmpty(),
        userServiceUid = userServiceUid,
        userServiceProtocolVersion = userServiceProtocolVersion,
        userServiceSessionId = userServiceSessionId,
        userServiceCallerUid = userServiceCallerUid,
    )

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext.packageName, ShizukuUserService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("shizuku-file-service")
        .debuggable(false)
        .version(USER_SERVICE_VERSION)

    companion object {
        const val PERMISSION_REQUEST_CODE = 0x4D52
        private const val USER_SERVICE_VERSION = 2
        private const val USER_SERVICE_BIND_TIMEOUT_MS = 10_000L
        private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"

        const val BINDER_UNAVAILABLE = "BINDER_UNAVAILABLE"
        const val API_UNSUPPORTED = "API_UNSUPPORTED"
        const val PERMISSION_REQUIRED = "PERMISSION_REQUIRED"
        const val SERVER_UID_UNTRUSTED = "SERVER_UID_UNTRUSTED"
        const val SERVER_VERSION_UNSUPPORTED = "SERVER_VERSION_UNSUPPORTED"
        const val USER_SERVICE_UNAVAILABLE = "USER_SERVICE_UNAVAILABLE"
        const val USER_SERVICE_UID_UNTRUSTED = "USER_SERVICE_UID_UNTRUSTED"
        const val USER_SERVICE_PROTOCOL_UNSUPPORTED = "USER_SERVICE_PROTOCOL_UNSUPPORTED"
        const val USER_SERVICE_HANDSHAKE_INVALID = "USER_SERVICE_HANDSHAKE_INVALID"
        const val USER_SERVICE_CALLER_UNTRUSTED = "USER_SERVICE_CALLER_UNTRUSTED"
        private const val MAX_SHELL_CALL_IDS = 2048
        private const val MAX_ENVELOPE_BYTES = 16 * 1024
    }
}

/**
 * App-side cwd validation intentionally stops at syntax.  Whether the path
 * exists and is a directory is checked by the Shizuku UserService process,
 * which is the authority boundary for elevated shell execution.
 */
internal fun isValidShizukuShellCwdSyntax(value: String): Boolean {
    val bytes = runCatching {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = encoder.encode(java.nio.CharBuffer.wrap(value))
        ByteArray(encoded.remaining()).also { encoded.get(it) }
    }.getOrNull() ?: return false
    return bytes.isNotEmpty() &&
        bytes.size <= ShizukuShellLimits.MAX_CWD_BYTES &&
        value.startsWith('/') &&
        !value.contains('\\') &&
        value.none { it.isISOControl() }
}

/** Public state contract kept separate from the implementation for stable UI use. */
data class ShizukuAuthorityState(
    val installedHint: Boolean,
    val binderAlive: Boolean,
    val permissionGranted: Boolean,
    val apiVersion: Int?,
    val serverUid: Int?,
    val userServiceAlive: Boolean,
    val ready: Boolean,
    val errorCode: String?,
    val preV11: Boolean = false,
    val userServiceUid: Int? = null,
    val userServiceProtocolVersion: Int? = null,
    val userServiceSessionId: String? = null,
    val userServiceCallerUid: Int? = null,
)
