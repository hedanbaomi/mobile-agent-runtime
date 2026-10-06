// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.resident

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.ErrnoException
import android.system.OsConstants
import android.system.Os
import android.util.Log
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import org.json.JSONObject
import runtime.mobileagent.shizuku.DeviceServiceConnection
import runtime.mobileagent.shizuku.IShizukuCommandService
import runtime.mobileagent.shizuku.ShizukuBridgePolicy

/** Process-local Binder cache and durable app consent are deliberately separate. */
internal class ResidentAdbRegistry private constructor(
    context: Context,
    private val elapsedNow: () -> Long = { SystemClock.elapsedRealtime() },
) : DeviceServiceConnection {
    enum class Publication { CONNECTED, ABSENT, UNKNOWN, NO_CREDENTIAL }
    private val appUid = context.applicationInfo.uid
    private val store = ResidentAdbCredentialStore(context.applicationContext)
    private val random = SecureRandom()
    private val lock = Any()
    private var token: ByteArray? = null
    private var tokenDeadline = 0L
    private var attempts = 0
    private var challenge: ByteArray? = null
    private var challengeDeadline = 0L
    private var command: IBinder? = null
    private var control: IBinder? = null
    private var credentialUnavailable = false
    private var pendingCredential: ResidentAdbCredentialStore.Credential? = null
    private var pendingCredentialDeadline = 0L
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var death: IBinder.DeathRecipient? = null

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    private fun notifyChanged() { listeners.forEach { runCatching { it() } } }

    private fun credential(): ResidentAdbCredentialStore.Credential? = try {
        store.load().also { credentialUnavailable = false }
    } catch (_: Exception) { credentialUnavailable = true; null }

    override fun granted(): Boolean = synchronized(lock) {
        store.enabled() && credential()?.use { true } == true
    }

    fun configured(): Boolean = synchronized(lock) { credential()?.use { true } == true }
    fun enabled(): Boolean = synchronized(lock) { store.enabled() }
    fun generation(): String? = synchronized(lock) { credential()?.use { it.generation } }
    fun persistenceFailed(): Boolean = synchronized(lock) { credentialUnavailable }
    fun activationPending(): Boolean = synchronized(lock) {
        expirePendingCredentialLocked()
        (token != null && attempts > 0 && elapsedNow() < tokenDeadline) || pendingCredential != null
    }

    override fun binder(): IBinder? = synchronized(lock) {
        val candidate = command ?: return@synchronized null
        if (!granted() || !runCatching { candidate.pingBinder() }.getOrDefault(false) ||
            control?.let { runCatching { it.pingBinder() }.getOrDefault(false) } != true) {
            return@synchronized null
        }
        candidate
    }

    fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            if (!enabled) { clearPendingLocked(); clearStagedLocked() }
            store.setEnabled(enabled)
        }
        notifyChanged()
    }

    /** Probe singleton presence only; app reattachment is published by the shell process watcher. */
    fun requestPublication(): Boolean {
        return requestPublicationState() == Publication.CONNECTED
    }

    fun requestPublicationState(): Publication {
        if (!configured()) return if (persistenceFailed()) Publication.UNKNOWN else Publication.NO_CREDENTIAL
        return try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(ResidentAdbMain.socketName(appUid / 100_000), LocalSocketAddress.Namespace.ABSTRACT))
                if (socket.peerCredentials.uid == ResidentAdbProtocol.SHELL_UID) Publication.CONNECTED else {
                    Log.e("ResidentADB", "stage=WAKE error=PEER_IDENTITY")
                    Publication.UNKNOWN
                }
            }
        } catch (failure: Exception) {
            // Only kernel ENOENT/ECONNREFUSED establishes absence. A credential failure,
            // permission error, wrong peer or unspecified socket error preserves recovery.
            classifyConnectionFailure(failure).also {
                if (it == Publication.UNKNOWN) Log.e("ResidentADB", "stage=WAKE error=${wakeFailureCode(failure)}")
            }
        }
    }

    fun activationToken(replace: Boolean): Pair<ByteArray, Long>? = synchronized(lock) {
        if (!replace && configured()) return@synchronized null
        // Replacing a verified live service is an explicit revoke/re-activate workflow.
        // Never overwrite a working grant simply because activation was clicked again.
        if (configured() && command?.pingBinder() == true && verify()) return@synchronized null
        clearPendingLocked()
        clearStagedLocked()
        token = ByteArray(ResidentAdbProtocol.TOKEN_BYTES).also(random::nextBytes)
        tokenDeadline = elapsedNow() + ResidentAdbProtocol.ACTIVATION_TTL_MS
        attempts = ResidentAdbProtocol.MAX_ATTEMPTS
        token!!.copyOf() to (System.currentTimeMillis() + ResidentAdbProtocol.ACTIVATION_TTL_MS)
    }

    fun cancelActivation() { synchronized(lock) { clearPendingLocked(); clearStagedLocked() }; notifyChanged() }

    /** Shell-only provider calls this after validating Android's incoming Binder identity. */
    fun bootstrap(suppliedToken: ByteArray, secret: ByteArray): String? = synchronized(lock) {
        val expected = token ?: return@synchronized null
        if (elapsedNow() >= tokenDeadline || attempts <= 0) {
            clearPendingLocked(); return@synchronized null
        }
        attempts--
        if (secret.size != ResidentAdbProtocol.SECRET_BYTES || !ResidentAdbProtocol.equal(expected, suppliedToken)) {
            if (attempts == 0) clearPendingLocked()
            return@synchronized null
        }
        val generation = UUID.randomUUID().toString()
        // Stage in RAM; preserve the old durable credential until a validated Binder arrives.
        pendingCredential?.close()
        pendingCredential = ResidentAdbCredentialStore.Credential(generation, secret.copyOf())
        pendingCredentialDeadline = elapsedNow() + ResidentAdbProtocol.CHALLENGE_TTL_MS
        clearPendingLocked()
        generation
    }.also { notifyChanged() }

    fun issueChallenge(generation: String): ByteArray? = synchronized(lock) {
        expirePendingCredentialLocked()
        val pending = pendingCredential?.takeIf { it.generation == generation }
        val current = pending?.let { ResidentAdbCredentialStore.Credential(it.generation, it.secret.copyOf()) }
            ?: credential() ?: return@synchronized null
        current.use {
            if (it.generation != generation) return@synchronized null
            challenge?.fill(0)
            challenge = ByteArray(ResidentAdbProtocol.NONCE_BYTES).also(random::nextBytes)
            challengeDeadline = elapsedNow() + ResidentAdbProtocol.CHALLENGE_TTL_MS
            challenge!!.copyOf()
        }
    }

    fun publish(generation: String, proof: ByteArray, candidate: IBinder, candidateControl: IBinder): Boolean = synchronized(lock) {
        val nonce = challenge ?: return@synchronized false
        challenge = null
        try {
            if (elapsedNow() >= challengeDeadline) return@synchronized false
            expirePendingCredentialLocked()
            val pending = pendingCredential?.takeIf { it.generation == generation }
            val current = pending?.let { ResidentAdbCredentialStore.Credential(it.generation, it.secret.copyOf()) }
                ?: credential() ?: return@synchronized false
            current.use {
                if (generation != it.generation) return@synchronized false
                val expected = ResidentAdbProtocol.proof(it.secret, nonce, generation, appUid)
                val valid = try { ResidentAdbProtocol.equal(expected, proof) } finally { expected.fill(0) }
                if (!valid || !candidate.pingBinder() || !candidateControl.pingBinder()) return@synchronized false
            }
            // Incoming provider call is shell; remote typed handshake must carry the app identity.
            val identity = Binder.clearCallingIdentity()
            val status = try { JSONObject(IShizukuCommandService.Stub.asInterface(candidate).getStatus()) }
                finally { Binder.restoreCallingIdentity(identity) }
            if (!status.optBoolean("ok", false) || status.optInt("serviceUid", -1) != ResidentAdbProtocol.SHELL_UID ||
                status.optInt("protocolVersion", -1) != ShizukuBridgePolicy.USER_SERVICE_PROTOCOL_VERSION ||
                status.optInt("callerUid", -1) != appUid || status.optString("sessionId", "").isBlank()) return@synchronized false
            if (pending != null) {
                store.save(generation, pending.secret)
                store.setEnabled(true)
                pending.close()
                pendingCredential = null
            }
            if (command !== candidate) {
                detachLocked()
                val recipient = IBinder.DeathRecipient {
                    synchronized(lock) { if (command === candidate) detachLocked() }
                    notifyChanged()
                }
                candidate.linkToDeath(recipient, 0)
                candidateControl.linkToDeath(recipient, 0)
                command = candidate
                control = candidateControl
                death = recipient
            }
            true
        } catch (_: Exception) { false } finally { nonce.fill(0) }
    }.also { if (it) notifyChanged() }

    /** Re-prove continuity for app refresh/restart; stale cached Binder is never sufficient. */
    fun verify(): Boolean = synchronized(lock) {
        val target = control ?: return@synchronized false
        val current = credential() ?: return@synchronized false
        current.use {
            val nonce = ByteArray(ResidentAdbProtocol.NONCE_BYTES).also(random::nextBytes)
            val expected = ResidentAdbProtocol.proof(it.secret, nonce, it.generation, appUid)
            val request = Parcel.obtain(); val response = Parcel.obtain()
            try {
                request.writeInterfaceToken(ResidentAdbProtocol.DESCRIPTOR)
                request.writeByteArray(nonce)
                if (!target.transact(ResidentAdbProtocol.PROOF, request, response, 0)) return@synchronized false
                response.readException()
                val actual = response.createByteArray() ?: return@synchronized false
                try { ResidentAdbProtocol.equal(expected, actual) } finally { actual.fill(0) }
            } catch (_: Exception) { detachLocked(); false }
            finally { nonce.fill(0); expected.fill(0); request.recycle(); response.recycle() }
        }
    }

    fun serviceAlive(): Boolean = synchronized(lock) {
        control?.let { runCatching { it.pingBinder() }.getOrDefault(false) } == true
    }

    /** Credential is retained until authenticated shutdown has actually killed the process. */
    fun requestShutdown(): Boolean = synchronized(lock) {
        val target = control ?: return@synchronized false
        if (!verify()) return@synchronized false
        val request = Parcel.obtain(); val response = Parcel.obtain()
        try {
            request.writeInterfaceToken(ResidentAdbProtocol.DESCRIPTOR)
            if (!target.transact(ResidentAdbProtocol.SHUTDOWN, request, response, 0)) return@synchronized false
            response.readException()
            true
        } catch (_: Exception) { false }
        finally { request.recycle(); response.recycle() }
    }

    fun forget(daemonKnownAbsent: Boolean): Boolean {
        synchronized(lock) {
            if (serviceAlive() || (!daemonKnownAbsent && configured())) return false
            store.clear()
            store.setEnabled(false)
            clearPendingLocked()
            clearStagedLocked()
            detachLocked()
        }
        notifyChanged()
        return true
    }

    /** Client close never destroys the daemon or forgets its encrypted credential. */
    private fun detachLocked() {
        death?.let { recipient ->
            command?.let { runCatching { it.unlinkToDeath(recipient, 0) } }
            control?.let { runCatching { it.unlinkToDeath(recipient, 0) } }
        }
        command = null; control = null
        death = null
    }

    private fun clearPendingLocked() { token?.fill(0); token = null; attempts = 0; tokenDeadline = 0 }

    private fun clearStagedLocked() {
        pendingCredential?.close(); pendingCredential = null; pendingCredentialDeadline = 0
        challenge?.fill(0); challenge = null; challengeDeadline = 0
    }

    private fun expirePendingCredentialLocked() {
        if (pendingCredential != null && elapsedNow() >= pendingCredentialDeadline) {
            pendingCredential?.close(); pendingCredential = null
        }
    }

    companion object {
        private fun wakeFailureCode(failure: Exception): String {
            val errno = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<ErrnoException>().firstOrNull()?.errno
            if (errno == OsConstants.EACCES || errno == OsConstants.EPERM ||
                (failure.javaClass == IOException::class.java && failure.cause == null &&
                    (failure.message == Os.strerror(OsConstants.EACCES) || failure.message == Os.strerror(OsConstants.EPERM)))) return "PERMISSION"
            return when (failure) { is IOException -> "IO"; is SecurityException -> "SECURITY"; else -> "OTHER" }
        }
        internal fun classifyConnectionFailure(failure: Throwable): Publication {
            val errno = generateSequence(failure) { it.cause }.filterIsInstance<ErrnoException>().firstOrNull()?.errno
            if (errno == OsConstants.ENOENT || errno == OsConstants.ECONNREFUSED) return Publication.ABSENT
            // API34 LocalSocketImpl JNI emits precisely IOException(strerror(errno))
            // without a cause. Its real absent-socket test established this boundary.
            // Translate only exact native ENOENT/ECONNREFUSED in that exact shape;
            // credential/auth/permission failures and all other messages remain unknown.
            if (failure.javaClass == IOException::class.java && failure.cause == null &&
                (failure.message == Os.strerror(OsConstants.ENOENT) || failure.message == Os.strerror(OsConstants.ECONNREFUSED))) {
                return Publication.ABSENT
            }
            return Publication.UNKNOWN
        }
        @Volatile private var instance: ResidentAdbRegistry? = null
        fun get(context: Context): ResidentAdbRegistry = instance ?: synchronized(this) {
            instance ?: ResidentAdbRegistry(context.applicationContext).also { instance = it }
        }
        internal fun forTest(context: Context, elapsedNow: () -> Long): ResidentAdbRegistry =
            ResidentAdbRegistry(context, elapsedNow)
    }
}
