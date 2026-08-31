// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.desktop.bridge

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import runtime.mobileagent.bridge.BridgeCodec
import runtime.mobileagent.bridge.BridgeErrorCodes
import runtime.mobileagent.bridge.BridgeErrorEnvelope
import runtime.mobileagent.bridge.BridgeFrameType
import runtime.mobileagent.bridge.BridgeOperation
import runtime.mobileagent.bridge.BridgeRequestEnvelope
import runtime.mobileagent.bridge.BridgeRequestState
import runtime.mobileagent.bridge.BridgeResponseEnvelope
import runtime.mobileagent.bridge.BridgeStatusEnvelope
import runtime.mobileagent.bridge.BridgeCancelRequest
import runtime.mobileagent.bridge.BridgeProtocol

/** Cancellation state passed to an authenticated typed operation. */
class BridgeCancellation internal constructor() {
    private val requested = AtomicBoolean(false)

    val isRequested: Boolean get() = requested.get()

    internal fun request(): Boolean = requested.compareAndSet(false, true)
}

/** The only Desktop operation seam. It receives already-authenticated, typed requests. */
fun interface TypedBridgeRequestHandler {
    fun handle(request: BridgeRequestEnvelope, cancellation: BridgeCancellation): BridgeResponseEnvelope
}

/**
 * The default handler is fail-closed, but it is not a no-op: every authenticated
 * request receives a typed terminal response. Shell is implemented through the
 * fixed ADB helper; all other operations require an explicitly supplied backend.
 */
class DesktopTypedBridgeRequestHandler(
    private val shell: () -> WiredAdbShellExecutor,
    private val typedFiles: (() -> WiredAdbTypedFileExecutor)? = null,
) : TypedBridgeRequestHandler {
    override fun handle(
        request: BridgeRequestEnvelope,
        cancellation: BridgeCancellation,
    ): BridgeResponseEnvelope {
        val operation = runCatching { BridgeOperation.parse(request.operation) }
            .getOrElse {
                return error(request, BridgeErrorCodes.REQUEST_INVALID, "request operation is invalid", false)
            }
        return when (operation) {
            BridgeOperation.SHELL_EXEC -> executeShell(request, cancellation)
            BridgeOperation.FILE_LIST,
            BridgeOperation.FILE_STAT,
            BridgeOperation.FILE_READ_TEXT,
            BridgeOperation.FILE_WRITE_TEXT,
            BridgeOperation.FILE_CREATE_DIRECTORY,
            BridgeOperation.FILE_MOVE,
            BridgeOperation.FILE_DELETE -> executeTypedFile(request, cancellation)
            else -> error(
                request,
                BridgeErrorCodes.UNSUPPORTED_OPERATION,
                "operation requires an explicitly configured typed backend",
                retryable = false,
            )
        }
    }

    private fun executeTypedFile(
        request: BridgeRequestEnvelope,
        cancellation: BridgeCancellation,
    ): BridgeResponseEnvelope {
        val backend = typedFiles?.invoke()
            ?: return error(
                request,
                BridgeErrorCodes.UNSUPPORTED_OPERATION,
                "operation requires an explicitly configured typed backend",
                retryable = false,
            )
        val typedRequest = try {
            WiredAdbTypedFileRequest.parse(request)
        } catch (_: IllegalArgumentException) {
            return error(request, BridgeErrorCodes.REQUEST_INVALID, "typed file request is invalid", false)
        }
        return try {
            backend.execute(typedRequest, cancellation)
                .takeIf { it.requestId == request.requestId }
                ?: error(request, BridgeErrorCodes.UNKNOWN_OUTCOME, "typed file outcome is unknown", false)
        } catch (_: Throwable) {
            // Once the typed executor has been selected, an exception does not
            // prove whether a device-side write reached the helper.
            error(request, BridgeErrorCodes.UNKNOWN_OUTCOME, "typed file outcome is unknown", false)
        }
    }

    private fun executeShell(
        request: BridgeRequestEnvelope,
        cancellation: BridgeCancellation,
    ): BridgeResponseEnvelope {
        val payload = request.payload
        val command = payload.string("command")
            ?: return error(request, BridgeErrorCodes.REQUEST_INVALID, "shell command is required", false)
        val cwd = payload.stringOrNull("cwd")
        val timeout = payload.longOrNull("timeout_ms") ?: 30_000L
        val maxOutput = payload.longOrNull("max_output_bytes") ?: 1L * 1024 * 1024
        val shellRequest = try {
            WiredAdbShellRequest(command, cwd, timeout, maxOutput.toInt())
        } catch (_: IllegalArgumentException) {
            return error(request, BridgeErrorCodes.REQUEST_INVALID, "shell request is invalid", false)
        }
        val result = try {
            shell().execute(shellRequest) { cancellation.isRequested }
        } catch (_: Exception) {
            return error(request, "SHELL_EXECUTION_FAILED", "shell execution failed", true)
        }
        if (result.outcome == ProcessOutcome.UNKNOWN_OUTCOME || result.exitCode == null) {
            // A timeout/cancelled adb process does not provide a reliable remote
            // exit status. Never turn that into a retryable success.
            return error(request, BridgeErrorCodes.UNKNOWN_OUTCOME, "remote exit status is unavailable", false)
        }
        // The adb process stderr is not a remote shell stream.  shell-v2 can
        // mix client diagnostics into it, including the configured serial and
        // canonical adb path.  Drop the marked bytes before constructing the
        // response payload; only remote stdout and bounded status metadata may
        // cross into Android/Provider-facing protocol handling.
        val bridgeStderr = result.stderrForBridge()
        val output = try {
            buildJsonObject {
                put("exit_code", result.exitCode)
                put("stdout_base64", Base64.getEncoder().encodeToString(result.stdout))
                put("stderr_base64", Base64.getEncoder().encodeToString(bridgeStderr))
                put("timed_out", result.timedOut)
                put("cancelled", result.cancelled)
                put("stdout_truncated", result.stdoutTruncated)
                put("stderr_truncated", result.stderrTruncated)
                put("duration_ms", result.durationMs)
            }
        } finally {
            java.util.Arrays.fill(bridgeStderr, 0)
        }
        return BridgeResponseEnvelope(
            protocolVersion = BridgeProtocol.VERSION,
            requestId = request.requestId,
            success = true,
            payload = output,
            // Marked diagnostics were discarded above; never signal to the
            // other side that raw adb stderr is present in this payload.
            stderrMayContainAdbDiagnostics = false,
        )
    }

    private fun error(
        request: BridgeRequestEnvelope,
        code: String,
        message: String,
        retryable: Boolean,
    ): BridgeResponseEnvelope = BridgeResponseEnvelope(
        protocolVersion = BridgeProtocol.VERSION,
        requestId = request.requestId,
        success = false,
        errorCode = code,
        errorMessage = message,
    )
}

/**
 * Authenticated connection reader/dispatcher. Requests execute on a bounded
 * pool, cancellation is a typed encrypted frame, and request IDs remain
 * tombstoned after completion so a replay cannot execute twice.
 */
class AuthenticatedBridgeConnectionHandler(
    private val requestHandler: TypedBridgeRequestHandler,
    private val requestExecutor: ThreadPoolExecutor = boundedExecutor(),
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
        NamedDaemonThreadFactory("mar-bridge-cancel"),
    ),
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val cancelGraceMs: Long = DEFAULT_CANCEL_GRACE_MS,
    private val tombstoneTtlMs: Long = DEFAULT_TOMBSTONE_TTL_MS,
) : CompanionConnectionHandler, AutoCloseable {
    init {
        require(readTimeoutMs in 1..5 * 60 * 1_000)
        require(cancelGraceMs in 1..60_000)
        require(tombstoneTtlMs >= cancelGraceMs)
    }

    override fun handle(socket: java.net.Socket, companion: DesktopCompanion) {
        socket.soTimeout = readTimeoutMs
        val connection = try {
            companion.acceptAuthenticated(socket, readTimeoutMs)
        } catch (_: Exception) {
            runCatching { socket.close() }
            return
        }
        try {
            ConnectionState(connection).run()
        } finally {
            connection.close()
        }
    }

    override fun close() {
        requestExecutor.shutdownNow()
        timer.shutdownNow()
    }

    private inner class ConnectionState(
        private val connection: DesktopAuthenticatedConnection,
    ) {
        private val closed = AtomicBoolean(false)
        private val lock = Any()
        private val active = LinkedHashMap<String, RequestRecord>()
        private val tombstones = LinkedHashMap<String, Tombstone>()

        fun run() {
            try {
                while (!closed.get()) {
                    val frame = connection.readEncrypted()
                    when (frame.type) {
                        BridgeFrameType.REQUEST -> receiveRequest(frame)
                        BridgeFrameType.CANCEL -> receiveCancel(frame)
                        else -> throw IllegalArgumentException("unexpected authenticated frame type")
                    }
                }
            } catch (_: Exception) {
                close()
            }
        }

        private fun receiveRequest(frame: runtime.mobileagent.bridge.BridgeDecodedFrame) {
            val request = try {
                connection.session.decryptRequest(frame)
            } catch (_: Exception) {
                sendError(frame.requestId, BridgeErrorCodes.REQUEST_INVALID, "request is invalid", false)
                return
            }
            val record = RequestRecord(request.requestId, BridgeCancellation())
            synchronized(lock) {
                pruneTombstones(System.currentTimeMillis())
                if (active.containsKey(request.requestId) || tombstones.containsKey(request.requestId)) {
                    sendErrorLocked(request.requestId, BridgeErrorCodes.REQUEST_REPLAYED, "request id was already used", false)
                    return
                }
                active[request.requestId] = record
            }
            try {
                record.future = requestExecutor.submit { execute(record, request) }
            } catch (_: RejectedExecutionException) {
                synchronized(lock) {
                    active.remove(request.requestId)
                    tombstoneLocked(request.requestId, BridgeRequestState.FAILED, System.currentTimeMillis())
                    sendErrorLocked(request.requestId, BridgeErrorCodes.REQUEST_CAPACITY, "request capacity is exhausted", true)
                }
            }
        }

        private fun execute(record: RequestRecord, request: BridgeRequestEnvelope) {
            record.state = BridgeRequestState.RUNNING
            val response = try {
                requestHandler.handle(request, record.cancellation)
            } catch (_: Throwable) {
                errorResponse(request, BridgeErrorCodes.UNKNOWN_OUTCOME, "request outcome is unknown", false)
            }
            synchronized(lock) {
                if (record.terminal) return
                val cancelled = record.cancellation.isRequested
                val finalResponse = if (cancelled) {
                    errorResponse(request, BridgeErrorCodes.UNKNOWN_OUTCOME, "request outcome is unknown", false)
                } else {
                    response.takeIf { it.requestId == request.requestId }
                        ?: errorResponse(request, BridgeErrorCodes.UNKNOWN_OUTCOME, "handler returned an invalid response", false)
                }
                record.terminal = true
                record.state = when {
                    cancelled -> BridgeRequestState.UNKNOWN_OUTCOME
                    finalResponse.errorCode == BridgeErrorCodes.UNKNOWN_OUTCOME -> BridgeRequestState.UNKNOWN_OUTCOME
                    finalResponse.success -> BridgeRequestState.COMPLETED
                    else -> BridgeRequestState.FAILED
                }
                active.remove(request.requestId)
                tombstoneLocked(request.requestId, record.state, System.currentTimeMillis())
                sendResponseLocked(finalResponse)
                if (cancelled) {
                    record.cancelRequestIds.forEach { cancelId ->
                        tombstoneLocked(cancelId, BridgeRequestState.UNKNOWN_OUTCOME, System.currentTimeMillis())
                        sendStatusLocked(
                            cancelId,
                            BridgeRequestState.UNKNOWN_OUTCOME,
                            terminal = true,
                            accepted = true,
                            outcome = BridgeErrorCodes.UNKNOWN_OUTCOME,
                        )
                    }
                }
            }
        }

        private fun receiveCancel(frame: runtime.mobileagent.bridge.BridgeDecodedFrame) {
            val cancel = try {
                connection.session.decryptCancel(frame)
            } catch (_: Exception) {
                sendStatus(frame.requestId, BridgeRequestState.CANCEL_ACK, terminal = false, accepted = false)
                return
            }
            synchronized(lock) {
                pruneTombstones(System.currentTimeMillis())
                if (active.containsKey(cancel.requestId) || tombstones.containsKey(cancel.requestId)) {
                    sendStatusLocked(cancel.requestId, BridgeRequestState.CANCELLED, true, accepted = false, outcome = BridgeErrorCodes.REQUEST_REPLAYED)
                    return
                }
                val record = active[cancel.targetRequestId]
                if (record == null || record.terminal) {
                    // The cancel itself is acknowledged, but no execution is
                    // resurrected for a completed/tombstoned target.
                    tombstoneLocked(cancel.requestId, BridgeRequestState.CANCELLED, System.currentTimeMillis())
                    sendStatusLocked(cancel.requestId, BridgeRequestState.CANCEL_ACK, false, accepted = false)
                    sendStatusLocked(cancel.requestId, BridgeRequestState.CANCELLED, true, accepted = false, outcome = BridgeErrorCodes.REQUEST_CANCELLED)
                    return
                }
                val accepted = record.cancellation.request()
                tombstoneLocked(cancel.requestId, BridgeRequestState.CANCEL_ACK, System.currentTimeMillis())
                if (accepted) {
                    record.state = BridgeRequestState.CANCEL_REQUESTED
                    record.cancelRequestIds += cancel.requestId
                    sendStatusLocked(cancel.requestId, BridgeRequestState.CANCEL_ACK, false, accepted = true)
                    timer.schedule({ forceUnknown(record, cancel.targetRequestId) }, cancelGraceMs, TimeUnit.MILLISECONDS)
                    record.future?.cancel(true)
                } else {
                    sendStatusLocked(cancel.requestId, BridgeRequestState.CANCEL_ACK, false, accepted = false)
                    sendStatusLocked(cancel.requestId, BridgeRequestState.CANCELLED, true, accepted = false, outcome = BridgeErrorCodes.REQUEST_CANCELLED)
                }
            }
        }

        private fun forceUnknown(record: RequestRecord, requestId: String) {
            synchronized(lock) {
                if (record.terminal) return
                record.terminal = true
                record.state = BridgeRequestState.UNKNOWN_OUTCOME
                active.remove(requestId)
                tombstoneLocked(requestId, BridgeRequestState.UNKNOWN_OUTCOME, System.currentTimeMillis())
                sendStatusLocked(requestId, BridgeRequestState.UNKNOWN_OUTCOME, true, accepted = true, outcome = BridgeErrorCodes.UNKNOWN_OUTCOME)
                sendErrorLocked(requestId, BridgeErrorCodes.UNKNOWN_OUTCOME, "request outcome is unknown", false)
                record.cancelRequestIds.forEach { cancelId ->
                    tombstoneLocked(cancelId, BridgeRequestState.UNKNOWN_OUTCOME, System.currentTimeMillis())
                    sendStatusLocked(
                        cancelId,
                        BridgeRequestState.UNKNOWN_OUTCOME,
                        terminal = true,
                        accepted = true,
                        outcome = BridgeErrorCodes.UNKNOWN_OUTCOME,
                    )
                }
            }
        }

        private fun sendError(requestId: String, code: String, message: String, retryable: Boolean) {
            synchronized(lock) {
                sendErrorLocked(requestId, code, message, retryable)
            }
        }

        private fun sendErrorLocked(requestId: String, code: String, message: String, retryable: Boolean) {
            sendResponseLocked(
                BridgeResponseEnvelope(
                    BridgeProtocol.VERSION,
                    requestId,
                    success = false,
                    errorCode = code,
                    errorMessage = message,
                ),
            )
        }

        private fun sendResponseLocked(response: BridgeResponseEnvelope) {
            if (!closed.get()) {
                runCatching { connection.writeEncrypted(BridgeFrameType.RESPONSE, response.requestId, BridgeCodec.encodeResponse(response)) }
                    .onFailure { close() }
            }
        }

        private fun sendStatus(
            requestId: String,
            state: BridgeRequestState,
            terminal: Boolean,
            accepted: Boolean,
            outcome: String? = null,
        ) {
            synchronized(lock) {
                sendStatusLocked(requestId, state, terminal, accepted, outcome)
            }
        }

        private fun sendStatusLocked(
            requestId: String,
            state: BridgeRequestState,
            terminal: Boolean,
            accepted: Boolean,
            outcome: String? = null,
        ) {
            if (!closed.get()) {
                val status = BridgeStatusEnvelope(
                    BridgeProtocol.VERSION,
                    requestId,
                    state.wireName,
                    terminal,
                    accepted,
                    outcome,
                )
                runCatching { connection.writeEncrypted(BridgeFrameType.STATUS, requestId, BridgeCodec.encodeStatus(status)) }
                    .onFailure { close() }
            }
        }

        private fun tombstoneLocked(requestId: String, state: BridgeRequestState, now: Long) {
            tombstones[requestId] = Tombstone(now + tombstoneTtlMs, state)
            while (tombstones.size > MAX_TOMBSTONES) tombstones.remove(tombstones.entries.first().key)
        }

        private fun pruneTombstones(now: Long) {
            val iterator = tombstones.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value.expiresAtMillis <= now) iterator.remove()
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            synchronized(lock) {
                active.values.forEach { it.cancellation.request(); it.future?.cancel(true) }
                active.clear()
                tombstones.clear()
            }
        }
    }

    private class RequestRecord(
        val requestId: String,
        val cancellation: BridgeCancellation,
        val cancelRequestIds: MutableList<String> = ArrayList(),
        @Volatile var future: Future<*>? = null,
        @Volatile var state: BridgeRequestState = BridgeRequestState.ACCEPTED,
        @Volatile var terminal: Boolean = false,
    )

    private data class Tombstone(
        val expiresAtMillis: Long,
        val terminalState: BridgeRequestState,
    )

    companion object {
        private const val DEFAULT_READ_TIMEOUT_MS = 60_000
        private const val DEFAULT_CANCEL_GRACE_MS = 2_000L
        private const val DEFAULT_TOMBSTONE_TTL_MS = 5 * 60 * 1_000L
        private const val MAX_TOMBSTONES = 1_024

        private fun boundedExecutor(): ThreadPoolExecutor = ThreadPoolExecutor(
            1,
            4,
            30,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(32),
            NamedDaemonThreadFactory("mar-bridge-request"),
            ThreadPoolExecutor.AbortPolicy(),
        )
    }
}

private fun errorResponse(
    request: BridgeRequestEnvelope,
    code: String,
    message: String,
    retryable: Boolean,
): BridgeResponseEnvelope = BridgeResponseEnvelope(
    BridgeProtocol.VERSION,
    request.requestId,
    success = false,
    errorCode = code,
    errorMessage = message,
)

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)
    ?.takeIf { it.isString }
    ?.content

private fun JsonObject.stringOrNull(name: String): String? = when (val element = this[name]) {
    null, JsonNull -> null
    else -> string(name) ?: throw IllegalArgumentException("$name must be a string")
}

private fun JsonObject.longOrNull(name: String): Long? = when (val element = this[name]) {
    null, JsonNull -> null
    is JsonPrimitive -> element.takeUnless { it.isString }?.content?.toLongOrNull()
    else -> null
}

private class NamedDaemonThreadFactory(private val prefix: String) : ThreadFactory {
    private val counter = AtomicInteger()
    override fun newThread(runnable: Runnable): Thread = Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply {
        isDaemon = true
    }
}
