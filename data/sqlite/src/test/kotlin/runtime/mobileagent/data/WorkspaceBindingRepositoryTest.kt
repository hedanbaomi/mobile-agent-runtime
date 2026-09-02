// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.AgentWorkspaceDefault
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.ConversationWorkspaceBinding
import runtime.mobileagent.domain.PrivilegedWorkspaceBinding
import runtime.mobileagent.domain.PrivilegedWorkspaceBindingStatus
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceScope

class WorkspaceBindingRepositoryTest {
    @Test
    fun v14MigrationCreatesBindingTablesAndCompletesCurrentSchema() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            seedAgentAndConversation(db, "agent-migrate", "snapshot-migrate", "conversation-migrate")
            seedWorkspace(db, "workspace-migrate")
            seedGrant(db, "grant-migrate", "agent-migrate", "workspace-migrate")
            rewindV15Tables(db)

            Migrations.apply(db)

            assertEquals(
                Migrations.VERSION.toLong(),
                db.query("SELECT version FROM schema_version").single().long("version"),
            )
            assertEquals(
                "workspace-migrate",
                db.query(
                    "SELECT workspace_id FROM conversation_workspace_bindings WHERE session_id = ?",
                    listOf("conversation-migrate"),
                ).single().string("workspace_id"),
            )
            assertTrue(
                db.query("PRAGMA table_info(privileged_workspace_bindings)")
                    .any { it.string("name") == "encrypted_locator" },
            )
            assertTrue(
                db.query("PRAGMA table_info(agent_workspace_defaults)")
                    .any { it.string("name") == "workspace_id" },
            )

            // Migration is repeatable and never increments/replaces the existing
            // thread binding on a later open.
            Migrations.apply(db)
            assertEquals(
                1,
                db.query(
                    "SELECT * FROM conversation_workspace_bindings WHERE session_id = ?",
                    listOf("conversation-migrate"),
                ).size,
            )
        }
    }

    @Test
    fun ambiguousMigrationLeavesConversationUnbound() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            seedAgentAndConversation(db, "agent-ambiguous", "snapshot-ambiguous", "conversation-ambiguous")
            seedWorkspace(db, "workspace-ambiguous-a")
            seedWorkspace(db, "workspace-ambiguous-b")
            seedGrant(db, "grant-ambiguous-a", "agent-ambiguous", "workspace-ambiguous-a")
            seedGrant(db, "grant-ambiguous-b", "agent-ambiguous", "workspace-ambiguous-b")
            rewindV15Tables(db)

            Migrations.apply(db)

            assertEquals(
                0,
                db.query(
                    "SELECT * FROM conversation_workspace_bindings WHERE session_id = ?",
                    listOf("conversation-ambiguous"),
                ).size,
            )
        }
    }

    @Test
    fun encryptedLocatorIsBlobAndNeverRenderedAsPlaintext() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            seedWorkspace(db, "workspace-secret", WorkspaceBackendType.PRIVILEGED)
            val marker = "plain-path-marker"
            val binding = PrivilegedWorkspaceBinding(
                workspaceId = "workspace-secret",
                authority = Authority.SHIZUKU,
                encryptedLocator = byteArrayOf(0x01, 0x22, 0x7f, 0x00),
                locatorNonce = ByteArray(12) { 0x44 },
                aadAppInstanceId = "app-instance",
            )
            val persisted = PrivilegedWorkspaceBindingRepository(db).save(binding)
            val raw = db.query(
                "SELECT encrypted_locator, locator_nonce FROM privileged_workspace_bindings WHERE workspace_id = ?",
                listOf(binding.workspaceId),
            ).single()
            assertTrue(raw.columns["encrypted_locator"] is ByteArray)
            assertTrue(raw.columns["locator_nonce"] is ByteArray)
            assertFalse(persisted.toString().contains(marker))
            assertFalse(raw.columns.values.any { it?.toString()?.contains(marker) == true })
            assertTrue(persisted.toString().contains("<encrypted>"))
        }
    }

    @Test
    fun privilegedStatusAndRevisionUseStrictCas() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            seedWorkspace(db, "workspace-status", WorkspaceBackendType.PRIVILEGED)
            val repository = PrivilegedWorkspaceBindingRepository(db) { "2026-09-02T00:00:00Z" }
            val saved = repository.save(
                PrivilegedWorkspaceBinding(
                    workspaceId = "workspace-status",
                    authority = Authority.WIRED_ADB,
                    encryptedLocator = byteArrayOf(1, 2, 3),
                    locatorNonce = ByteArray(12) { 2 },
                    aadAppInstanceId = "app-instance",
                ),
            )
            val unavailable = repository.updateStatus(
                saved.workspaceId,
                expectedRevision = saved.revision,
                status = PrivilegedWorkspaceBindingStatus.UNAVAILABLE_AUTHORITY,
            )
            assertEquals(PrivilegedWorkspaceBindingStatus.UNAVAILABLE_AUTHORITY, unavailable?.status)
            assertEquals(saved.revision + 1L, unavailable?.revision)
            assertThrows(WorkspaceBindingConflictException::class.java) {
                repository.updateStatus(
                    saved.workspaceId,
                    expectedRevision = saved.revision,
                    status = PrivilegedWorkspaceBindingStatus.ACTIVE,
                )
            }
            assertFalse(
                repository.compareAndSet(
                    saved.revision,
                    unavailable!!.copy(
                        status = PrivilegedWorkspaceBindingStatus.ACTIVE,
                        revision = saved.revision + 1L,
                    ),
                ),
            )
        }
    }

    @Test
    fun threadBindingAndDefaultAreIndependentAndMultipleGrantsSurvive() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            seedAgentAndConversation(db, "agent-thread", "snapshot-thread-a", "conversation-thread-a")
            seedAgentAndConversation(db, "agent-thread", "snapshot-thread-b", "conversation-thread-b")
            seedWorkspace(db, "workspace-thread-a")
            seedWorkspace(db, "workspace-thread-b")
            val grants = CapabilityGrantRepository(db)
            val firstGrant = seedGrant(db, "grant-thread-a", "agent-thread", "workspace-thread-a")
            val secondGrant = seedGrant(db, "grant-thread-b", "agent-thread", "workspace-thread-b")

            val defaults = AgentWorkspaceDefaultRepository(db) { "2026-09-02T00:00:00Z" }
            val firstDefault = defaults.set("agent-thread", "workspace-thread-a")
            assertEquals("workspace-thread-a", defaults.resolveForNewThread("agent-thread"))

            val bindings = ConversationWorkspaceBindingRepository(db) { "2026-09-02T00:00:00Z" }
            val firstBinding = bindings.bind("conversation-thread-a", "workspace-thread-a")
            assertEquals("workspace-thread-a", bindings.get(firstBinding.sessionId)?.workspaceId)

            val secondDefault = defaults.set(
                "agent-thread",
                "workspace-thread-b",
                expectedRevision = firstDefault.revision,
            )
            assertEquals("workspace-thread-b", defaults.resolveForNewThread("agent-thread"))
            assertEquals(
                "workspace-thread-a",
                bindings.get("conversation-thread-a")?.workspaceId,
            )
            assertEquals(
                "workspace-thread-b",
                bindings.bind("conversation-thread-b", "workspace-thread-b").workspaceId,
            )

            // Replacing the Agent default did not revoke or replace the first
            // workspace grant.
            assertEquals(
                setOf("workspace-thread-a", "workspace-thread-b"),
                grants.list(agentId = "agent-thread").mapNotNull { it.workspaceId }.toSet(),
            )
            assertEquals(secondDefault.revision, defaults.get("agent-thread")?.revision)
            assertEquals(firstGrant.grantId, grants.get(firstGrant.grantId)?.grantId)
            assertEquals(secondGrant.grantId, grants.get(secondGrant.grantId)?.grantId)
            assertThrows(WorkspaceBindingConflictException::class.java) {
                defaults.set("agent-thread", "workspace-thread-a")
            }
            assertThrows(WorkspaceBindingConflictException::class.java) {
                bindings.bind("conversation-thread-a", "workspace-thread-b", expectedRevision = firstBinding.revision)
            }
            assertEquals("workspace-thread-a", bindings.get("conversation-thread-a")?.workspaceId)
        }
    }

    @Test
    fun revokedDefaultDoesNotFallbackToAnotherWorkspace() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            seedAgentAndConversation(db, "agent-revoke", "snapshot-revoke", "conversation-revoke")
            seedWorkspace(db, "workspace-revoke-a")
            seedWorkspace(db, "workspace-revoke-b")
            val grants = CapabilityGrantRepository(db)
            val first = seedGrant(db, "grant-revoke-a", "agent-revoke", "workspace-revoke-a")
            seedGrant(db, "grant-revoke-b", "agent-revoke", "workspace-revoke-b")
            val defaults = AgentWorkspaceDefaultRepository(db) { "2026-09-02T00:00:00Z" }
            defaults.set("agent-revoke", "workspace-revoke-a")
            assertEquals("workspace-revoke-a", defaults.resolveForNewThread("agent-revoke"))
            grants.revoke(first.grantId, first.revision)
            assertNull(defaults.resolveForNewThread("agent-revoke"))
        }
    }

    private fun seedWorkspace(
        db: SqlConnection,
        id: String,
        backendType: WorkspaceBackendType = WorkspaceBackendType.SAF_TREE,
    ) {
        WorkspaceRepository(db).save(
            Workspace(
                id = id,
                displayName = id,
                backendType = backendType,
                rootReference = if (backendType == WorkspaceBackendType.PRIVILEGED) {
                    "authority:SHIZUKU"
                } else {
                    "workspace-root"
                },
                readable = true,
                writable = true,
                scope = WorkspaceScope.SELECTED_DIRECTORY,
            ),
        )
    }

    private fun seedGrant(
        db: SqlConnection,
        grantId: String,
        agentId: String,
        workspaceId: String,
    ): CapabilityGrant {
        return CapabilityGrantRepository(db).save(
            CapabilityGrant(
                grantId = grantId,
                agentId = agentId,
                capability = CapabilityId(CapabilityId.FILE_READ_TEXT),
                workspaceId = workspaceId,
                lifetime = GrantLifetime.PERSISTENT,
                policyVersion = 1,
                createdAt = "2026-09-02T00:00:00Z",
            ),
        )
    }

    private fun seedAgentAndConversation(
        db: SqlConnection,
        agentId: String,
        snapshotId: String,
        conversationId: String,
    ) {
        db.execute(
            "INSERT OR IGNORE INTO agent_profiles(id,name,prompt_revision_id,chat_profile_id,vision_profile_id,embedding_profile_id,reranker_profile_id,knowledge_base_ids,skill_ids,retrieval_mode,revision,parameter_overrides_json,context_policy_json,permission_settings_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                agentId,
                agentId,
                "prompt-$agentId",
                "model-$agentId",
                null,
                null,
                null,
                "[]",
                "[]",
                "explicit",
                1,
                "{}",
                "{}",
                "{}",
            ),
        )
        db.execute(
            "INSERT OR IGNORE INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at,provider_id,chat_model_revision,vision_model_id,vision_model_revision,embedding_model_id,embedding_model_revision,reranker_model_id,reranker_model_revision,parameter_overrides_json,context_policy_json,permission_settings_json,binding_manifest_json,expanded_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                snapshotId,
                1,
                agentId,
                "prompt-$agentId",
                "model-$agentId",
                1,
                "[]",
                "[]",
                "2026-09-02T00:00:00Z",
                "provider-$agentId",
                1,
                null,
                null,
                null,
                null,
                null,
                "{}",
                "{}",
                "{}",
                "{}",
                "{}",
                "{}",
            ),
        )
        db.execute(
            "INSERT OR IGNORE INTO conversations(id,snapshot_id,title,created_at,updated_at) VALUES(?,?,?,?,?)",
            listOf(
                conversationId,
                snapshotId,
                conversationId,
                "2026-09-02T00:00:00Z",
                "2026-09-02T00:00:00Z",
            ),
        )
    }

    private fun rewindV15Tables(db: SqlConnection) {
        db.execute("DROP TABLE IF EXISTS agent_workspace_defaults")
        db.execute("DROP TABLE IF EXISTS conversation_workspace_bindings")
        db.execute("DROP TABLE IF EXISTS privileged_workspace_bindings")
        db.execute("UPDATE schema_version SET version = 14")
    }
}
