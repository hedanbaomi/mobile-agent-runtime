// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

object Migrations {
    const val VERSION = 7

    private val statements = listOf(
        "CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL PRIMARY KEY)",
        "CREATE TABLE IF NOT EXISTS provider_profiles (id TEXT PRIMARY KEY, name TEXT NOT NULL, api_format TEXT NOT NULL, base_url TEXT NOT NULL, header_secret_refs TEXT NOT NULL, non_secret_headers TEXT NOT NULL, secret_ref TEXT NOT NULL, revision INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS model_profiles (id TEXT PRIMARY KEY, provider_id TEXT NOT NULL, role TEXT NOT NULL, model_id TEXT NOT NULL, capabilities TEXT NOT NULL, parameter_schema_json TEXT NOT NULL, context_limit INTEGER NOT NULL, output_limit INTEGER NOT NULL, revision INTEGER NOT NULL, FOREIGN KEY(provider_id) REFERENCES provider_profiles(id))",
        "CREATE TABLE IF NOT EXISTS agent_profiles (id TEXT PRIMARY KEY, name TEXT NOT NULL, prompt_revision_id TEXT NOT NULL, chat_profile_id TEXT NOT NULL, vision_profile_id TEXT, embedding_profile_id TEXT, reranker_profile_id TEXT, knowledge_base_ids TEXT NOT NULL, skill_ids TEXT NOT NULL, retrieval_mode TEXT NOT NULL, revision INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS prompt_revisions (id TEXT PRIMARY KEY, agent_id TEXT NOT NULL, parent_revision_id TEXT, template TEXT NOT NULL, allowed_variables TEXT NOT NULL, created_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS conversations (id TEXT PRIMARY KEY, snapshot_id TEXT NOT NULL, title TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS agent_snapshots (id TEXT PRIMARY KEY, schema_version INTEGER NOT NULL, agent_id TEXT NOT NULL, prompt_revision_id TEXT NOT NULL, chat_model_id TEXT NOT NULL, provider_revision INTEGER NOT NULL, knowledge_base_ids TEXT NOT NULL, skill_ids TEXT NOT NULL, created_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, parent_message_id TEXT, role TEXT NOT NULL, text TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL, FOREIGN KEY(conversation_id) REFERENCES conversations(id))",
        "CREATE TABLE IF NOT EXISTS knowledge_bases (id TEXT PRIMARY KEY, name TEXT NOT NULL, active_generation_id TEXT, embedding_space_id TEXT, created_at TEXT NOT NULL, deleted_at TEXT)",
        "CREATE TABLE IF NOT EXISTS blobs (hash TEXT PRIMARY KEY, byte_length INTEGER NOT NULL, media_type TEXT NOT NULL, local_ref TEXT NOT NULL, ref_count INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS documents (id TEXT PRIMARY KEY, kb_id TEXT NOT NULL, blob_hash TEXT NOT NULL, display_name TEXT NOT NULL, format TEXT NOT NULL, active_version_id TEXT, deleted_at TEXT, UNIQUE(kb_id, blob_hash), FOREIGN KEY(kb_id) REFERENCES knowledge_bases(id))",
        "CREATE TABLE IF NOT EXISTS chunks (id TEXT PRIMARY KEY, document_version_id TEXT NOT NULL, ordinal INTEGER NOT NULL, text TEXT NOT NULL, content_hash TEXT NOT NULL, source_span TEXT, asset_ids TEXT, page INTEGER, UNIQUE(document_version_id, ordinal))",
        "CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(text, content='chunks', content_rowid='rowid')",
        "CREATE TABLE IF NOT EXISTS embeddings (chunk_id TEXT NOT NULL, space_id TEXT NOT NULL, vector_blob BLOB NOT NULL, content_hash TEXT NOT NULL, PRIMARY KEY(chunk_id, space_id))",
        "CREATE TABLE IF NOT EXISTS secrets (ref TEXT PRIMARY KEY, ciphertext BLOB NOT NULL, created_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS announcement_state (announcement_id TEXT NOT NULL, revision INTEGER NOT NULL, read_at TEXT, displayed_at TEXT, dismissed_at TEXT, acknowledged_at TEXT, PRIMARY KEY(announcement_id, revision))",
        "CREATE TABLE IF NOT EXISTS app_prefs (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS announcement_feed_cache (cache_key TEXT PRIMARY KEY, etag TEXT NOT NULL, envelope_json TEXT NOT NULL, payload_json TEXT NOT NULL, feed_version INTEGER NOT NULL, issued_at TEXT NOT NULL, expires_at TEXT NOT NULL, fetched_at TEXT NOT NULL, last_attempt_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS announcement_items (announcement_id TEXT NOT NULL, revision INTEGER NOT NULL, item_json TEXT NOT NULL, withdrawn INTEGER NOT NULL, active INTEGER NOT NULL, PRIMARY KEY(announcement_id, revision))",
        "CREATE TABLE IF NOT EXISTS audit_events (id TEXT PRIMARY KEY, run_id TEXT, created_at TEXT NOT NULL, component TEXT NOT NULL, action TEXT NOT NULL, result TEXT NOT NULL, error_code TEXT, summary TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS import_jobs (id TEXT PRIMARY KEY, kb_id TEXT NOT NULL, document_id TEXT NOT NULL, display_name TEXT NOT NULL, stage TEXT NOT NULL, has_images INTEGER NOT NULL, error TEXT, updated_at TEXT NOT NULL, vision_consent INTEGER NOT NULL DEFAULT 0, embedding_is_api INTEGER NOT NULL DEFAULT 0, embedding_consent INTEGER NOT NULL DEFAULT 0, vision_binding_json TEXT)",
        "CREATE TABLE IF NOT EXISTS document_versions (id TEXT PRIMARY KEY, document_id TEXT NOT NULL, parser_fingerprint TEXT NOT NULL, content_hash TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS index_generations (id TEXT PRIMARY KEY, kb_id TEXT NOT NULL, space_id TEXT NOT NULL, manifest_hash TEXT NOT NULL, state TEXT NOT NULL, vector_count INTEGER NOT NULL, fts_version INTEGER NOT NULL, created_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS generation_members (generation_id TEXT NOT NULL, chunk_id TEXT NOT NULL, space_id TEXT NOT NULL, document_version_id TEXT NOT NULL, PRIMARY KEY(generation_id, chunk_id))",
        "CREATE TABLE IF NOT EXISTS assets (id TEXT PRIMARY KEY, document_id TEXT NOT NULL, document_version_id TEXT, blob_hash TEXT NOT NULL, page INTEGER, section TEXT, kind TEXT NOT NULL, surrounding_text_hash TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS vision_results (cache_key TEXT PRIMARY KEY, asset_hash TEXT NOT NULL, context_hash TEXT NOT NULL, model_fingerprint TEXT NOT NULL, prompt_version TEXT NOT NULL, schema_version TEXT NOT NULL, status TEXT NOT NULL, ocr_text TEXT NOT NULL, description TEXT NOT NULL, table_markdown TEXT NOT NULL DEFAULT '', result_type TEXT NOT NULL DEFAULT '', processed_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS skill_packages (package_hash TEXT PRIMARY KEY, id TEXT NOT NULL, name TEXT NOT NULL, version TEXT NOT NULL, license_id TEXT NOT NULL, classification TEXT NOT NULL, manifest_json TEXT, skill_markdown TEXT, reasons TEXT NOT NULL, created_at TEXT NOT NULL, package_bytes BLOB, source_hash TEXT)",
        "CREATE TABLE IF NOT EXISTS skill_installs (install_id TEXT PRIMARY KEY, package_hash TEXT NOT NULL, enabled INTEGER NOT NULL, created_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS permission_grants (grant_id TEXT PRIMARY KEY, install_id TEXT NOT NULL, package_hash TEXT NOT NULL, capabilities TEXT NOT NULL, revision INTEGER NOT NULL, revoked INTEGER NOT NULL, scopes_json TEXT)",
        "CREATE TABLE IF NOT EXISTS skill_invocations (invocation_id TEXT PRIMARY KEY, run_id TEXT, package_hash TEXT, grant_revision INTEGER, state TEXT NOT NULL, created_at TEXT NOT NULL)",
    )

    private val upgrades = listOf(
        "ALTER TABLE chunks ADD COLUMN source_span TEXT",
        "ALTER TABLE chunks ADD COLUMN asset_ids TEXT",
        "ALTER TABLE chunks ADD COLUMN page INTEGER",
        "ALTER TABLE import_jobs ADD COLUMN vision_consent INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE import_jobs ADD COLUMN embedding_is_api INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE import_jobs ADD COLUMN embedding_consent INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE assets ADD COLUMN document_version_id TEXT",
        "ALTER TABLE vision_results ADD COLUMN table_markdown TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE vision_results ADD COLUMN result_type TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE skill_packages ADD COLUMN package_bytes BLOB",
        "ALTER TABLE skill_packages ADD COLUMN source_hash TEXT",
        "ALTER TABLE permission_grants ADD COLUMN scopes_json TEXT",
        "ALTER TABLE import_jobs ADD COLUMN vision_binding_json TEXT",
    )

    fun apply(connection: SqlConnection) {
        connection.transaction {
            statements.forEach { connection.execute(it) }
            upgrades.forEach { sql -> runCatching { connection.execute(sql) } }
            connection.execute("DELETE FROM schema_version")
            connection.execute("INSERT INTO schema_version(version) VALUES (?)", listOf(VERSION))
        }
        KnowledgeRepository(connection, runtime.mobileagent.knowledge.MemoryBlobSink()).repairIndexes()
    }
}
