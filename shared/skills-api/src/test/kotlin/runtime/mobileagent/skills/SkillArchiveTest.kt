// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SkillArchiveTest {
    @Test
    fun missingManifestIsInstructionOnly() {
        val zip = zip("SKILL.md" to "# Helper\nRead the knowledge base.\n".toByteArray())
        val inspection = SkillArchive.inspect(zip)
        assertEquals(CompatibilityClass.A, inspection.classification)
        assertTrue(inspection.installable)
        assertTrue(inspection.skillMarkdown.orEmpty().contains("Helper"))
    }

    @Test
    fun unknownSchemaIsRejected() {
        val zip = zip(
            "mobile-skill.json" to """{"schemaVersion":99,"id":"x","name":"X","version":"1","license":"MIT","runtime":{"kind":"python"}}""".toByteArray(),
        )
        val inspection = SkillArchive.inspect(zip)
        assertEquals(CompatibilityClass.E, inspection.classification)
        assertFalse(inspection.installable)
    }

    @Test
    fun hashMismatchIsRejectedAndOriginalIsUnchanged() {
        val zip = zip("SKILL.md" to "# A".toByteArray())
        val result = SkillInstaller.install(zip, expectedHash = "00".repeat(32))
        assertFalse(result.accepted)
        assertEquals(CompatibilityClass.E, result.inspection.classification)
        assertTrue(result.originalUnchanged)
    }

    @Test
    fun zipSlipIsClassE() {
        val zip = zip("../escape/SKILL.md" to "# no".toByteArray())
        val inspection = SkillArchive.inspect(zip)
        assertEquals(CompatibilityClass.E, inspection.classification)
        assertFalse(inspection.installable)
    }

    @Test
    fun elfPayloadIsClassE() {
        val elf = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) + ByteArray(8)
        val zip = zip(
            "mobile-skill.json" to validManifest("python").toByteArray(),
            "native.bin" to elf,
        )
        val inspection = SkillArchive.inspect(zip)
        assertEquals(CompatibilityClass.E, inspection.classification)
        assertFalse(SkillInstaller.install(zip).accepted)
    }

    @Test
    fun pipRemoteDependencyIsClassE() {
        val zip = zip(
            "mobile-skill.json" to validManifest("python").toByteArray(),
            "requirements.txt" to "requests @ https://example.invalid/requests.whl\n".toByteArray(),
        )
        assertEquals(CompatibilityClass.E, SkillArchive.inspect(zip).classification)
    }

    @Test
    fun purePythonManifestIsClassB() {
        val zip = zip(
            "mobile-skill.json" to validManifest("python").toByteArray(),
            "SKILL.md" to "# Knowledge helper\n".toByteArray(),
            "scripts/main.py" to "def run():\n    return 1\n".toByteArray(),
        )
        val inspection = SkillArchive.inspect(zip)
        assertEquals(CompatibilityClass.B, inspection.classification)
        assertEquals("dev.example.knowledge_helper", inspection.manifest?.id)
        assertTrue(SkillInstaller.install(zip).accepted)
    }

    @Test
    fun shellRuntimeIsClassDAndInstallableAsInstructions() {
        val zip = zip("mobile-skill.json" to validManifest("shell").toByteArray())
        val inspection = SkillArchive.inspect(zip)
        assertEquals(CompatibilityClass.D, inspection.classification)
        assertTrue(inspection.installable)
    }

    private fun validManifest(kind: String): String = """
        {
          "schemaVersion": 1,
          "id": "dev.example.knowledge_helper",
          "name": "Knowledge helper",
          "version": "1.0.0",
          "license": "AGPL-3.0-only",
          "runtime": {"kind": "$kind", "python": "3.14", "entrypoint": "scripts.main:run", "mode": "pure-python"},
          "permissions": {"knowledge.search": {"scope": "selected-by-user"}}
        }
    """.trimIndent()

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, payload) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(payload)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
