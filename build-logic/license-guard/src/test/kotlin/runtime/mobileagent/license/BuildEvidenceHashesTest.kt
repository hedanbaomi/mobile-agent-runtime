// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.license

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BuildEvidenceHashesTest {
    @Test
    fun exactBytesHaveKnownSha256AndMissingFilesFail(@TempDir tmp: Path) {
        val file = tmp.resolve("payload").toFile().apply { writeBytes("abc".toByteArray()) }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", BuildEvidenceHashes.sha256(file))
        assertThrows(IllegalArgumentException::class.java) { BuildEvidenceHashes.sha256(tmp.resolve("absent").toFile()) }
    }
}
