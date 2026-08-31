// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApprovalDecision
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.DesktopIdentity
import runtime.mobileagent.domain.DesktopTrustStatus
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.SafGrantStatus
import runtime.mobileagent.domain.SafWorkspaceGrant
import runtime.mobileagent.domain.SnapshotGrantBinding
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.domain.WorkspaceBackendType

class AuthorityRepositoryTest {
    @Test
    fun policyCasPersistsDangerousModeAndNeverFallsBack() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repo = AuthorityPolicyRepository(db)
            assertEquals(Authority.NONE, repo.getPolicy().selectedAuthority)
            assertEquals(DangerousMode.DISABLED, repo.getPolicy().dangerousMode)

            val enabled = repo.updatePolicy(0, Authority.WIRED_ADB, DangerousMode.ENABLED_AUTONOMOUS)
            assertEquals(1, enabled.policyVersion)
            assertEquals(DangerousMode.ENABLED_AUTONOMOUS, AuthorityPolicyRepository(db).getPolicy().dangerousMode)
            assertThrows(IllegalArgumentException::class.java) {
                repo.requireSelectedAuthority(setOf(Authority.SHIZUKU))
            }
            assertEquals(Authority.WIRED_ADB, repo.requireSelectedAuthority(setOf(Authority.WIRED_ADB)))
            assertFalse(repo.compareAndSet(0, Authority.SHIZUKU, DangerousMode.DISABLED))

            // A driver that reports no affected row must not be accepted as a
            // successful CAS merely because the expected version was read first.
            db.execute(
                "CREATE TRIGGER ignore_policy_update BEFORE UPDATE ON authority_policy BEGIN SELECT RAISE(IGNORE); END",
            )
            assertThrows(AuthorityPolicyConflictException::class.java) {
                repo.updatePolicy(1, Authority.WIRED_ADB, DangerousMode.ENABLED_AUTONOMOUS)
            }
            db.execute("DROP TRIGGER ignore_policy_update")

            val disabled = repo.disable(1)
            assertEquals(Authority.NONE, disabled.selectedAuthority)
            assertEquals(DangerousMode.DISABLED, disabled.dangerousMode)
        }
    }

    @Test
    fun preferencesRepresentIndependentPerAuthorityIntent() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repo = AuthorityPolicyRepository(db)
            repo.setUserIntent(Authority.SHIZUKU, enabled = true)
            assertEquals(Authority.NONE, repo.getPolicy().selectedAuthority)
            assertEquals(DangerousMode.DISABLED, repo.getPolicy().dangerousMode)
            assertTrue(repo.getPreference(Authority.SHIZUKU).userIntentEnabled)
            assertEquals(3, repo.listPreferences().size)
            assertTrue(db.query("PRAGMA table_info(authority_preferences)").none { it.string("name") == "selected_authority" })
            assertTrue(db.query("PRAGMA table_info(authority_preferences)").none { it.string("name") == "dangerous_mode" })
        }
    }

    @Test
    fun canonicalCapabilityGrantProjectsWorkspaceAclAndSnapshotsFreezeScope() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            WorkspaceRepository(db).save(
                Workspace("workspace-1", "Private", WorkspaceBackendType.INTERNAL, "private-root", writable = true),
            )
            insertSnapshot(db, "snapshot-1", "agent-1")
            val grants = CapabilityGrantRepository(db)
            val grant = grants.save(
                CapabilityGrant(
                    grantId = "grant-1",
                    agentId = "agent-1",
                    capability = CapabilityId(CapabilityId.FILE_READ_TEXT),
                    packageHash = "package-hash",
                    workspaceId = "workspace-1",
                    pathScope = "notes",
                    lifetime = GrantLifetime.PERSISTENT,
                    policyVersion = 3,
                    createdAt = "now",
                ),
            )
            assertEquals(grant, grants.workspaceAcl("workspace-1").single())
            val snapshot = grants.bindSnapshot(
                SnapshotGrantBinding("snapshot-1", grant.grantId, grant.capability, "workspace-1", "notes", 3, "now"),
            )
            grants.revoke(grant.grantId)
            assertTrue(grants.workspaceAcl("workspace-1").isEmpty())
            assertEquals(snapshot, grants.listSnapshotBindings("snapshot-1").single())
            assertEquals("table", db.query("SELECT type FROM sqlite_master WHERE name = 'capability_grants'").single().string("type"))
            assertEquals("view", db.query("SELECT type FROM sqlite_master WHERE name = 'workspace_acl'").single().string("type"))
        }
    }

    @Test
    fun capabilityGrantLifetimeUsesIdentityAndDurableCasConsumption() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val now = "2026-08-30T00:00:00Z"
            val grants = CapabilityGrantRepository(db) { now }
            val once = grants.save(
                CapabilityGrant(
                    grantId = "grant-once",
                    agentId = "agent-1",
                    capability = CapabilityId(CapabilityId.FILE_READ_TEXT),
                    pathScope = "once",
                    lifetime = GrantLifetime.ONCE,
                    policyVersion = 1,
                    createdAt = now,
                ),
            )
            assertEquals(listOf(once), grants.active("agent-1", once.capability, taskIdentity = "task-1", sessionIdentity = "session-1"))
            val consumed = grants.consumeOnce(once.grantId, once.revision, consumedAt = now)
            assertEquals(once.revision + 1, consumed!!.revision)
            assertTrue(grants.active("agent-1", once.capability, taskIdentity = "task-1", sessionIdentity = "session-1").isEmpty())
            assertNull(grants.consumeOnce(once.grantId, once.revision, consumedAt = now))

            val task = grants.save(
                CapabilityGrant(
                    grantId = "grant-task",
                    agentId = "agent-1",
                    capability = once.capability,
                    pathScope = "task",
                    lifetime = GrantLifetime.TASK,
                    taskId = "task-1",
                    policyVersion = 1,
                    createdAt = now,
                ),
            )
            assertTrue(grants.active("agent-1", task.capability, taskIdentity = "task-1").any { it.grantId == task.grantId })
            assertTrue(grants.active("agent-1", task.capability, taskIdentity = "task-2").none { it.grantId == task.grantId })
            // A new repository instance still honors the durable task owner;
            // process recreation does not renew a grant under a new identity.
            assertTrue(CapabilityGrantRepository(db) { now }.active("agent-1", task.capability, taskIdentity = "task-1").any { it.grantId == task.grantId })

            val cas = grants.save(
                CapabilityGrant(
                    grantId = "grant-cas",
                    agentId = "agent-1",
                    capability = CapabilityId(CapabilityId.FILE_STAT),
                    lifetime = GrantLifetime.PERSISTENT,
                    policyVersion = 1,
                    createdAt = now,
                ),
            )
            assertTrue(grants.compareAndSet(cas.revision, cas.copy(revision = cas.revision + 1)))
            assertFalse(grants.compareAndSet(cas.revision, cas.copy(revision = cas.revision + 1)))
        }
    }

    @Test
    fun snapshotBindingRequiresExistingSnapshotOwnedByGrantAgent() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val grants = CapabilityGrantRepository(db)
            val grant = grants.save(
                CapabilityGrant(
                    grantId = "grant-binding",
                    agentId = "agent-1",
                    capability = CapabilityId(CapabilityId.FILE_READ_TEXT),
                    lifetime = GrantLifetime.PERSISTENT,
                    policyVersion = 1,
                    createdAt = "2026-08-30T00:00:00Z",
                ),
            )
            val binding = SnapshotGrantBinding("missing-snapshot", grant.grantId, grant.capability, policyVersion = 1)
            assertThrows(IllegalArgumentException::class.java) { grants.bindSnapshot(binding) }
            insertSnapshot(db, "other-agent-snapshot", "agent-2")
            assertThrows(IllegalArgumentException::class.java) {
                grants.bindSnapshot(binding.copy(snapshotId = "other-agent-snapshot"))
            }
            insertSnapshot(db, "owned-snapshot", "agent-1")
            val bound = grants.bindSnapshot(binding.copy(snapshotId = "owned-snapshot"))
            assertThrows(IllegalArgumentException::class.java) {
                grants.bindSnapshot(bound.copy(pathScope = "other"))
            }
        }
    }

    @Test
    fun safAndDesktopTrustLifecyclePreservesBindingsUntilExplicitForget() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            WorkspaceRepository(db).save(Workspace("workspace-saf", "SAF", WorkspaceBackendType.SAF_TREE, "content://tree/abc"))
            val saf = SafWorkspaceGrantRepository(db)
            saf.save(SafWorkspaceGrant("workspace-saf", "content://tree/abc", true, true, persistedFlags = 3, createdAt = "now", updatedAt = "now"))
            assertEquals(SafGrantStatus.ACTIVE, saf.get("workspace-saf")!!.status)
            assertEquals(SafGrantStatus.GRANT_LOST, saf.markLost("workspace-saf")!!.status)
            assertEquals("content://tree/abc", saf.get("workspace-saf")!!.uriReference)

            DesktopIdentityRepository(db).save(DesktopIdentity("desktop-1", "app-1", "now"))
            assertEquals("desktop-1", DesktopIdentityRepository(db).get()!!.desktopId)
            val trust = DesktopTrustRepository(db)
            val connected = trust.trust("desktop-1", "app-1", "bridge:desktop:desktop-1")
            assertEquals(DesktopTrustStatus.TRUSTED, connected.status)
            assertEquals(DesktopTrustStatus.REAUTH_REQUIRED, trust.markReauthRequired("desktop-1")!!.status)
            assertEquals("bridge:desktop:desktop-1", trust.get("desktop-1")!!.secretRef)
            assertEquals(DesktopTrustStatus.TRUSTED, trust.reauthenticate("desktop-1")!!.status)
            assertEquals(DesktopTrustStatus.FORGOTTEN, trust.forget("desktop-1")!!.status)
        }
    }

    @Test
    fun approvalUsesRuntimeRequestUuidAndOneShotConsumption() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val hash = "b".repeat(64)
            val approvals = ApprovalRepository(db)
            val pending = approvals.create(
                callId = "model-call-1",
                agentId = "agent-1",
                configSnapshotHash = hash,
                selectedAuthority = Authority.WIRED_ADB,
                dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
                policyVersion = 4,
                commandHash = hash,
                cwdHash = hash,
            )
            assertNotEquals("model-call-1", pending.requestId)
            assertEquals(0, db.query("SELECT * FROM approval_records").size)
            // A fresh repository/process has no access to the old in-memory pending map.
            assertNull(ApprovalRepository(db).get(pending.approvalId))
            assertEquals(ApprovalDecision.APPROVED, approvals.approve(pending.approvalId).decision)
            assertTrue(approvals.isValidFor(pending.approvalId, "model-call-1", "agent-1", hash, hash, Authority.WIRED_ADB, DangerousMode.ENABLED_CONFIRM_HIGH_RISK, 4, hash, 1))
            assertEquals(ApprovalDecision.CONSUMED, approvals.consume(pending.approvalId).decision)
            assertThrows(IllegalStateException::class.java) { approvals.consume(pending.approvalId) }
            assertTrue(db.query("PRAGMA table_info(approval_records)").none { it.string("name") == "cwd" })
            assertTrue(db.query("PRAGMA table_info(approval_records)").none { it.string("name") == "command" })
        }
    }

    private fun insertSnapshot(db: SqlConnection, snapshotId: String, agentId: String) {
        db.execute(
            "INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at,provider_id,chat_model_revision,parameter_overrides_json,context_policy_json,permission_settings_json,binding_manifest_json,expanded_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                snapshotId, 1, agentId, "prompt-1", "model-1", 1, "[]", "[]", "2026-08-30T00:00:00Z",
                "provider-1", 1, "{}", "{}", "{}", "{}", "{}",
            ),
        )
    }
}
