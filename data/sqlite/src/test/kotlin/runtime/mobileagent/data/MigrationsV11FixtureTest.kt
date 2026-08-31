// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.security.MessageDigest
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.GrantLifetime

/**
 * A real pre-v12 SQLite fixture.  This intentionally does not bootstrap through [Migrations]:
 * doing so would create the v12 control plane before the migration under test runs.
 */
class MigrationsV11FixtureTest {
    @Test
    fun v11FixtureMigratesToV12WithoutLegacyDataLeakageAndIsIdempotent() {
        JdbcSqlConnection().use { db ->
            createV11Fixture(db)
            val before = legacyState(db)

            assertEquals(11, db.query("SELECT version FROM schema_version").single().long("version"))
            assertTrue(db.query("SELECT name FROM sqlite_master WHERE name = 'authority_policy'").isEmpty())
            assertTrue(db.query("PRAGMA table_info(permission_grants)").none { it.string("name") == "lifetime" })
            assertEquals(LEGACY_SECRET_MARKER, db.query("SELECT ref FROM secrets").single().string("ref"))
            assertEquals(LEGACY_CONTENT_MARKER, db.query("SELECT text FROM messages").single().string("text"))

            Migrations.apply(db)

            assertEquals(Migrations.VERSION.toLong(), db.query("SELECT version FROM schema_version").single().long("version"))
            assertEquals(before.counts, legacyState(db).counts)
            assertEquals(before.hashes, legacyState(db).hashes)

            val activeGrant = db.query(
                "SELECT lifetime, policy_version, created_at, expires_at, revoked_at, revision, revoked, capabilities, scopes_json " +
                    "FROM permission_grants WHERE grant_id = 'grant-v11-active'",
            ).single()
            assertEquals("PERSISTENT", activeGrant.string("lifetime"))
            assertEquals(0, activeGrant.long("policy_version"))
            assertEquals(INSTALL_TIME, activeGrant.string("created_at"))
            assertEquals("", activeGrant.string("expires_at"))
            assertEquals("", activeGrant.string("revoked_at"))
            assertEquals(3, activeGrant.long("revision"))
            assertEquals(0, activeGrant.long("revoked"))
            assertEquals("file.read_text,file.write_text", activeGrant.string("capabilities"))
            assertEquals("{\"workspace\":\"private\"}", activeGrant.string("scopes_json"))

            val revokedGrant = db.query(
                "SELECT lifetime, policy_version, created_at, expires_at, revoked_at, revision, revoked " +
                    "FROM permission_grants WHERE grant_id = 'grant-v11-revoked'",
            ).single()
            assertEquals("PERSISTENT", revokedGrant.string("lifetime"))
            assertEquals(0, revokedGrant.long("policy_version"))
            assertEquals(INSTALL_TIME, revokedGrant.string("created_at"))
            assertEquals("", revokedGrant.string("expires_at"))
            assertEquals(INSTALL_TIME, revokedGrant.string("revoked_at"))
            assertEquals(7, revokedGrant.long("revision"))
            assertEquals(1, revokedGrant.long("revoked"))

            assertControlPlaneSchemaAndDefaults(db)
            assertNoLegacyMarkersInControlPlane(db)

            val afterFirstApply = legacyState(db)
            val controlPlaneAfterFirstApply = controlPlaneState(db)
            Migrations.apply(db)
            assertEquals(Migrations.VERSION.toLong(), db.query("SELECT version FROM schema_version").single().long("version"))
            assertEquals(afterFirstApply.counts, legacyState(db).counts)
            assertEquals(afterFirstApply.hashes, legacyState(db).hashes)
            assertEquals(controlPlaneAfterFirstApply, controlPlaneState(db))
            assertEquals(
                listOf(INSTALL_TIME, INSTALL_TIME),
                db.query("SELECT created_at FROM permission_grants ORDER BY grant_id").map { it.string("created_at") },
            )
            assertEquals(
                listOf("", INSTALL_TIME),
                db.query("SELECT revoked_at FROM permission_grants ORDER BY grant_id").map { it.string("revoked_at") },
            )
            assertEquals(1, db.query("SELECT * FROM authority_policy").size)
            assertEquals(3, db.query("SELECT * FROM authority_preferences").size)
            assertNoLegacyMarkersInControlPlane(db)
        }
    }

    @Test
    fun explicitLegacyAuthorityWorkspaceGrantAndSnapshotFactsAreProjectedWithoutPlatformTrust() {
        JdbcSqlConnection().use { db ->
            createV11Fixture(db)
            db.execute("CREATE TABLE shizuku_settings(enabled INTEGER, configured INTEGER, granted INTEGER, probe_status TEXT, updated_at TEXT)")
            db.execute(
                "INSERT INTO shizuku_settings(enabled,configured,granted,probe_status,updated_at) VALUES(?,?,?,?,?)",
                listOf(1, 1, 1, "READY", INSTALL_TIME),
            )
            // Selection and intent are explicit user facts.  `granted` and the probe result above
            // are observations; the migration must not turn them into a platform grant.
            db.execute("INSERT INTO app_prefs(key,value) VALUES(?,?)", listOf("selected_authority", "SHIZUKU"))
            db.execute("INSERT INTO app_prefs(key,value) VALUES(?,?)", listOf("shizuku_enabled", "true"))
            db.execute("INSERT INTO app_prefs(key,value) VALUES(?,?)", listOf("shizuku_configured", "true"))

            db.execute(
                "CREATE TABLE legacy_workspaces(id TEXT, name TEXT, backend_type TEXT, root_reference TEXT, readable INTEGER, writable INTEGER, enabled INTEGER, revision INTEGER, created_at TEXT, updated_at TEXT)",
            )
            db.execute(
                "INSERT INTO legacy_workspaces(id,name,backend_type,root_reference,readable,writable,enabled,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                listOf("workspace-old", "Old Shizuku workspace", "PRIVILEGED", "/data/user/0/runtime/workspace", 1, 0, 0, 4, INSTALL_TIME, INSTALL_TIME),
            )
            db.execute(
                "CREATE TABLE legacy_capability_grants(grant_id TEXT, agent_id TEXT, install_id TEXT, package_hash TEXT, capability TEXT, workspace_id TEXT, path_scope TEXT, lifetime TEXT, policy_version INTEGER, created_at TEXT, revoked INTEGER, revision INTEGER)",
            )
            db.execute(
                "INSERT INTO legacy_capability_grants(grant_id,agent_id,install_id,package_hash,capability,workspace_id,path_scope,lifetime,policy_version,created_at,revoked,revision) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                listOf("grant-old", "agent-v11", "install-v11", "package-v11", "file.read_text", "workspace-old", "notes", "PERSISTENT", 0, INSTALL_TIME, 0, 2),
            )
            db.execute(
                "CREATE TABLE legacy_snapshot_grant_bindings(snapshot_id TEXT, grant_id TEXT, capability TEXT, workspace_id TEXT, path_scope TEXT, policy_version INTEGER, bound_at TEXT)",
            )
            db.execute(
                "INSERT INTO legacy_snapshot_grant_bindings(snapshot_id,grant_id,capability,workspace_id,path_scope,policy_version,bound_at) VALUES(?,?,?,?,?,?,?)",
                listOf("snapshot-v11", "grant-old", "file.read_text", "workspace-old", "notes", 0, INSTALL_TIME),
            )

            Migrations.apply(db)

            assertEquals("SHIZUKU", db.query("SELECT selected_authority FROM authority_policy").single().string("selected_authority"))
            assertEquals("DISABLED", db.query("SELECT dangerous_mode FROM authority_policy").single().string("dangerous_mode"))
            val preference = db.query(
                "SELECT user_intent_enabled, explicitly_configured FROM authority_preferences WHERE authority = 'SHIZUKU'",
            ).single()
            assertEquals(1, preference.long("user_intent_enabled"))
            assertEquals(1, preference.long("explicitly_configured"))

            val workspace = db.query("SELECT * FROM workspaces WHERE id = 'legacy-workspace-old'").single()
            assertEquals("PRIVILEGED", workspace.string("backend_type"))
            assertEquals("/data/user/0/runtime/workspace", workspace.string("root_reference"))
            assertEquals(0L, workspace.long("enabled"))
            val grant = db.query("SELECT * FROM capability_grants WHERE grant_id = 'grant-old'").single()
            assertEquals("agent-v11", grant.string("agent_id"))
            assertEquals("legacy-workspace-old", grant.string("workspace_id"))
            assertEquals("notes", grant.string("path_scope"))
            assertEquals(2L, grant.long("revision"))
            val binding = db.query("SELECT * FROM snapshot_grant_bindings WHERE grant_id = 'grant-old'").single()
            assertEquals("snapshot-v11", binding.string("snapshot_id"))
            assertEquals("legacy-workspace-old", binding.string("workspace_id"))

            // Platform permission/probe observations do not become durable trust or SAF facts.
            assertTrue(db.query("SELECT * FROM desktop_trust").isEmpty())
            assertTrue(db.query("SELECT * FROM saf_workspace_grants").isEmpty())

            val first = controlPlaneState(db) + mapOf(
                "workspace" to tableState(db, "workspaces", listOf("id", "display_name", "backend_type", "root_reference", "readable", "writable", "quota_bytes", "max_file_bytes", "enabled", "revision", "created_at", "updated_at")).hash,
                "grant" to tableState(db, "capability_grants", listOf("grant_id", "agent_id", "skill_install_id", "package_hash", "capability", "workspace_id", "path_scope", "lifetime", "policy_version", "created_at", "expires_at", "revoked_at", "revision", "task_id", "session_id", "consumed_at")).hash,
                "binding" to tableState(db, "snapshot_grant_bindings", listOf("snapshot_id", "grant_id", "capability", "workspace_id", "path_scope", "policy_version", "bound_at")).hash,
            )
            Migrations.apply(db)
            val second = controlPlaneState(db) + mapOf(
                "workspace" to tableState(db, "workspaces", listOf("id", "display_name", "backend_type", "root_reference", "readable", "writable", "quota_bytes", "max_file_bytes", "enabled", "revision", "created_at", "updated_at")).hash,
                "grant" to tableState(db, "capability_grants", listOf("grant_id", "agent_id", "skill_install_id", "package_hash", "capability", "workspace_id", "path_scope", "lifetime", "policy_version", "created_at", "expires_at", "revoked_at", "revision", "task_id", "session_id", "consumed_at")).hash,
                "binding" to tableState(db, "snapshot_grant_bindings", listOf("snapshot_id", "grant_id", "capability", "workspace_id", "path_scope", "policy_version", "bound_at")).hash,
            )
            assertEquals(first, second)
        }
    }

    @Test
    fun malformedLegacyAuthorityBooleanRollsBackSchemaAndKeepsSourceRows() {
        JdbcSqlConnection().use { db ->
            createV11Fixture(db)
            db.execute("CREATE TABLE shizuku_settings(enabled TEXT, configured TEXT, granted INTEGER)")
            db.execute("INSERT INTO shizuku_settings(enabled,configured,granted) VALUES(?,?,?)", listOf("maybe", "true", 1))

            assertThrows(AppException::class.java) { Migrations.apply(db) }

            assertEquals(11, db.query("SELECT version FROM schema_version").single().long("version"))
            assertTrue(db.query("SELECT name FROM sqlite_master WHERE name = 'authority_policy'").isEmpty())
            assertTrue(db.query("SELECT name FROM sqlite_master WHERE name = 'workspaces'").isEmpty())
            assertEquals("maybe", db.query("SELECT enabled FROM shizuku_settings").single().string("enabled"))
            assertFalse(db.query("SELECT name FROM sqlite_master WHERE name = 'legacy_v2_shizuku_settings'").isNotEmpty())
        }
    }

    @Test
    fun v12CapabilityTableMigratesOwnersConsumptionAndUniqueScopeWithoutDataLoss() {
        JdbcSqlConnection().use { db ->
            // Start from the current catalog to keep this fixture small, then
            // replace only capability_grants with the real pre-v13 v12 shape.
            Migrations.apply(db)
            db.execute(
                "INSERT INTO workspaces(id,display_name,backend_type,root_reference,readable,writable,max_file_bytes,enabled,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                listOf("workspace-v12", "v12 workspace", "INTERNAL", "v12-root", 1, 1, 1024 * 1024, 1, 1, INSTALL_TIME, INSTALL_TIME),
            )
            db.execute("DROP VIEW workspace_acl")
            db.execute("DROP TABLE capability_grants")
            db.execute(
                "CREATE TABLE capability_grants (grant_id TEXT PRIMARY KEY, agent_id TEXT NOT NULL, skill_install_id TEXT, package_hash TEXT, capability TEXT NOT NULL, workspace_id TEXT, path_scope TEXT, lifetime TEXT NOT NULL CHECK(lifetime IN('ONCE','TASK','SESSION','PERSISTENT')), policy_version INTEGER NOT NULL CHECK(policy_version >= 0), created_at TEXT NOT NULL, expires_at TEXT, revoked_at TEXT, revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0), UNIQUE(agent_id, skill_install_id, package_hash, capability, workspace_id, path_scope))",
            )
            db.execute("CREATE INDEX idx_capability_grants_agent ON capability_grants(agent_id, revoked_at)")
            db.execute("CREATE INDEX idx_capability_grants_workspace ON capability_grants(workspace_id, revoked_at)")
            db.execute(
                "INSERT INTO capability_grants(grant_id,agent_id,skill_install_id,package_hash,capability,workspace_id,path_scope,lifetime,policy_version,created_at,revision) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                listOf("v12-persistent", "agent-v12", "install-v12", "package-v12", "file.read_text", "workspace-v12", "notes", "PERSISTENT", 2, INSTALL_TIME, 4),
            )
            db.execute(
                "CREATE VIEW workspace_acl AS SELECT grant_id, agent_id, skill_install_id, package_hash, capability, workspace_id, path_scope, lifetime, policy_version, created_at, expires_at, revoked_at, revision FROM capability_grants WHERE workspace_id IS NOT NULL AND revoked_at IS NULL",
            )
            db.execute("UPDATE schema_version SET version = 12")

            Migrations.apply(db)

            assertEquals(Migrations.VERSION.toLong(), db.query("SELECT version FROM schema_version").single().long("version"))
            val migrated = db.query("SELECT * FROM capability_grants WHERE grant_id = 'v12-persistent'").single()
            assertTrue(migrated.columns["task_id"] == null)
            assertTrue(migrated.columns["session_id"] == null)
            assertTrue(migrated.columns["consumed_at"] == null)
            assertEquals(1, db.query("SELECT * FROM legacy_v2_capability_grants WHERE grant_id = 'v12-persistent'").size)
            val indexes = db.query("PRAGMA index_list(capability_grants)").map { it.string("name") }
            assertTrue("idx_capability_grants_agent" in indexes)
            assertTrue("idx_capability_grants_workspace" in indexes)
            assertTrue(db.query("PRAGMA index_list(legacy_v2_capability_grants)").any { it.string("name") == "legacy_v2_idx_capability_grants_agent" })

            val repository = CapabilityGrantRepository(db) { FIXTURE_TIME }
            val taskA = repository.save(
                CapabilityGrant(
                    grantId = "task-owner-a",
                    agentId = "agent-v12",
                    capability = CapabilityId(CapabilityId.FILE_READ_TEXT),
                    workspaceId = "workspace-v12",
                    pathScope = "notes",
                    lifetime = GrantLifetime.TASK,
                    policyVersion = 2,
                    createdAt = FIXTURE_TIME,
                    taskId = "task-a",
                ),
            )
            val taskB = repository.save(taskA.copy(grantId = "task-owner-b", taskId = "task-b"))
            assertEquals(setOf("task-a", "task-b"), db.query(
                "SELECT task_id FROM capability_grants WHERE agent_id = 'agent-v12' AND lifetime = 'TASK'",
            ).map { it.string("task_id") }.toSet())

            val once = repository.save(
                CapabilityGrant(
                    grantId = "once-owner",
                    agentId = "agent-v12",
                    capability = CapabilityId(CapabilityId.FILE_READ_TEXT),
                    workspaceId = "workspace-v12",
                    pathScope = "once",
                    lifetime = GrantLifetime.ONCE,
                    policyVersion = 2,
                    createdAt = FIXTURE_TIME,
                    taskId = "task-a",
                ),
            )
            assertTrue(repository.workspaceAcl("workspace-v12").any { it.grantId == once.grantId })
            val consumed = repository.consumeOnce(once.grantId, once.revision, taskIdentity = "task-a", consumedAt = FIXTURE_TIME)
            assertEquals(once.revision + 1, consumed!!.revision)
            assertEquals(FIXTURE_TIME, db.query("SELECT consumed_at FROM capability_grants WHERE grant_id = 'once-owner'").single().string("consumed_at"))
            assertTrue(repository.workspaceAcl("workspace-v12").none { it.grantId == once.grantId })

            // The database boundary remains fail-closed even for callers which
            // bypass the domain constructor.
            assertThrows(Exception::class.java) {
                db.execute(
                    "INSERT INTO capability_grants(grant_id,agent_id,capability,lifetime,policy_version,created_at,revision,consumed_at) VALUES(?,?,?,?,?,?,?,?)",
                    listOf("invalid-consumed", "agent-v12", CapabilityId.FILE_READ_TEXT, "TASK", 2, FIXTURE_TIME, 1, FIXTURE_TIME),
                )
            }
            assertEquals(1, db.query("SELECT * FROM capability_grants WHERE grant_id = 'v12-persistent'").size)
            Migrations.apply(db)
            assertEquals(1, db.query("SELECT * FROM capability_grants WHERE grant_id = 'v12-persistent'").size)
            assertEquals(1, db.query("SELECT * FROM capability_grants WHERE grant_id = 'once-owner' AND consumed_at IS NOT NULL").size)
            assertEquals(taskB.taskId, db.query("SELECT task_id FROM capability_grants WHERE grant_id = 'task-owner-b'").single().string("task_id"))
        }
    }

    @Test
    fun v12ScopedGrantWithoutOwnerRollsBackAndLeavesLegacyTableUntouched() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            db.execute("DROP VIEW workspace_acl")
            db.execute("DROP TABLE capability_grants")
            db.execute(
                "CREATE TABLE capability_grants (grant_id TEXT PRIMARY KEY, agent_id TEXT NOT NULL, capability TEXT NOT NULL, workspace_id TEXT, path_scope TEXT, lifetime TEXT NOT NULL CHECK(lifetime IN('ONCE','TASK','SESSION','PERSISTENT')), policy_version INTEGER NOT NULL CHECK(policy_version >= 0), created_at TEXT NOT NULL, expires_at TEXT, revoked_at TEXT, revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0), UNIQUE(agent_id, capability))",
            )
            db.execute(
                "INSERT INTO capability_grants(grant_id,agent_id,capability,lifetime,policy_version,created_at,revision) VALUES(?,?,?,?,?,?,?)",
                listOf("bad-task-owner", "agent-v12", "file.read_text", "TASK", 0, INSTALL_TIME, 1),
            )
            db.execute(
                "CREATE VIEW workspace_acl AS SELECT grant_id, agent_id, capability, workspace_id, path_scope, lifetime, policy_version, created_at, expires_at, revoked_at, revision FROM capability_grants WHERE workspace_id IS NOT NULL AND revoked_at IS NULL",
            )
            db.execute("UPDATE schema_version SET version = 12")

            assertThrows(AppException::class.java) { Migrations.apply(db) }

            assertEquals(12L, db.query("SELECT version FROM schema_version").single().long("version"))
            assertEquals(1, db.query("SELECT * FROM capability_grants WHERE grant_id = 'bad-task-owner'").size)
            assertTrue(db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'legacy_v2_capability_grants'").isEmpty())
            assertTrue(db.query("SELECT name FROM sqlite_master WHERE type = 'view' AND name = 'workspace_acl'").isNotEmpty())
            assertTrue(db.query("PRAGMA table_info(capability_grants)").none { it.string("name") == "task_id" })
        }
    }

    private fun assertControlPlaneSchemaAndDefaults(db: SqlConnection) {
        val expectedTables = listOf(
            "authority_policy", "authority_preferences", "workspaces", "capability_grants", "workspace_acl",
            "snapshot_grant_bindings", "saf_workspace_grants", "desktop_identity", "desktop_trust",
            "skill_memory_spaces", "skill_memory_entries", "approval_records", "tool_audit_details",
        )
        expectedTables.forEach { name ->
            assertEquals(1, db.query("SELECT name FROM sqlite_master WHERE name = ?", listOf(name)).size, name)
        }
        assertEquals("table", db.query("SELECT type FROM sqlite_master WHERE name = 'authority_policy'").single().string("type"))
        assertEquals("view", db.query("SELECT type FROM sqlite_master WHERE name = 'workspace_acl'").single().string("type"))
        assertEquals(
            listOf("NONE", "DISABLED", 0L),
            db.query("SELECT selected_authority, dangerous_mode, policy_version FROM authority_policy").single().let {
                listOf(it.string("selected_authority"), it.string("dangerous_mode"), it.long("policy_version"))
            },
        )
        assertEquals(
            listOf("NONE", "SHIZUKU", "WIRED_ADB"),
            db.query("SELECT authority FROM authority_preferences ORDER BY authority").map { it.string("authority") },
        )
        assertTrue(db.query("SELECT * FROM authority_preferences WHERE user_intent_enabled <> 0 OR explicitly_configured <> 0").isEmpty())
        assertTrue(db.query("SELECT * FROM capability_grants").isEmpty())
        assertTrue(db.query("SELECT * FROM workspace_acl").isEmpty())
        assertTrue(db.query("SELECT * FROM approval_records").isEmpty())
        assertTrue(db.query("SELECT * FROM tool_audit_details").isEmpty())

        assertTrue(db.query("PRAGMA table_info(skill_memory_entries)").none { it.string("name") in setOf("content", "storage_path") })
        assertTrue(db.query("PRAGMA table_info(approval_records)").none { it.string("name") in setOf("command", "cwd", "stdout", "stderr") })
        assertTrue(db.query("PRAGMA table_info(tool_audit_details)").none { it.string("name") in setOf("command", "cwd", "stdout", "stderr", "command_preview") })
    }

    private fun assertNoLegacyMarkersInControlPlane(db: SqlConnection) {
        val controlPlane = listOf(
            "authority_policy", "authority_preferences", "workspaces", "capability_grants", "workspace_acl",
            "snapshot_grant_bindings", "saf_workspace_grants", "desktop_identity", "desktop_trust",
            "skill_memory_spaces", "skill_memory_entries", "approval_records", "tool_audit_details",
        )
        controlPlane.forEach { table ->
            db.query("SELECT * FROM $table").forEach { row ->
                assertTrue(row.columns.values.none { value -> value?.toString()?.contains(LEGACY_SECRET_MARKER) == true }, table)
                assertTrue(row.columns.values.none { value -> value?.toString()?.contains(LEGACY_CONTENT_MARKER) == true }, table)
            }
        }
    }

    private fun legacyState(db: SqlConnection): LegacyState {
        val counts = linkedMapOf<String, Long>()
        val hashes = linkedMapOf<String, String>()
        LEGACY_COLUMNS.forEach { (table, columns) ->
            val state = tableState(db, table, columns)
            counts[table] = state.count
            hashes[table] = state.hash
        }
        return LegacyState(counts, hashes)
    }

    private fun controlPlaneState(db: SqlConnection): Map<String, String> = linkedMapOf(
        "authority_policy" to tableState(
            db,
            "authority_policy",
            listOf("id", "selected_authority", "dangerous_mode", "policy_version", "updated_at"),
        ).hash,
        "authority_preferences" to tableState(
            db,
            "authority_preferences",
            listOf("authority", "user_intent_enabled", "explicitly_configured", "updated_at"),
        ).hash,
        "permission_grants" to tableState(
            db,
            "permission_grants",
            listOf("grant_id", "install_id", "package_hash", "capabilities", "revision", "revoked", "scopes_json", "lifetime", "policy_version", "created_at", "expires_at", "revoked_at"),
        ).hash,
    )

    private fun tableState(db: SqlConnection, table: String, columns: List<String>): TableState {
        val rows = db.query(
            "SELECT ${columns.joinToString(",")} FROM $table ORDER BY ${columns.first()}",
        )
        val canonical = rows.joinToString("\n") { row ->
            columns.joinToString("|") { column -> canonicalValue(row.columns[column]) }
        }
        return TableState(rows.size.toLong(), sha256(canonical))
    }

    private fun createV11Fixture(db: SqlConnection) {
        V11_SCHEMA.forEach { sql -> db.execute(sql) }
        db.execute("INSERT INTO schema_version(version) VALUES (11)")
        db.execute(
            "INSERT INTO provider_profiles(id,name,api_format,base_url,header_secret_refs,non_secret_headers,secret_ref,revision) VALUES(?,?,?,?,?,?,?,?)",
            listOf("provider-v11", "Legacy provider", "OPENAI_COMPATIBLE", "https://legacy.example.invalid/v1", "{}", "{}", LEGACY_SECRET_MARKER, 4),
        )
        db.execute(
            "INSERT INTO model_profiles(id,provider_id,role,model_id,capabilities,parameter_schema_json,parameters_json,context_limit,output_limit,revision,endpoint_json) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                "model-v11", "provider-v11", "CHAT", "legacy-chat", "[\"stream\"]", "{}", "{}", 8192, 1024, 2,
                "{\"operations\":[\"CHAT\"],\"inputModalities\":[\"TEXT\"],\"features\":[\"STREAMING\"],\"verification\":\"USER_DECLARED\"}",
            ),
        )
        db.execute(
            "INSERT INTO prompt_revisions(id,agent_id,parent_revision_id,template,allowed_variables,created_at) VALUES(?,?,?,?,?,?)",
            listOf("prompt-v11", "agent-v11", null, "Legacy prompt", "[]", INSTALL_TIME),
        )
        db.execute(
            "INSERT INTO agent_profiles(id,name,prompt_revision_id,chat_profile_id,vision_profile_id,embedding_profile_id,reranker_profile_id,knowledge_base_ids,skill_ids,retrieval_mode,revision,parameter_overrides_json,context_policy_json,permission_settings_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf("agent-v11", "Legacy agent", "prompt-v11", "model-v11", null, null, null, "[]", "[\"skill-v11\"]", "RRF", 5, "{}", "{}", "{}"),
        )
        db.execute(
            "INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at,provider_id,chat_model_revision,vision_model_id,vision_model_revision,embedding_model_id,embedding_model_revision,reranker_model_id,reranker_model_revision,parameter_overrides_json,context_policy_json,permission_settings_json,binding_manifest_json,expanded_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf("snapshot-v11", 11, "agent-v11", "prompt-v11", "model-v11", 4, "[\"kb-v11\"]", "[\"skill-v11\"]", INSTALL_TIME, "provider-v11", 2, null, null, null, null, null, null, "{}", "{}", "{}", "{}", "{}"),
        )
        db.execute(
            "INSERT INTO conversations(id,snapshot_id,title,created_at,updated_at) VALUES(?,?,?,?,?)",
            listOf("conversation-v11", "snapshot-v11", "Legacy conversation", INSTALL_TIME, INSTALL_TIME),
        )
        db.execute(
            "INSERT INTO messages(id,conversation_id,parent_message_id,role,text,status,created_at,parts_json,metadata_json) VALUES(?,?,?,?,?,?,?,?,?)",
            listOf("message-v11", "conversation-v11", null, "user", LEGACY_CONTENT_MARKER, "COMPLETED", INSTALL_TIME, "[]", "{}"),
        )
        db.execute(
            "INSERT INTO message_parts(id,message_id,ordinal,part_type,payload_json) VALUES(?,?,?,?,?)",
            listOf("part-v11", "message-v11", 0, "TEXT", "{\"text\":\"$LEGACY_CONTENT_MARKER\"}"),
        )
        db.execute(
            "INSERT INTO knowledge_bases(id,name,active_generation_id,embedding_space_id,created_at,deleted_at) VALUES(?,?,?,?,?,?)",
            listOf("kb-v11", "Legacy KB", null, "space-v11", INSTALL_TIME, null),
        )
        db.execute(
            "INSERT INTO embedding_query_attempts(kb_id,space_id,query_hash,error,updated_at) VALUES(?,?,?,?,?)",
            listOf("kb-v11", "space-v11", "query-hash-v11", "UNKNOWN", INSTALL_TIME),
        )
        db.execute(
            "INSERT INTO blobs(hash,byte_length,media_type,local_ref,ref_count) VALUES(?,?,?,?,?)",
            listOf("blob-v11", 4, "text/plain", "legacy-ref-v11", 1),
        )
        db.execute(
            "INSERT INTO documents(id,kb_id,blob_hash,display_name,format,active_version_id,deleted_at) VALUES(?,?,?,?,?,?,?)",
            listOf("document-v11", "kb-v11", "blob-v11", "legacy.txt", "TXT", null, null),
        )
        db.execute(
            "INSERT INTO chunks(id,document_version_id,ordinal,text,content_hash,source_span,asset_ids,page) VALUES(?,?,?,?,?,?,?,?)",
            listOf("chunk-v11", "version-v11", 0, LEGACY_CONTENT_MARKER, "content-hash-v11", "1:1", null, 1),
        )
        db.execute(
            "INSERT INTO embeddings(chunk_id,space_id,vector_blob,content_hash) VALUES(?,?,?,?)",
            listOf("chunk-v11", "space-v11", byteArrayOf(1, 2, 3), "content-hash-v11"),
        )
        db.execute(
            "INSERT INTO secrets(ref,ciphertext,created_at,status,retired_at) VALUES(?,?,?,?,?)",
            listOf(LEGACY_SECRET_MARKER, byteArrayOf(9, 8, 7, 6), INSTALL_TIME, "ACTIVE", null),
        )
        db.execute("INSERT INTO app_prefs(key,value) VALUES(?,?)", listOf("theme", "light"))
        db.execute(
            "INSERT INTO announcement_state(announcement_id,revision,read_at,displayed_at,dismissed_at,acknowledged_at) VALUES(?,?,?,?,?,?)",
            listOf("announcement-v11", 1, INSTALL_TIME, INSTALL_TIME, null, null),
        )
        db.execute(
            "INSERT INTO audit_events(id,run_id,created_at,component,action,result,error_code,summary,input_bytes,output_bytes,input_tokens,output_tokens,metadata_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf("audit-v11", null, INSTALL_TIME, "migration-fixture", "read", "OK", null, "legacy audit", 1, 2, 3, 4, "{}"),
        )
        db.execute(
            "INSERT INTO skill_packages(package_hash,id,name,version,license_id,classification,manifest_json,skill_markdown,reasons,created_at,package_bytes,source_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf("package-v11", "skill-v11", "Legacy skill", "1.0.0", "AGPL-3.0-only", "A", "{}", "# Legacy", "fixture", INSTALL_TIME, byteArrayOf(1), "source-v11"),
        )
        db.execute(
            "INSERT INTO skill_installs(install_id,package_hash,enabled,created_at) VALUES(?,?,?,?)",
            listOf("install-v11", "package-v11", 1, INSTALL_TIME),
        )
        db.execute(
            "INSERT INTO permission_grants(grant_id,install_id,package_hash,capabilities,revision,revoked,scopes_json) VALUES(?,?,?,?,?,?,?)",
            listOf("grant-v11-active", "install-v11", "package-v11", "file.read_text,file.write_text", 3, 0, "{\"workspace\":\"private\"}"),
        )
        db.execute(
            "INSERT INTO permission_grants(grant_id,install_id,package_hash,capabilities,revision,revoked,scopes_json) VALUES(?,?,?,?,?,?,?)",
            listOf("grant-v11-revoked", "install-v11", "package-v11", "shell.execute", 7, 1, "{\"workspace\":\"private\"}"),
        )
        db.execute(
            "INSERT INTO skill_invocations(invocation_id,run_id,package_hash,grant_revision,state,created_at) VALUES(?,?,?,?,?,?)",
            listOf("invocation-v11", null, "package-v11", 3, "COMPLETED", INSTALL_TIME),
        )
    }

    private fun canonicalValue(value: Any?): String = when (value) {
        null -> "<NULL>"
        is ByteArray -> "b64:${Base64.getEncoder().encodeToString(value)}"
        else -> value.toString()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class LegacyState(val counts: Map<String, Long>, val hashes: Map<String, String>)
    private data class TableState(val count: Long, val hash: String)

    private companion object {
        const val INSTALL_TIME = "2026-08-29T09:00:00Z"
        const val FIXTURE_TIME = "2026-08-31T00:00:00Z"
        const val LEGACY_SECRET_MARKER = "legacy-secret-marker-v11"
        const val LEGACY_CONTENT_MARKER = "legacy-content-marker-v11"

        val LEGACY_COLUMNS = linkedMapOf(
            "provider_profiles" to listOf("id", "name", "api_format", "base_url", "header_secret_refs", "non_secret_headers", "secret_ref", "revision"),
            "model_profiles" to listOf("id", "provider_id", "role", "model_id", "capabilities", "parameter_schema_json", "parameters_json", "context_limit", "output_limit", "revision", "endpoint_json"),
            "agent_profiles" to listOf("id", "name", "prompt_revision_id", "chat_profile_id", "vision_profile_id", "embedding_profile_id", "reranker_profile_id", "knowledge_base_ids", "skill_ids", "retrieval_mode", "revision", "parameter_overrides_json", "context_policy_json", "permission_settings_json"),
            "prompt_revisions" to listOf("id", "agent_id", "parent_revision_id", "template", "allowed_variables", "created_at"),
            "agent_snapshots" to listOf("id", "schema_version", "agent_id", "prompt_revision_id", "chat_model_id", "provider_revision", "knowledge_base_ids", "skill_ids", "created_at", "provider_id", "chat_model_revision", "vision_model_id", "vision_model_revision", "embedding_model_id", "embedding_model_revision", "reranker_model_id", "reranker_model_revision", "parameter_overrides_json", "context_policy_json", "permission_settings_json", "binding_manifest_json", "expanded_json"),
            "conversations" to listOf("id", "snapshot_id", "title", "created_at", "updated_at"),
            "messages" to listOf("id", "conversation_id", "parent_message_id", "role", "text", "status", "created_at", "parts_json", "metadata_json"),
            "message_parts" to listOf("id", "message_id", "ordinal", "part_type", "payload_json"),
            "knowledge_bases" to listOf("id", "name", "active_generation_id", "embedding_space_id", "created_at", "deleted_at"),
            "embedding_query_attempts" to listOf("kb_id", "space_id", "query_hash", "retry_authorized", "error", "updated_at"),
            "blobs" to listOf("hash", "byte_length", "media_type", "local_ref", "ref_count"),
            "documents" to listOf("id", "kb_id", "blob_hash", "display_name", "format", "active_version_id", "deleted_at"),
            "chunks" to listOf("id", "document_version_id", "ordinal", "text", "content_hash", "source_span", "asset_ids", "page"),
            "embeddings" to listOf("chunk_id", "space_id", "vector_blob", "content_hash"),
            "secrets" to listOf("ref", "ciphertext", "created_at", "status", "retired_at"),
            "app_prefs" to listOf("key", "value"),
            "announcement_state" to listOf("announcement_id", "revision", "read_at", "displayed_at", "dismissed_at", "acknowledged_at"),
            "audit_events" to listOf("id", "run_id", "created_at", "component", "action", "result", "error_code", "summary", "input_bytes", "output_bytes", "input_tokens", "output_tokens", "metadata_json"),
            "skill_packages" to listOf("package_hash", "id", "name", "version", "license_id", "classification", "manifest_json", "skill_markdown", "reasons", "created_at", "package_bytes", "source_hash"),
            "skill_installs" to listOf("install_id", "package_hash", "enabled", "created_at"),
            "permission_grants" to listOf("grant_id", "install_id", "package_hash", "capabilities", "revision", "revoked", "scopes_json"),
            "skill_invocations" to listOf("invocation_id", "run_id", "package_hash", "grant_revision", "state", "created_at"),
        )

        val V11_SCHEMA = listOf(
            "CREATE TABLE schema_version(version INTEGER NOT NULL PRIMARY KEY)",
            "CREATE TABLE provider_profiles(id TEXT PRIMARY KEY, name TEXT NOT NULL, api_format TEXT NOT NULL, base_url TEXT NOT NULL, header_secret_refs TEXT NOT NULL, non_secret_headers TEXT NOT NULL, secret_ref TEXT NOT NULL, revision INTEGER NOT NULL)",
            "CREATE TABLE model_profiles(id TEXT PRIMARY KEY, provider_id TEXT NOT NULL, role TEXT NOT NULL, model_id TEXT NOT NULL, capabilities TEXT NOT NULL, parameter_schema_json TEXT NOT NULL, parameters_json TEXT NOT NULL DEFAULT '{}', context_limit INTEGER NOT NULL, output_limit INTEGER NOT NULL, revision INTEGER NOT NULL, endpoint_json TEXT NOT NULL DEFAULT '{}', FOREIGN KEY(provider_id) REFERENCES provider_profiles(id))",
            "CREATE TABLE agent_profiles(id TEXT PRIMARY KEY, name TEXT NOT NULL, prompt_revision_id TEXT NOT NULL, chat_profile_id TEXT NOT NULL, vision_profile_id TEXT, embedding_profile_id TEXT, reranker_profile_id TEXT, knowledge_base_ids TEXT NOT NULL, skill_ids TEXT NOT NULL, retrieval_mode TEXT NOT NULL, revision INTEGER NOT NULL, parameter_overrides_json TEXT NOT NULL DEFAULT '{}', context_policy_json TEXT NOT NULL DEFAULT '{}', permission_settings_json TEXT NOT NULL DEFAULT '{}')",
            "CREATE TABLE prompt_revisions(id TEXT PRIMARY KEY, agent_id TEXT NOT NULL, parent_revision_id TEXT, template TEXT NOT NULL, allowed_variables TEXT NOT NULL, created_at TEXT NOT NULL)",
            "CREATE TABLE conversations(id TEXT PRIMARY KEY, snapshot_id TEXT NOT NULL, title TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
            "CREATE TABLE agent_snapshots(id TEXT PRIMARY KEY, schema_version INTEGER NOT NULL, agent_id TEXT NOT NULL, prompt_revision_id TEXT NOT NULL, chat_model_id TEXT NOT NULL, provider_revision INTEGER NOT NULL, knowledge_base_ids TEXT NOT NULL, skill_ids TEXT NOT NULL, created_at TEXT NOT NULL, provider_id TEXT NOT NULL DEFAULT '', chat_model_revision INTEGER NOT NULL DEFAULT 0, vision_model_id TEXT, vision_model_revision INTEGER, embedding_model_id TEXT, embedding_model_revision INTEGER, reranker_model_id TEXT, reranker_model_revision INTEGER, parameter_overrides_json TEXT NOT NULL DEFAULT '{}', context_policy_json TEXT NOT NULL DEFAULT '{}', permission_settings_json TEXT NOT NULL DEFAULT '{}', binding_manifest_json TEXT NOT NULL DEFAULT '{}', expanded_json TEXT NOT NULL DEFAULT '{}')",
            "CREATE TABLE messages(id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, parent_message_id TEXT, role TEXT NOT NULL, text TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL, parts_json TEXT NOT NULL DEFAULT '[]', metadata_json TEXT NOT NULL DEFAULT '{}', FOREIGN KEY(conversation_id) REFERENCES conversations(id))",
            "CREATE TABLE message_parts(id TEXT PRIMARY KEY, message_id TEXT NOT NULL, ordinal INTEGER NOT NULL, part_type TEXT NOT NULL, payload_json TEXT NOT NULL, UNIQUE(message_id, ordinal), FOREIGN KEY(message_id) REFERENCES messages(id))",
            "CREATE TABLE knowledge_bases(id TEXT PRIMARY KEY, name TEXT NOT NULL, active_generation_id TEXT, embedding_space_id TEXT, created_at TEXT NOT NULL, deleted_at TEXT)",
            "CREATE TABLE embedding_query_attempts(kb_id TEXT NOT NULL, space_id TEXT NOT NULL, query_hash TEXT NOT NULL, retry_authorized INTEGER NOT NULL DEFAULT 0 CHECK(retry_authorized IN(0,1)), error TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY(kb_id,space_id,query_hash), FOREIGN KEY(kb_id) REFERENCES knowledge_bases(id))",
            "CREATE TABLE blobs(hash TEXT PRIMARY KEY, byte_length INTEGER NOT NULL, media_type TEXT NOT NULL, local_ref TEXT NOT NULL, ref_count INTEGER NOT NULL)",
            "CREATE TABLE documents(id TEXT PRIMARY KEY, kb_id TEXT NOT NULL, blob_hash TEXT NOT NULL, display_name TEXT NOT NULL, format TEXT NOT NULL, active_version_id TEXT, deleted_at TEXT, UNIQUE(kb_id, blob_hash), FOREIGN KEY(kb_id) REFERENCES knowledge_bases(id))",
            "CREATE TABLE chunks(id TEXT PRIMARY KEY, document_version_id TEXT NOT NULL, ordinal INTEGER NOT NULL, text TEXT NOT NULL, content_hash TEXT NOT NULL, source_span TEXT, asset_ids TEXT, page INTEGER, UNIQUE(document_version_id, ordinal))",
            "CREATE VIRTUAL TABLE chunks_fts USING fts5(text, content='chunks', content_rowid='rowid')",
            "CREATE TABLE embeddings(chunk_id TEXT NOT NULL, space_id TEXT NOT NULL, vector_blob BLOB NOT NULL, content_hash TEXT NOT NULL, PRIMARY KEY(chunk_id, space_id))",
            "CREATE TABLE secrets(ref TEXT PRIMARY KEY, ciphertext BLOB, created_at TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'ACTIVE', retired_at TEXT)",
            "CREATE TABLE announcement_state(announcement_id TEXT NOT NULL, revision INTEGER NOT NULL, read_at TEXT, displayed_at TEXT, dismissed_at TEXT, acknowledged_at TEXT, PRIMARY KEY(announcement_id, revision))",
            "CREATE TABLE app_prefs(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            "CREATE TABLE announcement_feed_cache(cache_key TEXT PRIMARY KEY, etag TEXT NOT NULL, envelope_json TEXT NOT NULL, payload_json TEXT NOT NULL, feed_version INTEGER NOT NULL, issued_at TEXT NOT NULL, expires_at TEXT NOT NULL, fetched_at TEXT NOT NULL, last_attempt_at TEXT NOT NULL)",
            "CREATE TABLE announcement_items(announcement_id TEXT NOT NULL, revision INTEGER NOT NULL, item_json TEXT NOT NULL, withdrawn INTEGER NOT NULL, active INTEGER NOT NULL, PRIMARY KEY(announcement_id, revision))",
            "CREATE TABLE audit_events(id TEXT PRIMARY KEY, run_id TEXT, created_at TEXT NOT NULL, component TEXT NOT NULL, action TEXT NOT NULL, result TEXT NOT NULL, error_code TEXT, summary TEXT NOT NULL, input_bytes INTEGER NOT NULL DEFAULT 0, output_bytes INTEGER NOT NULL DEFAULT 0, input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0, metadata_json TEXT NOT NULL DEFAULT '{}')",
            "CREATE TABLE import_jobs(id TEXT PRIMARY KEY, kb_id TEXT NOT NULL, document_id TEXT NOT NULL, display_name TEXT NOT NULL, stage TEXT NOT NULL, has_images INTEGER NOT NULL, error TEXT, updated_at TEXT NOT NULL, vision_consent INTEGER NOT NULL DEFAULT 0, embedding_is_api INTEGER NOT NULL DEFAULT 0, embedding_consent INTEGER NOT NULL DEFAULT 0, vision_binding_json TEXT, batch_id TEXT)",
            "CREATE TABLE import_batches(id TEXT PRIMARY KEY, kb_id TEXT NOT NULL, generation_id TEXT, kind TEXT NOT NULL, display_name TEXT NOT NULL, state TEXT NOT NULL, total_items INTEGER NOT NULL DEFAULT 0, copied INTEGER NOT NULL DEFAULT 0, processing INTEGER NOT NULL DEFAULT 0, waiting INTEGER NOT NULL DEFAULT 0, failed INTEGER NOT NULL DEFAULT 0, error TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, FOREIGN KEY(kb_id) REFERENCES knowledge_bases(id))",
            "CREATE TABLE import_items(id TEXT PRIMARY KEY, batch_id TEXT NOT NULL, item_key TEXT NOT NULL, relative_path TEXT NOT NULL, job_id TEXT, kind TEXT NOT NULL, state TEXT NOT NULL, attempt_count INTEGER NOT NULL DEFAULT 0, error TEXT, UNIQUE(batch_id, item_key), FOREIGN KEY(batch_id) REFERENCES import_batches(id))",
            "CREATE TABLE consent_tickets(id TEXT PRIMARY KEY, kind TEXT NOT NULL CHECK(kind IN('VISION','API_EMBEDDING','QUERY_RETRY')), job_id TEXT, kb_id TEXT NOT NULL, fingerprint TEXT NOT NULL, consumed INTEGER NOT NULL DEFAULT 0 CHECK(consumed IN(0,1)), created_at TEXT NOT NULL)",
            "CREATE TABLE capability_probes(id TEXT PRIMARY KEY, provider_id TEXT NOT NULL, model_id TEXT NOT NULL, provider_revision INTEGER NOT NULL, verification TEXT NOT NULL, tools_summary TEXT NOT NULL, images_summary TEXT NOT NULL, source TEXT NOT NULL, probed_at TEXT NOT NULL)",
            "CREATE TABLE document_versions(id TEXT PRIMARY KEY, document_id TEXT NOT NULL, parser_fingerprint TEXT NOT NULL, content_hash TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL)",
            "CREATE TABLE embedding_operations(token TEXT PRIMARY KEY, kind TEXT NOT NULL CHECK(kind IN('IMPORT','REBUILD','REBIND')), kb_id TEXT NOT NULL, job_id TEXT, document_id TEXT, document_version_id TEXT, space_id TEXT NOT NULL, input_manifest_hash TEXT NOT NULL, binding_fingerprint TEXT NOT NULL, consent_fingerprint TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN('PREPARED','DISPATCHED','CACHE_READY','PUBLISHED','FAILED','CANCELLED','ABORTED','UNKNOWN')), cancel_requested INTEGER NOT NULL DEFAULT 0 CHECK(cancel_requested IN(0,1)), error TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, FOREIGN KEY(kb_id) REFERENCES knowledge_bases(id), FOREIGN KEY(job_id) REFERENCES import_jobs(id), FOREIGN KEY(document_id) REFERENCES documents(id), FOREIGN KEY(document_version_id) REFERENCES document_versions(id))",
            "CREATE TABLE embedding_query_vectors(space_id TEXT NOT NULL, query_hash TEXT NOT NULL, vector_blob BLOB NOT NULL, dimension INTEGER NOT NULL CHECK(dimension > 0), created_at TEXT NOT NULL, PRIMARY KEY(space_id,query_hash))",
            "CREATE TABLE index_generations(id TEXT PRIMARY KEY, kb_id TEXT NOT NULL, space_id TEXT NOT NULL, manifest_hash TEXT NOT NULL, state TEXT NOT NULL, vector_count INTEGER NOT NULL, fts_version INTEGER NOT NULL, created_at TEXT NOT NULL)",
            "CREATE TABLE generation_members(generation_id TEXT NOT NULL, chunk_id TEXT NOT NULL, space_id TEXT NOT NULL, document_version_id TEXT NOT NULL, PRIMARY KEY(generation_id, chunk_id))",
            "CREATE TABLE assets(id TEXT PRIMARY KEY, document_id TEXT NOT NULL, document_version_id TEXT, blob_hash TEXT NOT NULL, page INTEGER, section TEXT, kind TEXT NOT NULL, surrounding_text_hash TEXT NOT NULL)",
            "CREATE TABLE vision_results(cache_key TEXT PRIMARY KEY, asset_hash TEXT NOT NULL, context_hash TEXT NOT NULL, model_fingerprint TEXT NOT NULL, prompt_version TEXT NOT NULL, schema_version TEXT NOT NULL, status TEXT NOT NULL, ocr_text TEXT NOT NULL, description TEXT NOT NULL, table_markdown TEXT NOT NULL DEFAULT '', result_type TEXT NOT NULL DEFAULT '', processed_at TEXT NOT NULL)",
            "CREATE TABLE skill_packages(package_hash TEXT PRIMARY KEY, id TEXT NOT NULL, name TEXT NOT NULL, version TEXT NOT NULL, license_id TEXT NOT NULL, classification TEXT NOT NULL, manifest_json TEXT, skill_markdown TEXT, reasons TEXT NOT NULL, created_at TEXT NOT NULL, package_bytes BLOB, source_hash TEXT)",
            "CREATE TABLE skill_installs(install_id TEXT PRIMARY KEY, package_hash TEXT NOT NULL, enabled INTEGER NOT NULL, created_at TEXT NOT NULL)",
            "CREATE TABLE permission_grants(grant_id TEXT PRIMARY KEY, install_id TEXT NOT NULL, package_hash TEXT NOT NULL, capabilities TEXT NOT NULL, revision INTEGER NOT NULL, revoked INTEGER NOT NULL, scopes_json TEXT)",
            "CREATE TABLE skill_invocations(invocation_id TEXT PRIMARY KEY, run_id TEXT, package_hash TEXT, grant_revision INTEGER, state TEXT NOT NULL, created_at TEXT NOT NULL)",
            "CREATE TABLE runs(run_id TEXT PRIMARY KEY, snapshot_id TEXT NOT NULL, conversation_id TEXT NOT NULL, state TEXT NOT NULL, budget_json TEXT NOT NULL, stop_reason TEXT, error_code TEXT, model_rounds INTEGER NOT NULL DEFAULT 0, tool_calls INTEGER NOT NULL DEFAULT 0, input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0, started_at TEXT, finished_at TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, retry_acknowledged_at TEXT, FOREIGN KEY(snapshot_id) REFERENCES agent_snapshots(id), FOREIGN KEY(conversation_id) REFERENCES conversations(id))",
            "CREATE TABLE tool_invocations(invocation_id TEXT PRIMARY KEY, run_id TEXT NOT NULL, call_id TEXT NOT NULL, name TEXT NOT NULL, arguments_json TEXT NOT NULL, permission_decision TEXT NOT NULL, state TEXT NOT NULL, result_json TEXT, error_code TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(run_id, call_id), FOREIGN KEY(run_id) REFERENCES runs(run_id))",
            "CREATE INDEX idx_prompt_revisions_agent_created ON prompt_revisions(agent_id, created_at)",
            "CREATE INDEX idx_messages_conversation_created ON messages(conversation_id, created_at)",
            "CREATE INDEX idx_runs_state ON runs(state)",
            "CREATE INDEX idx_tool_invocations_run ON tool_invocations(run_id, created_at)",
            "CREATE UNIQUE INDEX uq_embedding_operations_active_kb ON embedding_operations(kb_id) WHERE state IN('PREPARED','DISPATCHED','CACHE_READY')",
            "CREATE INDEX idx_embedding_operations_job ON embedding_operations(job_id)",
        )
    }
}
