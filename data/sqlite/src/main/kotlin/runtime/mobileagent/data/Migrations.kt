// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

object Migrations {
    const val VERSION = 3

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
        "CREATE TABLE IF NOT EXISTS chunks (id TEXT PRIMARY KEY, document_version_id TEXT NOT NULL, ordinal INTEGER NOT NULL, text TEXT NOT NULL, content_hash TEXT NOT NULL, UNIQUE(document_version_id, ordinal))",
        "CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(text, content='chunks', content_rowid='rowid')",
        "CREATE TABLE IF NOT EXISTS embeddings (chunk_id TEXT NOT NULL, space_id TEXT NOT NULL, vector_blob BLOB NOT NULL, content_hash TEXT NOT NULL, PRIMARY KEY(chunk_id, space_id))",
        "CREATE TABLE IF NOT EXISTS secrets (ref TEXT PRIMARY KEY, ciphertext BLOB NOT NULL, created_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS announcement_state (announcement_id TEXT NOT NULL, revision INTEGER NOT NULL, read_at TEXT, displayed_at TEXT, dismissed_at TEXT, acknowledged_at TEXT, PRIMARY KEY(announcement_id, revision))",
        "CREATE TABLE IF NOT EXISTS app_prefs (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS announcement_feed_cache (cache_key TEXT PRIMARY KEY, etag TEXT NOT NULL, envelope_json TEXT NOT NULL, payload_json TEXT NOT NULL, feed_version INTEGER NOT NULL, issued_at TEXT NOT NULL, expires_at TEXT NOT NULL, fetched_at TEXT NOT NULL, last_attempt_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS announcement_items (announcement_id TEXT NOT NULL, revision INTEGER NOT NULL, item_json TEXT NOT NULL, withdrawn INTEGER NOT NULL, active INTEGER NOT NULL, PRIMARY KEY(announcement_id, revision))",
        "CREATE TABLE IF NOT EXISTS audit_events (id TEXT PRIMARY KEY, run_id TEXT, created_at TEXT NOT NULL, component TEXT NOT NULL, action TEXT NOT NULL, result TEXT NOT NULL, error_code TEXT, summary TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS import_jobs (id TEXT PRIMARY KEY, kb_id TEXT NOT NULL, document_id TEXT NOT NULL, display_name TEXT NOT NULL, stage TEXT NOT NULL, has_images INTEGER NOT NULL, error TEXT, updated_at TEXT NOT NULL)",
    )

    fun apply(connection: SqlConnection) {
        connection.transaction {
            statements.forEach { connection.execute(it) }
            connection.execute("DELETE FROM schema_version")
            connection.execute("INSERT INTO schema_version(version) VALUES (?)", listOf(VERSION))
        }
    }
}
