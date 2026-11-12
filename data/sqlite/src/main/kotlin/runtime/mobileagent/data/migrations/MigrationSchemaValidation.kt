// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data.migrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import runtime.mobileagent.data.MigrationValidation
import runtime.mobileagent.data.ProfileRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.SqlRow
import runtime.mobileagent.domain.AgentWorkspaceDefault
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.ConversationWorkspaceBinding
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.PrivilegedWorkspaceBinding
import runtime.mobileagent.domain.PrivilegedWorkspaceBindingStatus
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceScope

internal object MigrationSchemaValidation {
    val REQUIRED_TABLES = setOf(
        "schema_version", "provider_profiles", "model_profiles", "agent_profiles", "prompt_revisions",
        "conversations", "agent_snapshots", "messages", "message_parts", "knowledge_bases", "blobs",
        "embedding_query_attempts",
        "chunks_fts", "announcement_state", "announcement_feed_cache", "announcement_items",
        "documents", "chunks", "embeddings", "secrets", "app_prefs", "audit_events", "import_jobs",
        "import_batches", "import_items", "consent_tickets", "capability_probes",
        "document_versions", "embedding_operations", "embedding_query_vectors", "index_generations", "generation_members", "assets", "vision_results", "vision_attempts",
        "skill_packages", "skill_installs", "permission_grants", "skill_invocations", "runs", "tool_invocations",
        "authority_policy", "authority_preferences", "workspaces", "workspace_acl", "capability_grants",
        "full_device_files_grants", "snapshot_grant_bindings", "saf_workspace_grants", "privileged_workspace_bindings",
        "conversation_workspace_bindings", "agent_workspace_defaults", "desktop_identity", "desktop_trust",
        "skill_memory_spaces", "skill_memory_entries", "approval_records", "tool_audit_details",
        "context_compactions", "pipeline_plans", "pipeline_units", "pipeline_results", "pipeline_attempts",
        "pipeline_retry_permits", "pipeline_policies",
        "trpg_sessions", "trpg_run_receipts", "trpg_events", "trpg_undo", "trpg_checkpoints",
    )

    val REQUIRED_COLUMNS = listOf(
        "trpg_checkpoints" to "checkpoint_id",
        "trpg_checkpoints" to "name",
        "trpg_checkpoints" to "created_at",
        "trpg_checkpoints" to "payload_json",
        "trpg_events" to "event_id",
        "trpg_events" to "revision",
        "trpg_events" to "action_id",
        "trpg_events" to "request_hash",
        "trpg_events" to "event_json",
        "trpg_events" to "run_id",
        "trpg_events" to "kind",
        "trpg_undo" to "undo_json",
        "model_profiles" to "parameters_json",
        "agent_profiles" to "parameter_overrides_json",
        "agent_snapshots" to "expanded_json",
        "messages" to "parts_json",
        "messages" to "chronology_key",
        "messages" to "chronology_ordinal",
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
        "vision_attempts" to "request_id",
        "vision_attempts" to "dispatch_status",
        "model_profiles" to "endpoint_json",
        "secrets" to "status",
        "import_jobs" to "batch_id",
        "import_batches" to "staging_manifest",
        "import_batches" to "staging_complete",
        "import_batches" to "vision_target",
        "import_batches" to "vision_scope_hash",
        "import_batches" to "vision_authorized_at",
        "import_batches" to "paused_at",
        "import_batches" to "blocked_reason",
        "import_batches" to "published_items",
        "import_batches" to "unknown_items",
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
        "conversations" to "snapshot_id",
        "conversations" to "agent_snapshot_id",
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
        "privileged_workspace_bindings" to "workspace_id",
        "privileged_workspace_bindings" to "authority",
        "privileged_workspace_bindings" to "encrypted_locator",
        "privileged_workspace_bindings" to "locator_nonce",
        "privileged_workspace_bindings" to "locator_version",
        "privileged_workspace_bindings" to "key_version",
        "privileged_workspace_bindings" to "aad_app_instance_id",
        "privileged_workspace_bindings" to "aad_workspace_id",
        "privileged_workspace_bindings" to "aad_authority",
        "privileged_workspace_bindings" to "aad_locator_version",
        "privileged_workspace_bindings" to "scope",
        "privileged_workspace_bindings" to "status",
        "privileged_workspace_bindings" to "revision",
        "privileged_workspace_bindings" to "created_at",
        "privileged_workspace_bindings" to "updated_at",
        "conversation_workspace_bindings" to "session_id",
        "conversation_workspace_bindings" to "workspace_id",
        "conversation_workspace_bindings" to "bound_at",
        "conversation_workspace_bindings" to "revision",
        "agent_workspace_defaults" to "agent_id",
        "agent_workspace_defaults" to "workspace_id",
        "agent_workspace_defaults" to "revision",
        "agent_workspace_defaults" to "updated_at",
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
        "context_compactions" to "id",
        "context_compactions" to "conversation_id",
        "context_compactions" to "snapshot_id",
        "context_compactions" to "run_id",
        "context_compactions" to "source_message_ids_json",
        "context_compactions" to "source_content_hash",
        "context_compactions" to "input_hash",
        "context_compactions" to "state",
        "context_compactions" to "summary_json",
        "context_compactions" to "parent_id",
        "context_compactions" to "version",
        "context_compactions" to "updated_at",
    )

    fun validateRequiredSchema(connection: SqlConnection) {
        REQUIRED_TABLES.forEach { table ->
            val exists = connection.query(
                "SELECT name FROM sqlite_master WHERE type IN ('table','view') AND name = ?",
                listOf(table),
            ).isNotEmpty()
            if (!exists) migrationInvalid("Required table $table is missing after migration")
        }
        REQUIRED_COLUMNS.forEach { (table, column) ->
            if (connection.query("PRAGMA table_info($table)").none { it.string("name") == column }) {
                migrationInvalid("Required column $table.$column is missing after migration")
            }
        }
    }

    fun validateWorkspaceScopes(connection: SqlConnection) {
        connection.query("SELECT scope FROM workspaces").forEach { row ->
            val raw = row.columns["scope"]?.toString()
            if (raw == null || runCatching { WorkspaceScope.valueOf(raw) }.isFailure) {
                migrationInvalid("workspaces.scope is invalid")
            }
        }
    }

    fun validateWorkspaceBindings(connection: SqlConnection) {
        connection.query("SELECT * FROM privileged_workspace_bindings").forEach { row ->
            val source = "privileged_workspace_bindings[" + requiredText(row, "workspace_id") + "]"
            val encryptedLocator = row.columns["encrypted_locator"] as? ByteArray
                ?: migrationInvalid("$source.encrypted_locator is not a BLOB")
            val locatorNonce = row.columns["locator_nonce"] as? ByteArray
                ?: migrationInvalid("$source.locator_nonce is not a BLOB")
            val authority = parseAuthority(requiredText(row, "authority"), source)
            val aadAuthority = parseAuthority(requiredText(row, "aad_authority"), "$source.aad_authority")
            val scope = runCatching { WorkspaceScope.valueOf(requiredText(row, "scope")) }
                .getOrElse { migrationInvalid("$source.scope is invalid") }
            val status = runCatching {
                PrivilegedWorkspaceBindingStatus.valueOf(requiredText(row, "status"))
            }.getOrElse { migrationInvalid("$source.status is invalid") }
            val binding = runCatching {
                PrivilegedWorkspaceBinding(
                    workspaceId = requiredText(row, "workspace_id"),
                    authority = authority,
                    encryptedLocator = encryptedLocator,
                    locatorNonce = locatorNonce,
                    locatorVersion = requiredInt(row, "locator_version", source),
                    keyVersion = requiredInt(row, "key_version", source),
                    aadAppInstanceId = requiredText(row, "aad_app_instance_id"),
                    aadWorkspaceId = requiredText(row, "aad_workspace_id"),
                    aadAuthority = aadAuthority,
                    aadLocatorVersion = requiredInt(row, "aad_locator_version", source),
                    scope = scope,
                    status = status,
                    revision = requiredLong(row, "revision", source),
                    createdAt = requiredText(row, "created_at"),
                    updatedAt = requiredText(row, "updated_at"),
                )
            }.getOrElse {
                migrationInvalid("$source is invalid")
            }
            val workspace = connection.query(
                "SELECT backend_type, scope FROM workspaces WHERE id = ?",
                listOf(binding.workspaceId),
            ).singleOrNull() ?: migrationInvalid("$source references a missing workspace")
            if (workspace.string("backend_type") != WorkspaceBackendType.PRIVILEGED.name) {
                migrationInvalid("$source references a non-privileged workspace")
            }
            if (workspace.string("scope") != binding.scope.name) {
                migrationInvalid("$source scope does not match its workspace")
            }
        }

        connection.query("SELECT * FROM conversation_workspace_bindings").forEach { row ->
            val source = "conversation_workspace_bindings[" + requiredText(row, "session_id") + "]"
            val binding = runCatching {
                ConversationWorkspaceBinding(
                    sessionId = requiredText(row, "session_id"),
                    workspaceId = requiredText(row, "workspace_id"),
                    boundAt = requiredText(row, "bound_at"),
                    revision = requiredLong(row, "revision", source),
                )
            }.getOrElse {
                migrationInvalid("$source is invalid")
            }
            if (connection.query("SELECT id FROM conversations WHERE id = ?", listOf(binding.sessionId)).isEmpty()) {
                migrationInvalid("$source references a missing conversation")
            }
            if (connection.query("SELECT id FROM workspaces WHERE id = ?", listOf(binding.workspaceId)).isEmpty()) {
                migrationInvalid("$source references a missing workspace")
            }
        }

        connection.query("SELECT * FROM agent_workspace_defaults").forEach { row ->
            val source = "agent_workspace_defaults[" + requiredText(row, "agent_id") + "]"
            val workspaceId = row.columns["workspace_id"]?.let { value ->
                if (value !is String) migrationInvalid("$source.workspace_id is not text")
                value.takeIf { it.isNotBlank() }
            }
            val default = runCatching {
                AgentWorkspaceDefault(
                    agentId = requiredText(row, "agent_id"),
                    workspaceId = workspaceId,
                    revision = requiredLong(row, "revision", source),
                    updatedAt = requiredText(row, "updated_at"),
                )
            }.getOrElse {
                migrationInvalid("$source is invalid")
            }
            if (connection.query("SELECT id FROM agent_profiles WHERE id = ?", listOf(default.agentId)).isEmpty()) {
                migrationInvalid("$source references a missing agent")
            }
            if (default.workspaceId != null &&
                connection.query("SELECT id FROM workspaces WHERE id = ?", listOf(default.workspaceId)).isEmpty()
            ) {
                migrationInvalid("$source references a missing workspace")
            }
        }
    }

    fun ensureDefaultAuthorityRows(connection: SqlConnection) {
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

    fun validateAuthoritySchema(connection: SqlConnection) {
        val policy = connection.query("SELECT selected_authority, dangerous_mode, policy_version FROM authority_policy WHERE id = 1")
            .singleOrNull() ?: migrationInvalid("authority_policy singleton row is missing")
        runCatching { Authority.valueOf(policy.string("selected_authority")) }
            .getOrElse { migrationInvalid("authority_policy.selected_authority is invalid") }
        runCatching { DangerousMode.valueOf(policy.string("dangerous_mode")) }
            .getOrElse { migrationInvalid("authority_policy.dangerous_mode is invalid") }
        if (policy.long("policy_version") < 0) migrationInvalid("authority_policy.policy_version is negative")

        val preferences = connection.query(
            "SELECT authority, user_intent_enabled, explicitly_configured FROM authority_preferences",
        )
        if (preferences.size != Authority.values().size) migrationInvalid("authority_preferences rows are incomplete")
        preferences.forEach { preference ->
            runCatching { Authority.valueOf(preference.string("authority")) }
                .getOrElse { migrationInvalid("authority_preferences.authority is invalid") }
            if (preference.long("user_intent_enabled") !in 0L..1L) migrationInvalid("authority_preferences.user_intent_enabled is invalid")
            if (preference.long("explicitly_configured") !in 0L..1L) migrationInvalid("authority_preferences.explicitly_configured is invalid")
        }

        connection.query("SELECT lifetime, policy_version FROM permission_grants").forEach { row ->
            runCatching { GrantLifetime.valueOf(row.string("lifetime")) }
                .getOrElse { migrationInvalid("permission_grants.lifetime is invalid") }
            if (row.long("policy_version") < 0) migrationInvalid("permission_grants.policy_version is negative")
        }

        validateCapabilityGrantLifecycle(connection)
    }

    fun validateCapabilityGrantLifecycle(connection: SqlConnection) {
        if (!MigrationLegacyCollisions.hasOwnerUniqueConstraint(connection)) {
            migrationInvalid("capability_grants owner-aware uniqueness is missing")
        }
        connection.query("SELECT grant_id, lifetime, task_id, session_id, consumed_at FROM capability_grants")
            .forEach { row ->
                val source = "capability_grants[${row.string("grant_id")}]"
                val lifetime = runCatching { GrantLifetime.valueOf(row.string("lifetime")) }
                    .getOrElse { migrationInvalid("capability grant lifetime is invalid ($source)") }
                val taskId = persistedNullableText(row, "task_id", source)
                val sessionId = persistedNullableText(row, "session_id", source)
                val consumedAt = persistedNullableText(row, "consumed_at", source)
                if (taskId?.let { !it.isSafeMigrationId() } == true) {
                    migrationInvalid("capability grant task id is invalid ($source)")
                }
                if (sessionId?.let { !it.isSafeMigrationId() } == true) {
                    migrationInvalid("capability grant session id is invalid ($source)")
                }
                if (consumedAt != null && lifetime != GrantLifetime.ONCE) {
                    migrationInvalid("only ONCE capability grants may be consumed ($source)")
                }
                when (lifetime) {
                    GrantLifetime.ONCE -> Unit
                    GrantLifetime.TASK -> if (taskId == null) migrationInvalid("TASK capability grant has no task owner ($source)")
                    GrantLifetime.SESSION -> if (sessionId == null) migrationInvalid("SESSION capability grant has no session owner ($source)")
                    GrantLifetime.PERSISTENT -> if (taskId != null || sessionId != null) {
                        migrationInvalid("PERSISTENT capability grant has an owner ($source)")
                    }
                }
            }
    }

    private fun persistedNullableText(row: SqlRow, column: String, source: String): String? {
        val raw = row.columns[column] ?: return null
        val value = raw.toString()
        val maxLength = if (column == "consumed_at") 128 else 4096
        if (value.isBlank() || value.length > maxLength) migrationInvalid("capability grant $column is invalid ($source)")
        return value
    }

    private fun requiredText(row: SqlRow, column: String): String {
        val value = row.columns[column] as? String
            ?: migrationInvalid("Persisted $column is not text")
        if (value.length > 4096) migrationInvalid("Persisted $column is too long")
        return value
    }

    private fun requiredLong(row: SqlRow, column: String, source: String): Long {
        val raw = row.columns[column] ?: migrationInvalid("$source.$column is missing")
        return when (raw) {
            is Byte, is Short, is Int, is Long -> (raw as Number).toLong()
            else -> migrationInvalid("$source.$column is not an integer")
        }
    }

    private fun requiredInt(row: SqlRow, column: String, source: String): Int {
        val value = requiredLong(row, column, source)
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            migrationInvalid("$source.$column is out of range")
        }
        return value.toInt()
    }

    fun validateSnapshotManifests(connection: SqlConnection) {
        val json = Json { ignoreUnknownKeys = false; explicitNulls = false }
        connection.query("SELECT id, binding_manifest_json, expanded_json FROM agent_snapshots").forEach { row ->
            val snapshotId = row.string("id")
            val bindingRaw = row.columns["binding_manifest_json"] as? String
                ?: migrationInvalid("Snapshot $snapshotId binding_manifest_json is not text")
            val expandedRaw = row.columns["expanded_json"] as? String
                ?: migrationInvalid("Snapshot $snapshotId expanded_json is not text")
            val binding = decodeSnapshotObject(json, bindingRaw, snapshotId, "binding_manifest_json")
            val root = if (binding.isEmpty() && expandedRaw.isNotBlank() && expandedRaw.trim() != "{}") {
                val expanded = decodeSnapshotObject(json, expandedRaw, snapshotId, "expanded_json")
                val nested = expanded["bindingManifest"]
                if (nested == null) expanded else nested as? JsonObject
                    ?: migrationInvalid("Snapshot $snapshotId expanded bindingManifest is not an object")
            } else {
                binding
            }
            if (root.isEmpty()) return@forEach
            val manifestId = (root["snapshotId"] as? JsonPrimitive)?.contentOrNull
            if (manifestId != null && manifestId != snapshotId) {
                migrationInvalid("Snapshot $snapshotId manifest id does not match its row")
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
            .getOrNull() ?: migrationInvalid("Snapshot $snapshotId $field is not a JSON object")
    }

    private val SNAPSHOT_MODEL_KEYS = listOf("chatModel", "visionModel", "embeddingModel", "rerankerModel")
    private val SNAPSHOT_PROVIDER_KEYS = listOf("provider", "visionProvider", "embeddingProvider", "rerankerProvider")

    fun validateContextCompactions(connection: SqlConnection) {
        MigrationValidation.contextCompactions(connection) { message -> migrationInvalid(message) }
    }
}
