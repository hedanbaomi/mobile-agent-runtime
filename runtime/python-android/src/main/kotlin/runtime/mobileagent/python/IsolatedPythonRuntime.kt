// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.python

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.AssetManager
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select
import runtime.mobileagent.ipc.InvocationTicket
import runtime.mobileagent.ipc.PythonBinderCodec
import runtime.mobileagent.ipc.PythonIpcProtocol
import runtime.mobileagent.ipc.PythonStartMessage

/** Package material supplied by the verified SkillRepository/CAS layer. */
sealed interface PythonPackageSource {
    data class Bytes(val value: ByteArray) : PythonPackageSource
    data class File(val value: java.io.File) : PythonPackageSource
    data class Descriptor(val value: ParcelFileDescriptor) : PythonPackageSource
}

data class PythonExecutionRequest(
    val ticket: InvocationTicket,
    val entrypoint: String,
    val inputJson: String,
    val packageSource: PythonPackageSource,
    val limits: PythonIpcProtocol.PythonLimits = PythonIpcProtocol.PythonLimits(),
    /** Sticky phase notification for callers racing coroutine cancellation. */
    val onDispatched: (() -> Unit)? = null,
)

data class PythonExecutionResult(
    val status: String,
    val valueJson: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val isolatedUid: Int? = null,
    val isolatedPid: Int? = null,
    /** True only after the isolated service explicitly ACKs START. */
    val dispatchAccepted: Boolean = false,
)

/**
 * Cancellation preserves coroutine cancellation while exposing whether the
 * external invocation may already have happened.  Callers must inspect
 * [outcome] before deciding whether a retry is safe.
 */
class PythonExecutionCancellation(
    val outcome: PythonExecutionResult,
) : CancellationException("Python invocation cancellation outcome=${outcome.status}") {
    val dispatchAccepted: Boolean
        get() = outcome.dispatchAccepted
}

/**
 * Host side of the narrow Broker. Implementations must re-read one specific
 * grant for [authorize] on every call and must pass the ticket through to the
 * ToolExecutor/knowledge/model/storage adapter; they must not use a global
 * union of grants.
 */
interface PythonCapabilityBroker {
    /** Fail closed unless the app has an explicit per-ticket grant lookup. */
    suspend fun authorize(ticket: InvocationTicket): Boolean = false

    suspend fun invoke(request: PythonIpcProtocol.BrokerRequest): PythonIpcProtocol.BrokerResponse
}

object DenyingPythonCapabilityBroker : PythonCapabilityBroker {
    override suspend fun authorize(ticket: InvocationTicket): Boolean = false

    override suspend fun invoke(request: PythonIpcProtocol.BrokerRequest): PythonIpcProtocol.BrokerResponse =
        PythonIpcProtocol.BrokerResponse(
            requestId = request.requestId,
            status = "DENIED",
            errorCode = "permission_denied",
            errorMessage = "Capability not granted",
        )
}

/**
 * One-shot host facade. It binds a fresh isolated service for every call and
 * never loads PythonNative in this process.
 */
class IsolatedPythonRuntime(
    context: Context,
    private val broker: PythonCapabilityBroker = DenyingPythonCapabilityBroker,
) {
    private val applicationContext = context.applicationContext

    suspend fun execute(request: PythonExecutionRequest): PythonExecutionResult = try {
        withContext(Dispatchers.IO) {
            val validation = validateRequest(request)
            if (validation != null) return@withContext failed(validation.first, validation.second)
            if (!broker.authorize(request.ticket)) {
                return@withContext failed("permission_denied", "Capability not granted")
            }

            val descriptors = try {
                val packageFd = preparePackage(request.packageSource, request.ticket.packageHash)
                try {
                    val stdlibFd = prepareStdlib(applicationContext.assets)
                    try {
                        val pipes = PipeSet.create()
                        try {
                            InvocationDescriptors(
                                packageFd = packageFd,
                                stdlibFd = stdlibFd,
                                pipes = pipes,
                            )
                        } catch (error: Throwable) {
                            pipes.closeHost()
                            throw error
                        }
                    } catch (error: Throwable) {
                        runCatching { stdlibFd.close() }
                        throw error
                    }
                } catch (error: Throwable) {
                    runCatching { packageFd.close() }
                    throw error
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return@withContext failed("runtime_unavailable", "Python runtime material is unavailable")
            }
            try {
                runBoundInvocation(request, descriptors)
            } finally {
                descriptors.close()
            }
        }
    } catch (cancelled: PythonExecutionCancellation) {
        throw cancelled
    } catch (_: CancellationException) {
        // Cancellation before START is dispatched is a known local outcome.
        throw PythonExecutionCancellation(
            PythonExecutionResult(
                status = PythonIpcProtocol.RESULT_CANCELLED,
                errorCode = "cancelled_before_dispatch",
                errorMessage = "Python invocation cancelled before dispatch",
            ),
        )
    }

    private suspend fun runBoundInvocation(
        request: PythonExecutionRequest,
        descriptors: InvocationDescriptors,
    ): PythonExecutionResult {
        val bound = try {
            bindService()
        } catch (_: Throwable) {
            return failed("service_unavailable", "Isolated Python service unavailable")
        }
        val death = CompletableDeferred<Unit>()
        val deathRecipient = IBinder.DeathRecipient { death.complete(Unit) }
        var linked = false
        var dispatch = StartDispatch()
        try {
            linked = runCatching {
                bound.binder.linkToDeath(deathRecipient, 0)
                true
            }.getOrDefault(false)
            val ping = try {
                ping(bound.binder)
            } catch (_: Throwable) {
                return failed("service_unavailable", "Isolated Python service rejected the protocol")
            }
            if (ping.protocolVersion != PythonIpcProtocol.VERSION || !isIsolatedUidCompat(ping.uid)) {
                return failed("isolation_required", "Python service is not isolated")
            }

            val channelNonce = newChannelNonce()
            val result = coroutineScope {
                val protocolViolation = CompletableDeferred<String>()
                val logLimit = CompletableDeferred<Unit>()
                val cleanupStarted = AtomicBoolean(false)
                val brokerJob = launch(Dispatchers.IO) {
                    brokerLoop(
                        descriptors.pipes.brokerRequestRead,
                        descriptors.pipes.brokerResponseWrite,
                        request,
                        channelNonce,
                    ) { code ->
                        if (protocolViolation.complete(code)) abort(bound.binder, request.ticket)
                    }
                }
                val logJob = launch(Dispatchers.IO) {
                    drainLog(descriptors.pipes.logRead, request.limits.maxLogBytes, cleanupStarted) {
                        if (logLimit.complete(Unit)) abort(bound.binder, request.ticket)
                    }
                }
                var inputJob: Job? = null
                var resultJob: Job? = null
                try {
                    ensureActive()
                    dispatch = start(bound.binder, request, descriptors, channelNonce)
                    ensureActive()
                    if (dispatch.explicitlyRejected) {
                        failed("service_rejected", "Isolated Python service rejected invocation")
                    } else if (!dispatch.accepted) {
                        unknown("dispatch_uncertain", "Isolated Python service did not confirm START")
                    } else {
                        inputJob = launch(Dispatchers.IO) {
                            writeInput(descriptors.pipes.inputWrite, request.inputJson)
                        }
                        val resultDeferred = async(Dispatchers.IO) {
                            readResult(
                                descriptors.pipes.resultRead,
                                request.limits,
                                channelNonce,
                            ) { abort(bound.binder, request.ticket) }
                        }
                        resultJob = resultDeferred
                        try {
                            withTimeout(request.limits.timeoutMs.toLong()) {
                                select {
                                    logLimit.onAwait {
                                        unknown("log_limit", "Python log output limit exceeded")
                                    }
                                    protocolViolation.onAwait { code ->
                                        unknown(code, "Python private channel authentication failed")
                                    }
                                    death.onAwait {
                                        when {
                                            logLimit.isCompleted -> {
                                                unknown("log_limit", "Python log output limit exceeded")
                                            }
                                            protocolViolation.isCompleted -> {
                                                unknown("invalid_nonce", "Python private channel authentication failed")
                                            }
                                            else -> {
                                                val drained = withTimeoutOrNull(RESULT_DRAIN_AFTER_DEATH_MS) {
                                                    resultDeferred.await()
                                                }
                                                when {
                                                    logLimit.isCompleted -> unknown(
                                                        "log_limit",
                                                        "Python log output limit exceeded",
                                                    )
                                                    protocolViolation.isCompleted -> unknown(
                                                        "invalid_nonce",
                                                        "Python private channel authentication failed",
                                                    )
                                                    drained != null -> normalizeWorkerResult(drained)
                                                    else -> unknown(
                                                        "worker_death",
                                                        "Isolated Python service ended before a result",
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    resultDeferred.onAwait { result ->
                                        when {
                                            logLimit.isCompleted -> unknown(
                                                "log_limit",
                                                "Python log output limit exceeded",
                                            )
                                            protocolViolation.isCompleted -> unknown(
                                                "invalid_nonce",
                                                "Python private channel authentication failed",
                                            )
                                            else -> normalizeWorkerResult(result)
                                        }
                                    }
                                }
                            }
                        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                            val outcome = if (dispatch.mayHaveRun) {
                                stopAfterDispatch(bound.binder, request.ticket, death)
                                unknown("timeout_after_dispatch", "Python invocation outcome is unknown after timeout")
                            } else {
                                PythonExecutionResult(
                                    status = PythonIpcProtocol.RESULT_TIMED_OUT,
                                    errorCode = "timeout_before_dispatch",
                                    errorMessage = "Python invocation timed out before dispatch",
                                )
                            }
                            outcome
                        } catch (cancelled: PythonExecutionCancellation) {
                            throw cancelled
                        } catch (_: CancellationException) {
                            val outcome = if (dispatch.mayHaveRun) {
                                stopAfterDispatch(bound.binder, request.ticket, death)
                                unknown(
                                    if (dispatch.accepted) "cancelled_after_dispatch" else "cancelled_after_start_send",
                                    "Python invocation outcome is unknown after cancellation",
                                    dispatchAccepted = dispatch.accepted,
                                )
                            } else {
                                PythonExecutionResult(
                                    status = PythonIpcProtocol.RESULT_CANCELLED,
                                    errorCode = "cancelled_before_dispatch",
                                    errorMessage = "Python invocation cancelled before dispatch",
                                )
                            }
                            throw PythonExecutionCancellation(outcome)
                        }
                    }
                } catch (cancelled: PythonExecutionCancellation) {
                    throw cancelled
                } catch (_: CancellationException) {
                    val outcome = if (dispatch.mayHaveRun) {
                        stopAfterDispatch(bound.binder, request.ticket, death)
                        unknown(
                            if (dispatch.accepted) "cancelled_after_dispatch" else "cancelled_after_start_send",
                            "Python invocation outcome is unknown after cancellation",
                            dispatchAccepted = dispatch.accepted,
                        )
                    } else {
                        PythonExecutionResult(
                            status = PythonIpcProtocol.RESULT_CANCELLED,
                            errorCode = "cancelled_before_dispatch",
                            errorMessage = "Python invocation cancelled before dispatch",
                        )
                    }
                    throw PythonExecutionCancellation(outcome)
                } catch (_: Throwable) {
                    if (dispatch.mayHaveRun) {
                        unknown("dispatch_failed", "Python service stopped after dispatch")
                    } else {
                        failed("service_unavailable", "Isolated Python service stopped")
                    }
                } finally {
                    // Closing the descriptors before cancelling the blocking
                    // pipe readers is required on API 26, where a coroutine
                    // cancellation does not interrupt an InputStream read.
                    cleanupStarted.set(true)
                    descriptors.pipes.closeHost()
                    inputJob?.cancel()
                    resultJob?.cancel()
                    brokerJob.cancel()
                    logJob.cancel()
                }
            }
            // The process exits after writing its result. Waiting briefly for
            // binder death prevents an immediate next call from reusing it.
            withContext(kotlinx.coroutines.NonCancellable) {
                withTimeoutOrNull(PROCESS_DEATH_WAIT_MS) { death.await() }
            }
            return result.copy(
                isolatedUid = ping.uid,
                isolatedPid = ping.pid,
                dispatchAccepted = dispatch.accepted,
            )
        } finally {
            if (linked) runCatching { bound.binder.unlinkToDeath(deathRecipient, 0) }
            runCatching { applicationContext.unbindService(bound.connection) }
        }
    }

    private fun validateRequest(request: PythonExecutionRequest): Pair<String, String>? {
        if (!request.ticket.validate()) return "invalid_request" to "Invalid invocation ticket"
        if (!PythonIpcProtocol.validateEntrypoint(request.entrypoint)) return "invalid_request" to "Invalid Python entrypoint"
        if (!PythonIpcProtocol.validateLimits(request.limits)) return "invalid_request" to "Invalid Python limits"
        if (request.inputJson.toByteArray(Charsets.UTF_8).size > request.limits.maxInputBytes) {
            return "input_limit" to "Python input limit exceeded"
        }
        return null
    }

    private suspend fun brokerLoop(
        requestRead: ParcelFileDescriptor,
        responseWrite: ParcelFileDescriptor,
        request: PythonExecutionRequest,
        expectedNonce: String,
        onProtocolViolation: (String) -> Unit,
    ) {
        ParcelFileDescriptor.AutoCloseInputStream(requestRead).use { input ->
            ParcelFileDescriptor.AutoCloseOutputStream(responseWrite).use { output ->
                while (true) {
                    val payload = try {
                        PythonIpcProtocol.Frames.read(input)
                    } catch (_: Throwable) {
                        onProtocolViolation("invalid_nonce")
                        return
                    } ?: return
                    val frame = try {
                        PythonIpcProtocol.decodeBrokerRequestFrame(payload)
                    } catch (_: Throwable) {
                        onProtocolViolation("invalid_nonce")
                        return
                    }
                    if (frame.channelNonce != expectedNonce) {
                        onProtocolViolation("invalid_nonce")
                        return
                    }
                    val brokerRequest = frame.request
                    val response = if (brokerRequest.ticket != request.ticket || !broker.authorize(brokerRequest.ticket)) {
                        PythonIpcProtocol.BrokerResponse(
                            requestId = brokerRequest.requestId,
                            status = "DENIED",
                            errorCode = "permission_denied",
                            errorMessage = "Capability not granted",
                        )
                    } else {
                        try {
                            withTimeout(BROKER_CALL_TIMEOUT_MS) { broker.invoke(brokerRequest) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            PythonIpcProtocol.BrokerResponse(
                                requestId = brokerRequest.requestId,
                                status = "ERROR",
                                errorCode = "broker_error",
                                errorMessage = "Capability failed",
                            )
                        }
                    }
                    val normalized = response.copy(requestId = brokerRequest.requestId)
                    val encoded = try {
                        PythonIpcProtocol.encodeBrokerResponseChunks(normalized)
                    } catch (_: Throwable) {
                        listOf(PythonIpcProtocol.encodeBrokerResponse(
                            PythonIpcProtocol.BrokerResponse(
                                requestId = brokerRequest.requestId,
                                status = "ERROR",
                                errorCode = "broker_protocol",
                                errorMessage = "Invalid capability response",
                            ),
                        ))
                    }
                    encoded.forEach { frame -> PythonIpcProtocol.Frames.write(output, frame) }
                }
            }
        }
    }

    private fun writeInput(inputWrite: ParcelFileDescriptor, inputJson: String) {
        ParcelFileDescriptor.AutoCloseOutputStream(inputWrite).use { output ->
            output.write(inputJson.toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    private fun readResult(
        resultRead: ParcelFileDescriptor,
        limits: PythonIpcProtocol.PythonLimits,
        expectedNonce: String,
        onInvalidFrame: () -> Unit,
    ): PythonExecutionResult {
        return try {
            ParcelFileDescriptor.AutoCloseInputStream(resultRead).use { input ->
                val headerPayload = try {
                    PythonIpcProtocol.Frames.read(input)
                } catch (_: Throwable) {
                    onInvalidFrame()
                    return@use unknown("invalid_result", "Python service returned an invalid result frame")
                } ?: return@use unknown("service_stopped", "Python service ended without a result")
                val header = try {
                    PythonIpcProtocol.decodeResultHeader(headerPayload, expectedNonce)
                } catch (_: Throwable) {
                    onInvalidFrame()
                    return@use unknown("invalid_nonce", "Python service result authentication failed")
                }
                if (header.outputBytes > limits.maxOutputBytes) {
                    return@use unknown("output_limit", "Python output limit exceeded")
                }
                val output = ByteArray(header.outputBytes)
                try {
                    readFully(input, output)
                } catch (_: EOFException) {
                    return@use unknown("result_truncated", "Python service result ended early")
                }
                PythonExecutionResult(
                    status = header.status,
                    valueJson = output.toString(Charsets.UTF_8).takeIf { it.isNotEmpty() },
                    errorCode = header.errorCode,
                    errorMessage = header.errorMessage,
                )
            }
        } catch (_: EOFException) {
            unknown("result_truncated", "Python service result ended early")
        } catch (_: Throwable) {
            unknown("invalid_result", "Python service returned an invalid result")
        }
    }

    private fun drainLog(
        logRead: ParcelFileDescriptor,
        maximum: Int,
        cleanupStarted: AtomicBoolean,
        onLimit: () -> Unit,
    ) {
        try {
            ParcelFileDescriptor.AutoCloseInputStream(logRead).use { input ->
                val buffer = ByteArray(8192)
                var consumed = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) return
                    consumed += count.toLong()
                    if (consumed > maximum.toLong()) {
                        onLimit()
                        return
                    }
                }
            }
        } catch (error: IOException) {
            // On API 26 closing a blocking ParcelFileDescriptor wakes the read
            // with InterruptedIOException. It is expected only after the
            // invocation has selected its outcome and entered cleanup. An
            // earlier log-pipe failure remains an invocation failure and must
            // not be silently converted into success.
            if (!cleanupStarted.get()) throw error
        }
    }

    private fun start(
        binder: IBinder,
        request: PythonExecutionRequest,
        descriptors: InvocationDescriptors,
        channelNonce: String,
    ): StartDispatch {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            PythonBinderCodec.writeStart(
                data,
                PythonStartMessage(
                    ticket = request.ticket,
                    entrypoint = request.entrypoint,
                    limits = request.limits,
                    channelNonce = channelNonce,
                    packageFd = descriptors.packageFd,
                    stdlibFd = descriptors.stdlibFd,
                    inputFd = descriptors.pipes.inputRead,
                    resultFd = descriptors.pipes.resultWrite,
                    brokerRequestFd = descriptors.pipes.brokerRequestWrite,
                    brokerResponseFd = descriptors.pipes.brokerResponseRead,
                    logFd = descriptors.pipes.logWrite,
                ),
            )
            // This callback is intentionally before the Binder call.  It lets
            // a caller record the uncertain phase even if transact throws or
            // cancellation races the ACK.
            runCatching { request.onDispatched?.invoke() }
            val transacted = runCatching {
                binder.transact(PythonIpcProtocol.TRANSACTION_START, data, reply, 0)
            }.getOrDefault(false)
            if (!transacted) return StartDispatch(dispatchAttempted = true)
            val acknowledgement = runCatching { reply.readInt() }.getOrNull()
                ?: return StartDispatch(dispatchAttempted = true)
            if (acknowledgement == PythonIpcProtocol.ACK_ACCEPTED) {
                StartDispatch(dispatchAttempted = true, accepted = true)
            } else if (acknowledgement == PythonIpcProtocol.ACK_REJECTED) {
                StartDispatch(dispatchAttempted = true, explicitlyRejected = true)
            } else {
                StartDispatch(dispatchAttempted = true)
            }
        } finally {
            data.recycle()
            reply.recycle()
            descriptors.closeServiceCopies()
        }
    }

    private suspend fun stopAfterDispatch(
        binder: IBinder,
        ticket: InvocationTicket,
        death: CompletableDeferred<Unit>,
    ) {
        withContext(NonCancellable) {
            cancel(binder, ticket)
            withTimeoutOrNull(PROCESS_DEATH_WAIT_MS) { death.await() }
        }
    }

    private fun cancel(binder: IBinder, ticket: InvocationTicket) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            PythonBinderCodec.writeCancel(data, ticket)
            runCatching { binder.transact(PythonIpcProtocol.TRANSACTION_CANCEL, data, reply, 0) }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun abort(binder: IBinder, ticket: InvocationTicket) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            PythonBinderCodec.writeAbort(data, ticket)
            runCatching { binder.transact(PythonIpcProtocol.TRANSACTION_ABORT, data, reply, 0) }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun ping(binder: IBinder): PingReply {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            PythonBinderCodec.writePing(data)
            if (!binder.transact(PythonIpcProtocol.TRANSACTION_PING, data, reply, 0)) {
                throw RemoteException("ping failed")
            }
            return PingReply(reply.readInt(), reply.readInt(), reply.readInt())
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private suspend fun bindService(): BoundService = suspendCancellableCoroutine { continuation ->
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                continuation.resume(BoundService(this, service))
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (continuation.isActive) continuation.resumeWithException(RemoteException("service disconnected"))
            }

            override fun onBindingDied(name: ComponentName) {
                if (continuation.isActive) continuation.resumeWithException(RemoteException("service binding died"))
            }

            override fun onNullBinding(name: ComponentName) {
                if (continuation.isActive) continuation.resumeWithException(RemoteException("null service binding"))
            }
        }
        val intent = Intent(applicationContext, IsolatedPythonService::class.java)
        val bound = runCatching {
            applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            continuation.resumeWithException(RemoteException("bindService failed"))
        }
        continuation.invokeOnCancellation {
            runCatching { applicationContext.unbindService(connection) }
        }
    }

    private suspend fun preparePackage(source: PythonPackageSource, expectedHash: String): ParcelFileDescriptor =
        withContext(Dispatchers.IO) {
            val temp = tempFile("package")
            var success = false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val input = when (source) {
                    is PythonPackageSource.Bytes -> ByteArrayInputStream(source.value)
                    is PythonPackageSource.File -> FileInputStream(source.value)
                    is PythonPackageSource.Descriptor ->
                        ParcelFileDescriptor.AutoCloseInputStream(
                            ParcelFileDescriptor.dup(source.value.fileDescriptor),
                        )
                }
                input.use { stream ->
                    FileOutputStream(temp).use { output ->
                        copyBounded(stream, output, digest, MAX_PACKAGE_BYTES)
                    }
                }
                val actual = digest.digest().toHex()
                if (!actual.equals(expectedHash, ignoreCase = true)) throw IllegalArgumentException("package hash mismatch")
                val descriptor = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
                success = true
                descriptor
            } finally {
                if (!success || !temp.delete()) temp.delete()
            }
        }

    private suspend fun prepareStdlib(assets: AssetManager): ParcelFileDescriptor = withContext(Dispatchers.IO) {
        val temp = tempFile("stdlib")
        var success = false
        try {
            assets.open(STDLIB_ASSET, AssetManager.ACCESS_STREAMING).use { input ->
                FileOutputStream(temp).use { output ->
                    copyBounded(input, output, null, MAX_STDLIB_BYTES)
                }
            }
            val descriptor = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            success = true
            descriptor
        } finally {
            if (!success || !temp.delete()) temp.delete()
        }
    }

    private fun tempFile(prefix: String): File {
        val directory = File(applicationContext.cacheDir, "python-runtime")
        check(directory.exists() || directory.mkdirs())
        return File.createTempFile(".$prefix-", ".bin", directory)
    }

    private fun copyBounded(input: InputStream, output: OutputStream, digest: MessageDigest?, maximum: Long) {
        val buffer = ByteArray(32 * 1024)
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            copied += count
            if (copied > maximum) throw IllegalArgumentException("runtime material exceeds limit")
            output.write(buffer, 0, count)
            digest?.update(buffer, 0, count)
        }
    }

    private fun readFully(input: InputStream, output: ByteArray) {
        var offset = 0
        while (offset < output.size) {
            val count = input.read(output, offset, output.size - offset)
            if (count < 0) throw EOFException("result ended early")
            if (count == 0) continue
            offset += count
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun newChannelNonce(): String {
        val bytes = ByteArray(PythonIpcProtocol.CHANNEL_NONCE_BYTES)
        SecureRandom().nextBytes(bytes)
        val encoded = Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        check(PythonIpcProtocol.validateChannelNonce(encoded))
        return encoded
    }

    private fun unknown(code: String, message: String, dispatchAccepted: Boolean = false) =
        PythonExecutionResult(
            status = PythonIpcProtocol.RESULT_UNKNOWN,
            errorCode = code,
            errorMessage = message,
            dispatchAccepted = dispatchAccepted,
        )

    private fun normalizeWorkerResult(result: PythonExecutionResult): PythonExecutionResult =
        if (result.errorCode == "output_limit") {
            // The Python function may already have produced side effects when
            // serialization crossed the configured output bound.  A local
            // worker error is therefore not a safe replay signal.
            unknown("output_limit", "Python output limit exceeded", dispatchAccepted = true)
        } else {
            result
        }

    private fun failed(code: String, message: String) =
        PythonExecutionResult(status = PythonIpcProtocol.RESULT_FAILED, errorCode = code, errorMessage = message)

    private data class BoundService(val connection: ServiceConnection, val binder: IBinder)
    private data class PingReply(val protocolVersion: Int, val uid: Int, val pid: Int)
    private data class StartDispatch(
        val dispatchAttempted: Boolean = false,
        val accepted: Boolean = false,
        val explicitlyRejected: Boolean = false,
    ) {
        val mayHaveRun: Boolean
            get() = accepted || (dispatchAttempted && !explicitlyRejected)
    }

    private class InvocationDescriptors(
        val packageFd: ParcelFileDescriptor,
        val stdlibFd: ParcelFileDescriptor,
        val pipes: PipeSet,
    ) {
        fun closeServiceCopies() {
            listOf(
                packageFd,
                stdlibFd,
                pipes.inputRead,
                pipes.resultWrite,
                pipes.brokerRequestWrite,
                pipes.brokerResponseRead,
                pipes.logWrite,
            ).forEach { runCatching { it.close() } }
        }

        fun close() {
            closeServiceCopies()
            pipes.closeHost()
            runCatching { packageFd.close() }
            runCatching { stdlibFd.close() }
        }
    }

    private class PipeSet private constructor(
        val inputRead: ParcelFileDescriptor,
        val inputWrite: ParcelFileDescriptor,
        val resultRead: ParcelFileDescriptor,
        val resultWrite: ParcelFileDescriptor,
        val brokerRequestRead: ParcelFileDescriptor,
        val brokerRequestWrite: ParcelFileDescriptor,
        val brokerResponseRead: ParcelFileDescriptor,
        val brokerResponseWrite: ParcelFileDescriptor,
        val logRead: ParcelFileDescriptor,
        val logWrite: ParcelFileDescriptor,
    ) {
        fun closeServiceCopies() {
            listOf(inputRead, resultWrite, brokerRequestWrite, brokerResponseRead, logWrite)
                .forEach { runCatching { it.close() } }
        }

        fun closeHostWriters() {
            listOf(inputWrite, brokerResponseWrite).forEach { runCatching { it.close() } }
        }

        fun closeHost() {
            listOf(
                inputRead,
                inputWrite,
                resultRead,
                resultWrite,
                brokerRequestRead,
                brokerRequestWrite,
                brokerResponseRead,
                brokerResponseWrite,
                logRead,
                logWrite,
            ).forEach { runCatching { it.close() } }
        }

        companion object {
            fun create(): PipeSet {
                val input = ParcelFileDescriptor.createPipe()
                val result = ParcelFileDescriptor.createPipe()
                val brokerRequest = ParcelFileDescriptor.createPipe()
                val brokerResponse = ParcelFileDescriptor.createPipe()
                val log = ParcelFileDescriptor.createPipe()
                return PipeSet(
                    inputRead = input[0],
                    inputWrite = input[1],
                    resultRead = result[0],
                    resultWrite = result[1],
                    brokerRequestRead = brokerRequest[0],
                    brokerRequestWrite = brokerRequest[1],
                    brokerResponseRead = brokerResponse[0],
                    brokerResponseWrite = brokerResponse[1],
                    logRead = log[0],
                    logWrite = log[1],
                )
            }
        }
    }

    private companion object {
        const val STDLIB_ASSET = "python/python3.14.zip"
        const val MAX_PACKAGE_BYTES = 32L * 1024L * 1024L
        const val MAX_STDLIB_BYTES = 16L * 1024L * 1024L
        const val BROKER_CALL_TIMEOUT_MS = 10_000L
        const val PROCESS_DEATH_WAIT_MS = 2_000L
        const val RESULT_DRAIN_AFTER_DEATH_MS = 1_000L
    }
}
