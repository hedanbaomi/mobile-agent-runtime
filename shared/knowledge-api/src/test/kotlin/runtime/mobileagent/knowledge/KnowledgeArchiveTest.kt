// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
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
        val drive = zipOf("C:/windows/note.txt" to "nope")
        assertFalse(KnowledgeArchive.inspect(drive).ok)
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
}
