// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.resident

import android.net.LocalServerSocket
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import runtime.mobileagent.shizuku.ShizukuUserService

/** Fixed app_process entrypoint. This never initializes Application or AppContainer. */
object ResidentAdbMain {
    // Keep the reservation strongly reachable for the whole daemon lifetime.
    private var singletonSocket: LocalServerSocket? = null
    private enum class Startup { BOOTSTRAP, DETACH, CONTEXT, DISCOVERY, CONSENT, SERVICE, PUBLISH, READY }
    @JvmStatic
    fun main(args: Array<String>) {
        if (Process.myUid() != ResidentAdbProtocol.SHELL_UID) return
        val userId = args.singleOrNull()?.toIntOrNull() ?: if (args.isEmpty()) 0 else return
        if (userId !in 0..21474) return
        var credential: ResidentAdbProtocol.Bootstrap? = null
        var stage = Startup.BOOTSTRAP
        try {
            credential = ResidentAdbProtocol.readBootstrap(System.`in`)
            val bootstrap = checkNotNull(credential)
            stage = Startup.DETACH
            // Background child inherits adb stdin only until the bounded frame has been read.
            Os.setsid()
            val nullFd = Os.open("/dev/null", OsConstants.O_RDWR, 0)
            try {
                Os.dup2(nullFd, 0); Os.dup2(nullFd, 1); Os.dup2(nullFd, 2)
            } finally { Os.close(nullFd) }
            Looper.prepareMainLooper()
            stage = Startup.CONTEXT
            val provider = ResidentAdbProviderTransport(userId)
            // Reserve discovery before consuming activation consent. A surviving previous
            // daemon owns this name; a second activation cannot strand its credential.
            stage = Startup.DISCOVERY
            val socket = LocalServerSocket(socketName(userId))
            singletonSocket = socket
            stage = Startup.CONSENT
            val activation = provider.call("bootstrap", Bundle().apply {
                putByteArray("token", bootstrap.token); putByteArray("secret", bootstrap.secret)
            }) ?: run { startupFailure(stage, "EMPTY_RESPONSE"); return }
            bootstrap.token.fill(0)
            val appUid = activation.getInt("appUid", -1)
            if (appUid < 10_000 || appUid / 100_000 != userId) { startupFailure(stage, "CALLER_INVALID"); return }
            val generation = activation.getString("generation")?.takeIf { it.length == 36 }
                ?: run { startupFailure(stage, "CONSENT_REJECTED"); return }
            val secret = bootstrap.secret
            stage = Startup.SERVICE
            val service = ShizukuUserService(appUid)
            val main = Handler(Looper.getMainLooper())
            val control = object : Binder() {
                init { attachInterface(null, ResidentAdbProtocol.DESCRIPTOR) }
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == INTERFACE_TRANSACTION) { reply?.writeString(ResidentAdbProtocol.DESCRIPTOR); return true }
                    if (Binder.getCallingUid() != appUid) throw SecurityException("app identity required")
                    data.enforceInterface(ResidentAdbProtocol.DESCRIPTOR)
                    return when (code) {
                        ResidentAdbProtocol.PROOF -> {
                            val nonce = data.createByteArray() ?: return false
                            if (nonce.size != ResidentAdbProtocol.NONCE_BYTES || data.dataAvail() != 0) return false
                            val proof = ResidentAdbProtocol.proof(secret, nonce, generation, appUid)
                            try { reply?.writeNoException(); reply?.writeByteArray(proof) }
                            finally { nonce.fill(0); proof.fill(0) }
                            true
                        }
                        ResidentAdbProtocol.SHUTDOWN -> {
                            if (data.dataAvail() != 0) return false
                            reply?.writeNoException()
                            // Capture the authenticated caller before posting; the runner and
                            // all child pipes are closed by the reserved privileged destroy path.
                            main.post { service.destroyInternal(ResidentAdbProtocol.SHELL_UID, ResidentAdbProtocol.SHELL_UID) }
                            true
                        }
                        else -> super.onTransact(code, data, reply, flags)
                    }
                }
            }

            fun publish(): Boolean { return try {
                val response = provider.call("challenge", Bundle().apply { putString("generation", generation) })
                val nonce = response?.getByteArray("nonce") ?: return false
                if (response.getInt("appUid", -1) != appUid || nonce.size != ResidentAdbProtocol.NONCE_BYTES) return false
                val proof = ResidentAdbProtocol.proof(secret, nonce, generation, appUid)
                try {
                    provider.call("publish", Bundle().apply {
                        putString("generation", generation); putByteArray("proof", proof)
                        putBinder("service", service); putBinder("control", control)
                    })?.getBoolean("accepted", false) == true
                } finally { nonce.fill(0); proof.fill(0) }
            } catch (failure: Exception) { startupFailure(Startup.PUBLISH, exceptionCode(failure)); false } }

            // SELinux may deny app -> shell socket connections. The socket only reserves the
            // singleton name. Observe the pinned app process instead, publishing on PID change.
            // A missing/unknown app never triggers a provider call that would start a closed app.
            Thread({
                var observedPid = provider.runningAppPid(appUid)
                var retryPid = 0
                var remaining = 0
                while (true) {
                    try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                    val pid = provider.runningAppPid(appUid) ?: continue
                    if (pid != observedPid) {
                        observedPid = pid
                        retryPid = pid
                        remaining = if (pid > 0) 10 else 0
                    }
                    if (remaining > 0) {
                        remaining--
                        val expectedPid = retryPid
                        main.post {
                            if (provider.runningAppPid(appUid) == expectedPid) publish()
                        }
                    }
                }
            }, "resident-adb-app-presence").apply { isDaemon = true; start() }
            stage = Startup.PUBLISH
            if (!publish()) { startupFailure(stage, "PUBLICATION_REJECTED"); socket.close(); return }
            // No client/USB death listener destroys this process. Only explicit shutdown or
            // actual process loss removes privilege. The credential stays in RAM for this life.
            stage = Startup.READY
            Looper.loop()
        } catch (failure: Exception) {
            startupFailure(stage, exceptionCode(failure))
        } finally { credential?.close() }
    }

    internal fun socketName(userId: Int): String = "runtime.mobileagent.resident-adb.v1.u$userId"

    private fun startupFailure(stage: Startup, code: String) {
        // Closed stage/error fields only. No exception message/stack, token, secret or path.
        Log.e("ResidentADB", "stage=${stage.name} error=$code")
    }

    private fun exceptionCode(failure: Exception): String = when (failure) {
        is android.system.ErrnoException -> "ERRNO"
        is java.io.IOException -> "IO"
        is SecurityException -> "SECURITY"
        is java.lang.reflect.InvocationTargetException -> "REFLECTION_TARGET"
        is ReflectiveOperationException -> "REFLECTION"
        is IllegalArgumentException -> "ARGUMENT"
        else -> "OTHER"
    }
}
