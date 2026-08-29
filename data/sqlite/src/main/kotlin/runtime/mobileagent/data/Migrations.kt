// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass

/**
 * SQLite schema owner for the application database.
 *
 * The previous implementation ran every ALTER statement and swallowed every exception. That
 * made a typo, a damaged schema, and an already-present column indistinguishable. v8 through v10 keep
 * existing data and only execute an ALTER after inspecting the live table. Any other DDL or
 * version error escapes the transaction, leaving schema_version at its previous value.
 */
object Migrations {
    const val VERSION = 11

    private val statements = listOf(
        "CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL PRIMARY KEY)",
        "CREATE TABLE IF NOT EXISTS provider_profiles (id TEXT PRIMARY KEY, name TEXT NOT NULL, api_format TEXT NOT NULL, base_url TEXT NOT NULL, header_secret_refs TEXT NOT NULL, non_secret_headers TEXT NOT NULL, secret_ref TEXT NOT NULL, revision INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS model_profiles (id TEXT PRIMARY KEY, provider_id TEXT NOT NULL, role TEXT NOT NULL, model_id TEXT NOT NULL, capabilities TEXT NOT NULL, parameter_schema_json TEXT NOT NULL, parameters_json TEXT NOT NULL DEFAULT '{}', context_limit INTEGER NOT NULL, output_limit INTEGER NOT NULL, revision INTEGER NOT NULL, endpoint_json TEXT NOT NULL DEFAULT '{}', FOREIGN KEY(provider_id) REFERENCES provider_profiles(id))",
        "CREATE TABLE IF NOT EXISTS agent_profiles (id TEXT PRIMARY KEY, name TEXT NOT NULL, prompt_revision_id TEXT NOT NULL, chat_profile_id TEXT NOT NULL, vision_profile_id TEXT, embedding_profile_id TEXT, reranker_profile_id TEXT, knowledge_base_ids TEXT NOT NULL, skill_ids TEXT NOT NULL, retrieval_mode TEXT NOT NULL, revision INTEGER NOT NULL, parameter_overrides_json TEXT NOT NULL DEFAULT '{}', context_policy_json TEXT NOT NULL DEFAULT '{}', permission_settings_json TEXT NOT NULL DEFAULT '{}')",
        "CREATE TABLE IF NOT EXISTS prompt_revisions (id TEXT PRIMARY KEY, agent_id TEXT NOT NULL, parent_revision_id TEXT, template TEXT NOT NULL, allowed_variables TEXT NOT NULL, created_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS conversations (id TEXT PRIMARY KEY, snapshot_id TEXT NOT NULL, title TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS agent_snapshots (id TEXT PRIMARY KEY, schema_version INTEGER NOT NULL, agent_id TEXT NOT NULL, prompt_revision_id TEXT NOT NULL, chat_model_id TEXT NOT NULL, provider_revision INTEGER NOT NULL, knowledge_base_ids TEXT NOT NULL, skill_ids TEXT NOT NULL, created_at TEXT NOT NULL, provider_id TEXT NOT NULL DEFAULT '', chat_model_revision INTEGER NOT NULL DEFAULT 0, vision_model_id TEXT, vision_model_revision INTEGER, embedding_model_id TEXT, embedding_model_revision INTEGER, reranker_model_id TEXT, reranker_model_revision INTEGER, parameter_overrides_json TEXT NOT NULL DEFAULT '{}', context_policy_json TEXT NOT NULL DEFAULT '{}', permission_settings_json TEXT NOT NULL DEFAULT '{}', binding_manifest_json TEXT NOT NULL DEFAULT '{}', expanded_json TEXT NOT NULL DEFAULT '{}')",
        "CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, parent_message_id TEXT, role TEXT NOT NULL, text TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL, parts_json TEXT NOT NULL DEFAULT '[]', metadata_json TEXT NOT NULL DEFAULT '{}', FOREIGN KEY(conversation_id) REFERENCES conversations(id))",
        "CREATE TABLE IF NOT EXISTS message_parts (id TEXT PRIMARY KEY, message_id TEXT NOT NULL, ordinal INTEGER NOT NULL, part_type TEXT NOT NULL, payload_json TEXT NOT NULL, UNIQUE(message_id, ordinal), FOREIGN KEY(message_id) REFERENCES messages(id))",
        "CREATE TABLE IF NOT EXISTS knowledge_bases (id TEXT PRIMARY KEY, name TEXT NOT NULL, active_generation_id TEXT, embedding_space_id TEXT, created_at TEXT NOT NULL, deleted_at TEXT)",
        "CREATE TABLE IF NOT EXISTS embedding_query_attempts (kb_id TEXT NOT NULL, space_id TEXT NOT NULL, query_hash TEXT NOT NULL, retry_authorized INTEGER NOT NULL DEFAULT 0 CHECK(retry_authorized IN(0,1)), error TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY(kb_id,space_id,query_hash), FOREIGN KEY(kb_id) REFERENCES knowledge_bases(id))",
        "CREATE TABLE IF NOT EXISTS blobs (hash TEXT PRIMARY KEY, byte_length INTEGER NOT NULL, media_type TEXT NOT NULL, local_ref TEXT NOT NULL, ref_count INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS documents (id TEXT PRIMARY KEY, kb_id TEXT NOT NULL, blob_hash TEXT NOT NULL, display_name TEXT NOT NULL, format TEXT NOT NULL, active_version_id TEXT, deleted_at TEXT, UNIQUE(kb_id, blob_hash), FOREIGN KEY(kb_id) REFERENCES knowledge_bases(id))",
        "CREATE TABLE IF NOT EXISTS chunks (id TEXT PRIMARY KEY, document_version_id TEXT NOT NULL, ordinal INTEGER NOT NULL, text TEXT NOT NULL, content_hash TEXT NOT NULL, source_span TEXT, asset_ids TEXT, page INTEGER, UNIQUE(document_version_id, ordinal))",
        "CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(text, content='chunks', content_rowid='rowid')",
        "CREATE TABLE IF NOT EXISTS embeddings (chunk_id TEXT NOT NULL, space_id TEXT NOT NULL, vector_blob BLOB NOT NULL, content_hash TEXT NOT NULL, PRIMARY KEY(chunk_id, space_id))",
        "CREATE TABLE IF NOT EXISTS secrets (ref TEXT PRIMARY KEY, ciphertext BLOB, created_at TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'ACTIVE', retired_at TEXT)",
        "CREATE TABLE IF NOT EXISTS announcement_state (announcement_id TEXT NOT NULL, revision INTEGER NOT NULL, read_at TEXT, displayed_at TEXT, dismissed_at TEXT, acknowledged_at TEXT, PRIMARY KEY(announcement_id, revision))",
        "CREATE TABLE IF NOT EXISTS app_prefs (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS announcement_feed_cache (cache_key TEXT PRIMARY KEY, etag TEXT NOT NULL, envelope_json TEXT NOT NULL, payload_json TEXT NOT NULL, feed_version INTEGER NOT NULL, issued_at TEXT NOT NULL, expires_at TEXT NOT NULL, fetched_at TEXT NOT NULL, last_attempt_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS announcement_items (announcement_id TEXT NOT NULL, revision INTEGER NOT NULL, item_json TEXT NOT NULL, withdrawn INTEGER NOT NULL, active INTEGER NOT NULL, PRIMARY KEY(announcement_id, revision))",
        "CREATE TABLE IF NOT EXISTS audit_events (id TEXT PRIMARY KEY, run_id TEXT, created_at TEXT NOT NULL, component TEXT NOT NULL, action TEXT NOT NULL, result TEXT NOT NULL, error_code TEXT, summary TEXT NOT NULL, input_bytes INTEGER NOT NULL DEFAULT 0, output_bytes INTEGER NOT NULL DEFAULT 0, input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0, metadata_json TEXT NOT NULL DEFAULT '{}')",
        "CREATE TABLE IF NOT EXISTS import_jobs (id TEXT PRIMARY KEY, kb_id TEXT NOT NULL, document_id TEXT NOT NULL, display_name TEXT NOT NULL, stage TEXT NOT NULL, has_images INTEGER NOT NULL, error TEXT, updated_at TEXT NOT NULL, vision_consent INTEGER NOT NULL DEFAULT 0, embedding_is_api INTEGER NOT NULL DEFAULT 0, embedding_consent INTEGER NOT NULL DEFAULT 0, vision_binding_json TEXT, batch_id TEXT)",
        "CREATE TABLE IF NOT EXISTS import_batches (id TEXT PRIMARY KEY, kb_id TEXT NOT NULL, generation_id TEXT, kind TEXT NOT NULL, display_name TEXT NOT NULL, state TEXT NOT NULL, total_items INTEGER NOT NULL DEFAULT 0, copied INTEGER NOT NULL DEFAULT 0, processing INTEGER NOT NULL DEFAULT 0, waiting INTEGER NOT NULL DEFAULT 0, failed INTEGER NOT NULL DEFAULT 0, error TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, FOREIGN KEY(kb_id) REFERENCES knowledge_bases(id))",
        "CREATE TABLE IF NOT EXISTS import_items (id TEXT PRIMARY KEY, batch_id TEXT NOT NULL, item_key TEXT NOT NULL, relative_path TEXT NOT NULL, job_id TEXT, kind TEXT NOT NULL, state TEXT NOT NULL, attempt_count INTEGER NOT NULL DEFAULT 0, error TEXT, UNIQUE(batch_id, item_key), FOREIGN KEY(batch_id) REFERENCES import_batches(id))",
        "CREATE TABLE IF NOT EXISTS consent_tickets (id TEXT PRIMARY KEY, kind TEXT NOT NULL CHECK(kind IN('VISION','API_EMBEDDING','QUERY_RETRY')), job_id TEXT, kb_id TEXT NOT NULL, fingerprint TEXT NOT NULL, consumed INTEGER NOT NULL DEFAULT 0 CHECK(consumed IN(0,1)), created_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS capability_probes (id TEXT PRIMARY KEY, provider_id TEXT NOT NULL, model_id TEXT NOT NULL, provider_revision INTEGER NOT NULL, verification TEXT NOT NULL, tools_summary TEXT NOT NULL, images_summary TEXT NOT NULL, source TEXT NOT NULL, probed_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS document_versions (id TEXT PRIMARY KEY, document_id TEXT NOT NULL, parser_fingerprint TEXT NOT NULL, content_hash TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS embedding_operations (token TEXT PRIMARY KEY, kind TEXT NOT NULL CHECK(kind IN('IMPORT','REBUILD','REBIND')), kb_id TEXT NOT NULL, job_id TEXT, document_id TEXT, document_version_id TEXT, space_id TEXT NOT NULL, input_manifest_hash TEXT NOT NULL, binding_fingerprint TEXT NOT NULL, consent_fingerprint TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN('PREPARED','DISPATCHED','CACHE_READY','PUBLISHED','FAILED','CANCELLED','ABORTED','UNKNOWN')), cancel_requested INTEGER NOT NULL DEFAULT 0 CHECK(cancel_requested IN(0,1)), error TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, FOREIGN KEY(kb_id) REFERENCES knowledge_bases(id), FOREIGN KEY(job_id) REFERENCES import_jobs(id), FOREIGN KEY(document_id) REFERENCES documents(id), FOREIGN KEY(document_version_id) REFERENCES document_versions(id))",
        "CREATE TABLE IF NOT EXISTS embedding_query_vectors (space_id TEXT NOT NULL, query_hash TEXT NOT NULL, vector_blob BLOB NOT NULL, dimension INTEGER NOT NULL CHECK(dimension > 0), created_at TEXT NOT NULL, PRIMARY KEY(space_id,query_hash))",
        "CREATE TABLE IF NOT EXISTS index_generations (id TEXT PRIMARY KEY, kb_id TEXT NOT NULL, space_id TEXT NOT NULL, manifest_hash TEXT NOT NULL, state TEXT NOT NULL, vector_count INTEGER NOT NULL, fts_version INTEGER NOT NULL, created_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS generation_members (generation_id TEXT NOT NULL, chunk_id TEXT NOT NULL, space_id TEXT NOT NULL, document_version_id TEXT NOT NULL, PRIMARY KEY(generation_id, chunk_id))",
        "CREATE TABLE IF NOT EXISTS assets (id TEXT PRIMARY KEY, document_id TEXT NOT NULL, document_version_id TEXT, blob_hash TEXT NOT NULL, page INTEGER, section TEXT, kind TEXT NOT NULL, surrounding_text_hash TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS vision_results (cache_key TEXT PRIMARY KEY, asset_hash TEXT NOT NULL, context_hash TEXT NOT NULL, model_fingerprint TEXT NOT NULL, prompt_version TEXT NOT NULL, schema_version TEXT NOT NULL, status TEXT NOT NULL, ocr_text TEXT NOT NULL, description TEXT NOT NULL, table_markdown TEXT NOT NULL DEFAULT '', result_type TEXT NOT NULL DEFAULT '', processed_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS skill_packages (package_hash TEXT PRIMARY KEY, id TEXT NOT NULL, name TEXT NOT NULL, version TEXT NOT NULL, license_id TEXT NOT NULL, classification TEXT NOT NULL, manifest_json TEXT, skill_markdown TEXT, reasons TEXT NOT NULL, created_at TEXT NOT NULL, package_bytes BLOB, source_hash TEXT)",
        "CREATE TABLE IF NOT EXISTS skill_installs (install_id TEXT PRIMARY KEY, package_hash TEXT NOT NULL, enabled INTEGER NOT NULL, created_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS permission_grants (grant_id TEXT PRIMARY KEY, install_id TEXT NOT NULL, package_hash TEXT NOT NULL, capabilities TEXT NOT NULL, revision INTEGER NOT NULL, revoked INTEGER NOT NULL, scopes_json TEXT)",
        "CREATE TABLE IF NOT EXISTS skill_invocations (invocation_id TEXT PRIMARY KEY, run_id TEXT, package_hash TEXT, grant_revision INTEGER, state TEXT NOT NULL, created_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS runs (run_id TEXT PRIMARY KEY, snapshot_id TEXT NOT NULL, conversation_id TEXT NOT NULL, state TEXT NOT NULL, budget_json TEXT NOT NULL, stop_reason TEXT, error_code TEXT, model_rounds INTEGER NOT NULL DEFAULT 0, tool_calls INTEGER NOT NULL DEFAULT 0, input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0, started_at TEXT, finished_at TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, retry_acknowledged_at TEXT, FOREIGN KEY(snapshot_id) REFERENCES agent_snapshots(id), FOREIGN KEY(conversation_id) REFERENCES conversations(id))",
        "CREATE TABLE IF NOT EXISTS tool_invocations (invocation_id TEXT PRIMARY KEY, run_id TEXT NOT NULL, call_id TEXT NOT NULL, name TEXT NOT NULL, arguments_json TEXT NOT NULL, permission_decision TEXT NOT NULL, state TEXT NOT NULL, result_json TEXT, error_code TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(run_id, call_id), FOREIGN KEY(run_id) REFERENCES runs(run_id))",
        "CREATE INDEX IF NOT EXISTS idx_prompt_revisions_agent_created ON prompt_revisions(agent_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_messages_conversation_created ON messages(conversation_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_runs_state ON runs(state)",
        "CREATE INDEX IF NOT EXISTS idx_tool_invocations_run ON tool_invocations(run_id, created_at)",
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_embedding_operations_active_kb ON embedding_operations(kb_id) WHERE state IN('PREPARED','DISPATCHED','CACHE_READY')",
        "CREATE INDEX IF NOT EXISTS idx_embedding_operations_job ON embedding_operations(job_id)",
    )

    private val columns = listOf(
        // Columns introduced by the v1-v8 migrations.  Keep these checks explicit so a partially
        // applied historical migration is repaired or reported instead of being hidden by a
        // best-effort ALTER.
        Column("chunks", "source_span", "TEXT"),
        Column("chunks", "asset_ids", "TEXT"),
        Column("chunks", "page", "INTEGER"),
        Column("import_jobs", "vision_consent", "INTEGER NOT NULL DEFAULT 0"),
        Column("import_jobs", "embedding_is_api", "INTEGER NOT NULL DEFAULT 0"),
        Column("import_jobs", "embedding_consent", "INTEGER NOT NULL DEFAULT 0"),
        Column("assets", "document_version_id", "TEXT"),
        Column("vision_results", "table_markdown", "TEXT NOT NULL DEFAULT ''"),
        Column("vision_results", "result_type", "TEXT NOT NULL DEFAULT ''"),
        Column("skill_packages", "package_bytes", "BLOB"),
        Column("skill_packages", "source_hash", "TEXT"),
        Column("permission_grants", "scopes_json", "TEXT"),
        Column("model_profiles", "parameters_json", "TEXT NOT NULL DEFAULT '{}'"),
        Column("agent_profiles", "parameter_overrides_json", "TEXT NOT NULL DEFAULT '{}'"),
        Column("agent_profiles", "context_policy_json", "TEXT NOT NULL DEFAULT '{}'"),
        Column("agent_profiles", "permission_settings_json", "TEXT NOT NULL DEFAULT '{}'"),
        Column("agent_snapshots", "provider_id", "TEXT NOT NULL DEFAULT ''"),
        Column("agent_snapshots", "chat_model_revision", "INTEGER NOT NULL DEFAULT 0"),
        Column("agent_snapshots", "vision_model_id", "TEXT"),
        Column("agent_snapshots", "vision_model_revision", "INTEGER"),
        Column("agent_snapshots", "embedding_model_id", "TEXT"),
        Column("agent_snapshots", "embedding_model_revision", "INTEGER"),
        Column("agent_snapshots", "reranker_model_id", "TEXT"),
        Column("agent_snapshots", "reranker_model_revision", "INTEGER"),
        Column("agent_snapshots", "parameter_overrides_json", "TEXT NOT NULL DEFAULT '{}'"),
        Column("agent_snapshots", "context_policy_json", "TEXT NOT NULL DEFAULT '{}'"),
        Column("agent_snapshots", "permission_settings_json", "TEXT NOT NULL DEFAULT '{}'"),
        Column("agent_snapshots", "binding_manifest_json", "TEXT NOT NULL DEFAULT '{}'"),
        Column("agent_snapshots", "expanded_json", "TEXT NOT NULL DEFAULT '{}'"),
        Column("messages", "parts_json", "TEXT NOT NULL DEFAULT '[]'"),
        Column("messages", "metadata_json", "TEXT NOT NULL DEFAULT '{}'"),
        Column("audit_events", "input_bytes", "INTEGER NOT NULL DEFAULT 0"),
        Column("audit_events", "output_bytes", "INTEGER NOT NULL DEFAULT 0"),
        Column("audit_events", "input_tokens", "INTEGER NOT NULL DEFAULT 0"),
        Column("audit_events", "output_tokens", "INTEGER NOT NULL DEFAULT 0"),
        Column("audit_events", "metadata_json", "TEXT NOT NULL DEFAULT '{}'"),
        Column("import_jobs", "vision_binding_json", "TEXT"),
        Column("import_jobs", "batch_id", "TEXT"),
        Column("runs", "retry_acknowledged_at", "TEXT"),
        Column("model_profiles", "endpoint_json", "TEXT NOT NULL DEFAULT '{}'"),
        Column("secrets", "status", "TEXT NOT NULL DEFAULT 'ACTIVE'"),
        Column("secrets", "retired_at", "TEXT"),
    )

    fun apply(connection: SqlConnection) {
        connection.transaction {
            // A malformed pre-existing table must fail here; never replace it or clear data.
            connection.execute(statements.first())
            val current = readVersion(connection)
            if (current > VERSION) unsupported(current)
            statements.drop(1).forEach { sql -> connection.execute(sql) }
            columns.forEach { column -> ensureColumn(connection, column) }
            backfillV11(connection)
            validateRequiredSchema(connection)
            connection.execute("DELETE FROM schema_version")
            connection.execute("INSERT INTO schema_version(version) VALUES (?)", listOf(VERSION))
        }
        // Index repair is a data migration owned by KnowledgeRepository. It is deliberately not
        // wrapped in runCatching: callers must see a failed repair and can retry explicitly.
        KnowledgeRepository(connection, runtime.mobileagent.knowledge.MemoryBlobSink()).repairIndexes()
    }

    private fun readVersion(connection: SqlConnection): Long {
        val rows = connection.query("SELECT version FROM schema_version")
        if (rows.size > 1) invalid("schema_version has multiple rows")
        if (rows.isEmpty()) return 0L
        val raw = rows.single().columns["version"]
        // SQLite's INTEGER affinity still permits REAL/TEXT values.  Accept only the integer
        // runtime types returned by the adapters; coercing 8.5 or "8" would hide a damaged DB.
        val version = when (raw) {
            is Byte, is Short, is Int, is Long -> (raw as Number).toLong()
            else -> invalid("schema_version.version is not an integer")
        }
        if (version < 0L) invalid("schema_version.version must not be negative")
        return version
    }

    private fun ensureColumn(connection: SqlConnection, column: Column) {
        val exists = connection.query("PRAGMA table_info(${column.table})")
            .any { it.string("name") == column.name }
        if (!exists) {
            connection.execute("ALTER TABLE ${column.table} ADD COLUMN ${column.name} ${column.definition}")
        }
    }

    private fun validateRequiredSchema(connection: SqlConnection) {
        REQUIRED_TABLES.forEach { table ->
            val exists = connection.query(
                "SELECT name FROM sqlite_master WHERE type IN ('table','view') AND name = ?",
                listOf(table),
            ).isNotEmpty()
            if (!exists) invalid("Required table $table is missing after migration")
        }
        REQUIRED_COLUMNS.forEach { (table, column) ->
            if (connection.query("PRAGMA table_info($table)").none { it.string("name") == column }) {
                invalid("Required column $table.$column is missing after migration")
            }
        }
    }

    private fun unsupported(current: Long): Nothing = throw AppError(
        code = ErrorCode.SCHEMA_UNSUPPORTED,
        userMessage = "Database schema version $current is newer than supported version $VERSION",
        retryClass = RetryClass.USER_ACTION,
        stage = "migration",
        operationId = "database-migration",
        sanitizedDetails = "schemaVersion=$current",
    ).asException()

    private fun invalid(message: String): Nothing = throw AppError(
        code = ErrorCode.INVALID_CONFIG,
        userMessage = "Database schema is invalid: $message",
        retryClass = RetryClass.USER_ACTION,
        stage = "migration",
        operationId = "database-migration",
        sanitizedDetails = message,
    ).asException()

    private data class Column(val table: String, val name: String, val definition: String)

    private val REQUIRED_TABLES = setOf(
        "schema_version", "provider_profiles", "model_profiles", "agent_profiles", "prompt_revisions",
        "conversations", "agent_snapshots", "messages", "message_parts", "knowledge_bases", "blobs",
        "embedding_query_attempts",
        "chunks_fts", "announcement_state", "announcement_feed_cache", "announcement_items",
        "documents", "chunks", "embeddings", "secrets", "app_prefs", "audit_events", "import_jobs",
        "import_batches", "import_items", "consent_tickets", "capability_probes",
        "document_versions", "embedding_operations", "embedding_query_vectors", "index_generations", "generation_members", "assets", "vision_results",
        "skill_packages", "skill_installs", "permission_grants", "skill_invocations", "runs", "tool_invocations",
    )

    private val REQUIRED_COLUMNS = listOf(
        "model_profiles" to "parameters_json",
        "agent_profiles" to "parameter_overrides_json",
        "agent_snapshots" to "expanded_json",
        "messages" to "parts_json",
        "audit_events" to "metadata_json",
        "import_jobs" to "vision_binding_json",
        "runs" to "retry_acknowledged_at",
        "embedding_query_attempts" to "kb_id",
        "embedding_query_attempts" to "space_id",
        "embedding_query_attempts" to "query_hash",
        "embedding_query_attempts" to "retry_authorized",
        "embedding_query_attempts" to "error",
        "embedding_query_attempts" to "updated_at",
        "embedding_operations" to "token",
        "embedding_operations" to "kind",
        "embedding_operations" to "kb_id",
        "embedding_operations" to "job_id",
        "embedding_operations" to "document_id",
        "embedding_operations" to "document_version_id",
        "embedding_operations" to "space_id",
        "embedding_operations" to "input_manifest_hash",
        "embedding_operations" to "binding_fingerprint",
        "embedding_operations" to "consent_fingerprint",
        "embedding_operations" to "state",
        "embedding_operations" to "cancel_requested",
        "embedding_operations" to "error",
        "embedding_operations" to "created_at",
        "embedding_operations" to "updated_at",
        "embedding_query_vectors" to "space_id",
        "embedding_query_vectors" to "query_hash",
        "embedding_query_vectors" to "vector_blob",
        "embedding_query_vectors" to "dimension",
        "embedding_query_vectors" to "created_at",
        "model_profiles" to "endpoint_json",
        "secrets" to "status",
        "import_jobs" to "batch_id",
    )

    private fun backfillV11(connection: SqlConnection) {
        connection.query("SELECT id, role, capabilities, endpoint_json FROM model_profiles").forEach { row ->
            val stored = row.string("endpoint_json")
            if (stored.isNotBlank() && stored != "{}") return@forEach
            val role = runCatching { runtime.mobileagent.domain.ModelRole.valueOf(row.string("role")) }
                .getOrDefault(runtime.mobileagent.domain.ModelRole.CHAT)
            val caps = runCatching {
                kotlinx.serialization.json.Json.decodeFromString<List<String>>(row.string("capabilities").ifBlank { "[]" })
            }.getOrDefault(emptyList()).toSet()
            val endpoint = runtime.mobileagent.domain.ModelEndpoint.fromLegacy(role, caps)
            val encoded = kotlinx.serialization.json.Json.encodeToString(
                runtime.mobileagent.domain.ModelEndpoint.serializer(),
                endpoint,
            )
            connection.execute("UPDATE model_profiles SET endpoint_json = ? WHERE id = ?", listOf(encoded, row.string("id")))
        }
    }
}
