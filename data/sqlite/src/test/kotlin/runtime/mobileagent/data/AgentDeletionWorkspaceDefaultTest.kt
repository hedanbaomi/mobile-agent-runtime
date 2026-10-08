// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

class AgentDeletionWorkspaceDefaultTest {
    @Test fun deletionRemovesOnlyItsDefaultWithoutForeignKeyCascades() {
        JdbcSqlConnection().use { db ->
            val agents = seed(db)
            // BundledSQLiteDriver does not enable foreign keys by default.
            db.execute("PRAGMA foreign_keys = OFF")
            assertTrue(agents.delete("agent.deleted"))
            assertNull(AgentWorkspaceDefaultRepository(db).get("agent.deleted"))
            assertNotNull(AgentWorkspaceDefaultRepository(db).get("agent.retained"))
            Migrations.apply(db)
            assertNotNull(agents.get("agent.retained"))
        }
    }

    @Test fun deletionAlsoWorksWithForeignKeysEnabled() {
        JdbcSqlConnection().use { db ->
            val agents = seed(db)
            assertTrue(agents.delete("agent.deleted"))
            assertNull(AgentWorkspaceDefaultRepository(db).get("agent.deleted"))
            assertNotNull(AgentWorkspaceDefaultRepository(db).get("agent.retained"))
            Migrations.apply(db)
        }
    }

    @Test fun retainedSnapshotPreventsDeletingAgentOrItsDefault() {
        JdbcSqlConnection().use { db ->
            val agents = seed(db)
            db.execute("PRAGMA foreign_keys = OFF")
            agents.createSnapshot("agent.deleted", "snapshot.retained")
            assertFalse(agents.delete("agent.deleted"))
            assertNotNull(agents.get("agent.deleted"))
            assertNotNull(AgentWorkspaceDefaultRepository(db).get("agent.deleted"))
            Migrations.apply(db)
        }
    }

    @Test fun failedDeletionRollsBackDefaultAndPromptCleanup() {
        JdbcSqlConnection().use { db ->
            val agents = seed(db)
            db.execute("PRAGMA foreign_keys = OFF")
            db.execute("CREATE TRIGGER fail_agent_delete BEFORE DELETE ON agent_profiles BEGIN SELECT RAISE(ABORT, 'fixture deletion failure'); END")
            assertThrows(Exception::class.java) { agents.delete("agent.deleted") }
            assertNotNull(agents.get("agent.deleted"))
            assertNotNull(agents.promptRevision(agents.get("agent.deleted")!!.promptRevisionId))
            assertNotNull(AgentWorkspaceDefaultRepository(db).get("agent.deleted"))
            Migrations.apply(db)
        }
    }

    @Test fun legacyVersion30OrphanDefaultIsRemovedWithoutChangingLiveDefaults() {
        JdbcSqlConnection().use { db ->
            val agents = seed(db)
            db.execute("PRAGMA foreign_keys = OFF")
            db.execute("DELETE FROM prompt_revisions WHERE agent_id = ?", listOf("agent.deleted"))
            db.execute("DELETE FROM agent_profiles WHERE id = ?", listOf("agent.deleted"))
            db.execute("UPDATE schema_version SET version = 30")
            WorkspaceRepository(db).save(Workspace("workspace.retained", "Retained", WorkspaceBackendType.INTERNAL,
                "fixture-root", readable = true, writable = true, scope = WorkspaceScope.SELECTED_DIRECTORY))
            val grant = CapabilityGrantRepository(db).save(CapabilityGrant("grant.retained", "agent.retained",
                capability = CapabilityId(CapabilityId.FILE_READ_TEXT), workspaceId = "workspace.retained",
                lifetime = GrantLifetime.PERSISTENT, policyVersion = AuthorityPolicyRepository(db).getPolicy().policyVersion,
                createdAt = "2026-10-08T00:00:00Z"))
            AgentWorkspaceDefaultRepository(db).save(AgentWorkspaceDefault("agent.retained", "workspace.retained", 2,
                "2026-10-08T00:00:00Z"))
            val workspace = WorkspaceRepository(db).get("workspace.retained")
            val retained = AgentWorkspaceDefaultRepository(db).get("agent.retained")
            Migrations.apply(db)
            assertNull(AgentWorkspaceDefaultRepository(db).get("agent.deleted"))
            assertEquals(retained, AgentWorkspaceDefaultRepository(db).get("agent.retained"))
            assertNotNull(agents.get("agent.retained"))
            Migrations.apply(db)
            assertEquals(retained, AgentWorkspaceDefaultRepository(db).get("agent.retained"))
            assertEquals(workspace, WorkspaceRepository(db).get("workspace.retained"))
            assertEquals(listOf(grant), CapabilityGrantRepository(db).list(agentId = "agent.retained"))
        }
    }

    @Test fun failedUpgradeRollsBackOrphanCleanupAndSchemaVersion() {
        JdbcSqlConnection().use { db ->
            seed(db)
            db.execute("PRAGMA foreign_keys = OFF")
            db.execute("DELETE FROM prompt_revisions WHERE agent_id = ?", listOf("agent.deleted"))
            db.execute("DELETE FROM agent_profiles WHERE id = ?", listOf("agent.deleted"))
            db.execute("UPDATE schema_version SET version = 30")
            val orphan = AgentWorkspaceDefaultRepository(db).get("agent.deleted")
            val failing = object : SqlConnection by db {
                override fun execute(sql: String, args: List<Any?>) {
                    if (sql == "DELETE FROM schema_version") error("Fixture failure after preference cleanup")
                    db.execute(sql, args)
                }
            }
            assertThrows(IllegalStateException::class.java) { Migrations.apply(failing) }
            assertEquals(30L, db.query("SELECT version FROM schema_version").single().long("version"))
            assertEquals(orphan, AgentWorkspaceDefaultRepository(db).get("agent.deleted"))
        }
    }

    @Test fun currentVersionOrphanStillFailsValidation() {
        JdbcSqlConnection().use { db ->
            seed(db)
            db.execute("PRAGMA foreign_keys = OFF")
            db.execute("DELETE FROM prompt_revisions WHERE agent_id = ?", listOf("agent.deleted"))
            db.execute("DELETE FROM agent_profiles WHERE id = ?", listOf("agent.deleted"))
            assertThrows(AppException::class.java) { Migrations.apply(db) }
            assertNotNull(AgentWorkspaceDefaultRepository(db).get("agent.deleted"))
        }
    }

    private fun seed(db: SqlConnection): AgentRepository {
        Migrations.apply(db)
        val profiles = ProfileRepository(db)
        profiles.createProvider(ProviderProfile("provider.deletion", "Fixture", ApiFormat.OPENAI_COMPATIBLE,
            "https://example.invalid/v1", secretRef = "fixture-only", revision = 1))
        profiles.createModel(ModelProfile("model.deletion", "provider.deletion", ModelRole.CHAT, "fixture",
            emptySet(), contextLimit = 4096, outputLimit = 512, revision = 1))
        return AgentRepository(db).also { agents ->
            listOf("agent.deleted", "agent.retained").forEach { id ->
                agents.saveWithPrompt(AgentProfile(id, id, "pending", "model.deletion", revision = 0), "Fixture")
                AgentWorkspaceDefaultRepository(db).save(AgentWorkspaceDefault(id, null, 1, "2026-10-08T00:00:00Z"))
            }
        }
    }
}
