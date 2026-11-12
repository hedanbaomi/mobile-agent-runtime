// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data.migrations

import runtime.mobileagent.data.RootPendingDrainRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.SqlRow
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.WorkspaceBackendType

internal data class LegacyAuthorityFacts(
    var selected: Authority? = null,
    val enabled: MutableMap<Authority, Boolean> = linkedMapOf(),
    val configured: MutableMap<Authority, Boolean> = linkedMapOf(),
    var updatedAt: String? = null,
) {
    fun setSelected(value: Authority, source: String) {
        if (selected?.let { it != value } == true) migrationInvalid("conflicting legacy authority selection ($source)")
        selected = value
    }

    fun setEnabled(authority: Authority, value: Boolean, source: String) {
        if (enabled[authority]?.let { it != value } == true) migrationInvalid("conflicting legacy $authority enabled value ($source)")
        enabled[authority] = value
    }

    fun setConfigured(authority: Authority, value: Boolean, source: String) {
        if (configured[authority]?.let { it != value } == true) migrationInvalid("conflicting legacy $authority configured value ($source)")
        configured[authority] = value
    }

    fun setUpdatedAt(value: String, source: String) {
        if (updatedAt == null) updatedAt = value
    }
}

internal data class LegacyAuthorityAliases(
    val enabled: List<String>,
    val configured: List<String>,
)

internal object MigrationV2ControlPlane {
    private object WorkspaceDefaults {
        const val maxFileBytes: Long = 1L * 1024 * 1024
    }

    private val PRIVILEGED_AUTHORITIES = setOf(Authority.SHIZUKU, Authority.WIRED_ADB)

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
    private val LEGACY_SHIZUKU_ROOT_KEYS = setOf(
        "shizukuroot", "shizukuworkspaceroot", "mobileagentruntimeshizukuroot", "shizukupath", "shizukuworkspacepath",
    )
    private val LEGACY_SHIZUKU_READABLE_KEYS = setOf("shizukuworkspacereadable", "shizukureadable")
    private val LEGACY_SHIZUKU_WRITABLE_KEYS = setOf("shizukuworkspacewritable", "shizukuwritable")
    private val LEGACY_SHIZUKU_WORKSPACE_ENABLED_KEYS = setOf("shizukuworkspaceenabled", "shizukuworkspaceactive")
    private val LEGACY_SHIZUKU_CREATED_KEYS = setOf("shizukuworkspacecreatedat", "shizukurootcreatedat")
    private val LEGACY_SHIZUKU_UPDATED_KEYS = setOf("shizukuworkspaceupdatedat", "shizukurootupdatedat")
    private val PREFERENCE_SUFFIXES = setOf("enabled", "userintentenabled", "configured", "explicitlyconfigured", "enabledconfigured")

    fun backfillV2ControlPlane(connection: SqlConnection) {
        val authorityFacts = readLegacyAuthorityFacts(connection)
        applyLegacyAuthorityFacts(connection, authorityFacts)

        val workspaceIds = migrateLegacyWorkspaces(connection, authorityFacts)
        MigrationConversationBindings.migrateLegacySafWorkspaceGrants(connection, workspaceIds)
        MigrationLegacyGrants.migrateLegacyCapabilityGrants(connection, workspaceIds)
        MigrationConversationBindings.migrateLegacySnapshotBindings(connection, workspaceIds)
    }

    fun readLegacyAuthorityFacts(connection: SqlConnection): LegacyAuthorityFacts {
        val facts = LegacyAuthorityFacts()
        LEGACY_AUTHORITY_TABLES.forEach { table ->
            if (!MigrationLegacyCollisions.tableExists(connection, table)) return@forEach
            val columns = MigrationLegacyCollisions.tableColumns(connection, table)
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

        if (MigrationLegacyCollisions.tableExists(connection, "app_prefs")) {
            val preferenceRows = connection.query("SELECT key, value FROM app_prefs")
            preferenceRows.filter { row ->
                MigrationLegacyCollisions.normalizeName(row.columns["key"]?.toString().orEmpty()) in SELECTED_AUTHORITY_KEYS
            }.forEach { row ->
                val key = row.columns["key"]?.toString().orEmpty()
                val value = row.columns["value"]
                readLegacyAuthorityPreference(facts, key, value)
            }
            preferenceRows.filter { row ->
                MigrationLegacyCollisions.normalizeName(row.columns["key"]?.toString().orEmpty()) !in SELECTED_AUTHORITY_KEYS
            }.forEach { row ->
                val key = row.columns["key"]?.toString().orEmpty()
                val value = row.columns["value"]
                readLegacyAuthorityPreference(facts, key, value)
            }
        }
        return facts
    }

    fun readLegacyAuthorityPreference(facts: LegacyAuthorityFacts, key: String, raw: Any?) {
        if (RootPendingDrainRepository.isPendingDrainKey(key)) return
        val normalized = MigrationLegacyCollisions.normalizeName(key)
        if (normalized.isBlank()) return
        if (normalized in SELECTED_AUTHORITY_KEYS) {
            val value = raw?.toString()?.trim().orEmpty()
            if (value.isBlank()) return
            facts.setSelected(parseAuthority(value, "app_prefs.$key"), "app_prefs.$key")
            return
        }

        AUTHORITY_IMPORTS.forEach { (authority, _) ->
            val prefixes = (if (authority == Authority.WIRED_ADB) {
                listOf("wired_adb", "wiredadb", "adb", "authority_wired_adb", "authority_wiredadb")
            } else {
                listOf("shizuku", "authority_shizuku")
            }).map(MigrationLegacyCollisions::normalizeName)
            val prefix = prefixes.firstOrNull { normalized.startsWith(it) && normalized.removePrefix(it) in PREFERENCE_SUFFIXES }
            val suffix = prefix?.let { normalized.removePrefix(it) } ?: return@forEach
            val bool = parseLegacyBoolean(raw, "app_prefs.$key") ?: return@forEach
            when (suffix) {
                "enabled", "userintentenabled", "enabledconfigured" ->
                    facts.setEnabled(authority, bool, "app_prefs.$key")
                "configured", "explicitlyconfigured" ->
                    facts.setConfigured(authority, bool, "app_prefs.$key")
            }
        }

        if (normalized in GENERIC_ENABLED_KEYS || normalized in GENERIC_CONFIGURED_KEYS) {
            val selected = facts.selected ?: return
            val bool = parseLegacyBoolean(raw, "app_prefs.$key") ?: return
            if (normalized in GENERIC_ENABLED_KEYS) facts.setEnabled(selected, bool, "app_prefs.$key")
            else facts.setConfigured(selected, bool, "app_prefs.$key")
        }
    }

    fun applyLegacyAuthorityFacts(connection: SqlConnection, facts: LegacyAuthorityFacts) {
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
                connection.execute(
                    "UPDATE authority_preferences SET user_intent_enabled=?, explicitly_configured=?, updated_at=? WHERE authority=? AND user_intent_enabled=0 AND explicitly_configured=0",
                    listOf(if (intent) 1 else 0, if (explicit) 1 else 0, sourceTime, authority.name),
                )
            }
        }

        val selected = facts.selected
        if (selected != null) {
            if (policy == null) {
                connection.execute(
                    "INSERT OR IGNORE INTO authority_policy(id,selected_authority,dangerous_mode,policy_version,updated_at) VALUES(1,?,?,0,?)",
                    listOf(selected.name, DangerousMode.DISABLED.name, sourceTime),
                )
            } else if (policy.string("selected_authority") == Authority.NONE.name &&
                policy.string("dangerous_mode") == DangerousMode.DISABLED.name &&
                policy.long("policy_version") == 0L
            ) {
                connection.execute(
                    "UPDATE authority_policy SET selected_authority=?, updated_at=? WHERE id=1 AND selected_authority=? AND dangerous_mode=? AND policy_version=0",
                    listOf(selected.name, sourceTime, Authority.NONE.name, DangerousMode.DISABLED.name),
                )
            }
        }
    }

    fun migrateLegacyWorkspaces(
        connection: SqlConnection,
        authorityFacts: LegacyAuthorityFacts,
    ): MutableMap<String, String> {
        val mapping = linkedMapOf<String, String>()
        LEGACY_WORKSPACE_TABLES.forEach { table ->
            if (!MigrationLegacyCollisions.tableExists(connection, table)) return@forEach
            val columns = MigrationLegacyCollisions.tableColumns(connection, table)
            connection.query("SELECT * FROM $table").forEachIndexed { index, row ->
                val source = "$table[$index]"
                val oldId = readString(row, columns, WORKSPACE_ID_ALIASES)
                    ?: readString(row, columns, WORKSPACE_NAME_ALIASES)
                val root = readString(row, columns, WORKSPACE_ROOT_ALIASES)
                if (oldId.isNullOrBlank() || root.isNullOrBlank()) return@forEachIndexed
                if (root.length > 4096) migrationInvalid("legacy workspace root reference is invalid ($source)")
                val newId = legacyWorkspaceId(oldId)
                mapping[oldId] = newId

                val backend = readWorkspaceBackend(row, columns, root, source)
                val displayName = readString(row, columns, WORKSPACE_DISPLAY_ALIASES) ?: oldId
                if (displayName.length > 256 || displayName.isBlank()) migrationInvalid("legacy workspace display name is invalid ($source)")
                val readable = readBoolean(row, columns, WORKSPACE_READABLE_ALIASES, "$source.readable") ?: false
                val writable = readBoolean(row, columns, WORKSPACE_WRITABLE_ALIASES, "$source.writable") ?: false
                val enabled = readBoolean(row, columns, WORKSPACE_ENABLED_ALIASES, "$source.enabled") ?: false
                val quota = readPositiveLong(row, columns, WORKSPACE_QUOTA_ALIASES, "$source.quota_bytes")
                val maxFileBytes = readPositiveLong(row, columns, WORKSPACE_MAX_FILE_ALIASES, "$source.max_file_bytes")
                    ?: WorkspaceDefaults.maxFileBytes
                if (quota?.let { maxFileBytes > it } == true) migrationInvalid("legacy workspace file limit exceeds quota ($source)")
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

        if (MigrationLegacyCollisions.tableExists(connection, "workspaces")) {
            connection.query("SELECT id FROM workspaces").forEach { row ->
                val id = row.string("id")
                if (id.isSafeMigrationId()) mapping.putIfAbsent(id, id)
            }
        }

        if (MigrationLegacyCollisions.tableExists(connection, "app_prefs")) {
            val prefs = connection.query("SELECT key, value FROM app_prefs").associate {
                it.columns["key"]?.toString().orEmpty() to it.columns["value"]
            }
            val rootEntry = prefs.entries.firstOrNull { MigrationLegacyCollisions.normalizeName(it.key) in LEGACY_SHIZUKU_ROOT_KEYS }
            val root = rootEntry?.value?.toString()?.trim().orEmpty()
            if (root.length > 4096) migrationInvalid("legacy Shizuku workspace root reference is invalid")
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

    fun readWorkspaceBackend(
        row: SqlRow,
        columns: Map<String, String>,
        root: String,
        source: String,
    ): WorkspaceBackendType {
        val raw = readString(row, columns, WORKSPACE_BACKEND_ALIASES)?.trim()
        if (!raw.isNullOrBlank()) {
            return runCatching { WorkspaceBackendType.valueOf(raw.uppercase()) }
                .getOrElse { migrationInvalid("legacy workspace backend is invalid ($source)") }
        }
        val authority = readAuthority(row, columns, AUTHORITY_ALIASES, "$source.authority")
        return when {
            authority in PRIVILEGED_AUTHORITIES -> WorkspaceBackendType.PRIVILEGED
            root.startsWith("content://") -> WorkspaceBackendType.SAF_TREE
            else -> WorkspaceBackendType.INTERNAL
        }
    }

    fun authorityForTable(table: String): Authority? {
        val n = MigrationLegacyCollisions.normalizeName(table)
        return when {
            "shizuku" in n -> Authority.SHIZUKU
            "wiredadb" in n || "adb" in n -> Authority.WIRED_ADB
            else -> null
        }
    }

    fun readAuthority(
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
}
