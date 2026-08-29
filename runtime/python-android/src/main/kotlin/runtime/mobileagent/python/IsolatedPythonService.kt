// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.python

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import runtime.mobileagent.ipc.InvocationTicket
import runtime.mobileagent.ipc.PythonBinderCodec
import runtime.mobileagent.ipc.PythonIpcProtocol
import runtime.mobileagent.ipc.PythonStartMessage

/**
 * The only process in which CPython is loaded. It accepts one start message,
 * executes one invocation, closes its descriptors and terminates its process.
 */
class IsolatedPythonService : Service() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "python-isolated-invocation").apply { isDaemon = false }
    }
    private val handler by lazy { Handler(mainLooper) }
    private val stateLock = Any()
    private var accepted = false
    private var runningTicket: InvocationTicket? = null
    private var nativeRunning = false
    private var cancelRequested = false
    private var nativeStarted = false

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (!isIsolatedServiceProcess()) return false
            return when (code) {
                PythonIpcProtocol.TRANSACTION_PING -> handlePing(data, reply)
                PythonIpcProtocol.TRANSACTION_START -> handleStart(data, reply)
                PythonIpcProtocol.TRANSACTION_CANCEL -> handleCancel(data, reply)
                PythonIpcProtocol.TRANSACTION_ABORT -> handleAbort(data, reply)
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!isIsolatedServiceProcess()) stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (isIsolatedServiceProcess()) binder else null

    private fun handlePing(data: Parcel, reply: Parcel?): Boolean {
        data.enforceInterface(PythonIpcProtocol.DESCRIPTOR)
        reply ?: return true
        reply.writeInt(PythonIpcProtocol.VERSION)
        reply.writeInt(Process.myUid())
        reply.writeInt(Process.myPid())
        return true
    }

    private fun handleStart(data: Parcel, reply: Parcel?): Boolean {
        val message = try {
            PythonBinderCodec.readStart(data)
        } catch (_: Throwable) {
            reply?.writeInt(PythonIpcProtocol.ACK_REJECTED)
            reply?.writeString("invalid start message")
            return true
        }
        val rejection = validateStart(message)
        if (rejection != null) {
            closeMessage(message)
            reply?.writeInt(PythonIpcProtocol.ACK_REJECTED)
            reply?.writeString(rejection)
            return true
        }
        synchronized(stateLock) {
            if (accepted) {
                closeMessage(message)
                reply?.writeInt(PythonIpcProtocol.ACK_REJECTED)
                reply?.writeString("service accepts one invocation")
                return true
            }
            accepted = true
            runningTicket = message.ticket
            nativeRunning = true
            cancelRequested = false
            nativeStarted = false
        }
        try {
            executor.execute { execute(message) }
            reply?.writeInt(PythonIpcProtocol.ACK_ACCEPTED)
            reply?.writeString(null)
        } catch (_: Throwable) {
            synchronized(stateLock) { nativeRunning = false }
            closeMessage(message)
            reply?.writeInt(PythonIpcProtocol.ACK_REJECTED)
            reply?.writeString("service unavailable")
        }
        return true
    }

    private fun handleCancel(data: Parcel, reply: Parcel?): Boolean {
        val ticket = try {
            PythonBinderCodec.readCancel(data)
        } catch (_: Throwable) {
            reply?.writeInt(PythonIpcProtocol.ACK_REJECTED)
            return true
        }
        val acceptedCancellation = synchronized(stateLock) {
            val accepted = nativeRunning && runningTicket == ticket
            if (accepted) cancelRequested = true
            accepted
        }
        if (acceptedCancellation) {
            PythonNative.cancel()
            handler.postDelayed({ killAfterCancellation() }, CANCEL_GRACE_MS)
        }
        reply?.writeInt(if (acceptedCancellation) PythonIpcProtocol.ACK_ACCEPTED else PythonIpcProtocol.ACK_REJECTED)
        return true
    }

    /**
     * Abort is reserved for an authenticated private-channel violation (for
     * example a forged frame without the current nonce).  Killing this
     * disposable process immediately prevents a malformed peer from being
     * treated as a later valid invocation.
     */
    private fun handleAbort(data: Parcel, reply: Parcel?): Boolean {
        val ticket = try {
            PythonBinderCodec.readAbort(data)
        } catch (_: Throwable) {
            reply?.writeInt(PythonIpcProtocol.ACK_REJECTED)
            return true
        }
        val shouldAbort = synchronized(stateLock) {
            val matches = nativeRunning && runningTicket == ticket
            if (matches) cancelRequested = true
            matches
        }
        reply?.writeInt(if (shouldAbort) PythonIpcProtocol.ACK_ACCEPTED else PythonIpcProtocol.ACK_REJECTED)
        if (shouldAbort) Process.killProcess(Process.myPid())
        return true
    }

    private fun execute(message: PythonStartMessage) {
        try {
            val shouldStart = synchronized(stateLock) {
                if (cancelRequested) {
                    false
                } else {
                    nativeStarted = true
                    true
                }
            }
            if (shouldStart) {
                PythonNative.run(
                    invocationId = message.ticket.invocationId,
                    runId = message.ticket.runId,
                    packageHash = message.ticket.packageHash,
                    grantRevision = message.ticket.grantRevision,
                    oneTimeToken = message.ticket.oneTimeToken,
                    channelNonce = message.channelNonce,
                    entrypoint = message.entrypoint,
                    timeoutMs = message.limits.timeoutMs,
                    maxOutputBytes = message.limits.maxOutputBytes,
                    maxLogBytes = message.limits.maxLogBytes,
                    maxInputBytes = message.limits.maxInputBytes,
                    maxBrokerCalls = message.limits.maxBrokerCalls,
                    packageFd = message.packageFd,
                    stdlibFd = message.stdlibFd,
                    inputFd = message.inputFd,
                    resultFd = message.resultFd,
                    brokerRequestFd = message.brokerRequestFd,
                    brokerResponseFd = message.brokerResponseFd,
                    logFd = message.logFd,
                )
            }
        } catch (_: Throwable) {
            // The host classifies a failed JNI dispatch as UNKNOWN_OUTCOME.
            closeMessage(message)
        } finally {
            synchronized(stateLock) {
                nativeStarted = false
                nativeRunning = false
                runningTicket = null
            }
            closeMessage(message)
            stopSelf()
            // A new invocation must receive a new isolated UID and a fresh
            // CPython interpreter, so this process is disposable by design.
            handler.postDelayed({ Process.killProcess(Process.myPid()) }, PROCESS_EXIT_GRACE_MS)
        }
    }

    private fun killAfterCancellation() {
        synchronized(stateLock) {
            if (!nativeRunning) return
        }
        Process.killProcess(Process.myPid())
    }

    private fun validateStart(message: PythonStartMessage): String? {
        if (!message.ticket.validate()) return "invalid invocation ticket"
        if (!PythonIpcProtocol.validateChannelNonce(message.channelNonce)) return "invalid channel nonce"
        if (!PythonIpcProtocol.validateEntrypoint(message.entrypoint)) return "invalid entrypoint"
        if (!PythonIpcProtocol.validateLimits(message.limits)) return "invalid limits"
        if (!isReadOnly(message.packageFd) || !isReadOnly(message.stdlibFd) || !isReadOnly(message.inputFd)) {
            return "package, stdlib and input descriptors must be read-only"
        }
        if (!isReadOnly(message.brokerResponseFd) ||
            !isWriteOnly(message.resultFd) || !isWriteOnly(message.brokerRequestFd) ||
            !isWriteOnly(message.logFd)) {
            return "IPC descriptor directions are invalid"
        }
        if (message.packageFd.statSize == 0L || message.stdlibFd.statSize == 0L) return "empty runtime descriptor"
        return null
    }

    private fun isReadOnly(descriptor: ParcelFileDescriptor): Boolean =
        descriptorAccessMode(descriptor) == OsConstants.O_RDONLY

    private fun isWriteOnly(descriptor: ParcelFileDescriptor): Boolean =
        descriptorAccessMode(descriptor) == OsConstants.O_WRONLY

    /**
     * Android only exposed Os.fcntlInt in API 30. On API 26-29, read the Linux fdinfo entry for
     * this already-open descriptor; `flags` is an octal value and O_ACCMODE is stable across the
     * supported kernels. Any unavailable or malformed entry fails closed.
     */
    private fun descriptorAccessMode(descriptor: ParcelFileDescriptor): Int? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Os.fcntlInt(descriptor.fileDescriptor, OsConstants.F_GETFL, 0)
        } else {
            File("/proc/self/fdinfo/${descriptor.fd}").useLines { lines ->
                lines.firstOrNull { it.startsWith("flags:") }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.toLongOrNull(radix = 8)
                    ?.toInt()
                    ?: error("descriptor flags unavailable")
            }
        }
        flags and OsConstants.O_ACCMODE
    }.getOrNull()

    private fun closeMessage(message: PythonStartMessage) {
        listOf(
            message.packageFd,
            message.stdlibFd,
            message.inputFd,
            message.resultFd,
            message.brokerRequestFd,
            message.brokerResponseFd,
            message.logFd,
        ).forEach { descriptor -> runCatching { descriptor.close() } }
    }

    private fun isIsolatedServiceProcess(): Boolean = isCurrentProcessIsolated()

    override fun onDestroy() {
        var shouldCancel = false
        synchronized(stateLock) {
            if (nativeRunning) {
                cancelRequested = true
                shouldCancel = nativeStarted
            }
            nativeRunning = false
        }
        if (shouldCancel) PythonNative.cancel()
        executor.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val CANCEL_GRACE_MS = 500L
        const val PROCESS_EXIT_GRACE_MS = 100L
    }
}

object PythonRuntimeGate {
    fun refuseMainProcessExecution(): Nothing =
        throw IllegalStateException("Python may only run in the isolated service")

    fun requireIsolatedProcess() {
        check(isCurrentProcessIsolated()) {
            "Python runtime loaded outside the isolated service"
        }
    }
}

/**
 * API 28 added the exact self check.  On API 26/27 the public SDK exposes
 * [Process.isApplicationUid], which is the only UID classification available;
 * the manifest's isolatedProcess flag makes the service framework-owned UID
 * the only valid non-application UID for this entrypoint.
 */
internal fun isCurrentProcessIsolated(): Boolean =
    if (Build.VERSION.SDK_INT >= 28) Process.isIsolated() else !Process.isApplicationUid(Process.myUid())

/** Check an arbitrary UID without calling a method unavailable before API 34. */
internal fun isIsolatedUidCompat(uid: Int): Boolean =
    if (Build.VERSION.SDK_INT >= 34) Process.isIsolatedUid(uid) else !Process.isApplicationUid(uid)
