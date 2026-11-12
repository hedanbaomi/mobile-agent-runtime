// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data.migrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.SqlRow
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.GrantLifetime

internal object MigrationLegacyGrants {
    private val LEGACY_CAPABILITY_TABLES = listOf(
        "legacy_capability_grants", "capability_grants_legacy", "agent_capability_grants_legacy",
        "authority_capability_grants", "legacy_grants", "legacy_v2_capability_grants",
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

    fun migrateLegacyCapabilityGrants(
        connection: SqlConnection,
        workspaceIds: Map<String, String>,
    ) {
        LEGACY_CAPABILITY_TABLES.forEach { table ->
            if (!MigrationLegacyCollisions.tableExists(connection, table)) return@forEach
            val columns = MigrationLegacyCollisions.tableColumns(connection, table)
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
            migrationInvalid("legacy capability grant identity is invalid ($source)")
        }
        val workspaceRaw = readString(row, columns, GRANT_WORKSPACE_ALIASES)
        val workspaceId = workspaceRaw?.let { workspaceIds[it] ?: return }
        val pathScope = readString(row, columns, GRANT_PATH_ALIASES)?.also {
            if (!isRelativeMigrationScope(it)) return
        }
        val lifetime = readLifetime(row, columns, source) ?: GrantLifetime.PERSISTENT.name
        val taskId = readOwner(row, columns, GRANT_TASK_ALIASES, "$source.task_id")?.also {
            if (!it.isSafeMigrationId()) migrationInvalid("legacy capability grant task id is invalid ($source)")
        }
        val sessionId = readOwner(row, columns, GRANT_SESSION_ALIASES, "$source.session_id")?.also {
            if (!it.isSafeMigrationId()) migrationInvalid("legacy capability grant session id is invalid ($source)")
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
        if (!MigrationLegacyCollisions.tableExists(connection, "permission_grants")) return
        val columns = MigrationLegacyCollisions.tableColumns(connection, "permission_grants")
        connection.query("SELECT * FROM permission_grants").forEachIndexed { index, row ->
            val source = "permission_grants[$index]"
            val scopesRaw = readString(row, columns, PERMISSION_SCOPES_ALIASES) ?: return@forEachIndexed
            val scopes = parseLegacyObject(scopesRaw) ?: return@forEachIndexed
            val agentId = jsonString(scopes, "agentId", "agent_id")
                ?: MigrationConversationBindings.inferUniqueAgentForInstall(connection, readString(row, columns, GRANT_INSTALL_ALIASES))
                ?: return@forEachIndexed
            if (!agentId.isSafeMigrationId()) return@forEachIndexed
            val workspaceRaw = jsonString(scopes, "workspaceId", "workspace_id", "workspace")
                ?: readString(row, columns, GRANT_WORKSPACE_ALIASES)
            val workspaceId = workspaceRaw?.let { workspaceIds[it] ?: return@forEachIndexed }
            val pathScope = jsonString(scopes, "pathScope", "path_scope")
                ?: readString(row, columns, GRANT_PATH_ALIASES)
            if (pathScope?.let { !isRelativeMigrationScope(it) } == true) return@forEachIndexed
            val capabilities = parseLegacyCapabilities(readString(row, columns, PERMISSION_CAPABILITIES_ALIASES))
                ?: return@forEachIndexed
            val grantId = readString(row, columns, GRANT_ID_ALIASES) ?: return@forEachIndexed
            if (!grantId.isSafeMigrationId()) return@forEachIndexed
            val installRaw = readString(row, columns, GRANT_INSTALL_ALIASES)
            if (installRaw?.let { !it.isSafeMigrationId() } == true) return@forEachIndexed
            val packageRaw = readString(row, columns, GRANT_PACKAGE_ALIASES)
            if (packageRaw?.let { !isSafeMigrationHash(it) } == true) return@forEachIndexed
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
            if (taskId?.let { !it.isSafeMigrationId() } == true) return@forEachIndexed
            if (sessionId?.let { !it.isSafeMigrationId() } == true) return@forEachIndexed
            if (jsonHasAny(scopes, "taskId", "task_id", "ownerTaskId", "owner_task_id") && taskId == null) return@forEachIndexed
            if (jsonHasAny(scopes, "sessionId", "session_id", "ownerSessionId", "owner_session_id") && sessionId == null) return@forEachIndexed
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
            migrationInvalid("legacy capability grant lifecycle is incomplete ($source)")
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
                else -> migrationInvalid("legacy grant status is invalid ($source)")
            }
        }
        val values = listOfNotNull(revoked, active, status).distinct()
        if (values.size > 1) migrationInvalid("conflicting legacy grant lifecycle values ($source)")
        return values.singleOrNull()
    }

    private fun parseLifetime(value: String, source: String): String = runCatching {
        GrantLifetime.valueOf(value.trim().uppercase()).name
    }.getOrElse { migrationInvalid("legacy grant lifetime is invalid ($source)") }

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
}
