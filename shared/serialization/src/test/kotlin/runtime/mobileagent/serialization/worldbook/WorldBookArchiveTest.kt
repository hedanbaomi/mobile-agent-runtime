// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.worldbook

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import runtime.mobileagent.domain.worldbook.WorldBookFileSkip

/** Bounded ZIP reading for batch world book import: what is read, what is skipped, what fails. */
class WorldBookArchiveTest {
    @TempDir lateinit var directory: File

    private val book = """{"entries":{"0":{"uid":0,"key":["火球术"],"content":"3d6"}}}""".toByteArray()

    @Test
    fun `only json members are read and other files are reported, system metadata is silent`() {
        val zip = zip(
            "worlds/PHB.json" to book,
            "worlds/stored.json" to book,
            "worlds/sub/" to null,
            "cards/hero.png" to byteArrayOf(1, 2, 3),
            "nested/more.zip" to byteArrayOf(0x50, 0x4B, 3, 4),
            "notes/readme.txt" to "x".toByteArray(),
            "__MACOSX/worlds/._PHB.json" to byteArrayOf(0),
            "worlds/.DS_Store" to byteArrayOf(0),
            stored = setOf("worlds/stored.json"),
        )
        val (members, skips) = read(zip)
        assertEquals(listOf("worlds/PHB.json", "worlds/stored.json"), members.map { it.first })
        members.forEach { assertArrayEquals(book, it.second) }
        assertEquals(
            mapOf(
                "cards/hero.png" to WorldBookFileSkip.IMAGE,
                "nested/more.zip" to WorldBookFileSkip.NESTED_ARCHIVE,
                "notes/readme.txt" to WorldBookFileSkip.UNSUPPORTED_TYPE,
            ),
            skips.associate { it.path to it.reason },
        )
    }

    @Test
    fun `chinese names written without the utf8 flag decode as gb18030 and as unicode path extra`() {
        val gbk = Charset.forName("GB18030")
        val plain = rawZip(listOf(RawEntry("城主指南/怪物图鉴.json".toByteArray(gbk), book, flags = 0)))
        assertEquals(listOf("城主指南/怪物图鉴.json"), read(plain).first.map { it.first })

        val legacy = "????.json".toByteArray()
        val unicode = "法术大全.json".toByteArray(Charsets.UTF_8)
        val extra = ByteBuffer.allocate(4 + 5 + unicode.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x7075).putShort((5 + unicode.size).toShort()).put(1)
            .putInt(CRC32().apply { update(legacy) }.value.toInt()).put(unicode).array()
        val withExtra = rawZip(listOf(RawEntry(legacy, book, flags = 0, extra = extra)))
        assertEquals(listOf("法术大全.json"), read(withExtra).first.map { it.first })
    }

    @Test
    fun `a damaged member is skipped while its siblings are still read`() {
        val bytes = zip("a.json" to book, "b.json" to book, stored = setOf("a.json", "b.json")).readBytes()
        // Flip one payload byte of the first stored member; its CRC no longer matches.
        val payload = indexOf(bytes, book)
        bytes[payload] = (bytes[payload] + 1).toByte()
        val file = File(directory, "damaged.zip").apply { writeBytes(bytes) }
        val (members, skips) = read(file)
        assertEquals(listOf("b.json"), members.map { it.first })
        assertEquals(listOf(WorldBookArchiveSkip("a.json", WorldBookFileSkip.CORRUPT)), skips)
    }

    @Test
    fun `a member whose declared size disagrees with its data is corrupt`() {
        val entry = RawEntry("a.json".toByteArray(), book, flags = 0x800, declaredSize = book.size + 10L)
        val (members, skips) = read(rawZip(listOf(entry)))
        assertTrue(members.isEmpty())
        assertEquals(WorldBookFileSkip.CORRUPT, skips.single().reason)
        // DEFLATED with a data descriptor: the central directory alone declares the size.
        for (delta in listOf(10, -5)) {
            val bytes = zip("b.json" to book).readBytes()
            val central = (bytes.size - 4 downTo 0).first { ByteBuffer.wrap(bytes, it, 4).order(ByteOrder.LITTLE_ENDIAN).int == 0x02014B50 }
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(central + 24, book.size + delta)
            val (inflated, reasons) = read(File(directory, "patched$delta.zip").apply { writeBytes(bytes) })
            assertTrue(inflated.isEmpty(), "declared size delta $delta")
            assertEquals(WorldBookFileSkip.CORRUPT, reasons.single().reason)
        }
    }

    @Test
    fun `unsafe, linked, encrypted and duplicate members are skipped`() {
        val zip = rawZip(listOf(
            RawEntry("../escape.json".toByteArray(), book),
            RawEntry("/abs.json".toByteArray(), book),
            RawEntry("C:/drive.json".toByteArray(), book),
            RawEntry("link.json".toByteArray(), book, unixMode = 0xA1FF),
            RawEntry("secret.json".toByteArray(), book, flags = 0x801),
            RawEntry("Same.json".toByteArray(), book),
            RawEntry("same.JSON".toByteArray(), book),
        ))
        val (members, skips) = read(zip)
        assertEquals(listOf("Same.json"), members.map { it.first })
        assertEquals(
            listOf(WorldBookFileSkip.UNSAFE_PATH, WorldBookFileSkip.UNSAFE_PATH, WorldBookFileSkip.UNSAFE_PATH,
                WorldBookFileSkip.LINK, WorldBookFileSkip.ENCRYPTED, WorldBookFileSkip.DUPLICATE_PATH),
            skips.map { it.reason },
        )
    }

    @Test
    fun `json count and size beyond the remaining batch budget fail the whole archive`() {
        val zip = zip("a.json" to book, "b.json" to book)
        val count = assertThrows(WorldBookArchiveException::class.java) { WorldBookArchive.read(zip, Long.MAX_VALUE, 1) {} }
        assertTrue(count.limit)
        val size = assertThrows(WorldBookArchiveException::class.java) { WorldBookArchive.read(zip, book.size * 2L - 1, 10) {} }
        assertTrue(size.limit)
        var called = false
        assertThrows(WorldBookArchiveException::class.java) { WorldBookArchive.read(zip, 1, 10) { called = true } }
        assertFalse(called, "no member may be handed over before the budget check")
    }

    @Test
    fun `structurally invalid archives are rejected as a whole`() {
        val good = zip("a.json" to book).readBytes()
        val truncated = File(directory, "truncated.zip").apply { writeBytes(good.copyOf(good.size - 3)) }
        assertFalse(assertThrows(WorldBookArchiveException::class.java) { read(truncated) }.limit)

        val zip64 = good.copyOf()
        val eocd = good.size - 22
        ByteBuffer.wrap(zip64).order(ByteOrder.LITTLE_ENDIAN).putShort(eocd + 8, -1).putShort(eocd + 10, -1)
        assertThrows(WorldBookArchiveException::class.java) { read(File(directory, "z64.zip").apply { writeBytes(zip64) }) }

        val twice = rawZip(listOf(RawEntry("a.json".toByteArray(), book), RawEntry("b.json".toByteArray(), book)), shareOffset = true)
        assertThrows(WorldBookArchiveException::class.java) { read(twice) }

        assertThrows(WorldBookArchiveException::class.java) { read(File(directory, "text.zip").apply { writeText("not a zip") }) }
    }

    @Test
    fun `cancellation stops between members`() {
        val zip = zip("a.json" to book, "b.json" to book)
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            WorldBookArchive.read(zip, Long.MAX_VALUE, 10, checkpoint = { if (++calls > 1) throw IllegalStateException("cancelled") }) {}
        }
    }

    @Test
    fun `file classification and zip sniffing`() {
        assertEquals(null, WorldBookArchive.classifyName("a/B.JSON"))
        assertEquals(WorldBookFileSkip.IMAGE, WorldBookArchive.classifyName("card.PNG"))
        assertEquals(WorldBookFileSkip.NESTED_ARCHIVE, WorldBookArchive.classifyName("x.7z"))
        assertEquals(WorldBookFileSkip.UNSUPPORTED_TYPE, WorldBookArchive.classifyName("x.lorebook"))
        assertTrue(WorldBookArchive.isSystemMetadata("__MACOSX/a.json"))
        assertTrue(WorldBookArchive.isSystemMetadata("dir/._a.json"))
        assertFalse(WorldBookArchive.isSystemMetadata("dir/a.json"))
        assertTrue(WorldBookArchive.looksLikeZip(byteArrayOf(0x50, 0x4B, 3, 4)))
        assertFalse(WorldBookArchive.looksLikeZip("{\"entries\"".toByteArray()))
    }

    private fun read(file: File): Pair<List<Pair<String, ByteArray>>, List<WorldBookArchiveSkip>> {
        val members = ArrayList<Pair<String, ByteArray>>()
        val skips = WorldBookArchive.read(file, Long.MAX_VALUE, 1000) { members += it.path to it.bytes }
        return members to skips
    }

    private fun zip(vararg entries: Pair<String, ByteArray?>, stored: Set<String> = emptySet()): File {
        val file = File(directory, "test-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                if (name in stored && bytes != null) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                out.putNextEntry(entry)
                bytes?.let(out::write)
                out.closeEntry()
            }
        }
        return file
    }

    private class RawEntry(
        val name: ByteArray,
        val data: ByteArray,
        val flags: Int = 0x800,
        val extra: ByteArray = ByteArray(0),
        val unixMode: Int? = null,
        val declaredSize: Long? = null,
    )

    /** Hand-built STORED archive so headers can carry exactly the flags a test needs. */
    private fun rawZip(entries: List<RawEntry>, shareOffset: Boolean = false): File {
        val out = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        for (entry in entries) {
            offsets += out.size()
            val crc = CRC32().apply { update(entry.data) }.value.toInt()
            val size = (entry.declaredSize ?: entry.data.size.toLong()).toInt()
            out.write(le(30 + entry.name.size + entry.extra.size) { b ->
                b.putInt(0x04034B50).putShort(20).putShort(entry.flags.toShort()).putShort(0).putInt(0)
                    .putInt(crc).putInt(entry.data.size).putInt(size)
                    .putShort(entry.name.size.toShort()).putShort(entry.extra.size.toShort()).put(entry.name).put(entry.extra)
            })
            out.write(entry.data)
        }
        val central = out.size()
        entries.forEachIndexed { index, entry ->
            val crc = CRC32().apply { update(entry.data) }.value.toInt()
            val size = (entry.declaredSize ?: entry.data.size.toLong()).toInt()
            val madeBy = if (entry.unixMode != null) (3 shl 8) or 20 else 20
            out.write(le(46 + entry.name.size + entry.extra.size) { b ->
                b.putInt(0x02014B50).putShort(madeBy.toShort()).putShort(20).putShort(entry.flags.toShort()).putShort(0).putInt(0)
                    .putInt(crc).putInt(entry.data.size).putInt(size)
                    .putShort(entry.name.size.toShort()).putShort(entry.extra.size.toShort()).putShort(0)
                    .putShort(0).putShort(0).putInt((entry.unixMode ?: 0) shl 16)
                    .putInt(if (shareOffset) offsets[0] else offsets[index]).put(entry.name).put(entry.extra)
            })
        }
        val centralSize = out.size() - central
        out.write(le(22) { b ->
            b.putInt(0x06054B50).putShort(0).putShort(0).putShort(entries.size.toShort()).putShort(entries.size.toShort())
                .putInt(centralSize).putInt(central).putShort(0)
        })
        return File(directory, "raw-${System.nanoTime()}.zip").apply { writeBytes(out.toByteArray()) }
    }

    private fun le(size: Int, fill: (ByteBuffer) -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).also(fill).array()

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int =
        (0..haystack.size - needle.size).first { start -> needle.indices.all { haystack[start + it] == needle[it] } }
}
