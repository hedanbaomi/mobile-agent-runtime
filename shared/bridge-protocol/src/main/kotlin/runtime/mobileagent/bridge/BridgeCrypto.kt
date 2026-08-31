// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic primitives shared by the Android and desktop implementations.
 *
 * This class deliberately uses only JDK primitives.  The protocol has no
 * provider-dependent framing or KDF choices: HKDF-SHA-256, HMAC-SHA-256 and
 * AES-256-GCM are fixed by bridge protocol v2.
 */
object BridgeCrypto {
    private val utf8 = StandardCharsets.UTF_8
    private val domain = BridgeProtocol.DOMAIN.toByteArray(utf8)

    fun randomBytes(size: Int, random: SecureRandom = SecureRandom()): ByteArray {
        require(size >= 0)
        return ByteArray(size).also(random::nextBytes)
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach(digest::update)
        return digest.digest()
    }

    fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
        return MessageDigest.isEqual(left, right)
    }

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach(mac::update)
        return mac.doFinal()
    }

    /** RFC 5869 HKDF-Extract with SHA-256. */
    fun hkdfExtract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val actualSalt = salt?.takeUnless { it.isEmpty() } ?: ByteArray(32)
        return try {
            hmacSha256(actualSalt, ikm)
        } finally {
            if (salt == null || salt.isEmpty()) Arrays.fill(actualSalt, 0)
        }
    }

    /** RFC 5869 HKDF-Expand with SHA-256. */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(prk.size >= 32) { "HKDF PRK must be at least 256 bits" }
        require(length in 0..255 * 32) { "HKDF output is too long" }
        if (length == 0) return ByteArray(0)

        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        return try {
            while (offset < length) {
                val block = hmacSha256(prk, previous, info, byteArrayOf(counter.toByte()))
                val copy = minOf(block.size, length - offset)
                block.copyInto(output, offset, 0, copy)
                offset += copy
                Arrays.fill(previous, 0)
                previous = block
                counter++
            }
            return output
        } finally {
            Arrays.fill(previous, 0)
        }
    }

    internal fun pairingSecrets(token: ByteArray, transcript: PairingTranscript): PairingSecrets {
        require(token.size == BridgeProtocol.TOKEN_BYTES) { "pairing token must be 256 bits" }
        var transcriptBytes = ByteArray(0)
        var transcriptHash = ByteArray(0)
        var salt = ByteArray(0)
        var ikm = ByteArray(0)
        var root = ByteArray(0)
        var client = ByteArray(0)
        var server = ByteArray(0)
        var trust = ByteArray(0)
        return try {
            transcriptBytes = transcript.canonicalBytes()
            transcriptHash = sha256(transcriptBytes)
            salt = sha256(domain, "pairing-salt".toByteArray(utf8))
            ikm = token + transcriptBytes
            root = hkdfExtract(salt, ikm)
            client = hkdfExpand(root, info("client-finished", transcriptHash), 32)
            server = hkdfExpand(root, info("server-finished", transcriptHash), 32)
            trust = hkdfExpand(root, info("persistent-trust", transcriptHash), 32)
            PairingSecrets(
                clientFinished = SecretBytes(client),
                serverFinished = SecretBytes(server),
                persistentTrust = SecretBytes(trust),
            )
        } finally {
            Arrays.fill(transcriptBytes, 0)
            Arrays.fill(transcriptHash, 0)
            Arrays.fill(salt, 0)
            Arrays.fill(ikm, 0)
            Arrays.fill(root, 0)
            Arrays.fill(client, 0)
            Arrays.fill(server, 0)
            Arrays.fill(trust, 0)
        }
    }

    internal fun sessionSecrets(
        persistentTrust: ByteArray,
        transcriptHash: ByteArray,
        sessionId: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): BridgeSessionSecrets {
        require(persistentTrust.size == 32)
        require(transcriptHash.size == 32)
        require(sessionId.size == BridgeProtocol.SESSION_ID_BYTES)
        require(clientNonce.size == BridgeProtocol.NONCE_BYTES)
        require(serverNonce.size == BridgeProtocol.NONCE_BYTES)

        var context = ByteArray(0)
        var salt = ByteArray(0)
        var inputKeyMaterial = ByteArray(0)
        var root = ByteArray(0)
        var clientKey = ByteArray(0)
        var serverKey = ByteArray(0)
        var clientPrefix = ByteArray(0)
        var serverPrefix = ByteArray(0)
        var serverProof = ByteArray(0)
        return try {
            context = sessionId + clientNonce + serverNonce
            salt = sha256(domain, "session-salt".toByteArray(utf8), transcriptHash)
            inputKeyMaterial = persistentTrust + context
            root = hkdfExtract(salt, inputKeyMaterial)
            clientKey = hkdfExpand(root, info("c2s-key", context), BridgeProtocol.GCM_KEY_BYTES)
            serverKey = hkdfExpand(root, info("s2c-key", context), BridgeProtocol.GCM_KEY_BYTES)
            clientPrefix = hkdfExpand(root, info("c2s-nonce-prefix", context), BridgeProtocol.GCM_NONCE_PREFIX_BYTES)
            serverPrefix = hkdfExpand(root, info("s2c-nonce-prefix", context), BridgeProtocol.GCM_NONCE_PREFIX_BYTES)
            serverProof = hmacSha256(root, info("session-server-proof", context))
            BridgeSessionSecrets(
                clientToServerKey = SecretBytes(clientKey),
                serverToClientKey = SecretBytes(serverKey),
                clientToServerPrefix = SecretBytes(clientPrefix),
                serverToClientPrefix = SecretBytes(serverPrefix),
                serverProof = SecretBytes(serverProof),
            )
        } finally {
            Arrays.fill(context, 0)
            Arrays.fill(salt, 0)
            Arrays.fill(inputKeyMaterial, 0)
            Arrays.fill(root, 0)
            Arrays.fill(clientKey, 0)
            Arrays.fill(serverKey, 0)
            Arrays.fill(clientPrefix, 0)
            Arrays.fill(serverPrefix, 0)
            Arrays.fill(serverProof, 0)
        }
    }

    internal fun nonce(prefix: ByteArray, sequence: Long): ByteArray {
        require(prefix.size == BridgeProtocol.GCM_NONCE_PREFIX_BYTES)
        require(sequence >= 0) { "sequence must not be negative" }
        return ByteArray(12).also {
            prefix.copyInto(it, 0)
            ByteBuffer.wrap(it, BridgeProtocol.GCM_NONCE_PREFIX_BYTES, Long.SIZE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(sequence)
        }
    }

    internal fun aad(
        protocolVersion: Int,
        sessionId: ByteArray,
        direction: BridgeDirection,
        sequence: Long,
        requestId: String,
        type: BridgeFrameType,
    ): ByteArray = BridgeBinaryWriter().apply {
        writeBytes(BridgeProtocol.FRAME_MAGIC)
        writeU16(protocolVersion)
        writeFixed(sessionId, BridgeProtocol.SESSION_ID_BYTES)
        writeU8(direction.wireValue)
        writeU64(sequence)
        writeUtf8(requestId)
        writeU8(type.wireValue)
    }.toByteArray()

    internal fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(plaintext)
    }

    internal fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext)
    }

    private fun info(label: String, context: ByteArray): ByteArray =
        domain + byteArrayOf(0) + label.toByteArray(utf8) + byteArrayOf(0) + context
}

internal class PairingSecrets(
    val clientFinished: SecretBytes,
    val serverFinished: SecretBytes,
    val persistentTrust: SecretBytes,
) : AutoCloseable {
    fun trustFingerprint(): ByteArray = persistentTrust.use { BridgeCrypto.sha256(it) }

    override fun close() {
        clientFinished.close()
        serverFinished.close()
        persistentTrust.close()
    }
}

internal class BridgeSessionSecrets(
    val clientToServerKey: SecretBytes,
    val serverToClientKey: SecretBytes,
    val clientToServerPrefix: SecretBytes,
    val serverToClientPrefix: SecretBytes,
    val serverProof: SecretBytes,
) : AutoCloseable {
    override fun close() {
        clientToServerKey.close()
        serverToClientKey.close()
        clientToServerPrefix.close()
        serverToClientPrefix.close()
        serverProof.close()
    }
}
