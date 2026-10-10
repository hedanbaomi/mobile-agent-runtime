// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DatabaseRecoveryStoreTest {
    @TempDir lateinit var directory: Path

    @Test
    fun exportsClosedDatabaseAndSidecarsWithoutInterpretingSchema() {
        val database = directory.resolve("mobile-agent.db").toFile()
        val expected = mapOf("mobile-agent.db" to byteArrayOf(0, 1, 32, -1), "mobile-agent.db-wal" to byteArrayOf(2, 3), "mobile-agent.db-shm" to byteArrayOf(4, 5))
        expected.forEach { (name, bytes) -> directory.resolve(name).toFile().writeBytes(bytes) }
        val output = ByteArrayOutputStream()
        DatabaseRecoveryStore(database, directory.resolve("archives").toFile()).export(output)
        val actual = mutableMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; actual[entry.name] = zip.readBytes() }
        }
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, bytes) -> assertArrayEquals(bytes, actual[name]) }
        assertArrayEquals(expected.getValue(database.name), database.readBytes())
    }

    @Test
    fun startingNewPreservesOriginalAndLeavesCasUntouched() {
        val database = directory.resolve("mobile-agent.db").toFile().apply { writeText("opaque future schema") }
        val wal = directory.resolve("mobile-agent.db-wal").toFile().apply { writeText("future wal") }
        val cas = directory.resolve("cas").toFile().apply { mkdir(); resolve("retained").writeText("source") }
        val archive = DatabaseRecoveryStore(database, directory.resolve("archives").toFile()).preserveForNewDatabase()
        assertFalse(database.exists())
        assertFalse(wal.exists())
        assertEquals("opaque future schema", archive.resolve(database.name).readText())
        assertEquals("future wal", archive.resolve(wal.name).readText())
        assertEquals("source", cas.resolve("retained").readText())
    }

    @Test
    fun archiveFailureNeverRemovesTheOriginal() {
        val database = directory.resolve("mobile-agent.db").toFile().apply { writeText("preserve") }
        val impossible = directory.resolve("archives").toFile().apply { writeText("a file, not a directory") }
        assertThrows(IllegalStateException::class.java) { DatabaseRecoveryStore(database, impossible).preserveForNewDatabase() }
        assertEquals("preserve", database.readText())
    }
}
