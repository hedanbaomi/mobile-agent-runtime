// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
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
    fun manifestlessClaudeSkillWithStdlibCliProgramGetsIsolatedCompatibilityManifest() {
        val source = """
            import re
            import sys
            from pathlib import Path

            def main():
                files = list(Path(sys.argv[1]).rglob("*.md"))
                print(sum(len(re.findall(r"[一-鿿]", item.read_text(encoding="utf-8"))) for item in files))

            if __name__ == "__main__":
                main()
        """.trimIndent().toByteArray()
        val zip = zip(
            "lieflat-less-ai-tone/SKILL.md" to "---\nname: lieflat-less-ai-tone\n---\n# 去 AI 味\n".toByteArray(),
            "lieflat-less-ai-tone/scripts/check-translationese.py" to source,
        )

        val inspection = SkillArchive.inspect(zip)

        assertEquals(CompatibilityClass.B, inspection.classification)
        assertEquals("mobileagent_legacy_skill:run", inspection.manifest?.entrypoint)
        assertEquals("lieflat-less-ai-tone", inspection.manifest?.name)
        val raw = Json.parseToJsonElement(inspection.rawManifestJson!!).jsonObject
        val programs = raw.getValue("legacyPrograms").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("lieflat-less-ai-tone/scripts/check-translationese.py"), programs)
        val programEnum = raw.getValue("inputSchema").jsonObject
            .getValue("properties").jsonObject
            .getValue("program").jsonObject
            .getValue("enum").jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(programs, programEnum)
        assertEquals(source.toString(Charsets.UTF_8), inspection.legacyProgramSources.getValue(programs.single()))
        assertTrue(inspection.reasons.any { it.contains("isolated Python", ignoreCase = true) })
    }

    @Test
    fun barePythonZipDoesNotBecomeAnExecutableClaudeSkill() {
        val inspection = SkillArchive.inspect(zip(
            "scripts/rewrite.py" to "def main():\n    print('ok')\n\nif __name__ == '__main__':\n    main()\n".toByteArray(),
        ))

        assertEquals(CompatibilityClass.A, inspection.classification)
        assertEquals(null, inspection.manifest)
        assertTrue(inspection.legacyProgramSources.isEmpty())
    }

    @Test
    fun allThreeLieflatAnalyzerProgramShapesAreExposed() {
        val compare = """
            import re
            import sys
            import collections
            from pathlib import Path
            def main():
                print(len(list(Path(sys.argv[-1]).rglob("*.md"))))
            if __name__ == "__main__":
                main()
        """.trimIndent().toByteArray()
        val translationese = """
            import re
            import sys
            from collections import defaultdict
            from pathlib import Path
            def main():
                print(defaultdict(int), Path(sys.argv[-1]).stem)
            if __name__ == "__main__":
                main()
        """.trimIndent().toByteArray()
        val structure = """
            import re
            import sys
            from pathlib import Path
            def main():
                print(Path(sys.argv[-1]).parent.name)
            if __name__ == "__main__":
                main()
        """.trimIndent().toByteArray()
        val inspection = SkillArchive.inspect(zip(
            "SKILL.md" to "---\nname: lieflat-less-ai-tone\n---\n# Skill\n".toByteArray(),
            "scripts/compare-human-ai.py" to compare,
            "scripts/check-translationese.py" to translationese,
            "scripts/check-structure.py" to structure,
        ))

        assertEquals(CompatibilityClass.B, inspection.classification)
        assertEquals(
            setOf("scripts/compare-human-ai.py", "scripts/check-translationese.py", "scripts/check-structure.py"),
            inspection.legacyProgramSources.keys,
        )
    }

    @Test
    fun manifestlessClaudeSkillKeepsUnsupportedDependencyProgramInstructionOnly() {
        val source = """
            import numpy as np
            import torch

            def main():
                print(np.asarray([1]))

            if __name__ == "__main__":
                main()
        """.trimIndent().toByteArray()
        val zip = zip(
            "josephine/SKILL.md" to "---\nname: josephine-mccarthy-perspective\n---\n# Perspective\n".toByteArray(),
            "josephine/knowledge_base/books_kb.py" to source,
        )

        val inspection = SkillArchive.inspect(zip)

        assertEquals(CompatibilityClass.A, inspection.classification)
        assertEquals(null, inspection.manifest)
        assertTrue(inspection.reasons.any { it.contains("numpy") && it.contains("torch") })
        assertTrue(inspection.reasons.any { it.contains("knowledge_search") })
    }

    @Test
    fun mixedLegacyPackageExposesOnlyProgramsSupportedByTheIsolatedRuntime() {
        val safe = """
            import re
            import sys
            def main():
                print(len(re.findall("x", sys.argv[1])))
            if __name__ == "__main__":
                main()
        """.trimIndent().toByteArray()
        val unsafe = """
            import os
            def main():
                print(os.getcwd())
            if __name__ == "__main__":
                main()
        """.trimIndent().toByteArray()
        val inspection = SkillArchive.inspect(zip(
            "SKILL.md" to "---\nname: mixed-skill\n---\n# Mixed\n".toByteArray(),
            "scripts/safe.py" to safe,
            "scripts/unsafe.py" to unsafe,
        ))

        assertEquals(CompatibilityClass.B, inspection.classification)
        val raw = Json.parseToJsonElement(inspection.rawManifestJson!!).jsonObject
        assertEquals(
            listOf("scripts/safe.py"),
            raw.getValue("legacyPrograms").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(inspection.reasons.any { it.contains("unsafe.py") && it.contains("os") })
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
    fun consecutiveDotsInsideAFileNameRemainInstallable() {
        val zip = zip(
            "SKILL.md" to "# helper\n".toByteArray(),
            "references/Hes.+theog..pdf" to "%PDF-1.4\n%%EOF".toByteArray(),
        )

        val inspection = SkillArchive.inspect(zip)

        assertEquals(CompatibilityClass.A, inspection.classification)
        assertTrue(inspection.installable)
        assertTrue("references/Hes.+theog..pdf" in inspection.files)
    }

    @Test
    fun pathSegmentsControlsAndDrivePathsAreClassE() {
        listOf(
            "folder/../escape/SKILL.md",
            "folder/./escape/SKILL.md",
            "/absolute/SKILL.md",
            "C:/windows/SKILL.md",
            "folder/\u0001SKILL.md",
        ).forEach { name ->
            val inspection = SkillArchive.inspect(zip(name to "# no".toByteArray()))
            assertEquals(CompatibilityClass.E, inspection.classification, name)
            assertFalse(inspection.installable, name)
        }
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
    fun moreThanFiveThousandEntriesIsClassE() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("SKILL.md"))
            zip.write("# helper\n".toByteArray())
            zip.closeEntry()
            repeat(5001) { i ->
                zip.putNextEntry(ZipEntry("f$i.txt"))
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
        }
        val inspection = SkillArchive.inspect(out.toByteArray())
        assertEquals(CompatibilityClass.E, inspection.classification)
        assertFalse(inspection.installable)
        assertFalse(SkillInstaller.install(out.toByteArray()).accepted)
    }

    @Test
    fun compressionBombIsClassE() {
        val payload = "a".repeat(90_000).toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("SKILL.md"))
            zip.write("# helper\n".toByteArray())
            zip.closeEntry()
            val entry = ZipEntry("bomb.txt")
            zip.putNextEntry(entry)
            zip.write(payload)
            zip.closeEntry()
        }
        val bytes = out.toByteArray()
        val inspection = SkillArchive.inspect(bytes)
        assertEquals(CompatibilityClass.E, inspection.classification)
        assertFalse(inspection.installable)
    }

    @Test
    fun uppercaseRemoteWheelIsClassE() {
        val zip = zip(
            "mobile-skill.json" to validManifest("python").toByteArray(),
            "requirements.txt" to "requests @ HTTPS://example.invalid/a.whl\n".toByteArray(),
        )
        val inspection = SkillArchive.inspect(zip)
        assertEquals(CompatibilityClass.E, inspection.classification)
        assertFalse(inspection.installable)
    }

    @Test
    fun shellRuntimeIsClassDAndInstallableAsInstructions() {
        val zip = zip("mobile-skill.json" to validManifest("shell").toByteArray())
        val inspection = SkillArchive.inspect(zip)
        assertEquals(CompatibilityClass.D, inspection.classification)
        assertTrue(inspection.installable)
    }

    @Test
    fun truncatedZipIsClassE() {
        val truncated = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        val inspection = SkillArchive.inspect(truncated)
        assertEquals(CompatibilityClass.E, inspection.classification)
        assertFalse(inspection.installable)
        assertFalse(SkillInstaller.install(truncated).accepted)
    }

    @Test
    fun localHeaderWithFakeEocdAndNoCentralDirectoryIsClassE() {
        val valid = zip("SKILL.md" to "# helper\n".toByteArray())
        val centralDirectoryOffset = signatureOffset(valid, 0x50, 0x4B, 0x01, 0x02)
        val fakeEocd = ByteArray(22).also {
            it[0] = 0x50
            it[1] = 0x4B
            it[2] = 0x05
            it[3] = 0x06
        }
        val localHeaderOnly = valid.copyOfRange(0, centralDirectoryOffset) + fakeEocd

        val inspection = SkillArchive.inspect(localHeaderOnly)

        assertEquals(CompatibilityClass.E, inspection.classification)
        assertFalse(inspection.installable)
    }

    @Test
    fun caseInsensitiveDuplicatePathsAreClassEWithoutSourceExposure() {
        val bytes = zip(
            "mobile-skill.json" to validManifest("python").toByteArray(),
            "MOBILE-SKILL.JSON" to validManifest("python").toByteArray(),
            "SKILL.md" to "# helper\n".toByteArray(),
        )

        val inspection = SkillArchive.inspect(bytes)

        assertEquals(CompatibilityClass.E, inspection.classification)
        assertFalse(inspection.installable)
        assertEquals(null, inspection.manifest)
        assertEquals(null, inspection.skillMarkdown)
        assertEquals(null, inspection.rawManifestJson)
    }

    @Test
    fun normalizedDuplicatePathsAreClassEWithoutSourceExposure() {
        val bytes = zip(
            "./mobile-skill.json" to validManifest("python").toByteArray(),
            "mobile-skill.json" to validManifest("python").toByteArray(),
        )

        val inspection = SkillArchive.inspect(bytes)

        assertEquals(CompatibilityClass.E, inspection.classification)
        assertFalse(inspection.installable)
        assertEquals(null, inspection.manifest)
        assertEquals(null, inspection.rawManifestJson)
    }

    @Test
    fun localAndCentralEntryNamesMustMatch() {
        val valid = zip("SKILL.md" to "# helper\n".toByteArray())
        val mismatched = patchCentralName(valid, "OTHER.md")

        val inspection = SkillArchive.inspect(mismatched)

        assertEquals(CompatibilityClass.E, inspection.classification)
        assertFalse(inspection.installable)
    }

    @Test
    fun signedDataDescriptorArchiveRemainsSupported() {
        val inspection = SkillArchive.inspect(zip("SKILL.md" to "# helper\n".toByteArray()))

        assertEquals(CompatibilityClass.A, inspection.classification)
        assertTrue(inspection.installable)
    }

    @Test
    fun unsignedDataDescriptorArchiveRemainsSupported() {
        val signed = zip("SKILL.md" to "# helper\n".toByteArray())
        val unsigned = removeDataDescriptorSignature(signed)

        val inspection = SkillArchive.inspect(unsigned)

        assertEquals(CompatibilityClass.A, inspection.classification)
        assertTrue(inspection.installable)
    }

    @Test
    fun archiveWithoutDataDescriptorRemainsSupported() {
        val inspection = SkillArchive.inspect(storedZip("SKILL.md" to "# helper\n".toByteArray()))

        assertEquals(CompatibilityClass.A, inspection.classification)
        assertTrue(inspection.installable)
    }

    @Test
    fun dataDescriptorCentralCrcMismatchIsClassE() {
        val valid = zip("SKILL.md" to "# helper\n".toByteArray())
        val mismatched = patchCentralU32(valid, fieldOffset = 16, value = 0L)

        val inspection = SkillArchive.inspect(mismatched)

        assertEquals(CompatibilityClass.E, inspection.classification)
        assertFalse(inspection.installable)
    }

    @Test
    fun dataDescriptorCentralSizeMismatchIsClassE() {
        val valid = zip("SKILL.md" to "# helper\n".toByteArray())
        val mismatched = patchCentralU32(valid, fieldOffset = 24, value = 0L)

        val inspection = SkillArchive.inspect(mismatched)

        assertEquals(CompatibilityClass.E, inspection.classification)
        assertFalse(inspection.installable)
    }

    @Test
    fun directoryDescriptorCentralMetadataMismatchIsClassE() {
        val valid = zip(
            "empty/" to ByteArray(0),
            "SKILL.md" to "# helper\n".toByteArray(),
        )
        val mismatched = patchCentralU32(valid, fieldOffset = 16, value = 1L)

        val inspection = SkillArchive.inspect(mismatched)

        assertEquals(CompatibilityClass.E, inspection.classification)
        assertFalse(inspection.installable)
    }

    @Test
    fun directoryPayloadCannotBypassCompressionRatioLimit() {
        val bytes = zip(
            "compressed-directory/" to ByteArray(128 * 1024),
            "SKILL.md" to "# helper\n".toByteArray(),
        )

        val inspection = SkillArchive.inspect(bytes)

        assertEquals(CompatibilityClass.E, inspection.classification)
        assertFalse(inspection.installable)
        assertTrue(inspection.reasons.any { it.contains("Compression bomb") })
    }

    @Test
    fun unixSymlinkAttributeIsClassE() {
        val raw = zip("SKILL.md" to "# helper\n".toByteArray())
        val patched = patchUnixSymlink(raw)
        val inspection = SkillArchive.inspect(patched)
        assertEquals(CompatibilityClass.E, inspection.classification)
        assertFalse(inspection.installable)
    }

    private fun patchUnixSymlink(bytes: ByteArray): ByteArray {
        val out = bytes.copyOf()
        var i = 0
        while (i + 46 <= out.size) {
            if (out[i] == 0x50.toByte() && out[i + 1] == 0x4B.toByte() &&
                out[i + 2] == 0x01.toByte() && out[i + 3] == 0x02.toByte()
            ) {
                val mode = 0xA000 shl 16
                out[i + 38] = (mode and 0xFF).toByte()
                out[i + 39] = ((mode ushr 8) and 0xFF).toByte()
                out[i + 40] = ((mode ushr 16) and 0xFF).toByte()
                out[i + 41] = ((mode ushr 24) and 0xFF).toByte()
                return out
            }
            i++
        }
        error("central directory not found")
    }

    private fun signatureOffset(bytes: ByteArray, vararg signature: Int): Int {
        require(signature.size == 4)
        for (offset in 0..bytes.size - signature.size) {
            if (signature.indices.all { index ->
                    (bytes[offset + index].toInt() and 0xFF) == signature[index]
                }) {
                return offset
            }
        }
        error("signature not found")
    }

    private fun patchCentralName(bytes: ByteArray, replacement: String): ByteArray {
        val out = bytes.copyOf()
        val centralOffset = signatureOffset(out, 0x50, 0x4B, 0x01, 0x02)
        val nameLength = (out[centralOffset + 28].toInt() and 0xFF) or
            ((out[centralOffset + 29].toInt() and 0xFF) shl 8)
        val nameBytes = replacement.toByteArray()
        require(nameBytes.size == nameLength)
        nameBytes.copyInto(out, destinationOffset = centralOffset + 46)
        return out
    }

    private fun patchCentralU32(bytes: ByteArray, fieldOffset: Int, value: Long): ByteArray {
        require(value in 0..0xFFFF_FFFFL)
        val out = bytes.copyOf()
        val centralOffset = signatureOffset(out, 0x50, 0x4B, 0x01, 0x02)
        repeat(4) { index ->
            out[centralOffset + fieldOffset + index] = ((value ushr (index * 8)) and 0xFF).toByte()
        }
        return out
    }

    private fun removeDataDescriptorSignature(bytes: ByteArray): ByteArray {
        val descriptorOffset = signatureOffset(bytes, 0x50, 0x4B, 0x07, 0x08)
        val unsigned = bytes.copyOfRange(0, descriptorOffset) + bytes.copyOfRange(descriptorOffset + 4, bytes.size)
        val eocdOffset = signatureOffset(unsigned, 0x50, 0x4B, 0x05, 0x06)
        val centralOffset = readU32(unsigned, eocdOffset + 16)
        writeU32(unsigned, eocdOffset + 16, centralOffset - 4)
        return unsigned
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun writeU32(bytes: ByteArray, offset: Int, value: Long) {
        require(value in 0..0xFFFF_FFFFL)
        repeat(4) { index ->
            bytes[offset + index] = ((value ushr (index * 8)) and 0xFF).toByte()
        }
    }

    private fun storedZip(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, payload) ->
                val crc = CRC32().also { it.update(payload) }.value
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = payload.size.toLong()
                    this.crc = crc
                }
                zip.putNextEntry(entry)
                zip.write(payload)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
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
