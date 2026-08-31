// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
}
