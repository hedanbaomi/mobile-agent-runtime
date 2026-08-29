// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.python

import android.os.ParcelFileDescriptor

/**
 * JNI is referenced only from [IsolatedPythonService].  The host runtime does
 * not load this object, so the audited CPython shared library is never loaded
 * into the application process.
 */
internal object PythonNative {
    init {
        System.loadLibrary("mobileagent_python")
    }

    fun run(
        invocationId: String,
        runId: String,
        packageHash: String,
        grantRevision: Int,
        oneTimeToken: String,
        channelNonce: String,
        entrypoint: String,
        timeoutMs: Int,
        maxOutputBytes: Int,
        maxLogBytes: Int,
        maxInputBytes: Int,
        maxBrokerCalls: Int,
        packageFd: ParcelFileDescriptor,
        stdlibFd: ParcelFileDescriptor,
        inputFd: ParcelFileDescriptor,
        resultFd: ParcelFileDescriptor,
        brokerRequestFd: ParcelFileDescriptor,
        brokerResponseFd: ParcelFileDescriptor,
        logFd: ParcelFileDescriptor,
    ): Int = nativeRun(
        invocationId,
        runId,
        packageHash,
        grantRevision,
        oneTimeToken,
        channelNonce,
        entrypoint,
        timeoutMs,
        maxOutputBytes,
        maxLogBytes,
        maxInputBytes,
        maxBrokerCalls,
        packageFd.detachFd(),
        stdlibFd.detachFd(),
        inputFd.detachFd(),
        resultFd.detachFd(),
        brokerRequestFd.detachFd(),
        brokerResponseFd.detachFd(),
        logFd.detachFd(),
    )

    fun cancel() = nativeCancel()

    private external fun nativeRun(
        invocationId: String,
        runId: String,
        packageHash: String,
        grantRevision: Int,
        oneTimeToken: String,
        channelNonce: String,
        entrypoint: String,
        timeoutMs: Int,
        maxOutputBytes: Int,
        maxLogBytes: Int,
        maxInputBytes: Int,
        maxBrokerCalls: Int,
        packageFd: Int,
        stdlibFd: Int,
        inputFd: Int,
        resultFd: Int,
        brokerRequestFd: Int,
        brokerResponseFd: Int,
        logFd: Int,
    ): Int

    private external fun nativeCancel()
}
