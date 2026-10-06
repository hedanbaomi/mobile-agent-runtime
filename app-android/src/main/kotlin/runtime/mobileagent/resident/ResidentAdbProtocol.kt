// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.resident

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Closed bootstrap and challenge protocol. Credentials are never represented as text. */
internal object ResidentAdbProtocol {
    const val MAGIC = 0x4d415231
    const val VERSION = 1
    const val SHELL_UID = 2000
    const val TOKEN_BYTES = 32
    const val SECRET_BYTES = 32
    const val NONCE_BYTES = 32
    const val ACTIVATION_TTL_MS = 120_000L
    const val CHALLENGE_TTL_MS = 15_000L
    const val MAX_ATTEMPTS = 5
    const val PACKAGE = "runtime.mobileagent"
    const val AUTHORITY = "$PACKAGE.resident-adb"
    const val DESCRIPTOR = "$PACKAGE.resident.Control.v1"
    const val PROOF = 1
    const val SHUTDOWN = 2

    class Bootstrap(val token: ByteArray, val secret: ByteArray) : AutoCloseable {
        override fun close() { token.fill(0); secret.fill(0) }
    }

    fun readBootstrap(input: InputStream): Bootstrap {
        val data = DataInputStream(input)
        require(data.readInt() == MAGIC)
        val token = ByteArray(TOKEN_BYTES)
        val secret = ByteArray(SECRET_BYTES)
        try {
            data.readFully(token)
            data.readFully(secret)
            return Bootstrap(token, secret)
        } catch (failure: Exception) {
            token.fill(0); secret.fill(0)
            throw failure
        }
    }

    fun proof(secret: ByteArray, nonce: ByteArray, generation: String, appUid: Int): ByteArray {
        require(secret.size == SECRET_BYTES && nonce.size == NONCE_BYTES)
        require(generation.length == 36 && appUid >= 10_000)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update(DESCRIPTOR.toByteArray(Charsets.UTF_8))
        mac.update(ByteBuffer.allocate(8).putInt(VERSION).putInt(appUid).array())
        mac.update(generation.toByteArray(Charsets.US_ASCII))
        return mac.doFinal(nonce)
    }

    fun equal(expected: ByteArray, supplied: ByteArray): Boolean =
        expected.size == supplied.size && MessageDigest.isEqual(expected, supplied)
}
