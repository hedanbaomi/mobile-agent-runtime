// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.resident

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ResidentAdbProtocolTest {
    @Test fun boundedFrameMatchesDesktopAndErasesBothCredentials() {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).apply {
                writeInt(ResidentAdbProtocol.MAGIC)
                write(ByteArray(32) { 1 }); write(ByteArray(32) { 2 })
            }
        }.toByteArray()
        assertEquals(68, bytes.size)
        val frame = ResidentAdbProtocol.readBootstrap(ByteArrayInputStream(bytes))
        assertArrayEquals(ByteArray(32) { 1 }, frame.token)
        assertArrayEquals(ByteArray(32) { 2 }, frame.secret)
        frame.close()
        assertArrayEquals(ByteArray(32), frame.token)
        assertArrayEquals(ByteArray(32), frame.secret)
    }

    @Test fun proofBindsNonceGenerationUidAndSecret() {
        val secret = ByteArray(32) { 3 }
        val nonce = ByteArray(32) { 4 }
        val generation = "00000000-0000-0000-0000-000000000001"
        val proof = ResidentAdbProtocol.proof(secret, nonce, generation, 10234)
        assertTrue(ResidentAdbProtocol.equal(proof, ResidentAdbProtocol.proof(secret, nonce, generation, 10234)))
        assertFalse(ResidentAdbProtocol.equal(proof, ResidentAdbProtocol.proof(secret, ByteArray(32) { 5 }, generation, 10234)))
        assertFalse(ResidentAdbProtocol.equal(proof, ResidentAdbProtocol.proof(secret, nonce, "00000000-0000-0000-0000-000000000002", 10234)))
        assertFalse(ResidentAdbProtocol.equal(proof, ResidentAdbProtocol.proof(secret, nonce, generation, 10235)))
        assertFalse(ResidentAdbProtocol.equal(proof, ResidentAdbProtocol.proof(ByteArray(32) { 6 }, nonce, generation, 10234)))
        assertFalse(ResidentAdbProtocol.equal(proof, proof.copyOf(31)))
    }

    @Test fun truncatedAndWrongMagicFramesFailClosed() {
        for (size in listOf(0, 3, 4, 35, 67)) {
            val bytes = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(ResidentAdbProtocol.MAGIC) }.toByteArray() + ByteArray(64)
            assertThrows(Exception::class.java) { ResidentAdbProtocol.readBootstrap(ByteArrayInputStream(bytes.copyOf(size))) }
        }
        assertThrows(IllegalArgumentException::class.java) { ResidentAdbProtocol.readBootstrap(ByteArrayInputStream(ByteArray(68))) }
    }
}
