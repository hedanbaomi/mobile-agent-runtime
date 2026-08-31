// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.bridge

import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.Arrays
import java.util.UUID

class PairingRateLimitException(message: String) : BridgeProtocolException(message)
class PairingTokenConsumedException(message: String) : BridgeAuthenticationException(message)
class PairingTokenRegistrationException(message: String) : BridgeProtocolException(message)

private fun requirePairingAuthentication(condition: Boolean, message: String) {
    if (!condition) throw BridgeAuthenticationException(message)
}

/**
 * In-memory one-shot token registry. Only a SHA-256 token digest survives in
 * the registry. Token bytes are held transiently by a reservation and are
 * cleared when the reservation is committed or aborted.
 */
class PairingTokenManager(
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : AutoCloseable {
    private data class Record(
        val id: String,
        val digest: ByteArray,
        val expiresAtMillis: Long,
        var attempts: Int = 0,
        var pendingId: String? = null,
        var used: Boolean = false,
    )

    private val records = LinkedHashMap<String, Record>()
    private val attemptsInWindow = ArrayDeque<Long>()
    private var generation = 0L
    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        generation++
        clearRecords()
        attemptsInWindow.clear()
    }

    /**
     * Register the one-time token issued by the Android foreground UI.
     * Desktop code never creates a pairing token. Registration is bounded to
     * the protocol TTL so a Desktop cannot extend an App-issued token.
     */
    @Synchronized
    fun register(
        token: ByteArray,
        expiresAtMillis: Long,
        nowMillis: Long = clockMillis(),
    ) {
        try {
            ensureOpen()
            require(token.size == BridgeProtocol.TOKEN_BYTES) { "pairing token must be 256 bits" }
            require(expiresAtMillis > nowMillis) { "pairing token is already expired" }
            val latestExpiry = if (nowMillis > Long.MAX_VALUE - BridgeProtocol.PAIRING_MAX_REGISTRATION_WINDOW_MILLIS) {
                Long.MAX_VALUE
            } else {
                nowMillis + BridgeProtocol.PAIRING_MAX_REGISTRATION_WINDOW_MILLIS
            }
            require(expiresAtMillis <= latestExpiry) {
                "pairing token registration window is too long"
            }
            prune(nowMillis)
            val digest = BridgeCrypto.sha256(token)
            try {
                if (records.values.any { BridgeCrypto.constantTimeEquals(it.digest, digest) }) {
                    throw PairingTokenRegistrationException("pairing token is already registered")
                }
                val id = UUID.randomUUID().toString()
                records[id] = Record(
                    id = id,
                    digest = digest.copyOf(),
                    expiresAtMillis = expiresAtMillis,
                )
            } finally {
                Arrays.fill(digest, 0)
            }
        } finally {
            // Registration consumes the caller's transient token bytes even
            // when validation fails, so invalid input cannot linger.
            Arrays.fill(token, 0)
        }
    }

    /** Revokes all pending/registered tokens without permanently closing the manager. */
    @Synchronized
    fun revokeAll() {
        if (closed) return
        generation++
        clearRecords()
        attemptsInWindow.clear()
    }

    /**
     * Reserve a registered token for one pairing transcript. The reservation
     * does not consume the token: consumption occurs only at commit after the
     * peer has durably staged trust.
     */
    fun reserve(token: ByteArray, nowMillis: Long = clockMillis()): PairingTokenReservation {
        var presentedDigest = ByteArray(0)
        try {
            require(token.size == BridgeProtocol.TOKEN_BYTES) { "pairing token must be 256 bits" }
            presentedDigest = BridgeCrypto.sha256(token)
            synchronized(this) {
                ensureOpen()
                prune(nowMillis)
                // Count every reservation attempt, including invalid token
                // guesses, so the loopback endpoint cannot be brute-forced
                // without tripping the global rate guard.
                checkRateLimit(nowMillis)
                val record = records.values.firstOrNull {
                    BridgeCrypto.constantTimeEquals(it.digest, presentedDigest)
                } ?: throw BridgeAuthenticationException("pairing token is invalid")
                if (record.used || record.pendingId != null) {
                    throw PairingTokenConsumedException("pairing token is already in use")
                }
                if (nowMillis >= record.expiresAtMillis) {
                    removeRecord(record)
                    throw BridgeAuthenticationException("pairing token has expired")
                }
                if (record.attempts >= BridgeProtocol.PAIRING_MAX_ATTEMPTS) {
                    throw PairingTokenConsumedException("pairing token attempts exhausted")
                }
                record.attempts++
                val pendingId = UUID.randomUUID().toString()
                record.pendingId = pendingId
                return PairingTokenReservation(
                    manager = this,
                    recordId = record.id,
                    pendingId = pendingId,
                    token = SecretBytes(token),
                    expiresAtMillis = record.expiresAtMillis,
                    generation = generation,
                )
            }
        } finally {
            Arrays.fill(presentedDigest, 0)
            // The wire token is a one-shot secret; callers must pass a
            // disposable copy and it is always consumed by this operation.
            Arrays.fill(token, 0)
        }
    }

    @Synchronized
    internal fun commit(recordId: String, pendingId: String, reservationGeneration: Long) {
        if (closed || reservationGeneration != generation) {
            throw PairingTokenConsumedException("pairing token reservation was revoked")
        }
        val nowMillis = clockMillis()
        prune(nowMillis)
        val record = records[recordId]
            ?: throw PairingTokenConsumedException("pairing token is no longer available")
        if (nowMillis >= record.expiresAtMillis) {
            removeRecord(record)
            throw PairingTokenConsumedException("pairing token has expired")
        }
        check(record.pendingId == pendingId && !record.used) { "pairing reservation is invalid" }
        record.used = true
        record.pendingId = null
        // Retain only the digest until the original TTL expires so replay is
        // classified as consumed rather than an unrelated invalid token.
    }

    @Synchronized
    internal fun abort(recordId: String, pendingId: String, reservationGeneration: Long) {
        if (closed || reservationGeneration != generation) return
        val record = records[recordId] ?: return
        if (record.pendingId == pendingId && !record.used) record.pendingId = null
    }

    @Synchronized
    internal fun reservationActive(recordId: String, pendingId: String, reservationGeneration: Long): Boolean {
        if (closed || reservationGeneration != generation) return false
        val nowMillis = clockMillis()
        prune(nowMillis)
        return records[recordId]?.let {
            it.pendingId == pendingId && !it.used && nowMillis < it.expiresAtMillis
        } == true
    }

    @Synchronized
    private fun checkRateLimit(nowMillis: Long) {
        val cutoff = safeCutoff(nowMillis, BridgeProtocol.PAIRING_RATE_WINDOW_MILLIS)
        while (attemptsInWindow.isNotEmpty() && attemptsInWindow.first() < cutoff) attemptsInWindow.removeFirst()
        if (attemptsInWindow.size >= BridgeProtocol.PAIRING_MAX_ATTEMPTS_PER_WINDOW) {
            throw PairingRateLimitException("pairing rate limit exceeded")
        }
        attemptsInWindow.addLast(nowMillis)
    }

    private fun prune(nowMillis: Long) {
        val iterator = records.iterator()
        while (iterator.hasNext()) {
            val record = iterator.next().value
            if (nowMillis >= record.expiresAtMillis) {
                Arrays.fill(record.digest, 0)
                iterator.remove()
            }
        }
        val cutoff = safeCutoff(nowMillis, BridgeProtocol.PAIRING_RATE_WINDOW_MILLIS)
        while (attemptsInWindow.isNotEmpty() && attemptsInWindow.first() < cutoff) attemptsInWindow.removeFirst()
    }

    private fun removeRecord(record: Record) {
        records.remove(record.id)
        Arrays.fill(record.digest, 0)
    }

    private fun clearRecords() {
        records.values.forEach { Arrays.fill(it.digest, 0) }
        records.clear()
    }

    private fun ensureOpen() = check(!closed) { "pairing token manager is closed" }

    private fun safeCutoff(nowMillis: Long, windowMillis: Long): Long =
        if (nowMillis < Long.MIN_VALUE + windowMillis) Long.MIN_VALUE else nowMillis - windowMillis
}

class PairingTokenReservation internal constructor(
    private val manager: PairingTokenManager,
    private val recordId: String,
    private val pendingId: String,
    private val token: SecretBytes,
    val expiresAtMillis: Long,
    private val generation: Long,
) : AutoCloseable {
    private var closed = false
    private var committed = false

    fun <T> use(block: (ByteArray) -> T): T = synchronized(this) {
        ensureActive()
        token.use(block)
    }

    fun commit() = synchronized(this) {
        check(!closed) { "pairing reservation is closed" }
        check(!committed) { "pairing reservation is already committed" }
        manager.commit(recordId, pendingId, generation)
        committed = true
        closed = true
        token.close()
    }

    fun abort() = synchronized(this) {
        if (!closed) {
            manager.abort(recordId, pendingId, generation)
            closed = true
            token.close()
        }
    }

    override fun close() = abort()

    private fun ensureActive() {
        check(!closed) { "pairing reservation is closed" }
        check(manager.reservationActive(recordId, pendingId, generation)) {
            "pairing reservation was revoked or expired"
        }
    }
}

/** Persistent App-level trust; callers must store it encrypted and close it. */
class BridgeTrustMaterial internal constructor(
    identity: BridgeIdentity,
    transcriptHash: ByteArray,
    persistentTrust: ByteArray,
    fingerprint: ByteArray,
) : AutoCloseable {
    val identity: BridgeIdentity = BridgeIdentity(
        identity.desktopId,
        identity.appInstanceId,
        identity.serialFingerprint.copyOf(),
    )
    val transcriptHash: ByteArray = transcriptHash.copyOf()
    private val trust = SecretBytes(persistentTrust)
    private val trustFingerprint = fingerprint.copyOf()
    private var closed = false

    init {
        require(this.transcriptHash.size == 32)
        require(trustFingerprint.size == 32)
    }

    @Synchronized
    fun copyPersistentTrust(): ByteArray {
        check(!closed) { "trust material is closed" }
        return trust.copyBytes()
    }

    @Synchronized
    fun copyFingerprint(): ByteArray {
        check(!closed) { "trust material is closed" }
        return trustFingerprint.copyOf()
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            trust.close()
            Arrays.fill(transcriptHash, 0)
            Arrays.fill(trustFingerprint, 0)
        }
    }
}

/**
 * Desktop side of first pairing. The desktop identity and selected serial
 * fingerprint are constructor state; Android supplies only app identity,
 * app nonce, and its one-time token in [BridgePairStart].
 */
class BridgePairingServer(
    private val desktopId: String,
    serialFingerprint: ByteArray,
    private val tokenManager: PairingTokenManager,
    private val random: SecureRandom = SecureRandom(),
    private val expectedAppInstanceId: String? = null,
) {
    private val serialFingerprint = serialFingerprint.copyOf()

    init {
        requireIdentityPart(desktopId, "desktopId")
        require(this.serialFingerprint.size == BridgeProtocol.SERIAL_FINGERPRINT_BYTES) {
            "serial fingerprint must be 32 bytes"
        }
        expectedAppInstanceId?.let { requireIdentityPart(it, "appInstanceId") }
    }

    /** Compatibility constructor for callers that pin the full expected identity. */
    constructor(
        expectedIdentity: BridgeIdentity,
        tokenManager: PairingTokenManager,
        random: SecureRandom = SecureRandom(),
    ) : this(
        desktopId = expectedIdentity.desktopId,
        serialFingerprint = expectedIdentity.serialFingerprint,
        tokenManager = tokenManager,
        random = random,
        expectedAppInstanceId = expectedIdentity.appInstanceId,
    )

    fun begin(start: BridgePairStart): BridgePairingServerPending {
        BridgeCodec.decodePairStart(BridgeCodec.encodePairStart(start))
        val appNonce = hexBytes(start.appNonceHex, "appNonce", BridgeProtocol.NONCE_BYTES)
        val token = hexBytes(start.tokenHex, "pairingToken", BridgeProtocol.TOKEN_BYTES)
        var desktopNonce = ByteArray(0)
        var reservation: PairingTokenReservation? = null
        try {
            expectedAppInstanceId?.let { requirePairingAuthentication(it == start.appInstanceId, "app identity mismatch") }
            reservation = tokenManager.reserve(token)
            desktopNonce = BridgeCrypto.randomBytes(BridgeProtocol.NONCE_BYTES, random)
            require(!desktopNonce.contentEquals(appNonce)) { "pairing nonces must differ" }
            val identity = BridgeIdentity(desktopId, start.appInstanceId, serialFingerprint.copyOf())
            val transcript = PairingTranscript.from(identity, desktopNonce, appNonce)
            val secrets = reservation.use { BridgeCrypto.pairingSecrets(it, transcript) }
            return BridgePairingServerPending(reservation, transcript, secrets)
        } catch (error: Exception) {
            reservation?.abort()
            throw error
        } finally {
            Arrays.fill(appNonce, 0)
            Arrays.fill(token, 0)
            Arrays.fill(desktopNonce, 0)
        }
    }
}

class BridgePairingServerPending internal constructor(
    private val reservation: PairingTokenReservation,
    private val transcript: PairingTranscript,
    private val secrets: PairingSecrets,
) : AutoCloseable {
    private enum class State { CHALLENGE, FINISHED_SENT, PREPARED, COMMITTED, CLOSED }

    private var state = State.CHALLENGE
    private val transcriptHash = transcript.hash()
    private var preparedMaterial: BridgeTrustMaterial? = null

    val challenge: BridgePairChallenge = BridgePairChallenge(
        protocolVersion = BridgeProtocol.VERSION,
        desktopId = transcript.desktopId,
        appInstanceId = transcript.appInstanceId,
        serialFingerprintHex = BridgeEncoding.hex(transcript.serialFingerprint),
        desktopNonceHex = BridgeEncoding.hex(transcript.desktopNonce),
        appNonceHex = BridgeEncoding.hex(transcript.appNonce),
        transcriptHashHex = BridgeEncoding.hex(transcriptHash),
    )

    @Synchronized
    fun acceptResponse(response: BridgePairResponse): BridgePairFinished {
        check(state == State.CHALLENGE) { "pairing response is not expected" }
        reservation.use { /* reservation must still be live before proof validation */ }
        BridgeCodec.decodePairResponse(BridgeCodec.encodePairResponse(response))
        val receivedHash = hexBytes(response.transcriptHashHex, "transcriptHash", 32)
        val receivedFinished = hexBytes(response.clientFinishedHex, "clientFinished", 32)
        try {
            requirePairingAuthentication(
                BridgeCrypto.constantTimeEquals(receivedHash, transcriptHash),
                "pairing transcript mismatch",
            )
            val expectedFinished = secrets.clientFinished.copyBytes()
            try {
                requirePairingAuthentication(
                    BridgeCrypto.constantTimeEquals(receivedFinished, expectedFinished),
                    "pairing client proof is invalid",
                )
            } finally {
                Arrays.fill(expectedFinished, 0)
            }
            // Do not issue a finished proof after a TTL/revocation race.
            reservation.use { /* liveness check immediately before response */ }
            val serverFinished = secrets.serverFinished.copyBytes()
            val fingerprint = secrets.trustFingerprint()
            state = State.FINISHED_SENT
            return try {
                BridgePairFinished(
                    protocolVersion = BridgeProtocol.VERSION,
                    transcriptHashHex = BridgeEncoding.hex(transcriptHash),
                    serverFinishedHex = BridgeEncoding.hex(serverFinished),
                    persistentTrustFingerprintHex = BridgeEncoding.hex(fingerprint),
                )
            } finally {
                Arrays.fill(serverFinished, 0)
                Arrays.fill(fingerprint, 0)
            }
        } finally {
            Arrays.fill(receivedHash, 0)
            Arrays.fill(receivedFinished, 0)
        }
    }

    /**
     * Verifies the client's acknowledgement and prepares trust for durable
     * persistence. This method does not consume the token. Callers must save
     * the returned material, then invoke [commitPrepared] only after saving.
     */
    @Synchronized
    fun prepareCommit(ack: BridgePairCommitAck): BridgeTrustMaterial {
        check(state == State.FINISHED_SENT) { "pairing commit acknowledgement is not expected" }
        reservation.use { /* reservation must still be live before staging */ }
        BridgeCodec.decodePairCommitAck(BridgeCodec.encodePairCommitAck(ack))
        val receivedHash = hexBytes(ack.transcriptHashHex, "transcriptHash", 32)
        val receivedFingerprint = hexBytes(ack.persistentTrustFingerprintHex, "trustFingerprint", 32)
        var trust = ByteArray(0)
        var fingerprint = ByteArray(0)
        try {
            requirePairingAuthentication(
                BridgeCrypto.constantTimeEquals(receivedHash, transcriptHash),
                "pairing transcript mismatch",
            )
            val expectedFingerprint = secrets.trustFingerprint()
            try {
                requirePairingAuthentication(
                    BridgeCrypto.constantTimeEquals(receivedFingerprint, expectedFingerprint),
                    "pairing trust fingerprint mismatch",
                )
            } finally {
                Arrays.fill(expectedFingerprint, 0)
            }
            reservation.use { /* liveness check before creating staged trust */ }
            trust = secrets.persistentTrust.copyBytes()
            fingerprint = receivedFingerprint.copyOf()
            val material = BridgeTrustMaterial(
                identity = BridgeIdentity(
                    transcript.desktopId,
                    transcript.appInstanceId,
                    transcript.serialFingerprint.copyOf(),
                ),
                transcriptHash = transcriptHash,
                persistentTrust = trust,
                fingerprint = fingerprint,
            )
            preparedMaterial = material
            state = State.PREPARED
            return material
        } finally {
            Arrays.fill(receivedHash, 0)
            Arrays.fill(receivedFingerprint, 0)
            Arrays.fill(trust, 0)
            Arrays.fill(fingerprint, 0)
        }
    }

    /** Final commit after the Desktop has durably saved the prepared trust. */
    @Synchronized
    fun commitPrepared() {
        check(state == State.PREPARED) { "pairing trust has not been prepared" }
        reservation.commit()
        state = State.COMMITTED
        preparedMaterial = null // ownership is transferred to the caller
        secrets.close()
        Arrays.fill(transcriptHash, 0)
    }

    @Synchronized
    override fun close() {
        if (state != State.COMMITTED && state != State.CLOSED) reservation.abort()
        preparedMaterial?.close()
        preparedMaterial = null
        if (state != State.CLOSED) {
            state = State.CLOSED
            secrets.close()
            Arrays.fill(transcriptHash, 0)
        }
    }
}

/** Client side of the same pairing exchange. */
class BridgePairingClient(
    val appInstanceId: String,
    token: PairingToken,
    private val random: SecureRandom = SecureRandom(),
    private val expectedIdentity: BridgeIdentity? = null,
) : AutoCloseable {
    /** Optional pinning constructor; wire identity still comes only from the challenge. */
    constructor(
        expectedIdentity: BridgeIdentity,
        token: PairingToken,
        random: SecureRandom = SecureRandom(),
    ) : this(expectedIdentity.appInstanceId, token, random, expectedIdentity)

    private val tokenSecret = SecretBytes(token.copyBytes())
    private val appNonce = BridgeCrypto.randomBytes(BridgeProtocol.NONCE_BYTES, random)
    private var negotiatedIdentity: BridgeIdentity? = null
    private var transcript: PairingTranscript? = null
    private var secrets: PairingSecrets? = null
    private var trustMaterial: BridgeTrustMaterial? = null
    private var closed = false

    init {
        requireIdentityPart(appInstanceId, "appInstanceId")
        expectedIdentity?.let { check(it.appInstanceId == appInstanceId) { "app identity mismatch" } }
    }

    /** Identity learned from and authenticated by the desktop challenge. */
    val identity: BridgeIdentity
        get() = synchronized(this) {
            negotiatedIdentity ?: error("desktop identity has not been authenticated")
        }

    /** Build the short-lived pairing start envelope without logging its token. */
    @Synchronized
    fun startEnvelope(): BridgePairStart {
        ensureOpen()
        val tokenHex = tokenSecret.use { BridgeEncoding.hex(it) }
        return BridgePairStart(
            protocolVersion = BridgeProtocol.VERSION,
            appInstanceId = appInstanceId,
            appNonceHex = BridgeEncoding.hex(appNonce),
            tokenHex = tokenHex,
        )
    }

    @Synchronized
    fun acceptChallenge(challenge: BridgePairChallenge): BridgePairResponse {
        ensureOpen()
        check(transcript == null) { "pairing challenge already accepted" }
        BridgeCodec.decodePairChallenge(BridgeCodec.encodePairChallenge(challenge))
        val receivedSerialFingerprint = hexBytes(
            challenge.serialFingerprintHex,
            "serialFingerprint",
            BridgeProtocol.SERIAL_FINGERPRINT_BYTES,
        )
        val receivedDesktopNonce = hexBytes(challenge.desktopNonceHex, "desktopNonce", BridgeProtocol.NONCE_BYTES)
        val receivedAppNonce = hexBytes(challenge.appNonceHex, "appNonce", BridgeProtocol.NONCE_BYTES)
        val receivedHash = hexBytes(challenge.transcriptHashHex, "transcriptHash", 32)
        var expectedHash = ByteArray(0)
        try {
            requirePairingAuthentication(challenge.appInstanceId == appInstanceId, "app identity mismatch")
            requirePairingAuthentication(
                BridgeCrypto.constantTimeEquals(receivedAppNonce, appNonce),
                "pairing app nonce mismatch",
            )
            val discoveredIdentity = BridgeIdentity(
                challenge.desktopId,
                challenge.appInstanceId,
                receivedSerialFingerprint.copyOf(),
            )
            expectedIdentity?.let {
                requirePairingAuthentication(discoveredIdentity == it, "desktop identity mismatch")
            }
            val built = PairingTranscript.from(discoveredIdentity, receivedDesktopNonce, receivedAppNonce)
            expectedHash = built.hash()
            requirePairingAuthentication(
                BridgeCrypto.constantTimeEquals(receivedHash, expectedHash),
                "pairing transcript mismatch",
            )
            val derived = tokenSecret.use { BridgeCrypto.pairingSecrets(it, built) }
            val finished = derived.clientFinished.copyBytes()
            negotiatedIdentity = discoveredIdentity
            transcript = built
            secrets = derived
            return try {
                BridgePairResponse(
                    protocolVersion = BridgeProtocol.VERSION,
                    transcriptHashHex = BridgeEncoding.hex(expectedHash),
                    clientFinishedHex = BridgeEncoding.hex(finished),
                )
            } finally {
                Arrays.fill(finished, 0)
            }
        } finally {
            Arrays.fill(receivedSerialFingerprint, 0)
            Arrays.fill(receivedDesktopNonce, 0)
            Arrays.fill(receivedAppNonce, 0)
            Arrays.fill(receivedHash, 0)
            Arrays.fill(expectedHash, 0)
        }
    }

    /**
     * The returned material must be durably stored before
     * [BridgePairingClientCommit.ackAfterPersisted] is sent to the server.
     */
    @Synchronized
    fun acceptFinished(finished: BridgePairFinished): BridgePairingClientCommit {
        ensureOpen()
        val currentTranscript = transcript ?: error("pairing challenge has not been accepted")
        val currentIdentity = negotiatedIdentity ?: error("desktop identity has not been authenticated")
        val currentSecrets = secrets ?: error("pairing response has not been generated")
        BridgeCodec.decodePairFinished(BridgeCodec.encodePairFinished(finished))
        val receivedHash = hexBytes(finished.transcriptHashHex, "transcriptHash", 32)
        val receivedServerFinished = hexBytes(finished.serverFinishedHex, "serverFinished", 32)
        val receivedFingerprint = hexBytes(finished.persistentTrustFingerprintHex, "trustFingerprint", 32)
        val hash = currentTranscript.hash()
        try {
            requirePairingAuthentication(BridgeCrypto.constantTimeEquals(receivedHash, hash), "pairing transcript mismatch")
            val expectedFinished = currentSecrets.serverFinished.copyBytes()
            val expectedFingerprint = currentSecrets.trustFingerprint()
            try {
                requirePairingAuthentication(
                    BridgeCrypto.constantTimeEquals(receivedServerFinished, expectedFinished),
                    "pairing server proof is invalid",
                )
                requirePairingAuthentication(
                    BridgeCrypto.constantTimeEquals(receivedFingerprint, expectedFingerprint),
                    "pairing trust fingerprint mismatch",
                )
            } finally {
                Arrays.fill(expectedFinished, 0)
                Arrays.fill(expectedFingerprint, 0)
            }
            val trust = currentSecrets.persistentTrust.copyBytes()
            val material = BridgeTrustMaterial(currentIdentity, hash, trust, receivedFingerprint)
            trustMaterial = material
            currentSecrets.close()
            secrets = null
            tokenSecret.close()
            return BridgePairingClientCommit(
                material,
                BridgePairCommitAck(
                    protocolVersion = BridgeProtocol.VERSION,
                    transcriptHashHex = BridgeEncoding.hex(hash),
                    persistentTrustFingerprintHex = BridgeEncoding.hex(receivedFingerprint),
                    accepted = true,
                ),
            )
        } finally {
            Arrays.fill(receivedHash, 0)
            Arrays.fill(receivedServerFinished, 0)
            Arrays.fill(receivedFingerprint, 0)
            Arrays.fill(hash, 0)
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            secrets?.close()
            trustMaterial?.close()
            tokenSecret.close()
            Arrays.fill(appNonce, 0)
        }
    }

    private fun ensureOpen() = check(!closed) { "pairing client is closed" }
}

class BridgePairingClientCommit internal constructor(
    val trustMaterial: BridgeTrustMaterial,
    val ack: BridgePairCommitAck,
) : AutoCloseable {
    /** Call only after [trustMaterial] has been durably persisted. */
    fun ackAfterPersisted(): BridgePairCommitAck = ack

    override fun close() = trustMaterial.close()
}
