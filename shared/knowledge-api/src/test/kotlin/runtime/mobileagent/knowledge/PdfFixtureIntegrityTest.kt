// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.knowledge

import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PdfFixtureIntegrityTest {
    @Test
    fun encryptedPdfFixturesMatchTheirReviewedByteManifest() {
        val entries = checkNotNull(javaClass.getResourceAsStream("/pdf/fixtures.sha256"))
            .bufferedReader().use { it.readLines() }.filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(2, entries.size)
        for (entry in entries) {
            val (expected, name) = entry.split(Regex("\\s+"), limit = 2)
            val bytes = checkNotNull(javaClass.getResourceAsStream("/pdf/$name")).use { it.readBytes() }
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(expected, actual, name)
        }
    }
}
