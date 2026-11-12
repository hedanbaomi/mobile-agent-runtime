// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ErrorCode

class EmpiricalMigrationChallengerTest {

    @Test
    fun freshDatabaseMigratesFromV0ToV41WithAllExpectedTablesAndIndexes() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)

            // Verify final version is 41
            val versionRow = db.query("SELECT version FROM schema_version").single()
            assertEquals(41L, versionRow.long("version"))

            // Verify PRAGMA quick_check
            val quickCheck = db.query("PRAGMA quick_check").map { it.columns.values.singleOrNull() }
            assertEquals(listOf("ok"), quickCheck)

            // Verify core decomposed schema tables exist
            val tableNames = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").map { it.string("name") }.toSet()
            val expectedTables = listOf(
                "schema_version",
                "provider_profiles",
                "model_profiles",
                "agent_profiles",
                "prompt_revisions",
                "conversations",
                "messages",
                "message_parts",
                "knowledge_bases",
                "embedding_query_attempts",
                "blobs",
                "documents",
                "chunks",
                "embeddings",
                "secrets",
                "announcement_state",
                "app_prefs",
                "announcement_feed_cache",
                "announcement_items",
                "audit_events",
                "import_jobs",
                "import_batches",
                "import_items",
                "consent_tickets",
                "capability_probes",
                "document_versions",
                "embedding_operations",
                "embedding_query_vectors",
                "index_generations",
                "generation_members",
                "assets",
                "vision_results",
                "skill_packages",
                "skill_installs",
                "permission_grants",
                "skill_invocations",
                "runs",
                "tool_invocations",
                "blob_gc_pending",
                "pipeline_attempts",
                "pipeline_policies",
                "world_books",
                "world_book_revisions",
                "trpg_sessions",
                "trpg_run_receipts",
                "trpg_events",
                "trpg_checkpoints",
                "trpg_undo",
                "root_authority_state",
                "authority_policy",
                "authority_preferences",
                "conversation_workspace_bindings",
                "privileged_workspace_bindings",
                "context_compactions"
            )
            for (table in expectedTables) {
                assertTrue(tableNames.contains(table), "Expected table '$table' to exist in v41 schema")
            }

            // Verify added columns from CharacterCardStorage and TrpgSchema
            val profileCols = db.query("PRAGMA table_info(agent_profiles)").map { it.string("name") }.toSet()
            assertTrue(profileCols.contains("character_card_json"), "character_card_json column missing in agent_profiles")
            assertTrue(profileCols.contains("mode"), "mode column missing in agent_profiles")

            // Verify foreign key integrity
            val fkIssues = Migrations.foreignKeyCompatibility(db)
            assertTrue(fkIssues.isEmpty(), "Expected 0 foreign key issues on fresh DB, found: $fkIssues")
        }
    }

    @Test
    fun migrationIsIdempotentAcrossMultipleApplies() {
        JdbcSqlConnection().use { db ->
            // Apply 1: initial migration v0 -> v41
            Migrations.apply(db)
            assertEquals(41L, db.query("SELECT version FROM schema_version").single().long("version"))

            // Insert user data
            db.execute("INSERT INTO app_prefs(key, value) VALUES(?, ?)", listOf("test_key", "test_val"))
            db.execute("INSERT INTO provider_profiles(id, name, api_format, base_url, header_secret_refs, non_secret_headers, secret_ref, revision) VALUES(?,?,?,?,?,?,?,?)",
                listOf("prov-1", "Prov 1", "OPENAI_COMPATIBLE", "https://api.test", "{}", "{}", "sec-1", 1))

            // Apply 2: idempotent re-apply
            Migrations.apply(db)
            assertEquals(41L, db.query("SELECT version FROM schema_version").single().long("version"))
            assertEquals("test_val", db.query("SELECT value FROM app_prefs WHERE key = 'test_key'").single().string("value"))
            assertEquals("Prov 1", db.query("SELECT name FROM provider_profiles WHERE id = 'prov-1'").single().string("name"))

            // Apply 3: third idempotent apply
            Migrations.apply(db)
            assertEquals(41L, db.query("SELECT version FROM schema_version").single().long("version"))
            assertEquals(1, db.query("SELECT * FROM app_prefs WHERE key = 'test_key'").size)
            assertEquals(1, db.query("SELECT * FROM provider_profiles WHERE id = 'prov-1'").size)
        }
    }

    @Test
    fun pragmaQuickCheckFailureBlocksMigrationAndPreservesDatabase() {
        // Delegate SqlConnection that returns corrupted for PRAGMA quick_check
        val realDb = JdbcSqlConnection()
        realDb.execute("CREATE TABLE schema_version(version INTEGER NOT NULL PRIMARY KEY)")
        realDb.execute("INSERT INTO schema_version(version) VALUES (20)")
        realDb.execute("CREATE TABLE dummy(id TEXT PRIMARY KEY, val TEXT)")
        realDb.execute("INSERT INTO dummy(id, val) VALUES('d1', 'preserved')")

        val failingDb = object : SqlConnection by realDb {
            override fun query(sql: String, args: List<Any?>): List<SqlRow> {
                if (sql.contains("PRAGMA quick_check")) {
                    return listOf(SqlRow(mapOf("quick_check" to "corrupt page 3")))
                }
                return realDb.query(sql, args)
            }
        }

        val ex = assertThrows(IllegalStateException::class.java) {
            Migrations.apply(failingDb)
        }
        assertTrue(ex.message!!.contains("SQLite integrity check failed; database was preserved"))

        // Verify original data and schema_version remain unmutated
        assertEquals(20L, realDb.query("SELECT version FROM schema_version").single().long("version"))
        assertEquals("preserved", realDb.query("SELECT val FROM dummy WHERE id = 'd1'").single().string("val"))
        realDb.close()
    }

    @Test
    fun errorRollbackWhenSchemaVersionIsUnsupported() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            db.execute("UPDATE schema_version SET version = 42")
            db.execute("INSERT INTO app_prefs(key, value) VALUES('canary', 'present')")

            val ex = assertThrows(AppException::class.java) {
                Migrations.apply(db)
            }
            assertEquals(ErrorCode.SCHEMA_UNSUPPORTED, ex.error.code)

            // Database rolled back and preserved
            assertEquals(42L, db.query("SELECT version FROM schema_version").single().long("version"))
            assertEquals("present", db.query("SELECT value FROM app_prefs WHERE key = 'canary'").single().string("value"))
        }
    }

    @Test
    fun errorRollbackWhenSchemaVersionHasMultipleRows() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            db.execute("INSERT INTO schema_version(version) VALUES (40)")
            db.execute("INSERT INTO app_prefs(key, value) VALUES('canary', 'multiversion')")

            val ex = assertThrows(AppException::class.java) {
                Migrations.apply(db)
            }
            assertEquals(ErrorCode.INVALID_CONFIG, ex.error.code)
            assertTrue(ex.message!!.contains("schema_version has multiple rows"))

            // Verify canary is intact
            assertEquals("multiversion", db.query("SELECT value FROM app_prefs WHERE key = 'canary'").single().string("value"))
            assertEquals(2, db.query("SELECT * FROM schema_version").size)
        }
    }

    @Test
    fun errorRollbackWhenSchemaVersionIsNegative() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            db.execute("UPDATE schema_version SET version = -5")
            db.execute("INSERT INTO app_prefs(key, value) VALUES('canary', 'negversion')")

            val ex = assertThrows(AppException::class.java) {
                Migrations.apply(db)
            }
            assertEquals(ErrorCode.INVALID_CONFIG, ex.error.code)
            assertTrue(ex.message!!.contains("schema_version.version must not be negative"))

            // Verify canary is intact
            assertEquals(-5L, db.query("SELECT version FROM schema_version").single().long("version"))
            assertEquals("negversion", db.query("SELECT value FROM app_prefs WHERE key = 'canary'").single().string("value"))
        }
    }

    @Test
    fun intermediateVersionMigrationSurvivesStepTransitions() {
        // Test intermediate migration starting from v15, v27, v33, v40
        listOf(15L, 27L, 33L, 40L).forEach { startVersion ->
            JdbcSqlConnection().use { db ->
                Migrations.apply(db)
                db.execute("UPDATE schema_version SET version = ?", listOf(startVersion))
                db.execute("INSERT INTO app_prefs(key, value) VALUES(?, ?)", listOf("start_$startVersion", "ok"))

                Migrations.apply(db)

                assertEquals(41L, db.query("SELECT version FROM schema_version").single().long("version"))
                assertEquals("ok", db.query("SELECT value FROM app_prefs WHERE key = ?", listOf("start_$startVersion")).single().string("value"))
                val fkIssues = Migrations.foreignKeyCompatibility(db)
                assertTrue(fkIssues.isEmpty(), "FK issues after migrating from v$startVersion: $fkIssues")
            }
        }
    }
}
