// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.SkillMemoryEntry
import runtime.mobileagent.domain.SkillMemorySpace

class SkillMemoryRepositoryTest {
    @Test
    fun memoryBodyRoundTripsThroughPrivateSidecarAndNeverSqlite() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val root = Files.createTempDirectory("skill-memory-roundtrip")
            val repo = SkillMemoryRepository(db, root)
            val space = repo.ensureSpace("install-1", "package-v1")
            val first = repo.replace("install-1", "package-v1", "MEMORY.md", "first")
            val second = repo.append("install-1", "package-v1", "MEMORY.md", "\nsecond", first.version)
            assertEquals("first\nsecond", repo.read(space.spaceId, "MEMORY.md")!!.content)
            assertEquals(first.version + 1, second.version)
            assertEquals(second, repo.getEntry(second.entryId))
            assertTrue(db.query("PRAGMA table_info(skill_memory_entries)").none { it.string("name") == "content" })
            assertTrue(db.query("SELECT content_hash, storage_ref, byte_length FROM skill_memory_entries").single().string("storage_ref").contains(space.spaceId))
        }
    }

    @Test
    fun memoryPathQuotaVersionAndUpgradeIsolationAreEnforced() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val root = Files.createTempDirectory("skill-memory-limits")
            val repo = SkillMemoryRepository(db, root)
            val space = repo.ensureSpace("install-1", "package-v1", quotaBytes = 8, maxEntries = 1)
            val entry = repo.replace("install-1", "package-v1", "MEMORY.md", "12345678")
            assertThrows(AuthorityPolicyConflictException::class.java) {
                repo.replace("install-1", "package-v1", "MEMORY.md", "stale", entry.version - 1)
            }
            assertThrows(IllegalArgumentException::class.java) {
                repo.replace("install-1", "package-v1", "journal/2026-02-30.md", "x")
            }
            assertThrows(IllegalArgumentException::class.java) {
                repo.replace("install-1", "package-v1", "journal/2026-02-01.md", "x")
            }
            assertThrows(IllegalArgumentException::class.java) {
                repo.replace("install-1", "package-v1", "MEMORY.md", "123456789", entry.version)
            }
            val upgraded = repo.ensureSpace("install-1", "package-v2", quotaBytes = SkillMemorySpace.DEFAULT_QUOTA_BYTES)
            assertNotEquals(space.spaceId, upgraded.spaceId)
            assertEquals(null, repo.read(upgraded.spaceId, "MEMORY.md"))
        }
    }

    @Test
    fun memoryVersionQuotaAndSidecarSurviveRepositoryRestartAndMigrationReplay() {
        val dbFile = Files.createTempFile("skill-memory-restart", ".db")
        val root = Files.createTempDirectory("skill-memory-restart-root")
        try {
            val url = "jdbc:sqlite:${dbFile.toAbsolutePath()}"
            var firstVersion = 0L
            JdbcSqlConnection(url).use { db ->
                Migrations.apply(db)
                val repo = SkillMemoryRepository(db, root)
                repo.ensureSpace("install-restart", "package-restart", quotaBytes = 8, maxEntries = 1)
                firstVersion = repo.replace("install-restart", "package-restart", "MEMORY.md", "1234").version
            }

            // Re-opening the same DB and replaying the migration must not create a fresh space,
            // reset its quota, or lose the sidecar body.
            JdbcSqlConnection(url).use { db ->
                Migrations.apply(db)
                val repo = SkillMemoryRepository(db, root)
                val space = repo.forSkill("install-restart", "package-restart")!!
                assertEquals(firstVersion, repo.read(space.spaceId, "MEMORY.md")!!.version)
                assertEquals("1234", repo.read(space.spaceId, "MEMORY.md")!!.content)
                assertThrows(AuthorityPolicyConflictException::class.java) {
                    repo.replace("install-restart", "package-restart", "MEMORY.md", "stale", firstVersion - 1)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    repo.replace("install-restart", "package-restart", "MEMORY.md", "123456789", firstVersion)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    repo.replace("install-restart", "package-restart", "journal/2026-08-30.md", "x")
                }
                assertEquals(1, repo.listEntries(space.spaceId).size)
            }
        } finally {
            Files.deleteIfExists(dbFile)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun concurrentWritesToSameEntryEnforceOptimisticLockAndPreserveCommittedSidecar() {
        val dbFile = Files.createTempFile("skill-memory-concurrent", ".db")
        val root = Files.createTempDirectory("skill-memory-concurrent-root")
        val threadCount = 8
        val executor = Executors.newFixedThreadPool(threadCount)
        try {
            val url = "jdbc:sqlite:${dbFile.toAbsolutePath()}"
            JdbcSqlConnection(url).use { db ->
                Migrations.apply(db)
                val repo = SkillMemoryRepository(db, root)
                val space = repo.ensureSpace("install-conc", "pkg-conc", quotaBytes = 100_000, maxEntries = 10)
                val initial = repo.replace("install-conc", "pkg-conc", "MEMORY.md", "base-version-1")
                assertEquals(1L, initial.version)

                val readyLatch = CountDownLatch(threadCount)
                val startLatch = CountDownLatch(1)
                val doneLatch = CountDownLatch(threadCount)

                val successCount = AtomicInteger(0)
                val conflictCount = AtomicInteger(0)
                val otherErrorCount = AtomicInteger(0)
                val successfulContent = AtomicReference<String?>(null)

                for (i in 0 until threadCount) {
                    val index = i
                    executor.submit {
                        readyLatch.countDown()
                        startLatch.await()
                        try {
                            val content = "update-from-thread-$index"
                            val result = repo.replace("install-conc", "pkg-conc", "MEMORY.md", content, expectedVersion = initial.version)
                            successCount.incrementAndGet()
                            successfulContent.set(result.content)
                        } catch (_: AuthorityPolicyConflictException) {
                            conflictCount.incrementAndGet()
                        } catch (_: Throwable) {
                            otherErrorCount.incrementAndGet()
                        } finally {
                            doneLatch.countDown()
                        }
                    }
                }

                readyLatch.await()
                startLatch.countDown()
                doneLatch.await()

                assertEquals(0, otherErrorCount.get())
                assertEquals(1, successCount.get())
                assertEquals(threadCount - 1, conflictCount.get())

                val committed = repo.read(space.spaceId, "MEMORY.md")!!
                assertEquals(2L, committed.version)
                assertEquals(successfulContent.get(), committed.content)

                // Verify sidecar files: committed v1 and v2 exist, no failed attempt sidecars remain
                val sidecarDir = root.resolve(space.spaceId)
                val sidecarFiles = Files.list(sidecarDir).filter { Files.isRegularFile(it) }.toList()
                assertEquals(2, sidecarFiles.size)
            }
        } finally {
            executor.shutdownNow()
            Files.deleteIfExists(dbFile)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun competingWritesRespectSpaceByteQuotaUnderConcurrency() {
        val dbFile = Files.createTempFile("skill-memory-byte-quota", ".db")
        val root = Files.createTempDirectory("skill-memory-byte-quota-root")
        val threadCount = 6
        val executor = Executors.newFixedThreadPool(threadCount)
        try {
            val url = "jdbc:sqlite:${dbFile.toAbsolutePath()}"
            JdbcSqlConnection(url).use { db ->
                Migrations.apply(db)
                val repo = SkillMemoryRepository(db, root)
                // Total quota: 25 bytes. Each thread tries to write 10 bytes to a distinct journal entry.
                // At most 2 writes can succeed (2 * 10 = 20 <= 25). 3rd write would be 30 > 25.
                val space = repo.ensureSpace("install-byte-q", "pkg-byte-q", quotaBytes = 25, maxEntries = 10)

                val readyLatch = CountDownLatch(threadCount)
                val startLatch = CountDownLatch(1)
                val doneLatch = CountDownLatch(threadCount)

                val successCount = AtomicInteger(0)
                val quotaExceededCount = AtomicInteger(0)
                val otherErrorCount = AtomicInteger(0)

                for (i in 0 until threadCount) {
                    val day = "%02d".format(i + 1)
                    val path = "journal/2026-03-$day.md"
                    val content = "1234567890" // 10 bytes
                    executor.submit {
                        readyLatch.countDown()
                        startLatch.await()
                        try {
                            repo.replace("install-byte-q", "pkg-byte-q", path, content)
                            successCount.incrementAndGet()
                        } catch (e: IllegalArgumentException) {
                            if (e.message?.contains("quota exceeded", ignoreCase = true) == true) {
                                quotaExceededCount.incrementAndGet()
                            } else {
                                otherErrorCount.incrementAndGet()
                            }
                        } catch (_: Throwable) {
                            otherErrorCount.incrementAndGet()
                        } finally {
                            doneLatch.countDown()
                        }
                    }
                }

                readyLatch.await()
                startLatch.countDown()
                doneLatch.await()

                assertEquals(0, otherErrorCount.get())
                assertEquals(2, successCount.get())
                assertEquals(4, quotaExceededCount.get())

                val entries = repo.listEntries(space.spaceId)
                assertEquals(2, entries.size)
                val totalBytes = entries.sumOf { it.byteLength }
                assertTrue(totalBytes <= 25L)
                assertEquals(20L, totalBytes)
            }
        } finally {
            executor.shutdownNow()
            Files.deleteIfExists(dbFile)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun competingWritesRespectEntryCountQuotaUnderConcurrency() {
        val dbFile = Files.createTempFile("skill-memory-count-quota", ".db")
        val root = Files.createTempDirectory("skill-memory-count-quota-root")
        val threadCount = 6
        val executor = Executors.newFixedThreadPool(threadCount)
        try {
            val url = "jdbc:sqlite:${dbFile.toAbsolutePath()}"
            JdbcSqlConnection(url).use { db ->
                Migrations.apply(db)
                val repo = SkillMemoryRepository(db, root)
                // maxEntries = 2.
                val space = repo.ensureSpace("install-count-q", "pkg-count-q", quotaBytes = 100_000, maxEntries = 2)
                // 1 entry already exists
                repo.replace("install-count-q", "pkg-count-q", "MEMORY.md", "base")

                val readyLatch = CountDownLatch(threadCount)
                val startLatch = CountDownLatch(1)
                val doneLatch = CountDownLatch(threadCount)

                val successCount = AtomicInteger(0)
                val countQuotaExceededCount = AtomicInteger(0)
                val otherErrorCount = AtomicInteger(0)

                for (i in 0 until threadCount) {
                    val index = i
                    val day = "%02d".format(index + 1)
                    val path = "journal/2026-04-$day.md"
                    executor.submit {
                        readyLatch.countDown()
                        startLatch.await()
                        try {
                            repo.replace("install-count-q", "pkg-count-q", path, "content-$index")
                            successCount.incrementAndGet()
                        } catch (e: IllegalArgumentException) {
                            if (e.message?.contains("entry quota exceeded", ignoreCase = true) == true) {
                                countQuotaExceededCount.incrementAndGet()
                            } else {
                                otherErrorCount.incrementAndGet()
                            }
                        } catch (_: Throwable) {
                            otherErrorCount.incrementAndGet()
                        } finally {
                            doneLatch.countDown()
                        }
                    }
                }

                readyLatch.await()
                startLatch.countDown()
                doneLatch.await()

                assertEquals(0, otherErrorCount.get())
                assertEquals(1, successCount.get())
                assertEquals(5, countQuotaExceededCount.get())

                val entries = repo.listEntries(space.spaceId)
                assertEquals(2, entries.size)
            }
        } finally {
            executor.shutdownNow()
            Files.deleteIfExists(dbFile)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun concurrentInitialInsertsToSamePathEnforceSingleWinner() {
        val dbFile = Files.createTempFile("skill-memory-insert-conflict", ".db")
        val root = Files.createTempDirectory("skill-memory-insert-root")
        val threadCount = 6
        val executor = Executors.newFixedThreadPool(threadCount)
        try {
            val url = "jdbc:sqlite:${dbFile.toAbsolutePath()}"
            JdbcSqlConnection(url).use { db ->
                Migrations.apply(db)
                val space = SkillMemoryRepository(db, root)
                    .ensureSpace("install-insert", "pkg-insert", quotaBytes = 10_000, maxEntries = 5)
                // All writers must observe the missing row before any can commit an insert.
                // A writer that starts after the first commit can legitimately replace that row.
                val beforeTransaction = CyclicBarrier(threadCount)
                val racingDb = object : SqlConnection by db {
                    override fun <T> transaction(block: () -> T): T {
                        beforeTransaction.await(30, TimeUnit.SECONDS)
                        return db.transaction(block)
                    }
                }
                val repo = SkillMemoryRepository(racingDb, root)
                val writes = (0 until threadCount).map { index ->
                    executor.submit<String?> {
                        val content = "winner-$index"
                        try {
                            repo.replace("install-insert", "pkg-insert", "MEMORY.md", content)
                            content
                        } catch (_: AuthorityPolicyConflictException) {
                            null
                        }
                    }
                }
                val winner = writes.mapNotNull { it.get(45, TimeUnit.SECONDS) }.single()

                val entry = repo.read(space.spaceId, "MEMORY.md")!!
                assertEquals(1L, entry.version)
                assertEquals(winner, entry.content)

                // Verify only the 1 successful sidecar exists
                val sidecarDir = root.resolve(space.spaceId)
                Files.list(sidecarDir).use { files ->
                    assertEquals(1L, files.filter { Files.isRegularFile(it) }.count())
                }
            }
        } finally {
            executor.shutdownNow()
            Files.deleteIfExists(dbFile)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun committedRecordsRemainReadableAndIntactAfterFailureRollbackAndReopen() {
        val dbFile = Files.createTempFile("skill-memory-durability", ".db")
        val root = Files.createTempDirectory("skill-memory-durability-root")
        try {
            val url = "jdbc:sqlite:${dbFile.toAbsolutePath()}"
            JdbcSqlConnection(url).use { db ->
                Migrations.apply(db)
                val repo = SkillMemoryRepository(db, root)
                val space = repo.ensureSpace("install-dur", "pkg-dur", quotaBytes = 30, maxEntries = 2)
                val initialEntry = repo.replace("install-dur", "pkg-dur", "MEMORY.md", "committed-content-v1")
                assertEquals(1L, initialEntry.version)

                // Attempt 1 that fails: version conflict
                assertThrows(AuthorityPolicyConflictException::class.java) {
                    repo.replace("install-dur", "pkg-dur", "MEMORY.md", "failed-v2", expectedVersion = 999L)
                }

                // Attempt 2 that fails: quota overflow (30 bytes limit; existing is 20 bytes; adding 15 bytes exceeds quota)
                assertThrows(IllegalArgumentException::class.java) {
                    repo.replace("install-dur", "pkg-dur", "journal/2026-05-01.md", "123456789012345")
                }

                // Attempt 3 that succeeds: append within quota (8 bytes appended -> 20 + 8 = 28 <= 30)
                val appended = repo.append("install-dur", "pkg-dur", "MEMORY.md", "-plus-v2", expectedVersion = 1L)
                assertEquals(2L, appended.version)
                assertEquals("committed-content-v1-plus-v2", appended.content)

                // Attempt 4 that fails: maxEntries exceeded
                repo.replace("install-dur", "pkg-dur", "journal/2026-05-01.md", "x") // 2nd entry succeeds
                assertThrows(IllegalArgumentException::class.java) {
                    repo.replace("install-dur", "pkg-dur", "journal/2026-05-02.md", "y") // 3rd entry fails
                }
            }

            // Reopen database connection and repository from disk
            JdbcSqlConnection(url).use { db ->
                Migrations.apply(db)
                val repo = SkillMemoryRepository(db, root)
                val space = repo.forSkill("install-dur", "pkg-dur")!!
                val memory = repo.read(space.spaceId, "MEMORY.md")!!
                assertEquals(2L, memory.version)
                assertEquals("committed-content-v1-plus-v2", memory.content)
                assertEquals(28L, memory.byteLength)

                val journal = repo.read(space.spaceId, "journal/2026-05-01.md")!!
                assertEquals(1L, journal.version)
                assertEquals("x", journal.content)
                assertEquals(1L, journal.byteLength)

                val entries = repo.listEntries(space.spaceId)
                assertEquals(2, entries.size)
            }
        } finally {
            Files.deleteIfExists(dbFile)
            root.toFile().deleteRecursively()
        }
    }
}
