// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data.migrations

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.SqlRow
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.Utc

internal object MigrationConversationBindings {
    private val LEGACY_SAF_TABLES = listOf(
        "legacy_saf_workspace_grants", "saf_grants", "workspace_saf_grants", "legacy_v2_saf_workspace_grants",
    )
    private val SAF_URI_ALIASES = listOf("uri_reference", "uriReference", "saf_uri", "safUri", "uri", "tree_uri", "treeUri")
    private val SAF_READ_ALIASES = listOf("read_granted", "readGranted", "read", "read_enabled", "readEnabled")
    private val SAF_WRITE_ALIASES = listOf("write_granted", "writeGranted", "write", "write_enabled", "writeEnabled")
    private val SAF_PERSISTED_ALIASES = listOf("persisted_flags", "persistedFlags", "grant_flags", "grantFlags", "flags")
    private val SAF_STATUS_ALIASES = listOf("status", "grant_status", "grantStatus")
    private val SAF_STATUSES = setOf("ACTIVE", "GRANT_LOST", "REVOKED")

    private val LEGACY_SNAPSHOT_TABLES = listOf(
        "legacy_snapshot_grant_bindings", "snapshot_grants", "snapshot_capability_bindings",
        "agent_snapshot_grants", "legacy_v2_snapshot_grant_bindings",
    )
    private val SNAPSHOT_ID_ALIASES = listOf("snapshot_id", "snapshotId", "snapshot")
    private val BOUND_AT_ALIASES = listOf("bound_at", "boundAt", "created_at", "createdAt")
    private val GRANT_ID_ALIASES = listOf("grant_id", "grantId", "id", "permission_id", "permissionId")
    private val GRANT_CAPABILITY_ALIASES = listOf("capability", "capability_id", "capabilityId", "permission")
    private val GRANT_WORKSPACE_ALIASES = listOf("workspace_id", "workspaceId", "workspace")
    private val GRANT_PATH_ALIASES = listOf("path_scope", "pathScope", "scope", "relative_path")
    private val GRANT_POLICY_ALIASES = listOf("policy_version", "policyVersion", "version")

    fun migrateExistingConversationWorkspaceBindings(connection: SqlConnection) {
        connection.query(
            "SELECT c.id, c.created_at, s.agent_id " +
                "FROM conversations c JOIN agent_snapshots s ON s.id = c.snapshot_id " +
                "LEFT JOIN conversation_workspace_bindings b ON b.session_id = c.id " +
                "WHERE b.session_id IS NULL ORDER BY c.id",
        ).forEach { conversation ->
            val sessionId = conversation.string("id")
            val agentId = conversation.string("agent_id")
            if (!sessionId.isSafeMigrationId() || !agentId.isSafeMigrationId()) {
                migrationInvalid("conversation workspace migration identity is invalid")
            }
            val now = Utc.nowIso()
            val candidates = connection.query(
                "SELECT DISTINCT cg.workspace_id, cg.expires_at " +
                    "FROM capability_grants cg JOIN workspaces w ON w.id = cg.workspace_id " +
                    "WHERE cg.agent_id = ? AND cg.workspace_id IS NOT NULL " +
                    "AND cg.revoked_at IS NULL AND cg.consumed_at IS NULL " +
                    "AND cg.lifetime = 'PERSISTENT' " +
                    "AND (cg.expires_at IS NULL OR cg.expires_at > ?) " +
                    "AND w.scope = 'SELECTED_DIRECTORY' AND w.enabled = 1 AND w.readable = 1 " +
                    "ORDER BY cg.workspace_id",
                listOf(agentId, now),
            ).filter { row ->
                migrationExpiryIsValid(row.columns["expires_at"], now)
            }.map { it.string("workspace_id") }.filter { it.isSafeMigrationId() }.distinct()

            val selected = when {
                candidates.size == 1 -> candidates.single()
                candidates.size > 1 -> null
                else -> migrationDefaultWorkspace(connection, agentId)
            } ?: return@forEach

            val boundAt = conversation.columns["created_at"] as? String
                ?: Utc.nowIso()
            connection.execute(
                "INSERT OR IGNORE INTO conversation_workspace_bindings(session_id,workspace_id,bound_at,revision) VALUES(?,?,?,1)",
                listOf(sessionId, selected, boundAt),
            )
        }
    }

    private fun migrationDefaultWorkspace(connection: SqlConnection, agentId: String): String? {
        val now = Utc.nowIso()
        val persisted = connection.query(
            "SELECT d.workspace_id, cg.expires_at " +
                "FROM agent_workspace_defaults d JOIN workspaces w ON w.id = d.workspace_id " +
                "JOIN capability_grants cg ON cg.agent_id = d.agent_id AND cg.workspace_id = d.workspace_id " +
                "WHERE d.agent_id = ? AND d.workspace_id IS NOT NULL " +
                "AND w.scope = 'SELECTED_DIRECTORY' AND w.enabled = 1 AND w.readable = 1 " +
                "AND cg.lifetime = 'PERSISTENT' AND cg.revoked_at IS NULL AND cg.consumed_at IS NULL " +
                "AND (cg.expires_at IS NULL OR cg.expires_at > ?)",
            listOf(agentId, now),
        ).filter { row -> migrationExpiryIsValid(row.columns["expires_at"], now) }
            .map { it.string("workspace_id") }.filter { it.isSafeMigrationId() }.distinct()
        if (persisted.size == 1) return persisted.single()
        if (persisted.size > 1) return null

        val internal = connection.query(
            "SELECT id FROM workspaces " +
                "WHERE backend_type = 'INTERNAL' AND scope = 'SELECTED_DIRECTORY' " +
                "AND enabled = 1 AND readable = 1 ORDER BY id",
        ).map { it.string("id") }.filter { it.isSafeMigrationId() }.distinct()
        return internal.singleOrNull()
    }

    private fun migrationExpiryIsValid(raw: Any?, nowIso: String): Boolean {
        if (raw == null) return true
        val value = raw as? String ?: return false
        if (value.isBlank()) return true
        val now = runCatching { Instant.parse(nowIso) }.getOrNull() ?: return false
        return runCatching { Instant.parse(value).isAfter(now) }.getOrDefault(false)
    }

    fun migrateLegacySafWorkspaceGrants(
        connection: SqlConnection,
        workspaceIds: Map<String, String>,
    ) {
        LEGACY_SAF_TABLES.forEach { table ->
            if (!MigrationLegacyCollisions.tableExists(connection, table)) return@forEach
            val columns = MigrationLegacyCollisions.tableColumns(connection, table)
            connection.query("SELECT * FROM $table").forEachIndexed { index, row ->
                val source = "$table[$index]"
                val oldWorkspaceId = readString(row, columns, listOf("id", "workspace_id", "workspaceId", "key")) ?: return@forEachIndexed
                val workspaceId = workspaceIds[oldWorkspaceId] ?: return@forEachIndexed
                val uri = readString(row, columns, SAF_URI_ALIASES)?.trim().orEmpty()
                if (!isSafeUri(uri)) return@forEachIndexed
                val read = readBoolean(row, columns, SAF_READ_ALIASES, "$source.read_granted")
                val write = readBoolean(row, columns, SAF_WRITE_ALIASES, "$source.write_granted")
                val persisted = readNonNegativeLong(row, columns, SAF_PERSISTED_ALIASES, "$source.persisted_flags")
                val status = readSafStatus(row, columns, source)
                if (read == null || write == null || persisted == null || status == null) return@forEachIndexed
                val createdAt = readTimestamp(row, columns, listOf("created_at", "createdAt"), "$source.created_at").orEmpty()
                val updatedAt = readTimestamp(row, columns, listOf("updated_at", "updatedAt", "modified_at"), "$source.updated_at") ?: createdAt
                val lostAt = readTimestamp(row, columns, listOf("lost_at", "lostAt"), "$source.lost_at")
                connection.execute(
                    "INSERT OR IGNORE INTO saf_workspace_grants(workspace_id,uri_reference,read_granted,write_granted,persisted_flags,status,created_at,lost_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    listOf(workspaceId, uri, bool(read), bool(write), persisted, status, createdAt, lostAt, updatedAt),
                )
            }
        }
    }

    private fun readSafStatus(row: SqlRow, columns: Map<String, String>, source: String): String? {
        val raw = readString(row, columns, SAF_STATUS_ALIASES)?.trim().orEmpty()
        if (raw.isBlank()) return null
        return raw.uppercase().takeIf { it in SAF_STATUSES }
            ?: migrationInvalid("legacy SAF grant status is invalid ($source)")
    }

    fun migrateLegacySnapshotBindings(
        connection: SqlConnection,
        workspaceIds: Map<String, String>,
    ) {
        LEGACY_SNAPSHOT_TABLES.forEach { table ->
            if (!MigrationLegacyCollisions.tableExists(connection, table)) return@forEach
            val columns = MigrationLegacyCollisions.tableColumns(connection, table)
            connection.query("SELECT * FROM $table").forEachIndexed { index, row ->
                val source = "$table[$index]"
                val snapshotId = readString(row, columns, SNAPSHOT_ID_ALIASES) ?: return@forEachIndexed
                val grantId = readString(row, columns, GRANT_ID_ALIASES) ?: return@forEachIndexed
                val capability = readString(row, columns, GRANT_CAPABILITY_ALIASES) ?: return@forEachIndexed
                if (!snapshotId.isSafeMigrationId() || !grantId.isSafeMigrationId() || !CapabilityId.isValid(capability)) {
                    migrationInvalid("legacy snapshot binding identity is invalid ($source)")
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
                    return@forEachIndexed
                }
                val boundAt = readTimestamp(row, columns, BOUND_AT_ALIASES, "$source.bound_at") ?: return@forEachIndexed
                connection.execute(
                    "INSERT OR IGNORE INTO snapshot_grant_bindings(snapshot_id,grant_id,capability,workspace_id,path_scope,policy_version,bound_at) VALUES(?,?,?,?,?,?,?)",
                    listOf(snapshotId, grantId, capability, workspaceId, pathScope, policyVersion, boundAt),
                )
            }
        }
        migrateManifestBindings(connection)
    }

    private fun migrateManifestBindings(connection: SqlConnection) {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        connection.query("SELECT id, binding_manifest_json, expanded_json FROM agent_snapshots").forEach { row ->
            val snapshotId = row.string("id")
            val rawValues = listOf(row.string("binding_manifest_json"), row.string("expanded_json"))
            rawValues.forEach { raw ->
                val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return@forEach
                val element = root.entries.firstOrNull { MigrationLegacyCollisions.normalizeName(it.key) in setOf("grantbindings", "snapshotgrantbindings", "capabilitygrants") }?.value
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

    fun inferUniqueAgentForInstall(connection: SqlConnection, installId: String?): String? {
        if (installId.isNullOrBlank() || !installId.isSafeMigrationId()) return null
        val candidates = connection.query("SELECT id, skill_ids FROM agent_profiles").mapNotNull { row ->
            val agentId = row.string("id")
            if (!agentId.isSafeMigrationId()) return@mapNotNull null
            val skillIds = parseLegacyStringArray(row.string("skill_ids")) ?: return@mapNotNull null
            agentId.takeIf { installId in skillIds }
        }.distinct()
        return candidates.singleOrNull()
    }

    fun parseLegacyStringArray(raw: String): List<String>? {
        val value = raw.trim()
        if (value.isBlank()) return emptyList()
        if (!value.startsWith("[")) return null
        return runCatching {
            val array = Json { ignoreUnknownKeys = false; explicitNulls = false }.parseToJsonElement(value) as? JsonArray
                ?: return@runCatching null
            array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }.getOrNull()
    }
}
