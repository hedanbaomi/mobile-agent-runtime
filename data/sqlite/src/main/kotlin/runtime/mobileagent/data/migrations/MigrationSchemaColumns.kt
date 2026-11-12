// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data.migrations

import runtime.mobileagent.data.SqlConnection

internal data class MigrationColumn(val table: String, val name: String, val definition: String)

internal object MigrationSchemaColumns {
    val columns: List<MigrationColumn> = listOf(
        // Columns introduced by the v1-v8 migrations. Keep these checks explicit so a partially
        // applied historical migration is repaired or reported instead of being hidden by a
        // best-effort ALTER.
        MigrationColumn("chunks", "source_span", "TEXT"),
        MigrationColumn("chunks", "asset_ids", "TEXT"),
        MigrationColumn("chunks", "page", "INTEGER"),
        MigrationColumn("import_jobs", "vision_consent", "INTEGER NOT NULL DEFAULT 0"),
        MigrationColumn("import_jobs", "embedding_is_api", "INTEGER NOT NULL DEFAULT 0"),
        MigrationColumn("import_jobs", "embedding_consent", "INTEGER NOT NULL DEFAULT 0"),
        MigrationColumn("assets", "document_version_id", "TEXT"),
        MigrationColumn("vision_results", "table_markdown", "TEXT NOT NULL DEFAULT ''"),
        MigrationColumn("vision_results", "result_type", "TEXT NOT NULL DEFAULT ''"),
        MigrationColumn("skill_packages", "package_bytes", "BLOB"),
        MigrationColumn("skill_packages", "source_hash", "TEXT"),
        MigrationColumn("permission_grants", "scopes_json", "TEXT"),
        MigrationColumn("model_profiles", "parameters_json", "TEXT NOT NULL DEFAULT '{}'"),
        MigrationColumn("agent_profiles", "parameter_overrides_json", "TEXT NOT NULL DEFAULT '{}'"),
        MigrationColumn("agent_profiles", "context_policy_json", "TEXT NOT NULL DEFAULT '{}'"),
        MigrationColumn("agent_profiles", "permission_settings_json", "TEXT NOT NULL DEFAULT '{}'"),
        MigrationColumn("agent_snapshots", "provider_id", "TEXT NOT NULL DEFAULT ''"),
        MigrationColumn("agent_snapshots", "chat_model_revision", "INTEGER NOT NULL DEFAULT 0"),
        MigrationColumn("agent_snapshots", "vision_model_id", "TEXT"),
        MigrationColumn("agent_snapshots", "vision_model_revision", "INTEGER"),
        MigrationColumn("agent_snapshots", "embedding_model_id", "TEXT"),
        MigrationColumn("agent_snapshots", "embedding_model_revision", "INTEGER"),
        MigrationColumn("agent_snapshots", "reranker_model_id", "TEXT"),
        MigrationColumn("agent_snapshots", "reranker_model_revision", "INTEGER"),
        MigrationColumn("agent_snapshots", "parameter_overrides_json", "TEXT NOT NULL DEFAULT '{}'"),
        MigrationColumn("agent_snapshots", "context_policy_json", "TEXT NOT NULL DEFAULT '{}'"),
        MigrationColumn("agent_snapshots", "permission_settings_json", "TEXT NOT NULL DEFAULT '{}'"),
        MigrationColumn("agent_snapshots", "binding_manifest_json", "TEXT NOT NULL DEFAULT '{}'"),
        MigrationColumn("agent_snapshots", "expanded_json", "TEXT NOT NULL DEFAULT '{}'"),
        MigrationColumn("messages", "parts_json", "TEXT NOT NULL DEFAULT '[]'"),
        MigrationColumn("messages", "metadata_json", "TEXT NOT NULL DEFAULT '{}'"),
        MigrationColumn("messages", "chronology_key", "TEXT NOT NULL DEFAULT ''"),
        MigrationColumn("messages", "chronology_ordinal", "INTEGER"),
        MigrationColumn("audit_events", "input_bytes", "INTEGER NOT NULL DEFAULT 0"),
        MigrationColumn("audit_events", "output_bytes", "INTEGER NOT NULL DEFAULT 0"),
        MigrationColumn("audit_events", "input_tokens", "INTEGER NOT NULL DEFAULT 0"),
        MigrationColumn("audit_events", "output_tokens", "INTEGER NOT NULL DEFAULT 0"),
        MigrationColumn("audit_events", "metadata_json", "TEXT NOT NULL DEFAULT '{}'"),
        MigrationColumn("import_jobs", "vision_binding_json", "TEXT"),
        MigrationColumn("import_jobs", "batch_id", "TEXT"),
        MigrationColumn("runs", "retry_acknowledged_at", "TEXT"),
        MigrationColumn("model_profiles", "endpoint_json", "TEXT NOT NULL DEFAULT '{}'"),
        MigrationColumn("secrets", "status", "TEXT NOT NULL DEFAULT 'ACTIVE'"),
        MigrationColumn("secrets", "retired_at", "TEXT"),
        // v12 adds a lifecycle to legacy skill grants. Existing rows are deliberately
        // backfilled as persistent grants; no old capability is broadened or discarded.
        MigrationColumn("permission_grants", "lifetime", "TEXT NOT NULL DEFAULT 'PERSISTENT' CHECK(lifetime IN('ONCE','TASK','SESSION','PERSISTENT'))"),
        MigrationColumn("permission_grants", "policy_version", "INTEGER NOT NULL DEFAULT 0 CHECK(policy_version >= 0)"),
        MigrationColumn("permission_grants", "created_at", "TEXT NOT NULL DEFAULT ''"),
        MigrationColumn("permission_grants", "expires_at", "TEXT"),
        MigrationColumn("permission_grants", "revoked_at", "TEXT"),
        // v13 durable capability-grant lifecycle columns. They stay nullable so
        // old persistent rows can be copied without inventing an owner or a use.
        MigrationColumn("capability_grants", "task_id", "TEXT"),
        MigrationColumn("capability_grants", "session_id", "TEXT"),
        MigrationColumn("capability_grants", "consumed_at", "TEXT"),
        // Workspace scope was added after the original v13 table. Existing
        // rows are ordinary selected directories; only explicit full-device
        // attachments may carry the high-risk scope.
        MigrationColumn("workspaces", "scope", "TEXT NOT NULL DEFAULT 'SELECTED_DIRECTORY' CHECK(scope IN('SELECTED_DIRECTORY','FULL_DEVICE_FILES'))"),
        // v16 exposes the snapshot under the explicit name used by the
        // thread/workspace integration. Existing snapshot_id remains the
        // compatibility source and is copied into this projection below.
        MigrationColumn("conversations", "agent_snapshot_id", "TEXT NOT NULL DEFAULT ''"),
        // v17 freezes per-run facts (RunManifest) on the run row. The empty
        // object marks runs that predate frozen facts.
        MigrationColumn("runs", "manifest_json", "TEXT NOT NULL DEFAULT '{}'"),
        // v19: durable batch-level Vision authorization and explicit pause/block state.
        MigrationColumn("import_batches", "vision_target", "TEXT"),
        MigrationColumn("import_batches", "vision_scope_hash", "TEXT"),
        MigrationColumn("import_batches", "vision_authorized_at", "TEXT"),
        MigrationColumn("import_batches", "paused_at", "TEXT"),
        MigrationColumn("import_batches", "blocked_reason", "TEXT"),
        MigrationColumn("import_batches", "published_items", "INTEGER NOT NULL DEFAULT 0"),
        MigrationColumn("import_batches", "unknown_items", "INTEGER NOT NULL DEFAULT 0"),
        // v20: freeze source selection and fence incomplete URI/archive staging.
        MigrationColumn("import_batches", "staging_manifest", "TEXT"),
        MigrationColumn("import_batches", "staging_complete", "INTEGER NOT NULL DEFAULT 1 CHECK(staging_complete IN (0,1))"),
        // v22: automatic (follow-provider) output cap. Existing rows keep their
        // configured number and are treated as MANUAL, so the upgrade never
        // silently drops a user's cap.
        MigrationColumn("model_profiles", "output_limit_mode", "TEXT NOT NULL DEFAULT 'MANUAL' CHECK(output_limit_mode IN ('AUTO','MANUAL'))"),
        // v23: automatic context window. Legacy rows stay MANUAL with their number,
        // and an AUTO row records the value, its source and the frozen target it was
        // measured for so a stale capability degrades to unknown instead of lying.
        MigrationColumn("model_profiles", "context_limit_mode", "TEXT NOT NULL DEFAULT 'MANUAL' CHECK(context_limit_mode IN ('AUTO','MANUAL'))"),
        MigrationColumn("model_profiles", "context_window_value", "INTEGER"),
        MigrationColumn("model_profiles", "context_window_source", "TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK(context_window_source IN ('UNKNOWN','USER_DECLARED','PROVIDER_METADATA'))"),
        MigrationColumn("model_profiles", "context_window_target", "TEXT"),
        MigrationColumn("model_profiles", "context_window_checked_at", "TEXT"),
        // v24: unit-level local failure reporting and phase
        MigrationColumn("pipeline_units", "failure_code", "TEXT"),
        MigrationColumn("pipeline_units", "failure_phase", "TEXT"),
        MigrationColumn("pipeline_attempts", "dispatch_slot", "INTEGER"),
        MigrationColumn("chunks", "text_utf16_length", "INTEGER NOT NULL DEFAULT -1 CHECK(text_utf16_length >= -1)"),
        MigrationColumn("conversations", "archived", "INTEGER NOT NULL DEFAULT 0 CHECK(archived IN (0,1))"),
    )

    fun ensureColumn(connection: SqlConnection, column: MigrationColumn) {
        val exists = connection.query("PRAGMA table_info(${column.table})")
            .any { it.string("name") == column.name }
        if (!exists) {
            connection.execute("ALTER TABLE ${column.table} ADD COLUMN ${column.name} ${column.definition}")
        }
    }
}
