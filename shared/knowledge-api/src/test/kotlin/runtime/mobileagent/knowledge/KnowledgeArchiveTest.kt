// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class KnowledgeArchiveTest {
    @Test
    fun inspectsPlainTextEntries() {
        val zip = zipOf("notes.txt" to "hello knowledge")
        val summary = KnowledgeArchive.inspect(zip)
        assertTrue(summary.ok, summary.reason)
        assertEquals(1, summary.entries.size)
        assertEquals("notes.txt", summary.entries.single().name)
    }

    @Test
    fun rejectsZipSlipAndDrivePaths() {
        val slip = zipOf("../secret.txt" to "nope")
        assertFalse(KnowledgeArchive.inspect(slip).ok)
        val nestedSlip = zipOf("folder/../secret.txt" to "nope")
        assertFalse(KnowledgeArchive.inspect(nestedSlip).ok)
        val dotSegment = zipOf("folder/./secret.txt" to "nope")
        assertFalse(KnowledgeArchive.inspect(dotSegment).ok)
        val absolute = zipOf("/absolute/secret.txt" to "nope")
        assertFalse(KnowledgeArchive.inspect(absolute).ok)
        val drive = zipOf("C:/windows/note.txt" to "nope")
        assertFalse(KnowledgeArchive.inspect(drive).ok)
        val control = zipOf("folder/\u0001secret.txt" to "nope")
        assertFalse(KnowledgeArchive.inspect(control).ok)
    }

    @Test
    fun allowsConsecutiveDotsInsideALegitimateFileName() {
        val zip = zipOf("books/Hes.+theog..pdf" to "%PDF-1.4\n%%EOF")

        val summary = KnowledgeArchive.inspect(zip)

        assertTrue(summary.ok, summary.reason)
        assertEquals("books/Hes.+theog..pdf", summary.entries.single().name)
    }

    @Test
    fun fileBackedArchiveAllowsConsecutiveDotsInsideALegitimateFileName() {
        val path = Files.createTempFile("knowledge-archive-dots-", ".zip")
        try {
            Files.write(path, zipOf("books/Hes.+theog..pdf" to "%PDF-1.4\n%%EOF"))
            val summary = KnowledgeArchive.forEachEntry(path.toFile()) { _, _ -> }
            assertTrue(summary.ok, summary.reason)
            assertEquals("books/Hes.+theog..pdf", summary.entries.single().name)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun zipSafetyUsesPathSegmentsAndRejectsControls() {
        val legal = ZipSafety.inspect(zipOf("books/Hes.+theog..pdf" to "content"))
        assertTrue(legal.ok, legal.reason)

        listOf(
            "folder/../escape.txt",
            "folder/./escape.txt",
            "/absolute.txt",
            "C:/windows.txt",
            "folder/\u0001.txt",
        ).forEach { name ->
            val inspection = ZipSafety.inspect(zipOf(name to "content"))
            assertFalse(inspection.ok, name)
        }
    }

    @Test
    fun rejectsNestedZipAndDuplicates() {
        val nested = zipOf("inner.zip" to "PK\u0003\u0004")
        assertFalse(KnowledgeArchive.inspect(nested).ok)
        val duplicate = zipBytes { zip ->
            zip.putNextEntry(ZipEntry("a.txt")); zip.write("one".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("A.txt")); zip.write("two".toByteArray()); zip.closeEntry()
        }
        assertFalse(KnowledgeArchive.inspect(duplicate).ok)
    }

    @Test
    fun skipsUnknownSidecarFiles() {
        val zip = zipBytes { zip ->
            zip.putNextEntry(ZipEntry("notes.txt")); zip.write("hello knowledge".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry(".DS_Store")); zip.write(byteArrayOf(0, 1, 2, 3, 4)); zip.closeEntry()
        }
        val summary = KnowledgeArchive.inspect(zip)
        assertTrue(summary.ok, summary.reason)
        assertEquals(1, summary.entries.size)
        assertEquals("notes.txt", summary.entries.single().name)
    }

    @Test
    fun streamsImportableEntriesWithoutBuildingAnExtractedPayloadList() {
        val zip = zipBytes {
            it.putNextEntry(ZipEntry("one.txt")); it.write("first".toByteArray()); it.closeEntry()
            it.putNextEntry(ZipEntry("two.md")); it.write("second".toByteArray()); it.closeEntry()
        }
        val seen = mutableListOf<String>()
        val summary = KnowledgeArchive.forEachEntry(zip) { entry, payload ->
            seen += "${entry.name}:${String(payload, Charsets.UTF_8)}"
        }
        assertTrue(summary.ok, summary.reason)
        assertEquals(listOf("one.txt:first", "two.md:second"), seen)
        assertEquals(2, summary.entries.size)
    }

    @Test
    fun rejectsAbnormalCompressionRatioBeforeEntryCallback() {
        val zip = zipBytes { out ->
            out.putNextEntry(ZipEntry("zeros.txt"))
            out.write(ByteArray(256 * 1024))
            out.closeEntry()
        }
        var callbacks = 0
        val summary = KnowledgeArchive.forEachEntry(zip) { _, _ -> callbacks += 1 }
        assertFalse(summary.ok)
        assertTrue(summary.reason.contains("compression ratio", ignoreCase = true), summary.reason)
        assertEquals(0, callbacks)
    }

    @Test
    fun rejectsCentralLocalNameMismatchBeforeEntryCallback() {
        val zip = zipOf("safe.txt" to "hello")
        // First local-file name starts after the fixed 30-byte local header.
        zip[30] = 'x'.code.toByte()
        var callbacks = 0
        val summary = KnowledgeArchive.forEachEntry(zip) { _, _ -> callbacks += 1 }
        assertFalse(summary.ok)
        assertTrue(summary.reason.contains("central/local", ignoreCase = true), summary.reason)
        assertEquals(0, callbacks)
    }

    @Test
    fun rejectsUnixSymlinkBeforeEntryCallback() {
        val zip = zipOf("link.txt" to "target.txt")
        val central = signatureOffset(zip, 0x02014B50)
        // version-made-by host=Unix and external file type=S_IFLNK (0120000).
        putShortLe(zip, central + 4, (3 shl 8) or 20)
        putIntLe(zip, central + 38, 0xA000 shl 16)
        var callbacks = 0
        val summary = KnowledgeArchive.forEachEntry(zip) { _, _ -> callbacks += 1 }
        assertFalse(summary.ok)
        assertTrue(summary.reason.contains("links", ignoreCase = true), summary.reason)
        assertEquals(0, callbacks)
    }

    @Test
    fun fileBackedPathStreamsOneBoundedEntryAtATime() {
        val path = Files.createTempFile("knowledge-archive-", ".zip")
        try {
            Files.write(path, zipOf("folder/notes.txt" to "file backed"))
            val seen = mutableListOf<String>()
            val summary = KnowledgeArchive.forEachEntry(path.toFile()) { entry, payload ->
                seen += "${entry.name}:${String(payload, Charsets.UTF_8)}"
            }
            assertTrue(summary.ok, summary.reason)
            assertEquals(listOf("folder/notes.txt:file backed"), seen)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray = zipBytes { zip ->
        entries.forEach { (name, body) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(body.toByteArray())
            zip.closeEntry()
        }
    }

    private fun zipBytes(block: (ZipOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use(block)
        return out.toByteArray()
    }

    private fun signatureOffset(bytes: ByteArray, signature: Int): Int {
        for (offset in 0..bytes.size - 4) {
            val value = (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
            if (value == signature) return offset
        }
        error("ZIP signature not found")
    }

    private fun putShortLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putIntLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }
}
