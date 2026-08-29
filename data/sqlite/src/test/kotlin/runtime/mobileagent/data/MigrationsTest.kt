// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.ProviderProfile

class MigrationsTest {
    @Test
    fun schemaAndProviderRoundTrip() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = ProfileRepository(db)
        repo.upsertProvider(
            ProviderProfile(
                id = "p1",
                name = "Local",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "ref-1",
                revision = 1,
            ),
        )
        assertEquals("Local", repo.listProviders().single().name)
        assertEquals(Migrations.VERSION, db.query("SELECT version FROM schema_version").single().long("version").toInt())
        assertEquals(1, db.query("PRAGMA table_info(model_profiles)").count { it.string("name") == "parameters_json" })
        assertEquals(1, db.query("PRAGMA table_info(agent_snapshots)").count { it.string("name") == "expanded_json" })
        assertEquals(1, db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", listOf("embedding_query_attempts")).size)
    }

    @Test
    fun embeddingQueryAttemptsSurviveV10Migration() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)

        db.execute(
            "INSERT INTO knowledge_bases(id, name, active_generation_id, embedding_space_id, created_at, deleted_at) VALUES (?, ?, ?, ?, ?, ?)",
            listOf("kb-v8", "Existing KB", null, "space-v8", "2026-08-29T00:00:00Z", null),
        )
        db.execute(
            "INSERT INTO embedding_query_attempts(kb_id, space_id, query_hash, error, updated_at) VALUES (?, ?, ?, ?, ?)",
            listOf("kb-v8", "space-v8", "sha256-query", "UNKNOWN", "2026-08-29T00:01:00Z"),
        )
        // Simulate a valid v9 database: v9 data remains, while only the v10 tables are absent.
        db.execute("DROP TABLE embedding_operations")
        db.execute("DROP TABLE embedding_query_vectors")
        db.execute("UPDATE schema_version SET version = ?", listOf(9))

        Migrations.apply(db)

        assertEquals(Migrations.VERSION, db.query("SELECT version FROM schema_version").single().long("version").toInt())
        assertEquals("Existing KB", db.query("SELECT name FROM knowledge_bases WHERE id = ?", listOf("kb-v8")).single().string("name"))
        assertEquals(
            listOf("kb_id", "space_id", "query_hash", "retry_authorized", "error", "updated_at"),
            db.query("PRAGMA table_info(embedding_query_attempts)").map { it.string("name") },
        )
        assertEquals("knowledge_bases", db.query("PRAGMA foreign_key_list(embedding_query_attempts)").single().string("table"))
        assertEquals("0", db.query("PRAGMA table_info(embedding_query_attempts)").single { it.string("name") == "retry_authorized" }.string("dflt_value"))
        val createSql = db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
            listOf("embedding_query_attempts"),
        ).single().string("sql")
        assertTrue(createSql.replace(" ", "").contains("CHECK(retry_authorizedIN(0,1))"))
        assertEquals("UNKNOWN", db.query("SELECT error FROM embedding_query_attempts").single().string("error"))

        // A second apply must not replace the v9 data or newly created v10 tables.
        Migrations.apply(db)
        assertEquals(1, db.query("SELECT * FROM embedding_query_attempts").size)
        assertEquals("UNKNOWN", db.query("SELECT error FROM embedding_query_attempts").single().string("error"))
    }

    @Test
    fun embeddingOperationAndQueryVectorSchemaIsStrictAndIdempotent() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)

        val operationInfo = db.query("PRAGMA table_info(embedding_operations)")
        assertEquals(
            listOf(
                "token", "kind", "kb_id", "job_id", "document_id", "document_version_id", "space_id",
                "input_manifest_hash", "binding_fingerprint", "consent_fingerprint", "state", "cancel_requested",
                "error", "created_at", "updated_at",
            ),
            operationInfo.map { it.string("name") },
        )
        assertEquals(0L, operationInfo.single { it.string("name") == "job_id" }.long("notnull"))
        assertEquals(0L, operationInfo.single { it.string("name") == "document_id" }.long("notnull"))
        assertEquals(0L, operationInfo.single { it.string("name") == "document_version_id" }.long("notnull"))
        assertEquals("0", operationInfo.single { it.string("name") == "cancel_requested" }.string("dflt_value"))

        val vectorInfo = db.query("PRAGMA table_info(embedding_query_vectors)")
        assertEquals(
            listOf("space_id", "query_hash", "vector_blob", "dimension", "created_at"),
            vectorInfo.map { it.string("name") },
        )
        assertEquals(1L, vectorInfo.single { it.string("name") == "dimension" }.long("notnull"))

        assertEquals(
            setOf("knowledge_bases", "import_jobs", "documents", "document_versions"),
            db.query("PRAGMA foreign_key_list(embedding_operations)").map { it.string("table") }.toSet(),
        )
        val operationIndexes = db.query("PRAGMA index_list(embedding_operations)")
        val activeIndex = operationIndexes.single { it.string("name") == "uq_embedding_operations_active_kb" }
        assertEquals(1L, activeIndex.long("unique"))
        assertEquals(1L, activeIndex.long("partial"))
        assertTrue(operationIndexes.any { it.string("name") == "idx_embedding_operations_job" })

        val operationSql = db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
            listOf("embedding_operations"),
        ).single().string("sql").lowercase().replace(" ", "")
        assertTrue(operationSql.contains("check(kindin('import','rebuild','rebind'))"))
        assertTrue(operationSql.contains("check(statein('prepared','dispatched','cache_ready','published','failed','cancelled','aborted','unknown'))"))
        assertTrue(operationSql.contains("check(cancel_requestedin(0,1))"))
        val vectorSql = db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
            listOf("embedding_query_vectors"),
        ).single().string("sql").lowercase().replace(" ", "")
        assertTrue(vectorSql.contains("check(dimension>0)"))

        db.execute(
            "INSERT INTO knowledge_bases(id, name, active_generation_id, embedding_space_id, created_at, deleted_at) VALUES (?, ?, ?, ?, ?, ?)",
            listOf("kb-v10", "Embedding KB", null, "space-v10", "2026-08-29T00:00:00Z", null),
        )
        insertEmbeddingOperation(db, "op-1", "kb-v10", state = "PREPARED")
        assertThrows(Exception::class.java) {
            insertEmbeddingOperation(db, "op-2", "kb-v10", state = "DISPATCHED")
        }
        assertThrows(Exception::class.java) {
            insertEmbeddingOperation(db, "op-invalid", "missing-kb", state = "FAILED")
        }
        assertThrows(Exception::class.java) {
            insertEmbeddingOperation(db, "op-invalid-kind", "kb-v10", kind = "OTHER", state = "FAILED")
        }

        db.execute(
            "INSERT INTO embedding_query_vectors(space_id, query_hash, vector_blob, dimension, created_at) VALUES (?, ?, ?, ?, ?)",
            listOf("space-v10", "sha256-query", byteArrayOf(1, 2), 2, "2026-08-29T00:01:00Z"),
        )
        assertThrows(Exception::class.java) {
            db.execute(
                "INSERT INTO embedding_query_vectors(space_id, query_hash, vector_blob, dimension, created_at) VALUES (?, ?, ?, ?, ?)",
                listOf("space-v10", "sha256-query", byteArrayOf(3, 4), 2, "2026-08-29T00:02:00Z"),
            )
        }
        assertThrows(Exception::class.java) {
            db.execute(
                "INSERT INTO embedding_query_vectors(space_id, query_hash, vector_blob, dimension, created_at) VALUES (?, ?, ?, ?, ?)",
                listOf("space-v10", "sha256-invalid", byteArrayOf(1), 0, "2026-08-29T00:03:00Z"),
            )
        }

        // Re-running the migration must leave operation and vector records untouched.
        Migrations.apply(db)
        assertEquals(1, db.query("SELECT * FROM embedding_operations").size)
        assertEquals(1, db.query("SELECT * FROM embedding_query_vectors").size)
        assertEquals(Migrations.VERSION, db.query("SELECT version FROM schema_version").single().long("version").toInt())
    }

    private fun insertEmbeddingOperation(
        db: SqlConnection,
        token: String,
        kbId: String,
        kind: String = "IMPORT",
        state: String,
        cancelRequested: Int = 0,
    ) {
        db.execute(
            "INSERT INTO embedding_operations(token, kind, kb_id, job_id, document_id, document_version_id, space_id, input_manifest_hash, binding_fingerprint, consent_fingerprint, state, cancel_requested, error, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(
                token, kind, kbId, null, null, null, "space-v10", "manifest-hash", "binding-fingerprint",
                "consent-fingerprint", state, cancelRequested, "", "2026-08-29T00:00:00Z", "2026-08-29T00:00:00Z",
            ),
        )
    }

    @Test
    fun chatBindingUsesTheModelProviderNotNameAndModelSort() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = ProfileRepository(db)
        repo.upsertProvider(
            ProviderProfile(
                id = "alpha",
                name = "Alpha endpoint",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://alpha.example.invalid/v1",
                secretRef = "ref-alpha",
                revision = 1,
            ),
        )
        repo.upsertProvider(
            ProviderProfile(
                id = "zulu",
                name = "Zulu endpoint",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://zulu.example.invalid/v1",
                secretRef = "ref-zulu",
                revision = 1,
            ),
        )
        repo.upsertModel(
            runtime.mobileagent.domain.ModelProfile(
                id = "m-z",
                providerId = "alpha",
                role = runtime.mobileagent.domain.ModelRole.CHAT,
                modelId = "zeta-chat",
                capabilities = setOf("stream"),
                contextLimit = 8_000,
                outputLimit = 1_024,
                revision = 1,
            ),
        )
        repo.upsertModel(
            runtime.mobileagent.domain.ModelProfile(
                id = "m-a",
                providerId = "zulu",
                role = runtime.mobileagent.domain.ModelRole.CHAT,
                modelId = "alpha-chat",
                capabilities = setOf("stream"),
                contextLimit = 8_000,
                outputLimit = 1_024,
                revision = 1,
            ),
        )
        val (provider, model) = repo.chatBinding()!!
        assertEquals(provider.id, model.providerId)
        assertEquals("alpha", provider.id)
        assertEquals("zeta-chat", model.modelId)
    }

    @Test
    fun newerSchemaIsRejectedWithoutRewritingVersion() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        db.execute("DELETE FROM schema_version")
        db.execute("INSERT INTO schema_version(version) VALUES (?)", listOf(Migrations.VERSION + 1))

        val error = assertThrows(AppException::class.java) { Migrations.apply(db) }
        assertEquals(ErrorCode.SCHEMA_UNSUPPORTED, error.error.code)
        assertEquals(Migrations.VERSION + 1, db.query("SELECT version FROM schema_version").single().long("version").toInt())
    }

    @Test
    fun nonIntegerSchemaVersionIsRejectedWithoutCoercion() {
        // INTEGER PRIMARY KEY is a rowid alias in SQLite and rejects a REAL before the
        // migration code can inspect it.  Use a deliberately damaged, non-rowid schema_version
        // fixture so the strict reader receives the REAL value and can reject it itself.
        JdbcSqlConnection().use { db ->
            db.execute("CREATE TABLE schema_version(version REAL NOT NULL)")
            db.execute("INSERT INTO schema_version(version) VALUES (?)", listOf(8.5))

            assertThrows(AppException::class.java) { Migrations.apply(db) }
            assertEquals(8.5, db.query("SELECT version FROM schema_version").single().columns["version"])
        }
    }
}
