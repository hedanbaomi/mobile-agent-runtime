// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Used only after the unsupported database has been closed; never opens its schema. */
internal class DatabaseRecoveryStore(private val database: File, private val archives: File) {
    private fun files() = listOf(database, File(database.path + "-wal"), File(database.path + "-shm")).filter(File::isFile)

    fun export(output: OutputStream) {
        check(database.isFile) { "Recovery database is missing" }
        ZipOutputStream(output).use { zip ->
            for (file in files()) {
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Explicit user action. Keep the old SQLite files; CAS and secrets are never removed. */
    fun preserveForNewDatabase(): File {
        check(database.isFile) { "Recovery database is missing" }
        val directory = File(archives, UUID.randomUUID().toString())
        check(directory.mkdirs()) { "Cannot create recovery archive" }
        val moved = mutableListOf<Pair<File, File>>()
        try {
            for (source in files()) {
                val target = File(directory, source.name)
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                moved += source to target
            }
        } catch (failure: Exception) {
            for ((source, target) in moved.asReversed()) {
                try { Files.move(target.toPath(), source.toPath(), StandardCopyOption.ATOMIC_MOVE) }
                catch (rollback: Exception) { failure.addSuppressed(rollback) }
            }
            throw failure
        }
        return directory
    }
}
