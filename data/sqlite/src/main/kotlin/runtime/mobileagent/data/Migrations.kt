// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceScope

/**
 * SQLite schema owner for the application database.
 *
 * The previous implementation ran every ALTER statement and swallowed every exception. That
 * made a typo, a damaged schema, and an already-present column indistinguishable. v8 through v10 keep
 * existing data and only execute an ALTER after inspecting the live table. Any other DDL or
 * version error escapes the transaction, leaving schema_version at its previous value.
 */
object Migrations {
    // v13 adds durable grant owners/consumption markers and changes the canonical
    // uniqueness key. SQLite cannot alter a table-level UNIQUE constraint in place,
    // so old v12 tables are preserved under a legacy name and copied transactionally.
    // v14 persists workspace scope and the explicit high-risk full-device grant.
    const val VERSION = 14

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
        "CREATE TABLE IF NOT EXISTS permission_grants (grant_id TEXT PRIMARY KEY, install_id TEXT NOT NULL, package_hash TEXT NOT NULL, capabilities TEXT NOT NULL, revision INTEGER NOT NULL, revoked INTEGER NOT NULL, scopes_json TEXT, lifetime TEXT NOT NULL DEFAULT 'PERSISTENT' CHECK(lifetime IN('ONCE','TASK','SESSION','PERSISTENT')), policy_version INTEGER NOT NULL DEFAULT 0 CHECK(policy_version >= 0), created_at TEXT NOT NULL DEFAULT '', expires_at TEXT, revoked_at TEXT)",
        "CREATE TABLE IF NOT EXISTS authority_policy (id INTEGER NOT NULL PRIMARY KEY CHECK(id = 1), selected_authority TEXT NOT NULL CHECK(selected_authority IN('NONE','SHIZUKU','WIRED_ADB')), dangerous_mode TEXT NOT NULL CHECK(dangerous_mode IN('DISABLED','ENABLED_CONFIRM_HIGH_RISK','ENABLED_AUTONOMOUS')), policy_version INTEGER NOT NULL DEFAULT 0 CHECK(policy_version >= 0), updated_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS authority_preferences (authority TEXT NOT NULL PRIMARY KEY CHECK(authority IN('NONE','SHIZUKU','WIRED_ADB')), user_intent_enabled INTEGER NOT NULL DEFAULT 0 CHECK(user_intent_enabled IN(0,1)), explicitly_configured INTEGER NOT NULL DEFAULT 0 CHECK(explicitly_configured IN(0,1)), updated_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS workspaces (id TEXT PRIMARY KEY, display_name TEXT NOT NULL, backend_type TEXT NOT NULL CHECK(backend_type IN('INTERNAL','SAF_TREE','PRIVILEGED')), root_reference TEXT NOT NULL, readable INTEGER NOT NULL CHECK(readable IN(0,1)), writable INTEGER NOT NULL CHECK(writable IN(0,1)), quota_bytes INTEGER, max_file_bytes INTEGER NOT NULL, enabled INTEGER NOT NULL CHECK(enabled IN(0,1)), revision INTEGER NOT NULL DEFAULT 0 CHECK(revision >= 0), created_at TEXT NOT NULL, updated_at TEXT NOT NULL, scope TEXT NOT NULL DEFAULT 'SELECTED_DIRECTORY' CHECK(scope IN('SELECTED_DIRECTORY','FULL_DEVICE_FILES')))",
        // This row may be created immediately before a provider opens its
        // device-root handle. The container materializes the corresponding
        // workspace only after that typed operation succeeds, so the grant
        // table deliberately has no eager FK; startup reconciliation treats
        // orphaned rows as unavailable rather than widening access.
        "CREATE TABLE IF NOT EXISTS full_device_files_grants (workspace_id TEXT PRIMARY KEY, revision INTEGER NOT NULL CHECK(revision > 0), confirmed_at_epoch_ms INTEGER NOT NULL CHECK(confirmed_at_epoch_ms > 0), created_at TEXT NOT NULL, updated_at TEXT NOT NULL, revoked_at TEXT)",
        "CREATE TABLE IF NOT EXISTS capability_grants (grant_id TEXT PRIMARY KEY, agent_id TEXT NOT NULL, skill_install_id TEXT, package_hash TEXT, capability TEXT NOT NULL, workspace_id TEXT, path_scope TEXT, lifetime TEXT NOT NULL CHECK(lifetime IN('ONCE','TASK','SESSION','PERSISTENT')), policy_version INTEGER NOT NULL CHECK(policy_version >= 0), created_at TEXT NOT NULL, expires_at TEXT, revoked_at TEXT, revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0), task_id TEXT, session_id TEXT, consumed_at TEXT, CHECK(consumed_at IS NULL OR lifetime = 'ONCE'), CHECK((lifetime = 'ONCE' AND (task_id IS NULL OR trim(task_id) <> '') AND (session_id IS NULL OR trim(session_id) <> '')) OR (lifetime = 'TASK' AND task_id IS NOT NULL AND trim(task_id) <> '') OR (lifetime = 'SESSION' AND session_id IS NOT NULL AND trim(session_id) <> '') OR (lifetime = 'PERSISTENT' AND task_id IS NULL AND session_id IS NULL)), UNIQUE(agent_id, skill_install_id, package_hash, capability, workspace_id, path_scope, task_id, session_id))",
        "CREATE TRIGGER IF NOT EXISTS capability_grants_lifecycle_insert BEFORE INSERT ON capability_grants FOR EACH ROW WHEN NOT ((NEW.consumed_at IS NULL OR NEW.lifetime = 'ONCE') AND ((NEW.lifetime = 'ONCE' AND (NEW.task_id IS NULL OR trim(NEW.task_id) <> '') AND (NEW.session_id IS NULL OR trim(NEW.session_id) <> '')) OR (NEW.lifetime = 'TASK' AND NEW.task_id IS NOT NULL AND trim(NEW.task_id) <> '') OR (NEW.lifetime = 'SESSION' AND NEW.session_id IS NOT NULL AND trim(NEW.session_id) <> '') OR (NEW.lifetime = 'PERSISTENT' AND NEW.task_id IS NULL AND NEW.session_id IS NULL))) BEGIN SELECT RAISE(ABORT, 'invalid capability grant lifecycle'); END",
        "CREATE TRIGGER IF NOT EXISTS capability_grants_lifecycle_update BEFORE UPDATE OF lifetime,task_id,session_id,consumed_at ON capability_grants FOR EACH ROW WHEN NOT ((NEW.consumed_at IS NULL OR NEW.lifetime = 'ONCE') AND ((NEW.lifetime = 'ONCE' AND (NEW.task_id IS NULL OR trim(NEW.task_id) <> '') AND (NEW.session_id IS NULL OR trim(NEW.session_id) <> '')) OR (NEW.lifetime = 'TASK' AND NEW.task_id IS NOT NULL AND trim(NEW.task_id) <> '') OR (NEW.lifetime = 'SESSION' AND NEW.session_id IS NOT NULL AND trim(NEW.session_id) <> '') OR (NEW.lifetime = 'PERSISTENT' AND NEW.task_id IS NULL AND NEW.session_id IS NULL))) BEGIN SELECT RAISE(ABORT, 'invalid capability grant lifecycle'); END",
        "CREATE VIEW IF NOT EXISTS workspace_acl AS SELECT grant_id, agent_id, skill_install_id, package_hash, capability, workspace_id, path_scope, lifetime, policy_version, created_at, expires_at, revoked_at, revision, task_id, session_id, consumed_at FROM capability_grants WHERE workspace_id IS NOT NULL AND revoked_at IS NULL AND consumed_at IS NULL",
        "CREATE TABLE IF NOT EXISTS snapshot_grant_bindings (snapshot_id TEXT NOT NULL, grant_id TEXT NOT NULL, capability TEXT NOT NULL, workspace_id TEXT, path_scope TEXT, policy_version INTEGER NOT NULL CHECK(policy_version >= 0), bound_at TEXT NOT NULL, PRIMARY KEY(snapshot_id, grant_id, capability))",
        "CREATE TABLE IF NOT EXISTS saf_workspace_grants (workspace_id TEXT PRIMARY KEY, uri_reference TEXT NOT NULL, read_granted INTEGER NOT NULL CHECK(read_granted IN(0,1)), write_granted INTEGER NOT NULL CHECK(write_granted IN(0,1)), persisted_flags INTEGER NOT NULL DEFAULT 0 CHECK(persisted_flags >= 0), status TEXT NOT NULL CHECK(status IN('ACTIVE','GRANT_LOST','REVOKED')), created_at TEXT NOT NULL, lost_at TEXT, updated_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS desktop_identity (id INTEGER NOT NULL PRIMARY KEY CHECK(id = 1), desktop_id TEXT NOT NULL, app_instance_id TEXT NOT NULL, updated_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS desktop_trust (desktop_id TEXT PRIMARY KEY, app_instance_id TEXT NOT NULL, secret_ref TEXT NOT NULL, status TEXT NOT NULL CHECK(status IN('TRUSTED','REAUTH_REQUIRED','FORGOTTEN')), created_at TEXT NOT NULL, last_seen_at TEXT, forgotten_at TEXT, revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0))",
        "CREATE TABLE IF NOT EXISTS skill_memory_spaces (space_id TEXT PRIMARY KEY, install_id TEXT NOT NULL, package_hash TEXT NOT NULL, quota_bytes INTEGER NOT NULL CHECK(quota_bytes > 0), max_entries INTEGER NOT NULL CHECK(max_entries > 0), version INTEGER NOT NULL DEFAULT 1 CHECK(version > 0), created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(install_id, package_hash))",
        "CREATE TABLE IF NOT EXISTS skill_memory_entries (entry_id TEXT PRIMARY KEY, space_id TEXT NOT NULL, path TEXT NOT NULL, content_hash TEXT NOT NULL, storage_ref TEXT NOT NULL, byte_length INTEGER NOT NULL CHECK(byte_length >= 0), version INTEGER NOT NULL CHECK(version > 0), created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(space_id, path), FOREIGN KEY(space_id) REFERENCES skill_memory_spaces(space_id))",
        "CREATE TABLE IF NOT EXISTS approval_records (approval_id TEXT PRIMARY KEY, request_id TEXT NOT NULL, call_id TEXT NOT NULL, agent_id TEXT NOT NULL, skill_id TEXT, command_hash TEXT, cwd_hash TEXT, selected_authority TEXT NOT NULL CHECK(selected_authority IN('NONE','SHIZUKU','WIRED_ADB')), dangerous_mode TEXT NOT NULL CHECK(dangerous_mode IN('DISABLED','ENABLED_CONFIRM_HIGH_RISK','ENABLED_AUTONOMOUS')), tool_schema_version INTEGER NOT NULL CHECK(tool_schema_version > 0), policy_version INTEGER NOT NULL CHECK(policy_version >= 0), config_snapshot_hash TEXT NOT NULL, decision TEXT NOT NULL CHECK(decision IN('APPROVED','DENIED','EXPIRED','CONSUMED')), created_at TEXT NOT NULL, expires_at TEXT, consumed_at TEXT, UNIQUE(request_id))",
        "CREATE TABLE IF NOT EXISTS tool_audit_details (audit_id TEXT PRIMARY KEY, request_id TEXT NOT NULL, agent_id TEXT NOT NULL, skill_id TEXT, capability TEXT NOT NULL, workspace_id TEXT, relative_path_sha256 TEXT, authority TEXT NOT NULL CHECK(authority IN('NONE','SHIZUKU','WIRED_ADB')), approval_id TEXT, dangerous_mode TEXT, policy_version INTEGER NOT NULL DEFAULT 0 CHECK(policy_version >= 0), cwd_sha256 TEXT, command_sha256 TEXT, exit_code INTEGER, timed_out INTEGER NOT NULL DEFAULT 0 CHECK(timed_out IN(0,1)), cancelled INTEGER NOT NULL DEFAULT 0 CHECK(cancelled IN(0,1)), stdout_truncated INTEGER NOT NULL DEFAULT 0 CHECK(stdout_truncated IN(0,1)), stderr_truncated INTEGER NOT NULL DEFAULT 0 CHECK(stderr_truncated IN(0,1)), stdout_bytes INTEGER NOT NULL DEFAULT 0 CHECK(stdout_bytes >= 0), stderr_bytes INTEGER NOT NULL DEFAULT 0 CHECK(stderr_bytes >= 0), duration_ms INTEGER NOT NULL DEFAULT 0 CHECK(duration_ms >= 0), result TEXT NOT NULL, created_at TEXT NOT NULL, FOREIGN KEY(audit_id) REFERENCES audit_events(id))",
        "CREATE TABLE IF NOT EXISTS skill_invocations (invocation_id TEXT PRIMARY KEY, run_id TEXT, package_hash TEXT, grant_revision INTEGER, state TEXT NOT NULL, created_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS runs (run_id TEXT PRIMARY KEY, snapshot_id TEXT NOT NULL, conversation_id TEXT NOT NULL, state TEXT NOT NULL, budget_json TEXT NOT NULL, stop_reason TEXT, error_code TEXT, model_rounds INTEGER NOT NULL DEFAULT 0, tool_calls INTEGER NOT NULL DEFAULT 0, input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0, started_at TEXT, finished_at TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, retry_acknowledged_at TEXT, FOREIGN KEY(snapshot_id) REFERENCES agent_snapshots(id), FOREIGN KEY(conversation_id) REFERENCES conversations(id))",
        "CREATE TABLE IF NOT EXISTS tool_invocations (invocation_id TEXT PRIMARY KEY, run_id TEXT NOT NULL, call_id TEXT NOT NULL, name TEXT NOT NULL, arguments_json TEXT NOT NULL, permission_decision TEXT NOT NULL, state TEXT NOT NULL, result_json TEXT, error_code TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(run_id, call_id), FOREIGN KEY(run_id) REFERENCES runs(run_id))",
        "CREATE INDEX IF NOT EXISTS idx_prompt_revisions_agent_created ON prompt_revisions(agent_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_messages_conversation_created ON messages(conversation_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_runs_state ON runs(state)",
        "CREATE INDEX IF NOT EXISTS idx_tool_invocations_run ON tool_invocations(run_id, created_at)",
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_embedding_operations_active_kb ON embedding_operations(kb_id) WHERE state IN('PREPARED','DISPATCHED','CACHE_READY')",
        "CREATE INDEX IF NOT EXISTS idx_embedding_operations_job ON embedding_operations(job_id)",
        "CREATE INDEX IF NOT EXISTS idx_capability_grants_agent ON capability_grants(agent_id, revoked_at)",
        "CREATE INDEX IF NOT EXISTS idx_capability_grants_workspace ON capability_grants(workspace_id, revoked_at)",
        "CREATE INDEX IF NOT EXISTS idx_snapshot_grant_bindings_snapshot ON snapshot_grant_bindings(snapshot_id)",
        "CREATE INDEX IF NOT EXISTS idx_memory_entries_space ON skill_memory_entries(space_id, path)",
        "CREATE INDEX IF NOT EXISTS idx_approval_records_call ON approval_records(call_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_tool_audit_details_request ON tool_audit_details(request_id, created_at)",
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
        // v12 adds a lifecycle to legacy skill grants.  Existing rows are deliberately
        // backfilled as persistent grants; no old capability is broadened or discarded.
        Column("permission_grants", "lifetime", "TEXT NOT NULL DEFAULT 'PERSISTENT' CHECK(lifetime IN('ONCE','TASK','SESSION','PERSISTENT'))"),
        Column("permission_grants", "policy_version", "INTEGER NOT NULL DEFAULT 0 CHECK(policy_version >= 0)"),
        Column("permission_grants", "created_at", "TEXT NOT NULL DEFAULT ''"),
        Column("permission_grants", "expires_at", "TEXT"),
        Column("permission_grants", "revoked_at", "TEXT"),
        // v13 durable capability-grant lifecycle columns.  They stay nullable so
        // old persistent rows can be copied without inventing an owner or a use.
        Column("capability_grants", "task_id", "TEXT"),
        Column("capability_grants", "session_id", "TEXT"),
        Column("capability_grants", "consumed_at", "TEXT"),
        // Workspace scope was added after the original v13 table. Existing
        // rows are ordinary selected directories; only explicit full-device
        // attachments may carry the high-risk scope.
        Column("workspaces", "scope", "TEXT NOT NULL DEFAULT 'SELECTED_DIRECTORY' CHECK(scope IN('SELECTED_DIRECTORY','FULL_DEVICE_FILES'))"),
    )

    fun apply(connection: SqlConnection) {
        connection.transaction {
            // A malformed pre-existing table must fail here; never replace it or clear data.
            connection.execute(statements.first())
            val current = readVersion(connection)
            if (current > VERSION) unsupported(current)
            // v2 gives several control-plane tables canonical names which were never present in
            // the v1 schema.  A few early prototypes used one of those names for a different
            // shape.  Preserve such a table under a deterministic legacy name before creating
            // the canonical table; never drop or overwrite it.  A table which already has the
            // canonical shape is left in place so a damaged v2 install still fails validation.
            if (current < VERSION) prepareLegacyNameCollisions(connection)
            // workspace_acl is derived state.  Recreate it so a v12 view cannot
            // silently hide the durable lifecycle columns introduced in v13.
            if (connection.query("SELECT type FROM sqlite_master WHERE name = 'workspace_acl'").singleOrNull()?.string("type") == "view") {
                connection.execute("DROP VIEW workspace_acl")
            }
            statements.drop(1).forEach { sql -> connection.execute(sql) }
            columns.forEach { column -> ensureColumn(connection, column) }
            backfillV11(connection)
            backfillV12(connection)
            backfillV2ControlPlane(connection)
            ensureDefaultAuthorityRows(connection)
            validateSnapshotManifests(connection)
            validateAuthoritySchema(connection)
            validateWorkspaceScopes(connection)
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

    private fun validateWorkspaceScopes(connection: SqlConnection) {
        connection.query("SELECT scope FROM workspaces").forEach { row ->
            val raw = row.columns["scope"]?.toString()
            if (raw == null || runCatching { WorkspaceScope.valueOf(raw) }.isFailure) {
                invalid("workspaces.scope is invalid")
            }
        }
    }

    private fun ensureDefaultAuthorityRows(connection: SqlConnection) {
        val now = Utc.nowIso()
        connection.execute(
            "INSERT OR IGNORE INTO authority_policy(id, selected_authority, dangerous_mode, policy_version, updated_at) VALUES(1,?,?,?,?)",
            listOf(Authority.NONE.name, DangerousMode.DISABLED.name, 0L, now),
        )
        Authority.values().forEach { authority ->
            connection.execute(
                "INSERT OR IGNORE INTO authority_preferences(authority, user_intent_enabled, explicitly_configured, updated_at) VALUES(?,?,?,?)",
                listOf(authority.name, 0, 0, now),
            )
        }
    }

    private fun validateAuthoritySchema(connection: SqlConnection) {
        val policy = connection.query("SELECT selected_authority, dangerous_mode, policy_version FROM authority_policy WHERE id = 1")
            .singleOrNull() ?: invalid("authority_policy singleton row is missing")
        runCatching { Authority.valueOf(policy.string("selected_authority")) }
            .getOrElse { invalid("authority_policy.selected_authority is invalid") }
        runCatching { DangerousMode.valueOf(policy.string("dangerous_mode")) }
            .getOrElse { invalid("authority_policy.dangerous_mode is invalid") }
        if (policy.long("policy_version") < 0) invalid("authority_policy.policy_version is negative")

        val preferences = connection.query(
            "SELECT authority, user_intent_enabled, explicitly_configured FROM authority_preferences",
        )
        if (preferences.size != Authority.values().size) invalid("authority_preferences rows are incomplete")
        preferences.forEach { preference ->
            runCatching { Authority.valueOf(preference.string("authority")) }
                .getOrElse { invalid("authority_preferences.authority is invalid") }
            if (preference.long("user_intent_enabled") !in 0L..1L) invalid("authority_preferences.user_intent_enabled is invalid")
            if (preference.long("explicitly_configured") !in 0L..1L) invalid("authority_preferences.explicitly_configured is invalid")
        }

        connection.query("SELECT lifetime, policy_version FROM permission_grants").forEach { row ->
            runCatching { GrantLifetime.valueOf(row.string("lifetime")) }
                .getOrElse { invalid("permission_grants.lifetime is invalid") }
            if (row.long("policy_version") < 0) invalid("permission_grants.policy_version is negative")
        }

        validateCapabilityGrantLifecycle(connection)
    }

    private fun validateCapabilityGrantLifecycle(connection: SqlConnection) {
        if (!hasOwnerUniqueConstraint(connection)) {
            invalid("capability_grants owner-aware uniqueness is missing")
        }
        connection.query("SELECT grant_id, lifetime, task_id, session_id, consumed_at FROM capability_grants")
            .forEach { row ->
                val source = "capability_grants[${row.string("grant_id")}]"
                val lifetime = runCatching { GrantLifetime.valueOf(row.string("lifetime")) }
                    .getOrElse { invalid("capability grant lifetime is invalid ($source)") }
                val taskId = persistedNullableText(row, "task_id", source)
                val sessionId = persistedNullableText(row, "session_id", source)
                val consumedAt = persistedNullableText(row, "consumed_at", source)
                if (taskId != null && !taskId.isSafeMigrationId()) {
                    invalid("capability grant task id is invalid ($source)")
                }
                if (sessionId != null && !sessionId.isSafeMigrationId()) {
                    invalid("capability grant session id is invalid ($source)")
                }
                if (consumedAt != null && lifetime != GrantLifetime.ONCE) {
                    invalid("only ONCE capability grants may be consumed ($source)")
                }
                when (lifetime) {
                    GrantLifetime.ONCE -> Unit
                    GrantLifetime.TASK -> if (taskId == null) invalid("TASK capability grant has no task owner ($source)")
                    GrantLifetime.SESSION -> if (sessionId == null) invalid("SESSION capability grant has no session owner ($source)")
                    GrantLifetime.PERSISTENT -> if (taskId != null || sessionId != null) {
                        invalid("PERSISTENT capability grant has an owner ($source)")
                    }
                }
            }
    }

    private fun persistedNullableText(row: SqlRow, column: String, source: String): String? {
        val raw = row.columns[column] ?: return null
        val value = raw.toString()
        val maxLength = if (column == "consumed_at") 128 else 4096
        if (value.isBlank() || value.length > maxLength) invalid("capability grant $column is invalid ($source)")
        return value
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
        "authority_policy", "authority_preferences", "workspaces", "workspace_acl", "capability_grants",
        "full_device_files_grants", "snapshot_grant_bindings", "saf_workspace_grants", "desktop_identity", "desktop_trust",
        "skill_memory_spaces", "skill_memory_entries", "approval_records", "tool_audit_details",
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
        "permission_grants" to "lifetime",
        "permission_grants" to "policy_version",
        "permission_grants" to "created_at",
        "permission_grants" to "expires_at",
        "permission_grants" to "revoked_at",
        "authority_policy" to "selected_authority",
        "authority_policy" to "dangerous_mode",
        "authority_policy" to "policy_version",
        "authority_policy" to "updated_at",
        "authority_preferences" to "authority",
        "authority_preferences" to "user_intent_enabled",
        "authority_preferences" to "explicitly_configured",
        "workspaces" to "root_reference",
        "workspaces" to "scope",
        "full_device_files_grants" to "workspace_id",
        "full_device_files_grants" to "revision",
        "full_device_files_grants" to "confirmed_at_epoch_ms",
        "full_device_files_grants" to "revoked_at",
        "capability_grants" to "capability",
        "capability_grants" to "lifetime",
        "capability_grants" to "policy_version",
        "capability_grants" to "revoked_at",
        "capability_grants" to "task_id",
        "capability_grants" to "session_id",
        "capability_grants" to "consumed_at",
        "workspace_acl" to "task_id",
        "workspace_acl" to "session_id",
        "workspace_acl" to "consumed_at",
        "snapshot_grant_bindings" to "snapshot_id",
        "saf_workspace_grants" to "uri_reference",
        "saf_workspace_grants" to "status",
        "desktop_identity" to "app_instance_id",
        "desktop_trust" to "secret_ref",
        "desktop_trust" to "status",
        "skill_memory_spaces" to "package_hash",
        "skill_memory_entries" to "path",
        "skill_memory_entries" to "content_hash",
        "skill_memory_entries" to "storage_ref",
        "approval_records" to "request_id",
        "approval_records" to "command_hash",
        "approval_records" to "cwd_hash",
        "tool_audit_details" to "command_sha256",
        "tool_audit_details" to "relative_path_sha256",
        "tool_audit_details" to "cwd_sha256",
        "tool_audit_details" to "stdout_bytes",
        "tool_audit_details" to "stderr_bytes",
    )

    /**
     * Validate every existing model before marking v11 complete and only derive endpoint_json
     * where the old schema genuinely had no endpoint column/value.  Earlier code used
     * getOrDefault(CHAT), getOrDefault(emptyList()) and a catch-all endpoint fallback, which
     * silently changed damaged data into a different model binding.
     */
    private fun backfillV11(connection: SqlConnection) {
        connection.query("SELECT id, role, capabilities, endpoint_json FROM model_profiles").forEach { row ->
            val modelId = row.string("id")
            val role = ProfileRepository.decodeRole(
                ProfileRepository.requirePersistedString(row.columns["role"], "role", modelId),
                modelId,
            )
            val caps = ProfileRepository.decodeCapabilities(
                ProfileRepository.requirePersistedString(row.columns["capabilities"], "capabilities", modelId),
                modelId,
            )
            val stored = ProfileRepository.requirePersistedString(row.columns["endpoint_json"], "endpoint_json", modelId)
            val endpoint = ProfileRepository.decodePersistedEndpoint(stored, role, caps, modelId)
            if (stored.isBlank() || stored.trim() == "{}") {
                val encoded = kotlinx.serialization.json.Json.encodeToString(
                    runtime.mobileagent.domain.ModelEndpoint.serializer(),
                    endpoint,
                )
                connection.execute("UPDATE model_profiles SET endpoint_json = ? WHERE id = ?", listOf(encoded, modelId))
            }
        }
    }

    /**
     * v12 only adds metadata to the legacy Skill grant rows.  Every pre-v12 grant is a
     * persistent user grant because the old schema had no expiry concept; capability sets,
     * revoked flags and revisions are intentionally left untouched.
     */
    private fun backfillV12(connection: SqlConnection) {
        connection.execute(
            "UPDATE permission_grants SET lifetime = ? WHERE lifetime IS NULL OR lifetime = ''",
            listOf(GrantLifetime.PERSISTENT.name),
        )
        connection.query("SELECT grant_id, install_id, created_at FROM permission_grants").forEach { row ->
            val grantId = row.string("grant_id")
            val createdAt = row.string("created_at")
            if (createdAt.isNotBlank()) return@forEach
            val fromInstall = connection.query(
                "SELECT created_at FROM skill_installs WHERE install_id = ?",
                listOf(row.string("install_id")),
            ).singleOrNull()?.string("created_at").orEmpty()
            connection.execute(
                "UPDATE permission_grants SET created_at = ? WHERE grant_id = ?",
                // An unknown historical timestamp is not evidence.  Keep the row and leave the
                // new metadata blank instead of fabricating an event time during migration.
                listOf(fromInstall, grantId),
            )
        }
        // Preserve the legacy revoked bit in the new lifecycle projection.  This only adds a
        // timestamp; capabilities, revision and the original revoked flag remain untouched.
        connection.execute(
            "UPDATE permission_grants SET revoked_at = COALESCE(revoked_at, created_at) WHERE revoked <> 0 AND (revoked_at IS NULL OR revoked_at = '')",
        )
    }

    /**
     * Import the small amount of control-plane state that existed in the v1 Shizuku prototype.
     * This is deliberately an additive projection: the old tables and preference rows remain in
     * place, while only values which identify a v2 object unambiguously are copied.  In
     * particular, a boolean named `granted`, a probe result, a Binder/UID, or an ADB/SAF handle
     * is never turned into a durable v2 grant or trust record.
     */
    private fun backfillV2ControlPlane(connection: SqlConnection) {
        val authorityFacts = readLegacyAuthorityFacts(connection)
        applyLegacyAuthorityFacts(connection, authorityFacts)

        val workspaceIds = migrateLegacyWorkspaces(connection, authorityFacts)
        migrateLegacySafWorkspaceGrants(connection, workspaceIds)
        migrateLegacyCapabilityGrants(connection, workspaceIds)
        migrateLegacySnapshotBindings(connection, workspaceIds)
    }

    /**
     * A few development snapshots used a control-plane table name before the v2 schema was
     * frozen.  Keep those rows under a legacy name.  This runs only for an old schema version;
     * a partially-created v2 table must instead fail validation and be repaired from backup.
     */
    private fun prepareLegacyNameCollisions(connection: SqlConnection) {
        LEGACY_NAME_COLLISIONS.forEach { table ->
            if (!tableExists(connection, table)) return@forEach
            val columns = tableColumns(connection, table)
            if (hasCanonicalColumns(table, columns) &&
                (table != "capability_grants" || hasOwnerUniqueConstraint(connection))
            ) return@forEach
            if (!looksLikeLegacyControlPlaneTable(table, columns)) {
                invalid("pre-existing $table table has an unknown schema")
            }
            val legacyName = "legacy_v2_$table"
            if (tableExists(connection, legacyName)) {
                invalid("legacy table name collision for $table")
            }
            val detachedIndexes = detachIndexesForTableRename(connection, table, legacyName)
            connection.execute("ALTER TABLE $table RENAME TO $legacyName")
            restoreDetachedIndexes(connection, detachedIndexes, table, legacyName)
        }
    }

    private data class DetachedIndex(val oldName: String, val sql: String, val newName: String)

    /**
     * SQLite does not provide ALTER INDEX.  Renaming a table leaves ordinary
     * index names unchanged, which would make a later CREATE INDEX IF NOT
     * EXISTS accidentally attach no index to the new canonical table.  Move
     * those derived objects with the legacy table; autoindexes are recreated
     * by SQLite as part of ALTER TABLE and are intentionally skipped.
     */
    private fun detachIndexesForTableRename(
        connection: SqlConnection,
        table: String,
        legacyName: String,
    ): List<DetachedIndex> {
        val detached = mutableListOf<DetachedIndex>()
        connection.query(
            "SELECT name, sql FROM sqlite_master WHERE type = 'index' AND tbl_name = ?",
            listOf(table),
        ).forEach { row ->
            val oldName = row.string("name")
            val sql = row.string("sql")
            if (oldName.isBlank() || oldName.startsWith("sqlite_autoindex") || sql.isBlank()) return@forEach
            val newName = "legacy_v2_$oldName"
            if (connection.query("SELECT name FROM sqlite_master WHERE name = ?", listOf(newName)).isNotEmpty()) {
                invalid("legacy index name collision for $oldName")
            }
            detached += DetachedIndex(oldName, sql, newName)
            connection.execute("DROP INDEX ${quoteIdentifier(oldName)}")
        }
        return detached
    }

    private fun restoreDetachedIndexes(
        connection: SqlConnection,
        indexes: List<DetachedIndex>,
        table: String,
        legacyName: String,
    ) {
        indexes.forEach { index ->
            val indexName = identifierPattern(index.oldName)
            val tableName = identifierPattern(table)
            val rewritten = index.sql
                .replace(
                    Regex("(?i)(CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+)$indexName(\\s+ON\\s+)"),
                    "${'$'}1${quoteIdentifier(index.newName)}${'$'}2",
                )
                .replace(
                    Regex("(?i)(\\s+ON\\s+)$tableName(\\s*\\()"),
                    "${'$'}1${quoteIdentifier(legacyName)}${'$'}2",
                )
            connection.execute(rewritten)
        }
    }

    private fun identifierPattern(value: String): String =
        "(?:\"${Regex.escape(value)}\"|\\[${Regex.escape(value)}\\]|`${Regex.escape(value)}`|${Regex.escape(value)})"

    private fun quoteIdentifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun readLegacyAuthorityFacts(connection: SqlConnection): LegacyAuthorityFacts {
        val facts = LegacyAuthorityFacts()
        LEGACY_AUTHORITY_TABLES.forEach { table ->
            if (!tableExists(connection, table)) return@forEach
            val columns = tableColumns(connection, table)
            connection.query("SELECT * FROM $table").forEachIndexed { index, row ->
                val tableAuthority = authorityForTable(table)
                val rowAuthority = readAuthority(row, columns, AUTHORITY_ALIASES, "${table}[$index]")
                val selected = rowAuthority ?: readAuthority(
                    row,
                    columns,
                    SELECTED_AUTHORITY_ALIASES,
                    "${table}[$index].selected_authority",
                )
                if (selected != null) facts.setSelected(selected, "$table[$index]")

                val genericAuthority = rowAuthority ?: tableAuthority
                if (genericAuthority != null) {
                    readBoolean(row, columns, ENABLED_ALIASES, "$table[$index].enabled")?.let {
                        facts.setEnabled(genericAuthority, it, "$table[$index].enabled")
                    }
                    readBoolean(row, columns, CONFIGURED_ALIASES, "$table[$index].configured")?.let {
                        facts.setConfigured(genericAuthority, it, "$table[$index].configured")
                    }
                }

                AUTHORITY_IMPORTS.forEach { (authority, aliases) ->
                    readBoolean(row, columns, aliases.enabled, "$table[$index].${authority.name}.enabled")?.let {
                        facts.setEnabled(authority, it, "$table[$index].${authority.name}.enabled")
                    }
                    readBoolean(row, columns, aliases.configured, "$table[$index].${authority.name}.configured")?.let {
                        facts.setConfigured(authority, it, "$table[$index].${authority.name}.configured")
                    }
                }
                readTimestamp(row, columns, UPDATED_AT_ALIASES, "$table[$index].updated_at")?.let {
                    facts.setUpdatedAt(it, "$table[$index].updated_at")
                }
            }
        }

        if (tableExists(connection, "app_prefs")) {
            val preferenceRows = connection.query("SELECT key, value FROM app_prefs")
            // Resolve selection before generic authority_enabled/configured keys.  SQLite orders
            // the preference table by its primary key, which is not a semantic ordering and can
            // otherwise make an otherwise explicit generic boolean look ambiguous.
            preferenceRows.filter { row ->
                normalizeName(row.columns["key"]?.toString().orEmpty()) in SELECTED_AUTHORITY_KEYS
            }.forEach { row ->
                val key = row.columns["key"]?.toString().orEmpty()
                val value = row.columns["value"]
                readLegacyAuthorityPreference(facts, key, value)
            }
            preferenceRows.filter { row ->
                normalizeName(row.columns["key"]?.toString().orEmpty()) !in SELECTED_AUTHORITY_KEYS
            }.forEach { row ->
                val key = row.columns["key"]?.toString().orEmpty()
                val value = row.columns["value"]
                readLegacyAuthorityPreference(facts, key, value)
            }
        }
        return facts
    }

    private fun readLegacyAuthorityPreference(facts: LegacyAuthorityFacts, key: String, raw: Any?) {
        val normalized = normalizeName(key)
        if (normalized.isBlank()) return
        if (normalized in SELECTED_AUTHORITY_KEYS) {
            val value = raw?.toString()?.trim().orEmpty()
            if (value.isBlank()) return
            facts.setSelected(parseAuthority(value, "app_prefs.$key"), "app_prefs.$key")
            return
        }

        AUTHORITY_IMPORTS.forEach { (authority, aliases) ->
            val prefixes = (if (authority == Authority.WIRED_ADB) {
                listOf("wired_adb", "wiredadb", "adb", "authority_wired_adb", "authority_wiredadb")
            } else {
                listOf("shizuku", "authority_shizuku")
            }).map(::normalizeName)
            val suffix = prefixes.firstOrNull { prefix ->
                normalized == prefix + "enabled" || normalized == prefix + "userintentenabled" ||
                    normalized == prefix + "configured" || normalized == prefix + "explicitlyconfigured" ||
                    normalized == prefix + "enabledconfigured"
            }?.let { prefix -> normalized.removePrefix(prefix) }
            if (suffix == null) return@forEach
            val bool = parseLegacyBoolean(raw, "app_prefs.$key") ?: return@forEach
            when (suffix) {
                "enabled", "userintentenabled", "enabledconfigured" ->
                    facts.setEnabled(authority, bool, "app_prefs.$key")
                "configured", "explicitlyconfigured" ->
                    facts.setConfigured(authority, bool, "app_prefs.$key")
            }
        }

        // A generic authority_enabled preference is meaningful only when the selected provider
        // is also explicit.  If selection is absent it is ambiguous and remains un-migrated.
        if (normalized in GENERIC_ENABLED_KEYS || normalized in GENERIC_CONFIGURED_KEYS) {
            val selected = facts.selected ?: return
            val bool = parseLegacyBoolean(raw, "app_prefs.$key") ?: return
            if (normalized in GENERIC_ENABLED_KEYS) facts.setEnabled(selected, bool, "app_prefs.$key")
            else facts.setConfigured(selected, bool, "app_prefs.$key")
        }

        // `shizuku_granted`, `probe`, availability, Binder and connection values are intentionally
        // ignored.  They are observations which must be re-probed by the live adapter.
    }

    private fun applyLegacyAuthorityFacts(connection: SqlConnection, facts: LegacyAuthorityFacts) {
        val now = Utc.nowIso()
        val sourceTime = facts.updatedAt ?: now
        val policy = connection.query(
            "SELECT selected_authority, dangerous_mode, policy_version, updated_at FROM authority_policy WHERE id = 1",
        ).singleOrNull()

        Authority.values().filter { it != Authority.NONE }.forEach { authority ->
            val enabled = facts.enabled[authority]
            val configured = facts.configured[authority]
            val selected = facts.selected == authority
            if (enabled == null && configured == null && !selected) return@forEach
            val intent = enabled ?: false
            val explicit = configured ?: (enabled != null || selected)
            val existing = connection.query(
                "SELECT user_intent_enabled, explicitly_configured FROM authority_preferences WHERE authority = ?",
                listOf(authority.name),
            ).singleOrNull()
            if (existing == null) {
                connection.execute(
                    "INSERT INTO authority_preferences(authority,user_intent_enabled,explicitly_configured,updated_at) VALUES(?,?,?,?)",
                    listOf(authority.name, if (intent) 1 else 0, if (explicit) 1 else 0, sourceTime),
                )
            } else if (existing.long("user_intent_enabled") == 0L && existing.long("explicitly_configured") == 0L) {
                // This also repairs a v12 database which already received the empty defaults.
                connection.execute(
                    "UPDATE authority_preferences SET user_intent_enabled=?, explicitly_configured=?, updated_at=? WHERE authority=? AND user_intent_enabled=0 AND explicitly_configured=0",
                    listOf(if (intent) 1 else 0, if (explicit) 1 else 0, sourceTime, authority.name),
                )
            }
        }

        val selected = facts.selected
        if (selected != null && policy == null) {
            connection.execute(
                "INSERT OR IGNORE INTO authority_policy(id,selected_authority,dangerous_mode,policy_version,updated_at) VALUES(1,?,?,0,?)",
                listOf(selected.name, DangerousMode.DISABLED.name, sourceTime),
            )
        } else if (selected != null && policy != null &&
            policy.string("selected_authority") == Authority.NONE.name &&
            policy.string("dangerous_mode") == DangerousMode.DISABLED.name &&
            policy.long("policy_version") == 0L
        ) {
            // Keep Dangerous Mode disabled.  Selection is only durable user intent; actual grant,
            // availability and connection are re-probed by the Authority adapter after startup.
            connection.execute(
                "UPDATE authority_policy SET selected_authority=?, updated_at=? WHERE id=1 AND selected_authority=? AND dangerous_mode=? AND policy_version=0",
                listOf(selected.name, sourceTime, Authority.NONE.name, DangerousMode.DISABLED.name),
            )
        }
    }

    private data class LegacyAuthorityFacts(
        var selected: Authority? = null,
        val enabled: MutableMap<Authority, Boolean> = linkedMapOf(),
        val configured: MutableMap<Authority, Boolean> = linkedMapOf(),
        var updatedAt: String? = null,
    ) {
        fun setSelected(value: Authority, source: String) {
            if (selected != null && selected != value) invalid("conflicting legacy authority selection ($source)")
            selected = value
        }

        fun setEnabled(authority: Authority, value: Boolean, source: String) {
            val previous = enabled[authority]
            if (previous != null && previous != value) invalid("conflicting legacy $authority enabled value ($source)")
            enabled[authority] = value
        }

        fun setConfigured(authority: Authority, value: Boolean, source: String) {
            val previous = configured[authority]
            if (previous != null && previous != value) invalid("conflicting legacy $authority configured value ($source)")
            configured[authority] = value
        }

        fun setUpdatedAt(value: String, source: String) {
            // Authority settings commonly store one timestamp per provider.  A timestamp is
            // audit metadata rather than an authorization fact, so keep the first valid value
            // and do not make otherwise-unambiguous booleans fail on a harmless difference.
            if (updatedAt == null) updatedAt = value
        }
    }

    private data class LegacyAuthorityAliases(
        val enabled: List<String>,
        val configured: List<String>,
    )

    private val AUTHORITY_IMPORTS = linkedMapOf(
        Authority.SHIZUKU to LegacyAuthorityAliases(
            enabled = listOf("shizuku_enabled", "shizukuEnabled", "authority_shizuku_enabled", "authorityShizukuEnabled"),
            configured = listOf("shizuku_configured", "shizukuConfigured", "shizuku_explicitly_configured", "authority_shizuku_configured", "authorityShizukuConfigured"),
        ),
        Authority.WIRED_ADB to LegacyAuthorityAliases(
            enabled = listOf("wired_adb_enabled", "wiredAdbEnabled", "adb_enabled", "authority_wired_adb_enabled"),
            configured = listOf("wired_adb_configured", "wiredAdbConfigured", "adb_configured", "authority_wired_adb_configured"),
        ),
    )

    private val AUTHORITY_ALIASES = listOf("authority", "authority_name", "provider", "provider_name", "backend")
    private val SELECTED_AUTHORITY_ALIASES = listOf("selected_authority", "selectedAuthority", "selected_provider", "selectedProvider")
    private val ENABLED_ALIASES = listOf("enabled", "user_intent_enabled", "userIntentEnabled", "intent_enabled")
    private val CONFIGURED_ALIASES = listOf("configured", "explicitly_configured", "explicitlyConfigured", "user_configured")
    private val UPDATED_AT_ALIASES = listOf("updated_at", "updatedAt", "modified_at", "created_at", "createdAt")
    private val SELECTED_AUTHORITY_KEYS = setOf("authority", "selectedauthority", "selectedprovider", "executionauthority")
    private val GENERIC_ENABLED_KEYS = setOf("authorityenabled", "executionauthorityenabled")
    private val GENERIC_CONFIGURED_KEYS = setOf("authorityconfigured", "executionauthorityconfigured")

    private val LEGACY_AUTHORITY_TABLES = listOf(
        "authority_settings", "authority_config", "authority_state", "execution_authority",
        "legacy_authority", "legacy_authority_settings", "shizuku_settings", "shizuku_config",
        "shizuku_state", "wired_adb_settings", "wired_adb_config", "legacy_v2_authority_policy",
        "legacy_v2_authority_preferences",
    )

    private val LEGACY_WORKSPACE_TABLES = listOf(
        "legacy_workspaces", "legacy_workspace", "workspace_registry", "workspace_settings",
        "workspace_config", "shizuku_workspaces", "shizuku_workspace", "legacy_v2_workspaces",
    )

    private val LEGACY_SAF_TABLES = listOf(
        "legacy_saf_workspace_grants", "saf_grants", "workspace_saf_grants", "legacy_v2_saf_workspace_grants",
    )

    private val WORKSPACE_ID_ALIASES = listOf("id", "workspace_id", "workspaceId", "key")
    private val WORKSPACE_NAME_ALIASES = listOf("display_name", "displayName", "name", "label")
    private val WORKSPACE_DISPLAY_ALIASES = listOf("display_name", "displayName", "name", "label")
    private val WORKSPACE_ROOT_ALIASES = listOf("root_reference", "rootReference", "root", "path", "directory", "workspace_root")
    private val WORKSPACE_BACKEND_ALIASES = listOf("backend_type", "backendType", "workspace_type", "workspaceType", "type")
    private val WORKSPACE_READABLE_ALIASES = listOf("readable", "read_enabled", "readEnabled", "read_granted", "readGranted")
    private val WORKSPACE_WRITABLE_ALIASES = listOf("writable", "write_enabled", "writeEnabled", "write_granted", "writeGranted")
    private val WORKSPACE_ENABLED_ALIASES = listOf("enabled", "active", "workspace_enabled", "workspaceEnabled")
    private val WORKSPACE_QUOTA_ALIASES = listOf("quota_bytes", "quotaBytes", "quota")
    private val WORKSPACE_MAX_FILE_ALIASES = listOf("max_file_bytes", "maxFileBytes", "max_bytes", "file_limit")
    private val SAF_URI_ALIASES = listOf("uri_reference", "uriReference", "saf_uri", "safUri", "uri", "tree_uri", "treeUri")
    private val SAF_READ_ALIASES = listOf("read_granted", "readGranted", "read", "read_enabled", "readEnabled")
    private val SAF_WRITE_ALIASES = listOf("write_granted", "writeGranted", "write", "write_enabled", "writeEnabled")
    private val SAF_PERSISTED_ALIASES = listOf("persisted_flags", "persistedFlags", "grant_flags", "grantFlags", "flags")
    private val SAF_STATUS_ALIASES = listOf("status", "grant_status", "grantStatus")
    private val SAF_STATUSES = setOf("ACTIVE", "GRANT_LOST", "REVOKED")
    private val LEGACY_SHIZUKU_ROOT_KEYS = setOf(
        "shizukuroot", "shizukuworkspaceroot", "mobileagentruntimeshizukuroot", "shizukupath", "shizukuworkspacepath",
    )
    private val LEGACY_SHIZUKU_READABLE_KEYS = setOf("shizukuworkspacereadable", "shizukureadable")
    private val LEGACY_SHIZUKU_WRITABLE_KEYS = setOf("shizukuworkspacewritable", "shizukuwritable")
    private val LEGACY_SHIZUKU_WORKSPACE_ENABLED_KEYS = setOf("shizukuworkspaceenabled", "shizukuworkspaceactive")
    private val LEGACY_SHIZUKU_CREATED_KEYS = setOf("shizukuworkspacecreatedat", "shizukurootcreatedat")
    private val LEGACY_SHIZUKU_UPDATED_KEYS = setOf("shizukuworkspaceupdatedat", "shizukurootupdatedat")

    private val LEGACY_NAME_COLLISIONS = listOf(
        "authority_policy", "authority_preferences", "workspaces", "capability_grants",
        "snapshot_grant_bindings", "saf_workspace_grants", "desktop_identity", "desktop_trust",
        "skill_memory_spaces", "skill_memory_entries", "approval_records", "tool_audit_details",
    )

    private fun authorityForTable(table: String): Authority? = when {
        normalizeName(table).contains("shizuku") -> Authority.SHIZUKU
        normalizeName(table).contains("wiredadb") || normalizeName(table).contains("adb") -> Authority.WIRED_ADB
        else -> null
    }

    private fun readAuthority(
        row: SqlRow,
        columns: Map<String, String>,
        aliases: List<String>,
        source: String,
    ): Authority? {
        val raw = readRaw(row, columns, aliases) ?: return null
        val value = raw.toString().trim()
        if (value.isBlank()) return null
        return parseAuthority(value, source)
    }

    private fun parseAuthority(value: String, source: String): Authority = runCatching {
        Authority.valueOf(value.uppercase())
    }.getOrElse { invalid("legacy authority is invalid ($source)") }

    private fun readBoolean(
        row: SqlRow,
        columns: Map<String, String>,
        aliases: List<String>,
        source: String,
    ): Boolean? = readRaw(row, columns, aliases)?.let { parseLegacyBoolean(it, source) }

    private fun parseLegacyBoolean(raw: Any?, source: String): Boolean? {
        if (raw == null) return null
        return when (raw) {
            is Boolean -> raw
            is Number -> when (raw.toLong()) {
                0L -> false
                1L -> true
                else -> invalid("legacy boolean is invalid ($source)")
            }
            else -> when (raw.toString().trim().lowercase()) {
                "", "null" -> null
                "true", "yes", "on", "1" -> true
                "false", "no", "off", "0" -> false
                else -> invalid("legacy boolean is invalid ($source)")
            }
        }
    }

    private fun readTimestamp(
        row: SqlRow,
        columns: Map<String, String>,
        aliases: List<String>,
        source: String,
    ): String? {
        val raw = readRaw(row, columns, aliases) ?: return null
        val value = raw.toString()
        if (value.length > 128) invalid("legacy timestamp is invalid ($source)")
        return value.ifBlank { null }
    }

    private fun readRaw(row: SqlRow, columns: Map<String, String>, aliases: List<String>): Any? {
        val actual = aliases.asSequence().map(::normalizeName).mapNotNull { columns[it] }.firstOrNull() ?: return null
        return row.columns[actual]
    }

    private fun tableExists(connection: SqlConnection, table: String): Boolean = connection.query(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
        listOf(table),
    ).isNotEmpty()

    private fun tableColumns(connection: SqlConnection, table: String): Map<String, String> = connection.query(
        "PRAGMA table_info($table)",
    ).mapNotNull { row ->
        row.string("name").takeIf { it.isNotBlank() }?.let { normalizeName(it) to it }
    }.toMap()

    private fun normalizeName(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

    private fun hasCanonicalColumns(table: String, columns: Map<String, String>): Boolean {
        val required = when (table) {
            "authority_policy" -> listOf("id", "selected_authority", "dangerous_mode", "policy_version", "updated_at")
            "authority_preferences" -> listOf("authority", "user_intent_enabled", "explicitly_configured", "updated_at")
            "workspaces" -> listOf("id", "display_name", "backend_type", "root_reference", "readable", "writable", "max_file_bytes", "enabled", "revision", "created_at", "updated_at")
            "capability_grants" -> listOf("grant_id", "agent_id", "capability", "lifetime", "policy_version", "created_at", "revision", "task_id", "session_id", "consumed_at")
            "snapshot_grant_bindings" -> listOf("snapshot_id", "grant_id", "capability", "policy_version", "bound_at")
            "saf_workspace_grants" -> listOf("workspace_id", "uri_reference", "read_granted", "write_granted", "persisted_flags", "status", "created_at", "updated_at")
            "desktop_identity" -> listOf("id", "desktop_id", "app_instance_id", "updated_at")
            "desktop_trust" -> listOf("desktop_id", "app_instance_id", "secret_ref", "status", "created_at", "revision")
            "skill_memory_spaces" -> listOf("space_id", "install_id", "package_hash", "quota_bytes", "max_entries", "version", "created_at", "updated_at")
            "skill_memory_entries" -> listOf("entry_id", "space_id", "path", "content_hash", "storage_ref", "byte_length", "version", "created_at", "updated_at")
            "approval_records" -> listOf("approval_id", "request_id", "call_id", "agent_id", "selected_authority", "dangerous_mode", "decision", "created_at")
            "tool_audit_details" -> listOf("audit_id", "request_id", "agent_id", "capability", "authority", "result", "created_at")
            else -> emptyList()
        }
        return required.all { normalizeName(it) in columns }
    }

    /**
     * SQLite keeps a table-level UNIQUE constraint as an auto-index.  Once a
     * v12 table has been created that index cannot be edited with ALTER TABLE;
     * the v13 upgrade therefore requires an owner-aware unique index before the
     * table is considered canonical.
     */
    private fun hasOwnerUniqueConstraint(connection: SqlConnection): Boolean {
        val expected = listOf(
            "agent_id", "skill_install_id", "package_hash", "capability",
            "workspace_id", "path_scope", "task_id", "session_id",
        )
        return connection.query("PRAGMA index_list(capability_grants)").any { index ->
            if (index.long("unique") != 1L) return@any false
            val name = index.string("name")
            if (name.isBlank()) return@any false
            val quoted = name.replace("\"", "\"\"")
            val columns = connection.query("PRAGMA index_info(\"$quoted\")")
                .sortedBy { it.long("seqno") }
                .map { it.string("name") }
            columns == expected
        }
    }

    private fun looksLikeLegacyControlPlaneTable(table: String, columns: Map<String, String>): Boolean {
        val names = columns.keys
        return when (table) {
            "authority_policy", "authority_preferences" -> names.any {
                it in setOf("authority", "selectedauthority", "selectedprovider", "enabled", "configured", "shizukuenabled")
            }
            "workspaces" -> names.any { it in setOf("id", "workspaceid", "name", "displayname", "root", "path", "directory", "authority") }
            "capability_grants" -> names.any { it in setOf("grantid", "agentid", "capability", "permission", "workspaceid") }
            "snapshot_grant_bindings" -> names.any { it in setOf("snapshotid", "grantid", "capability", "workspaceid") }
            "saf_workspace_grants" -> names.any { it in setOf("workspaceid", "uri", "urireference", "read", "write", "persistedflags") }
            else -> names.isNotEmpty()
        }
    }

    private fun migrateLegacyWorkspaces(
        connection: SqlConnection,
        authorityFacts: LegacyAuthorityFacts,
    ): MutableMap<String, String> {
        val mapping = linkedMapOf<String, String>()
        LEGACY_WORKSPACE_TABLES.forEach { table ->
            if (!tableExists(connection, table)) return@forEach
            val columns = tableColumns(connection, table)
            connection.query("SELECT * FROM $table").forEachIndexed { index, row ->
                val source = "$table[$index]"
                val oldId = readString(row, columns, WORKSPACE_ID_ALIASES)
                    ?: readString(row, columns, WORKSPACE_NAME_ALIASES)
                val root = readString(row, columns, WORKSPACE_ROOT_ALIASES)
                if (oldId.isNullOrBlank() || root.isNullOrBlank()) return@forEachIndexed
                if (root.length > 4096) invalid("legacy workspace root reference is invalid ($source)")
                val newId = legacyWorkspaceId(oldId)
                mapping[oldId] = newId

                val backend = readWorkspaceBackend(row, columns, root, source)
                val displayName = readString(row, columns, WORKSPACE_DISPLAY_ALIASES) ?: oldId
                if (displayName.length > 256 || displayName.isBlank()) invalid("legacy workspace display name is invalid ($source)")
                val readable = readBoolean(row, columns, WORKSPACE_READABLE_ALIASES, "$source.readable") ?: false
                val writable = readBoolean(row, columns, WORKSPACE_WRITABLE_ALIASES, "$source.writable") ?: false
                val enabled = readBoolean(row, columns, WORKSPACE_ENABLED_ALIASES, "$source.enabled") ?: false
                val quota = readPositiveLong(row, columns, WORKSPACE_QUOTA_ALIASES, "$source.quota_bytes")
                val maxFileBytes = readPositiveLong(row, columns, WORKSPACE_MAX_FILE_ALIASES, "$source.max_file_bytes")
                    ?: WorkspaceDefaults.maxFileBytes
                if (quota != null && maxFileBytes > quota) invalid("legacy workspace file limit exceeds quota ($source)")
                val revision = readNonNegativeLong(row, columns, listOf("revision", "version"), "$source.revision") ?: 0L
                val createdAt = readTimestamp(row, columns, listOf("created_at", "createdAt"), "$source.created_at").orEmpty()
                val updatedAt = readTimestamp(row, columns, UPDATED_AT_ALIASES, "$source.updated_at") ?: createdAt

                connection.execute(
                    "INSERT OR IGNORE INTO workspaces(id,display_name,backend_type,root_reference,readable,writable,quota_bytes,max_file_bytes,enabled,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    listOf(
                        newId, displayName, backend.name, root, bool(readable), bool(writable), quota,
                        maxFileBytes, bool(enabled), revision, createdAt, updatedAt,
                    ),
                )
            }
        }

        // v12 already had the canonical workspaces table.  Keep those IDs
        // addressable when a v12 capability/SAF row points at one; only rows
        // imported from a genuinely legacy table receive the neutral legacy-
        // prefix above.
        if (tableExists(connection, "workspaces")) {
            connection.query("SELECT id FROM workspaces").forEach { row ->
                val id = row.string("id")
                if (id.isSafeMigrationId()) mapping.putIfAbsent(id, id)
            }
        }

        // The original Shizuku prototype kept its fixed workspace root in app_prefs rather than
        // a table.  Preserve that existing root as a disabled, neutral-ID workspace.  A root
        // preference alone does not prove a platform grant, so readable/writable/enabled remain
        // false unless the old workspace flags were explicit.
        if (tableExists(connection, "app_prefs")) {
            val prefs = connection.query("SELECT key, value FROM app_prefs").associate {
                it.columns["key"]?.toString().orEmpty() to it.columns["value"]
            }
            val rootEntry = prefs.entries.firstOrNull { normalizeName(it.key) in LEGACY_SHIZUKU_ROOT_KEYS }
            val root = rootEntry?.value?.toString()?.trim().orEmpty()
            if (root.length > 4096) invalid("legacy Shizuku workspace root reference is invalid")
            if (root.isNotBlank()) {
                val oldId = "MobileAgentRuntime-Shizuku"
                val newId = legacyWorkspaceId(oldId)
                mapping.putIfAbsent(oldId, newId)
                val readable = preferenceBoolean(prefs, LEGACY_SHIZUKU_READABLE_KEYS, "shizuku workspace readable") ?: false
                val writable = preferenceBoolean(prefs, LEGACY_SHIZUKU_WRITABLE_KEYS, "shizuku workspace writable") ?: false
                val enabled = preferenceBoolean(prefs, LEGACY_SHIZUKU_WORKSPACE_ENABLED_KEYS, "shizuku workspace enabled") ?: false
                val createdAt = preferenceTimestamp(prefs, LEGACY_SHIZUKU_CREATED_KEYS)
                val updatedAt = preferenceTimestamp(prefs, LEGACY_SHIZUKU_UPDATED_KEYS) ?: createdAt
                connection.execute(
                    "INSERT OR IGNORE INTO workspaces(id,display_name,backend_type,root_reference,readable,writable,quota_bytes,max_file_bytes,enabled,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    listOf(
                        newId, "Shizuku workspace", WorkspaceBackendType.PRIVILEGED.name, root,
                        bool(readable), bool(writable), null, WorkspaceDefaults.maxFileBytes, bool(enabled), 0L,
                        createdAt, updatedAt,
                    ),
                )
            }
        }
        return mapping
    }

    private fun migrateLegacySafWorkspaceGrants(
        connection: SqlConnection,
        workspaceIds: Map<String, String>,
    ) {
        LEGACY_SAF_TABLES.forEach { table ->
            if (!tableExists(connection, table)) return@forEach
            val columns = tableColumns(connection, table)
            connection.query("SELECT * FROM $table").forEachIndexed { index, row ->
                val source = "$table[$index]"
                val oldWorkspaceId = readString(row, columns, WORKSPACE_ID_ALIASES) ?: return@forEachIndexed
                val workspaceId = workspaceIds[oldWorkspaceId] ?: return@forEachIndexed
                val uri = readString(row, columns, SAF_URI_ALIASES)?.trim().orEmpty()
                // A SAF row is only useful when the source contains the actual URI.  Never
                // synthesize one from a filesystem root or from a workspace ID.
                if (!isSafeUri(uri)) return@forEachIndexed
                val read = readBoolean(row, columns, SAF_READ_ALIASES, "$source.read_granted")
                val write = readBoolean(row, columns, SAF_WRITE_ALIASES, "$source.write_granted")
                val persisted = readNonNegativeLong(row, columns, SAF_PERSISTED_ALIASES, "$source.persisted_flags")
                val status = readSafStatus(row, columns, source)
                // Without explicit grant flags/status there is no proof of a current platform
                // grant.  Keep the old row and let the user re-authorize instead.
                if (read == null || write == null || persisted == null || status == null) return@forEachIndexed
                val createdAt = readTimestamp(row, columns, listOf("created_at", "createdAt"), "$source.created_at").orEmpty()
                val updatedAt = readTimestamp(row, columns, UPDATED_AT_ALIASES, "$source.updated_at") ?: createdAt
                val lostAt = readTimestamp(row, columns, listOf("lost_at", "lostAt"), "$source.lost_at")
                connection.execute(
                    "INSERT OR IGNORE INTO saf_workspace_grants(workspace_id,uri_reference,read_granted,write_granted,persisted_flags,status,created_at,lost_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    listOf(workspaceId, uri, bool(read), bool(write), persisted, status, createdAt, lostAt, updatedAt),
                )
            }
        }
    }

    private fun readWorkspaceBackend(
        row: SqlRow,
        columns: Map<String, String>,
        root: String,
        source: String,
    ): WorkspaceBackendType {
        val raw = readString(row, columns, WORKSPACE_BACKEND_ALIASES)?.trim()
        if (raw != null && raw.isNotBlank()) {
            return runCatching { WorkspaceBackendType.valueOf(raw.uppercase()) }
                .getOrElse { invalid("legacy workspace backend is invalid ($source)") }
        }
        val authority = readAuthority(row, columns, AUTHORITY_ALIASES, "$source.authority")
        return when {
            authority == Authority.SHIZUKU || authority == Authority.WIRED_ADB -> WorkspaceBackendType.PRIVILEGED
            // A URI-shaped root is not enough to establish a SAF grant, but it is safe to retain
            // the explicit backend classification when the old row supplied no grant metadata.
            root.startsWith("content://") -> WorkspaceBackendType.SAF_TREE
            else -> WorkspaceBackendType.INTERNAL
        }
    }

    private fun readSafStatus(row: SqlRow, columns: Map<String, String>, source: String): String? {
        val raw = readString(row, columns, SAF_STATUS_ALIASES)?.trim().orEmpty()
        if (raw.isBlank()) return null
        return raw.uppercase().takeIf { it in SAF_STATUSES }
            ?: invalid("legacy SAF grant status is invalid ($source)")
    }

    private fun readString(row: SqlRow, columns: Map<String, String>, aliases: List<String>): String? =
        readRaw(row, columns, aliases)?.toString()?.takeIf { it.isNotBlank() }

    private fun readOwner(
        row: SqlRow,
        columns: Map<String, String>,
        aliases: List<String>,
        source: String,
    ): String? {
        val raw = readRaw(row, columns, aliases) ?: return null
        val value = raw.toString()
        if (value.isBlank()) invalid("legacy capability grant owner is blank ($source)")
        return value
    }

    private fun readPositiveLong(
        row: SqlRow,
        columns: Map<String, String>,
        aliases: List<String>,
        source: String,
    ): Long? = readLong(row, columns, aliases, source)?.also {
        if (it <= 0L) invalid("legacy positive integer is invalid ($source)")
    }

    private fun readNonNegativeLong(
        row: SqlRow,
        columns: Map<String, String>,
        aliases: List<String>,
        source: String,
    ): Long? = readLong(row, columns, aliases, source)?.also {
        if (it < 0L) invalid("legacy non-negative integer is invalid ($source)")
    }

    private fun readLong(
        row: SqlRow,
        columns: Map<String, String>,
        aliases: List<String>,
        source: String,
    ): Long? {
        val raw = readRaw(row, columns, aliases) ?: return null
        val text = raw.toString().trim()
        if (text.isBlank()) return null
        return text.toLongOrNull() ?: invalid("legacy integer is invalid ($source)")
    }

    private fun bool(value: Boolean): Int = if (value) 1 else 0

    private fun legacyWorkspaceId(oldId: String): String {
        val normalized = oldId.trim()
        if (normalized.isSafeMigrationId()) return "legacy-$normalized"
        return "legacy-${sha256(normalized).take(32)}"
    }

    private fun String.isSafeMigrationId(): Boolean =
        length in 1..240 && all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' } && first().isLetterOrDigit()

    private fun isSafeUri(value: String): Boolean =
        value.length in 1..4096 && value.startsWith("content://") &&
            value.none { it == '\u0000' || it.isWhitespace() }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun preferenceBoolean(
        preferences: Map<String, Any?>,
        aliases: Set<String>,
        source: String,
    ): Boolean? {
        var result: Boolean? = null
        preferences.entries.filter { normalizeName(it.key) in aliases }.forEach { (key, raw) ->
            val next = parseLegacyBoolean(raw, "app_prefs.$key ($source)") ?: return@forEach
            if (result != null && result != next) invalid("conflicting legacy boolean ($source)")
            result = next
        }
        return result
    }

    private fun preferenceTimestamp(preferences: Map<String, Any?>, aliases: Set<String>): String? =
        preferences.entries.firstOrNull { normalizeName(it.key) in aliases }?.let { (key, raw) ->
            raw?.toString()?.let {
                if (it.length > 128) invalid("legacy timestamp is invalid (app_prefs.$key)")
                it.ifBlank { null }
            }
        }

    private object WorkspaceDefaults {
        const val maxFileBytes: Long = 1L * 1024 * 1024
    }

    private val LEGACY_CAPABILITY_TABLES = listOf(
        "legacy_capability_grants", "capability_grants_legacy", "agent_capability_grants_legacy",
        "authority_capability_grants", "legacy_grants", "legacy_v2_capability_grants",
    )

    private val LEGACY_SNAPSHOT_TABLES = listOf(
        "legacy_snapshot_grant_bindings", "snapshot_grants", "snapshot_capability_bindings",
        "agent_snapshot_grants", "legacy_v2_snapshot_grant_bindings",
    )

    private val GRANT_ID_ALIASES = listOf("grant_id", "grantId", "id", "permission_id", "permissionId")
    private val GRANT_AGENT_ALIASES = listOf("agent_id", "agentId", "agent")
    private val GRANT_INSTALL_ALIASES = listOf("skill_install_id", "skillInstallId", "install_id", "installId")
    private val GRANT_PACKAGE_ALIASES = listOf("package_hash", "packageHash", "package")
    private val GRANT_CAPABILITY_ALIASES = listOf("capability", "capability_id", "capabilityId", "permission")
    private val GRANT_WORKSPACE_ALIASES = listOf("workspace_id", "workspaceId", "workspace")
    private val GRANT_PATH_ALIASES = listOf("path_scope", "pathScope", "scope", "relative_path")
    private val GRANT_LIFETIME_ALIASES = listOf("lifetime", "grant_lifetime", "grantLifetime")
    private val GRANT_POLICY_ALIASES = listOf("policy_version", "policyVersion", "version")
    private val GRANT_CREATED_ALIASES = listOf("created_at", "createdAt", "granted_at", "grantedAt")
    private val GRANT_EXPIRES_ALIASES = listOf("expires_at", "expiresAt", "expiry", "expires")
    private val GRANT_REVOKED_AT_ALIASES = listOf("revoked_at", "revokedAt")
    private val GRANT_REVOKED_ALIASES = listOf("revoked", "is_revoked", "isRevoked")
    private val GRANT_ACTIVE_ALIASES = listOf("active", "is_active", "isActive")
    private val GRANT_STATUS_ALIASES = listOf("status", "state", "grant_status", "grantStatus")
    private val GRANT_REVISION_ALIASES = listOf("revision", "grant_revision", "grantRevision")
    private val GRANT_TASK_ALIASES = listOf("task_id", "taskId", "task", "owner_task_id", "ownerTaskId")
    private val GRANT_SESSION_ALIASES = listOf("session_id", "sessionId", "session", "owner_session_id", "ownerSessionId")
    private val GRANT_CONSUMED_AT_ALIASES = listOf("consumed_at", "consumedAt", "used_at", "usedAt")
    private val PERMISSION_CAPABILITIES_ALIASES = listOf("capabilities", "capability_ids", "capabilityIds", "permissions")
    private val PERMISSION_SCOPES_ALIASES = listOf("scopes_json", "scopesJson", "scope_json", "scopeJson")

    private val SNAPSHOT_ID_ALIASES = listOf("snapshot_id", "snapshotId", "snapshot")
    private val BOUND_AT_ALIASES = listOf("bound_at", "boundAt", "created_at", "createdAt")

    private fun migrateLegacyCapabilityGrants(
        connection: SqlConnection,
        workspaceIds: Map<String, String>,
    ) {
        LEGACY_CAPABILITY_TABLES.forEach { table ->
            if (!tableExists(connection, table)) return@forEach
            val columns = tableColumns(connection, table)
            connection.query("SELECT * FROM $table").forEachIndexed { index, row ->
                migrateExplicitGrantRow(connection, row, columns, workspaceIds, "$table[$index]")
            }
        }
        migrateLegacyPermissionGrants(connection, workspaceIds)
    }

    private fun migrateExplicitGrantRow(
        connection: SqlConnection,
        row: SqlRow,
        columns: Map<String, String>,
        workspaceIds: Map<String, String>,
        source: String,
    ) {
        val grantId = readString(row, columns, GRANT_ID_ALIASES) ?: return
        val agentId = readString(row, columns, GRANT_AGENT_ALIASES) ?: return
        val capability = readString(row, columns, GRANT_CAPABILITY_ALIASES) ?: return
        if (!grantId.isSafeMigrationId() || !agentId.isSafeMigrationId() || !CapabilityId.isValid(capability)) {
            invalid("legacy capability grant identity is invalid ($source)")
        }
        val workspaceRaw = readString(row, columns, GRANT_WORKSPACE_ALIASES)
        val workspaceId = workspaceRaw?.let { workspaceIds[it] ?: return }
        val pathScope = readString(row, columns, GRANT_PATH_ALIASES)?.also {
            if (!isRelativeMigrationScope(it)) return
        }
        val lifetime = readLifetime(row, columns, source) ?: GrantLifetime.PERSISTENT.name
        val taskId = readOwner(row, columns, GRANT_TASK_ALIASES, "$source.task_id")?.also {
            if (!it.isSafeMigrationId()) invalid("legacy capability grant task id is invalid ($source)")
        }
        val sessionId = readOwner(row, columns, GRANT_SESSION_ALIASES, "$source.session_id")?.also {
            if (!it.isSafeMigrationId()) invalid("legacy capability grant session id is invalid ($source)")
        }
        val consumedAt = readTimestamp(row, columns, GRANT_CONSUMED_AT_ALIASES, "$source.consumed_at")
        validateLegacyGrantLifecycle(lifetime, taskId, sessionId, consumedAt, source)
        val policyVersion = readNonNegativeLong(row, columns, GRANT_POLICY_ALIASES, "$source.policy_version") ?: 0L
        val createdAt = readTimestamp(row, columns, GRANT_CREATED_ALIASES, "$source.created_at").orEmpty()
        val expiresAt = readTimestamp(row, columns, GRANT_EXPIRES_ALIASES, "$source.expires_at")
        val revokedAt = readTimestamp(row, columns, GRANT_REVOKED_AT_ALIASES, "$source.revoked_at")
        val revoked = readRevoked(row, columns, source)
        val effectiveRevokedAt = when {
            revokedAt != null -> revokedAt
            revoked == true && createdAt.isNotBlank() -> createdAt
            revoked == true -> return
            else -> null
        }
        val revision = readPositiveLong(row, columns, GRANT_REVISION_ALIASES, "$source.revision") ?: 1L
        val installId = readString(row, columns, GRANT_INSTALL_ALIASES)?.also {
            if (!it.isSafeMigrationId()) return
        }
        val packageHash = readString(row, columns, GRANT_PACKAGE_ALIASES)?.also {
            if (!isSafeMigrationHash(it)) return
        }
        insertCapabilityGrant(
            connection, grantId, agentId, installId, packageHash, capability, workspaceId, pathScope,
            lifetime, policyVersion, createdAt, expiresAt, effectiveRevokedAt, revision,
            taskId, sessionId, consumedAt,
        )
    }

    private fun migrateLegacyPermissionGrants(
        connection: SqlConnection,
        workspaceIds: Map<String, String>,
    ) {
        if (!tableExists(connection, "permission_grants")) return
        val columns = tableColumns(connection, "permission_grants")
        connection.query("SELECT * FROM permission_grants").forEachIndexed { index, row ->
            val source = "permission_grants[$index]"
            val scopesRaw = readString(row, columns, PERMISSION_SCOPES_ALIASES) ?: return@forEachIndexed
            val scopes = parseLegacyObject(scopesRaw) ?: return@forEachIndexed
            val agentId = jsonString(scopes, "agentId", "agent_id")
                ?: inferUniqueAgentForInstall(connection, readString(row, columns, GRANT_INSTALL_ALIASES))
                ?: return@forEachIndexed
            if (!agentId.isSafeMigrationId()) return@forEachIndexed
            val workspaceRaw = jsonString(scopes, "workspaceId", "workspace_id", "workspace")
                ?: readString(row, columns, GRANT_WORKSPACE_ALIASES)
            val workspaceId = workspaceRaw?.let { workspaceIds[it] ?: return@forEachIndexed }
            val pathScope = jsonString(scopes, "pathScope", "path_scope")
                ?: readString(row, columns, GRANT_PATH_ALIASES)
            if (pathScope != null && !isRelativeMigrationScope(pathScope)) return@forEachIndexed
            val capabilities = parseLegacyCapabilities(readString(row, columns, PERMISSION_CAPABILITIES_ALIASES))
                ?: return@forEachIndexed
            val grantId = readString(row, columns, GRANT_ID_ALIASES) ?: return@forEachIndexed
            if (!grantId.isSafeMigrationId()) return@forEachIndexed
            val installRaw = readString(row, columns, GRANT_INSTALL_ALIASES)
            if (installRaw != null && !installRaw.isSafeMigrationId()) return@forEachIndexed
            val packageRaw = readString(row, columns, GRANT_PACKAGE_ALIASES)
            if (packageRaw != null && !isSafeMigrationHash(packageRaw)) return@forEachIndexed
            val installId = installRaw
            val packageHash = packageRaw
            val lifetime = jsonString(scopes, "lifetime")?.let { parseLifetime(it, source) }
                ?: readLifetime(row, columns, source) ?: GrantLifetime.PERSISTENT.name
            val taskId = jsonString(scopes, "taskId", "task_id", "ownerTaskId", "owner_task_id")
                ?: readOwner(row, columns, GRANT_TASK_ALIASES, "$source.task_id")
            val sessionId = jsonString(scopes, "sessionId", "session_id", "ownerSessionId", "owner_session_id")
                ?: readOwner(row, columns, GRANT_SESSION_ALIASES, "$source.session_id")
            val consumedAt = jsonString(scopes, "consumedAt", "consumed_at", "usedAt", "used_at")
                ?: readTimestamp(row, columns, GRANT_CONSUMED_AT_ALIASES, "$source.consumed_at")
            if (taskId != null && !taskId.isSafeMigrationId()) return@forEachIndexed
            if (sessionId != null && !sessionId.isSafeMigrationId()) return@forEachIndexed
            // A present-but-empty owner key is evidence of a scoped legacy
            // record whose owner cannot be proved.  Do not reinterpret it as
            // an unscoped persistent grant.
            if (jsonHasAny(scopes, "taskId", "task_id", "ownerTaskId", "owner_task_id") && taskId == null) return@forEachIndexed
            if (jsonHasAny(scopes, "sessionId", "session_id", "ownerSessionId", "owner_session_id") && sessionId == null) return@forEachIndexed
            // The old permission_grants row is not a typed owner record.  A scoped
            // lifetime without its durable owner is isolated in the source table;
            // it must never be projected as a persistent grant.  Likewise an old
            // persistent row carrying an owner is ambiguous and is left untouched.
            if (!isMigratableLegacyLifecycle(lifetime, taskId, sessionId, consumedAt)) return@forEachIndexed
            val policyVersion = jsonLong(scopes, "policyVersion", "policy_version")
                ?: readNonNegativeLong(row, columns, GRANT_POLICY_ALIASES, "$source.policy_version") ?: 0L
            val createdAt = jsonString(scopes, "createdAt", "created_at")
                ?: readTimestamp(row, columns, GRANT_CREATED_ALIASES, "$source.created_at").orEmpty()
            val expiresAt = jsonString(scopes, "expiresAt", "expires_at")
                ?: readTimestamp(row, columns, GRANT_EXPIRES_ALIASES, "$source.expires_at")
            val revokedAt = jsonString(scopes, "revokedAt", "revoked_at")
                ?: readTimestamp(row, columns, GRANT_REVOKED_AT_ALIASES, "$source.revoked_at")
            val revoked = readRevoked(row, columns, source)
            val effectiveRevokedAt = when {
                revokedAt != null -> revokedAt
                revoked == true && createdAt.isNotBlank() -> createdAt
                revoked == true -> return@forEachIndexed
                else -> null
            }
            val revision = readPositiveLong(row, columns, GRANT_REVISION_ALIASES, "$source.revision") ?: 1L
            capabilities.forEachIndexed { capabilityIndex, capability ->
                val id = if (capabilities.size == 1) grantId else "$grantId.$capabilityIndex"
                if (!id.isSafeMigrationId()) return@forEachIndexed
                insertCapabilityGrant(
                    connection, id, agentId, installId, packageHash, capability, workspaceId, pathScope,
                    lifetime, policyVersion, createdAt, expiresAt, effectiveRevokedAt, revision,
                    taskId, sessionId, consumedAt,
                )
            }
        }
    }

    private fun insertCapabilityGrant(
        connection: SqlConnection,
        grantId: String,
        agentId: String,
        installId: String?,
        packageHash: String?,
        capability: String,
        workspaceId: String?,
        pathScope: String?,
        lifetime: String,
        policyVersion: Long,
        createdAt: String,
        expiresAt: String?,
        revokedAt: String?,
        revision: Long,
        taskId: String? = null,
        sessionId: String? = null,
        consumedAt: String? = null,
    ) {
        connection.execute(
            "INSERT OR IGNORE INTO capability_grants(grant_id,agent_id,skill_install_id,package_hash,capability,workspace_id,path_scope,lifetime,policy_version,created_at,expires_at,revoked_at,revision,task_id,session_id,consumed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(grantId, agentId, installId, packageHash, capability, workspaceId, pathScope, lifetime, policyVersion, createdAt, expiresAt, revokedAt, revision, taskId, sessionId, consumedAt),
        )
    }

    private fun validateLegacyGrantLifecycle(
        lifetime: String,
        taskId: String?,
        sessionId: String?,
        consumedAt: String?,
        source: String,
    ) {
        if (!isMigratableLegacyLifecycle(lifetime, taskId, sessionId, consumedAt)) {
            // Explicit capability-grant tables are typed enough that malformed
            // lifecycle rows are a migration error.  The transaction rolls back,
            // preserving the source table for repair instead of guessing.
            invalid("legacy capability grant lifecycle is incomplete ($source)")
        }
    }

    private fun isMigratableLegacyLifecycle(
        lifetime: String,
        taskId: String?,
        sessionId: String?,
        consumedAt: String?,
    ): Boolean = when (lifetime) {
        GrantLifetime.ONCE.name -> true
        GrantLifetime.TASK.name -> taskId != null
        GrantLifetime.SESSION.name -> sessionId != null
        GrantLifetime.PERSISTENT.name -> taskId == null && sessionId == null && consumedAt == null
        else -> false
    } && (consumedAt == null || lifetime == GrantLifetime.ONCE.name)

    private fun readLifetime(row: SqlRow, columns: Map<String, String>, source: String): String? =
        readString(row, columns, GRANT_LIFETIME_ALIASES)?.let { parseLifetime(it, source) }

    private fun readRevoked(row: SqlRow, columns: Map<String, String>, source: String): Boolean? {
        val revoked = readBoolean(row, columns, GRANT_REVOKED_ALIASES, "$source.revoked")
        val active = readBoolean(row, columns, GRANT_ACTIVE_ALIASES, "$source.active")?.not()
        val status = readString(row, columns, GRANT_STATUS_ALIASES)?.trim()?.uppercase()?.let {
            when (it) {
                "ACTIVE", "GRANTED", "ENABLED" -> false
                "REVOKED", "DISABLED", "EXPIRED" -> true
                else -> invalid("legacy grant status is invalid ($source)")
            }
        }
        val values = listOfNotNull(revoked, active, status).distinct()
        if (values.size > 1) invalid("conflicting legacy grant lifecycle values ($source)")
        return values.singleOrNull()
    }

    private fun parseLifetime(value: String, source: String): String = runCatching {
        GrantLifetime.valueOf(value.trim().uppercase()).name
    }.getOrElse { invalid("legacy grant lifetime is invalid ($source)") }

    private fun isSafeMigrationHash(value: String): Boolean =
        value.length in 1..128 && value == value.trim() && value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }

    private fun isRelativeMigrationScope(value: String): Boolean =
        value.isNotBlank() && value.length <= 4096 && !value.contains('\u0000') && !value.contains('\\') &&
            !value.startsWith('/') && !value.startsWith("//") && !value.contains(':') &&
            value.split('/').all { it.isNotBlank() && it != "." && it != ".." }

    private fun parseLegacyObject(raw: String): JsonObject? = runCatching {
        Json { ignoreUnknownKeys = true; explicitNulls = false }.parseToJsonElement(raw) as? JsonObject
    }.getOrNull()

    private fun jsonString(obj: JsonObject, vararg names: String): String? {
        val element = names.asSequence().mapNotNull { name ->
            obj.entries.firstOrNull { normalizeName(it.key) == normalizeName(name) }?.value
        }.firstOrNull() ?: return null
        return (element as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun jsonHasAny(obj: JsonObject, vararg names: String): Boolean =
        obj.keys.any { key -> names.any { normalizeName(it) == normalizeName(key) } }

    private fun jsonLong(obj: JsonObject, vararg names: String): Long? =
        jsonString(obj, *names)?.toLongOrNull()?.also { if (it < 0L) invalid("legacy JSON version is negative") }

    private fun parseLegacyCapabilities(raw: String?): List<String>? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        val values = if (value.startsWith("[")) {
            runCatching {
                val array = Json { ignoreUnknownKeys = false; explicitNulls = false }.parseToJsonElement(value) as? JsonArray
                    ?: return@runCatching null
                array.map { (it as? JsonPrimitive)?.contentOrNull ?: return@runCatching null }
            }.getOrNull()
        } else if (value.startsWith("{")) {
            null
        } else {
            value.split(',').map { it.trim() }.filter { it.isNotBlank() }
        }
        return values?.takeIf { it.isNotEmpty() && it.all(CapabilityId::isValid) }
    }

    private fun inferUniqueAgentForInstall(connection: SqlConnection, installId: String?): String? {
        if (installId.isNullOrBlank() || !installId.isSafeMigrationId()) return null
        val candidates = connection.query("SELECT id, skill_ids FROM agent_profiles").mapNotNull { row ->
            val agentId = row.string("id")
            if (!agentId.isSafeMigrationId()) return@mapNotNull null
            val skillIds = parseLegacyStringArray(row.string("skill_ids")) ?: return@mapNotNull null
            agentId.takeIf { installId in skillIds }
        }.distinct()
        return candidates.singleOrNull()
    }

    private fun parseLegacyStringArray(raw: String): List<String>? {
        val value = raw.trim()
        if (value.isBlank()) return emptyList()
        if (!value.startsWith("[")) return null
        return runCatching {
            val array = Json { ignoreUnknownKeys = false; explicitNulls = false }.parseToJsonElement(value) as? JsonArray
                ?: return@runCatching null
            array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }.getOrNull()
    }

    private fun migrateLegacySnapshotBindings(
        connection: SqlConnection,
        workspaceIds: Map<String, String>,
    ) {
        LEGACY_SNAPSHOT_TABLES.forEach { table ->
            if (!tableExists(connection, table)) return@forEach
            val columns = tableColumns(connection, table)
            connection.query("SELECT * FROM $table").forEachIndexed { index, row ->
                val source = "$table[$index]"
                val snapshotId = readString(row, columns, SNAPSHOT_ID_ALIASES) ?: return@forEachIndexed
                val grantId = readString(row, columns, GRANT_ID_ALIASES) ?: return@forEachIndexed
                val capability = readString(row, columns, GRANT_CAPABILITY_ALIASES) ?: return@forEachIndexed
                if (!snapshotId.isSafeMigrationId() || !grantId.isSafeMigrationId() || !CapabilityId.isValid(capability)) {
                    invalid("legacy snapshot binding identity is invalid ($source)")
                }
                if (connection.query("SELECT id FROM agent_snapshots WHERE id = ?", listOf(snapshotId)).isEmpty()) return@forEachIndexed
                val grant = connection.query(
                    "SELECT capability,workspace_id,path_scope,policy_version FROM capability_grants WHERE grant_id = ?",
                    listOf(grantId),
                ).singleOrNull() ?: return@forEachIndexed
                val oldWorkspace = readString(row, columns, GRANT_WORKSPACE_ALIASES)
                val workspaceId = oldWorkspace?.let { workspaceIds[it] ?: it.takeIf { value -> value.isSafeMigrationId() } }
                    ?: grant.string("workspace_id").ifBlank { null }
                val pathScope = readString(row, columns, GRANT_PATH_ALIASES)
                    ?: grant.string("path_scope").ifBlank { null }
                if (pathScope != null && !isRelativeMigrationScope(pathScope)) return@forEachIndexed
                val policyVersion = readNonNegativeLong(row, columns, GRANT_POLICY_ALIASES, "$source.policy_version")
                    ?: grant.long("policy_version")
                if (grant.string("capability") != capability || grant.string("workspace_id").ifBlank { null } != workspaceId ||
                    grant.string("path_scope").ifBlank { null } != pathScope || grant.long("policy_version") != policyVersion) {
                    // A binding which cannot be matched exactly would either broaden scope or
                    // refer to a different grant.  Leave the old record untouched.
                    return@forEachIndexed
                }
                val boundAt = readTimestamp(row, columns, BOUND_AT_ALIASES, "$source.bound_at") ?: return@forEachIndexed
                connection.execute(
                    "INSERT OR IGNORE INTO snapshot_grant_bindings(snapshot_id,grant_id,capability,workspace_id,path_scope,policy_version,bound_at) VALUES(?,?,?,?,?,?,?)",
                    listOf(snapshotId, grantId, capability, workspaceId, pathScope, policyVersion, boundAt),
                )
            }
        }
        // Some snapshots stored bindings in their immutable manifest.  Import only an explicit
        // array whose rows match a canonical grant exactly; opaque/old manifests remain intact.
        migrateManifestBindings(connection)
    }

    private fun migrateManifestBindings(connection: SqlConnection) {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        connection.query("SELECT id, binding_manifest_json, expanded_json FROM agent_snapshots").forEach { row ->
            val snapshotId = row.string("id")
            val rawValues = listOf(row.string("binding_manifest_json"), row.string("expanded_json"))
            rawValues.forEach { raw ->
                val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return@forEach
                val element = root.entries.firstOrNull { normalizeName(it.key) in setOf("grantbindings", "snapshotgrantbindings", "capabilitygrants") }?.value
                val bindings = element as? JsonArray ?: return@forEach
                bindings.forEach { bindingElement ->
                    val binding = bindingElement as? JsonObject ?: return@forEach
                    val grantId = jsonString(binding, "grantId", "grant_id") ?: return@forEach
                    val capability = jsonString(binding, "capability", "capabilityId", "capability_id") ?: return@forEach
                    val workspaceId = jsonString(binding, "workspaceId", "workspace_id")
                    val pathScope = jsonString(binding, "pathScope", "path_scope")
                    val policyVersion = jsonLong(binding, "policyVersion", "policy_version") ?: return@forEach
                    val boundAt = jsonString(binding, "boundAt", "bound_at") ?: return@forEach
                    if (!snapshotId.isSafeMigrationId() || !grantId.isSafeMigrationId() || !CapabilityId.isValid(capability)) return@forEach
                    if (pathScope != null && !isRelativeMigrationScope(pathScope)) return@forEach
                    val grant = connection.query(
                        "SELECT capability,workspace_id,path_scope,policy_version FROM capability_grants WHERE grant_id = ?",
                        listOf(grantId),
                    ).singleOrNull() ?: return@forEach
                    if (grant.string("capability") != capability || grant.string("workspace_id").ifBlank { null } != workspaceId ||
                        grant.string("path_scope").ifBlank { null } != pathScope || grant.long("policy_version") != policyVersion) return@forEach
                    connection.execute(
                        "INSERT OR IGNORE INTO snapshot_grant_bindings(snapshot_id,grant_id,capability,workspace_id,path_scope,policy_version,bound_at) VALUES(?,?,?,?,?,?,?)",
                        listOf(snapshotId, grantId, capability, workspaceId, pathScope, policyVersion, boundAt),
                    )
                }
            }
        }
    }

    /**
     * Snapshot expansion is immutable input to a run.  Validate its embedded model/provider
     * fragments during startup migration as well as when the snapshot is later resolved, so a
     * malformed old row cannot survive a successful schema upgrade and silently retarget a run.
     * Empty manifests are retained for pre-expansion snapshots; there is no source data from
     * which to reconstruct those rows, so the normal snapshot resolver remains fail-closed.
     */
    private fun validateSnapshotManifests(connection: SqlConnection) {
        val json = Json { ignoreUnknownKeys = false; explicitNulls = false }
        connection.query("SELECT id, binding_manifest_json, expanded_json FROM agent_snapshots").forEach { row ->
            val snapshotId = row.string("id")
            val bindingRaw = row.columns["binding_manifest_json"] as? String
                ?: invalid("Snapshot $snapshotId binding_manifest_json is not text")
            val expandedRaw = row.columns["expanded_json"] as? String
                ?: invalid("Snapshot $snapshotId expanded_json is not text")
            val binding = decodeSnapshotObject(json, bindingRaw, snapshotId, "binding_manifest_json")
            val root = if (binding.isEmpty() && expandedRaw.isNotBlank() && expandedRaw.trim() != "{}") {
                val expanded = decodeSnapshotObject(json, expandedRaw, snapshotId, "expanded_json")
                val nested = expanded["bindingManifest"]
                if (nested == null) expanded else nested as? JsonObject
                    ?: invalid("Snapshot $snapshotId expanded bindingManifest is not an object")
            } else {
                binding
            }
            if (root.isEmpty()) return@forEach
            val manifestId = (root["snapshotId"] as? JsonPrimitive)?.contentOrNull
            if (manifestId != null && manifestId != snapshotId) {
                invalid("Snapshot $snapshotId manifest id does not match its row")
            }
            SNAPSHOT_MODEL_KEYS.forEach { key ->
                root[key]?.let { ProfileRepository.validatePersistedModelElement(it, "Snapshot $snapshotId $key") }
            }
            SNAPSHOT_PROVIDER_KEYS.forEach { key ->
                root[key]?.let { ProfileRepository.validatePersistedProviderElement(it, "Snapshot $snapshotId $key") }
            }
        }
    }

    private fun decodeSnapshotObject(json: Json, raw: String, snapshotId: String, field: String): JsonObject {
        if (raw.isBlank() || raw.trim() == "{}") return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(raw) as? JsonObject }
            .getOrNull() ?: invalid("Snapshot $snapshotId $field is not a JSON object")
    }

    private val SNAPSHOT_MODEL_KEYS = listOf("chatModel", "visionModel", "embeddingModel", "rerankerModel")
    private val SNAPSHOT_PROVIDER_KEYS = listOf("provider", "visionProvider", "embeddingProvider", "rerankerProvider")
}
