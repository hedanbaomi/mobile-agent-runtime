// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.security.MessageDigest
import java.util.Locale
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ContentHashEncodingTest {
    @Test fun knownVectorsAndBinaryPayloadsKeepTheExactPersistedIdentity() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(byteArrayOf()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".toByteArray()))
        val random = Random(42)
        val samples = listOf(ByteArray(256) { it.toByte() }, ByteArray(65_536) { (it % 251).toByte() }) +
            List(64) { random.nextBytes(it * 31) }
        samples.forEach { bytes ->
            val previous = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }
            assertEquals(previous, sha256Hex(bytes))
        }
    }
}
