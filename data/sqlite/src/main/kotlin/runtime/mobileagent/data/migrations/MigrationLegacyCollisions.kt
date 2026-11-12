// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data.migrations

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.SqlRow
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass

internal fun migrationInvalid(message: String): Nothing = throw AppError(
    code = ErrorCode.INVALID_CONFIG,
    userMessage = "Database schema is invalid: $message",
    retryClass = RetryClass.USER_ACTION,
    stage = "migration",
    operationId = "database-migration",
    sanitizedDetails = message,
).asException()

internal data class DetachedIndex(val oldName: String, val sql: String, val newName: String)

internal fun String.isSafeMigrationId(): Boolean =
    length in 1..240 && all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' } && first().isLetterOrDigit()

internal fun isSafeMigrationHash(value: String): Boolean =
    value.length in 1..128 && value == value.trim() && value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }

internal fun isRelativeMigrationScope(value: String): Boolean =
    value.isNotBlank() && value.length <= 4096 && !value.contains('\u0000') && !value.contains('\\') &&
        !value.startsWith('/') && !value.startsWith("//") && !value.contains(':') &&
        value.split('/').all { it.isNotBlank() && it != "." && it != ".." }

internal fun isSafeUri(value: String): Boolean =
    value.length in 1..4096 && value.startsWith("content://") &&
        value.none { it == '\u0000' || it.isWhitespace() }

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

internal fun legacyWorkspaceId(oldId: String): String {
    val normalized = oldId.trim()
    if (normalized.isSafeMigrationId()) return "legacy-$normalized"
    return "legacy-${sha256(normalized).take(32)}"
}

internal fun readString(row: SqlRow, columns: Map<String, String>, aliases: List<String>): String? =
    readRaw(row, columns, aliases)?.toString()?.takeIf { it.isNotBlank() }

internal fun readRaw(row: SqlRow, columns: Map<String, String>, aliases: List<String>): Any? {
    val actual = aliases.asSequence().map(MigrationLegacyCollisions::normalizeName).mapNotNull { columns[it] }.firstOrNull() ?: return null
    return row.columns[actual]
}

internal fun readTimestamp(
    row: SqlRow,
    columns: Map<String, String>,
    aliases: List<String>,
    source: String,
): String? {
    val raw = readRaw(row, columns, aliases) ?: return null
    val value = raw.toString()
    if (value.length > 128) migrationInvalid("legacy timestamp is invalid ($source)")
    return value.ifBlank { null }
}

internal fun readBoolean(
    row: SqlRow,
    columns: Map<String, String>,
    aliases: List<String>,
    source: String,
): Boolean? = readRaw(row, columns, aliases)?.let { parseLegacyBoolean(it, source) }

internal fun parseLegacyBoolean(raw: Any?, source: String): Boolean? {
    if (raw == null) return null
    return when (raw) {
        is Boolean -> raw
        is Number -> when (raw.toLong()) {
            0L -> false
            1L -> true
            else -> migrationInvalid("legacy boolean is invalid ($source)")
        }
        else -> when (raw.toString().trim().lowercase()) {
            "", "null" -> null
            "true", "yes", "on", "1" -> true
            "false", "no", "off", "0" -> false
            else -> migrationInvalid("legacy boolean is invalid ($source)")
        }
    }
}

internal fun readOwner(
    row: SqlRow,
    columns: Map<String, String>,
    aliases: List<String>,
    source: String,
): String? {
    val raw = readRaw(row, columns, aliases) ?: return null
    val value = raw.toString()
    if (value.isBlank()) migrationInvalid("legacy capability grant owner is blank ($source)")
    return value
}

internal fun readPositiveLong(
    row: SqlRow,
    columns: Map<String, String>,
    aliases: List<String>,
    source: String,
): Long? = readLong(row, columns, aliases, source)?.also {
    if (it <= 0L) migrationInvalid("legacy positive integer is invalid ($source)")
}

internal fun readNonNegativeLong(
    row: SqlRow,
    columns: Map<String, String>,
    aliases: List<String>,
    source: String,
): Long? = readLong(row, columns, aliases, source)?.also {
    if (it < 0L) migrationInvalid("legacy non-negative integer is invalid ($source)")
}

internal fun readLong(
    row: SqlRow,
    columns: Map<String, String>,
    aliases: List<String>,
    source: String,
): Long? {
    val raw = readRaw(row, columns, aliases) ?: return null
    val text = raw.toString().trim()
    if (text.isBlank()) return null
    return text.toLongOrNull() ?: migrationInvalid("legacy integer is invalid ($source)")
}

internal fun bool(value: Boolean): Int = if (value) 1 else 0

internal fun parseAuthority(value: String, source: String): Authority = runCatching {
    Authority.valueOf(value.uppercase())
}.getOrElse { migrationInvalid("legacy authority is invalid ($source)") }

internal fun preferenceBoolean(
    preferences: Map<String, Any?>,
    aliases: Set<String>,
    source: String,
): Boolean? {
    var result: Boolean? = null
    preferences.entries.filter { MigrationLegacyCollisions.normalizeName(it.key) in aliases }.forEach { (key, raw) ->
        val next = parseLegacyBoolean(raw, "app_prefs.${'$'}key ($source)") ?: return@forEach
        if (result != null && result != next) migrationInvalid("conflicting legacy boolean ($source)")
        result = next
    }
    return result
}

internal fun preferenceTimestamp(preferences: Map<String, Any?>, aliases: Set<String>): String? =
    preferences.entries.firstOrNull { MigrationLegacyCollisions.normalizeName(it.key) in aliases }?.let { (key, raw) ->
        raw?.toString()?.let {
            if (it.length > 128) migrationInvalid("legacy timestamp is invalid (app_prefs.${'$'}key)")
            it.ifBlank { null }
        }
    }

internal fun parseLegacyObject(raw: String): JsonObject? = runCatching {
    Json { ignoreUnknownKeys = true; explicitNulls = false }.parseToJsonElement(raw) as? JsonObject
}.getOrNull()

internal fun jsonString(obj: JsonObject, vararg names: String): String? {
    val element = names.asSequence().mapNotNull { name ->
        obj.entries.firstOrNull { MigrationLegacyCollisions.normalizeName(it.key) == MigrationLegacyCollisions.normalizeName(name) }?.value
    }.firstOrNull() ?: return null
    return (element as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

internal fun jsonHasAny(obj: JsonObject, vararg names: String): Boolean =
    obj.keys.any { key -> names.any { MigrationLegacyCollisions.normalizeName(it) == MigrationLegacyCollisions.normalizeName(key) } }

internal fun jsonLong(obj: JsonObject, vararg names: String): Long? =
    jsonString(obj, *names)?.toLongOrNull()?.also { if (it < 0L) migrationInvalid("legacy JSON version is negative") }

internal object MigrationLegacyCollisions {
    val LEGACY_NAME_COLLISIONS = listOf(
        "authority_policy", "authority_preferences", "workspaces", "capability_grants",
        "snapshot_grant_bindings", "saf_workspace_grants", "desktop_identity", "desktop_trust",
        "skill_memory_spaces", "skill_memory_entries", "approval_records", "tool_audit_details",
        "context_compactions",
    )

    fun prepareLegacyNameCollisions(connection: SqlConnection) {
        LEGACY_NAME_COLLISIONS.forEach { table ->
            if (!tableExists(connection, table)) return@forEach
            val columns = tableColumns(connection, table)
            if (hasCanonicalColumns(table, columns) &&
                (table != "capability_grants" || hasOwnerUniqueConstraint(connection))
            ) return@forEach
            if (!looksLikeLegacyControlPlaneTable(table, columns)) {
                migrationInvalid("pre-existing $table table has an unknown schema")
            }
            val legacyName = "legacy_v2_$table"
            if (tableExists(connection, legacyName)) {
                migrationInvalid("legacy table name collision for $table")
            }
            val detachedIndexes = detachIndexesForTableRename(connection, table, legacyName)
            connection.execute("ALTER TABLE $table RENAME TO $legacyName")
            restoreDetachedIndexes(connection, detachedIndexes, table, legacyName)
        }
    }

    fun detachIndexesForTableRename(
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
                migrationInvalid("legacy index name collision for $oldName")
            }
            detached += DetachedIndex(oldName, sql, newName)
            connection.execute("DROP INDEX ${quoteIdentifier(oldName)}")
        }
        return detached
    }

    fun restoreDetachedIndexes(
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

    fun identifierPattern(value: String): String =
        "(?:\"${Regex.escape(value)}\"|\\[${Regex.escape(value)}\\]|`${Regex.escape(value)}`|${Regex.escape(value)})"

    fun quoteIdentifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    fun tableExists(connection: SqlConnection, table: String): Boolean = connection.query(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
        listOf(table),
    ).isNotEmpty()

    fun tableColumns(connection: SqlConnection, table: String): Map<String, String> = connection.query(
        "PRAGMA table_info($table)",
    ).mapNotNull { row ->
        row.string("name").takeIf { it.isNotBlank() }?.let { normalizeName(it) to it }
    }.toMap()

    fun normalizeName(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

    fun hasCanonicalColumns(table: String, columns: Map<String, String>): Boolean {
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

    fun hasOwnerUniqueConstraint(connection: SqlConnection): Boolean {
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

    fun looksLikeLegacyControlPlaneTable(table: String, columns: Map<String, String>): Boolean {
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
}
