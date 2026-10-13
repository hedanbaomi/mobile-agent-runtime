// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.python

import android.os.ParcelFileDescriptor
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import runtime.mobileagent.ipc.PythonIpcProtocol

/** Private framed capability channel. Authentication and live authorization precede dispatch. */
internal suspend fun runPythonBrokerChannel(
    requestRead: ParcelFileDescriptor,
    responseWrite: ParcelFileDescriptor,
    request: PythonExecutionRequest,
    expectedNonce: String,
    broker: PythonCapabilityBroker,
    brokerCallTimeoutMs: Long,
    onProtocolViolation: (String) -> Unit,
    onResponseChannelClosed: () -> Unit,
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
                        withTimeout(brokerCallTimeoutMs) { broker.invoke(brokerRequest) }
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
                // A host-side close of this pipe (teardown or a revoked
                // invocation) makes FileOutputStream.write throw an
                // InterruptedIOException on this thread.  That is an I/O
                // failure of the private channel, not an outcome of the
                // invocation: hand it to the invocation scope so the call
                // terminates as a bounded UNKNOWN instead of escaping as an
                // uncaught exception.  The capability decision above has
                // already been enforced, so this cannot grant anything.
                try {
                    encoded.forEach { frame -> PythonIpcProtocol.Frames.write(output, frame) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    onResponseChannelClosed()
                    return
                }
            }
        }
    }
}
