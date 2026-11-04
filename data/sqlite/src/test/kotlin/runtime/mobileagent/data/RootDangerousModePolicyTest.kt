// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

/** The global CAS still fences old work; only an existing persistent Root permission continues. */
class RootDangerousModePolicyTest {
    private val fixture = RootApprovalFixture()
    private val now get() = fixture.now

    @Test
    fun dangerousModeOnlyKeepsConsentDirectoryAndDurableGrantsForANewSnapshotAndRun() {
        JdbcSqlConnection().use { db ->
            fixture.setup(db)
            val roots = RootAuthorityRepository(db) { now }
            val previous = roots.get(Authority.ROOT_DIRECT)
            val shellConsent = requireNotNull(roots.setShellConsent(Authority.ROOT_DIRECT, previous.revision, true, 1))
            val beforeExecution = fixture.execution().copy(shellConsentRevision = shellConsent.consent.shellRevision)
            val grants = RootGrantRepository(db) { now }
            grants.create(CapabilityGrant("shell-grant", "agent-root", CapabilityId(CapabilityId.SHELL_EXECUTE),
                workspaceId = "root-workspace", policyVersion = 1), beforeExecution)
            val oldCanonical = CapabilityGrantRepository(db).get("root-grant")!!
            val oldBinding = grants.get("root-grant")!!
            val oldSnapshot = db.query("SELECT binding_json FROM root_snapshot_grants WHERE snapshot_id='snapshot-root'").single().string("binding_json")
            val origin = RootWorkspaceRepository(db).getOrigin("root-workspace")!!
            // Storage-only synthetic ciphertext; this test does not claim a Keystore decrypt.
            val locator = RootWorkspaceLocator(origin, byteArrayOf(1, 2, 3))
            RootWorkspaceLocatorRepository(db).attachNew(locator, beforeExecution)
            val workspace = WorkspaceRepository(db).get("root-workspace")!!
            val policy = AuthorityPolicyRepository(db) { now }
            val next = policy.updatePolicy(1, Authority.ROOT_DIRECT, DangerousMode.ENABLED_AUTONOMOUS)
            val state = roots.get(Authority.ROOT_DIRECT)
            assertEquals(2, next.policyVersion)
            assertEquals(shellConsent.consent.copy(policyVersion = 2), state.consent)
            assertEquals(shellConsent.identityEpoch, state.identityEpoch)
            assertEquals(shellConsent.revision + 1, state.revision)
            assertTrue(state.consent.shellEnabled)
            assertEquals(workspace, WorkspaceRepository(db).get(workspace.id))
            assertEquals(origin, RootWorkspaceRepository(db).getOrigin(workspace.id))
            assertEquals(locator, RootWorkspaceLocatorRepository(db).getCurrent(workspace.id, beforeExecution.copy(policyVersion = 2)))
            val currentGrant = CapabilityGrantRepository(db).get("root-grant")!!
            assertEquals(oldCanonical.copy(policyVersion = 2, revision = oldCanonical.revision + 1), currentGrant)
            assertEquals(oldBinding.copy(policyVersion = 2, grantRevision = oldBinding.grantRevision + 1), grants.get("root-grant"))
            assertTrue(grants.isDurablyBoundGrant(currentGrant, origin, next))
            assertEquals(oldSnapshot, db.query("SELECT binding_json FROM root_snapshot_grants WHERE snapshot_id='snapshot-root'").single().string("binding_json"))
            assertNull(RootSnapshotGrantRepository(db) { now }.revalidate("snapshot-root", "root-grant", fixture.execution()))
            val execution = beforeExecution.copy(policyVersion = 2)
            val snapshots = RootSnapshotGrantRepository(db) { now }
            snapshots.createNewSnapshot("snapshot-next", "agent-root", setOf("root-grant", "shell-grant"), execution) {
                cloneSnapshot(db, "snapshot-next")
            }
            assertNotNull(snapshots.revalidate("snapshot-next", "root-grant", execution))
            assertNotNull(snapshots.revalidate("snapshot-next", "shell-grant", execution))
            val run = createRun(db, "run-next", "snapshot-next", execution)
            assertDoesNotThrow { RootRunManifest.requireMatches(run.manifestJson, execution) }
            assertTrue(RootToolExposureGate(db) { now }.evaluateExposure("snapshot-next", setOf("root-grant", "shell-grant"), execution).shellExposed)
            // Turning the unrelated switch back off continues the same explicit Shell consent.
            policy.updatePolicy(2, Authority.ROOT_DIRECT, DangerousMode.DISABLED)
            assertTrue(roots.get(Authority.ROOT_DIRECT).consent.shellEnabled)
            assertEquals(shellConsent.consent.shellRevision, roots.get(Authority.ROOT_DIRECT).consent.shellRevision)
            val offExecution = beforeExecution.copy(policyVersion = 3)
            snapshots.createNewSnapshot("snapshot-off", "agent-root", setOf("root-grant", "shell-grant"), offExecution) {
                cloneSnapshot(db, "snapshot-off")
            }
            assertTrue(RootToolExposureGate(db) { now }.evaluateExposure("snapshot-off", setOf("root-grant", "shell-grant"), offExecution).shellExposed)
        }
    }

    @Test
    fun pendingAndApprovedOldCallsRemainFencedAfterTheModeEdit() {
        for (approved in listOf(false, true)) JdbcSqlConnection().use { db ->
            fixture.setup(db)
            val approvals = RootApprovalRepository(db, clock = { now })
            val pending = approvals.createPending(fixture.request())
            val bound = if (approved) approvals.approve(pending.approval.approvalId) else pending
            AuthorityPolicyRepository(db) { now }.updatePolicy(1, Authority.ROOT_DIRECT, DangerousMode.ENABLED_CONFIRM_HIGH_RISK)
            if (approved) assertNull(approvals.getCurrent(bound.approval.approvalId, fixture.request()))
            else assertThrows(Exception::class.java) { approvals.approve(bound.approval.approvalId) }
            assertThrows(Exception::class.java) { requireLocalRootState(db, fixture.execution()) }
            // Re-reading an old run/snapshot must never silently replace its stamp.
            assertDoesNotThrow { RootRunManifest.requireMatches(RunRepository(db).get("run-root")!!.manifestJson, fixture.execution()) }
        }
    }

    @Test
    fun staleRevokedExpiredConsumedForeignAndSessionPermissionsAreNotCarriedOrPromoted() {
        JdbcSqlConnection().use { db ->
            fixture.setup(db)
            val repo = RootGrantRepository(db) { now }
            val canonical = CapabilityGrantRepository(db) { now }
            val base = canonical.get("root-grant")!!
            for (id in listOf("revoked", "expired", "consumed", "stale", "foreign", "session")) {
                val grant = base.copy(grantId = id,
                    expiresAt = if (id == "expired") "2026-10-30T11:00:00Z" else null,
                    lifetime = when (id) { "session" -> GrantLifetime.SESSION; "consumed" -> GrantLifetime.ONCE; else -> GrantLifetime.PERSISTENT },
                    sessionId = if (id == "session") "session-bound" else null)
                repo.create(grant, fixture.execution())
            }
            canonical.revoke("revoked")
            val consumed = canonical.get("consumed")!!
            assertTrue(canonical.compareAndSet(consumed.revision, consumed.copy(revision = consumed.revision + 1, consumedAt = now)))
            db.execute("UPDATE capability_grants SET policy_version=0 WHERE grant_id='stale'")
            db.execute("UPDATE root_grant_bindings SET app_instance_id='another-app-instance-0001' WHERE grant_id='foreign'")
            canonical.save(base.copy(grantId = "ordinary-unbound"))
            val ids = listOf("revoked", "expired", "consumed", "stale", "foreign", "session", "ordinary-unbound")
            val before = ids.associateWith { canonical.get(it) }
            val bindings = ids.associateWith { repo.get(it) }
            AuthorityPolicyRepository(db) { "2026-10-31T11:00:00Z" }.updatePolicy(1, Authority.ROOT_DIRECT, DangerousMode.ENABLED_AUTONOMOUS)
            ids.forEach { id ->
                assertEquals(before[id], canonical.get(id), id)
                assertEquals(bindings[id], repo.get(id), id)
            }
            assertNull(repo.get("ordinary-unbound"))
            assertEquals(2, canonical.get("root-grant")!!.policyVersion)
        }
    }

    @Test
    fun authoritySelectionPreservesPermissionsButRevokedConsentNeverCarries() {
        JdbcSqlConnection().use { db ->
            fixture.setup(db)
            val policy = AuthorityPolicyRepository(db) { now }
            val original = CapabilityGrantRepository(db).get("root-grant")
            policy.updatePolicy(1, Authority.SHIZUKU, DangerousMode.ENABLED_AUTONOMOUS)
            policy.updatePolicy(2, Authority.ROOT_DIRECT, DangerousMode.ENABLED_AUTONOMOUS)
            policy.updatePolicy(3, Authority.ROOT_DIRECT, DangerousMode.DISABLED)
            val carried = CapabilityGrantRepository(db).get("root-grant")!!
            assertEquals(original!!.copy(policyVersion = 4, revision = original.revision + 3), carried)
            assertEquals(4, RootAuthorityRepository(db).get(Authority.ROOT_DIRECT).consent.policyVersion)
            val origin = RootWorkspaceRepository(db).getOrigin("root-workspace")!!
            assertTrue(RootGrantRepository(db).isDurablyBoundGrant(carried, origin, policy.getPolicy()))
            assertThrows(Exception::class.java) { requireLocalRootState(db, fixture.execution()) }
        }
        JdbcSqlConnection().use { db ->
            fixture.setup(db)
            val roots = RootAuthorityRepository(db) { now }
            roots.revokeRoot(Authority.ROOT_DIRECT, roots.get(Authority.ROOT_DIRECT).revision)
            val revoked = roots.get(Authority.ROOT_DIRECT)
            val original = CapabilityGrantRepository(db).get("root-grant")
            AuthorityPolicyRepository(db) { now }.updatePolicy(1, Authority.ROOT_DIRECT, DangerousMode.ENABLED_AUTONOMOUS)
            assertEquals(revoked, roots.get(Authority.ROOT_DIRECT))
            assertEquals(original, CapabilityGrantRepository(db).get("root-grant"))
        }
    }

    @Test
    fun aFailedCarryRollsBackGlobalPolicyConsentAndBothGrantRowsAndStaleCasCannotRetry() {
        JdbcSqlConnection().use { db ->
            fixture.setup(db)
            val policies = AuthorityPolicyRepository(db) { now }
            val oldPolicy = policies.getPolicy()
            val oldRoot = RootAuthorityRepository(db).get(Authority.ROOT_DIRECT)
            val oldGrant = CapabilityGrantRepository(db).get("root-grant")
            val oldBinding = RootGrantRepository(db).get("root-grant")
            db.execute("CREATE TRIGGER reject_carry BEFORE UPDATE ON root_authority_state BEGIN SELECT RAISE(ABORT,'fixture rollback'); END")
            assertThrows(Exception::class.java) { policies.updatePolicy(1, Authority.ROOT_DIRECT, DangerousMode.ENABLED_AUTONOMOUS) }
            assertEquals(oldPolicy, policies.getPolicy())
            assertEquals(oldRoot, RootAuthorityRepository(db).get(Authority.ROOT_DIRECT))
            assertEquals(oldGrant, CapabilityGrantRepository(db).get("root-grant"))
            assertEquals(oldBinding, RootGrantRepository(db).get("root-grant"))
            assertThrows(AuthorityPolicyConflictException::class.java) { policies.updatePolicy(0, Authority.ROOT_DIRECT, DangerousMode.ENABLED_AUTONOMOUS) }
            assertEquals(oldPolicy, policies.getPolicy())
            assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
        }
    }

    private fun cloneSnapshot(db: SqlConnection, id: String) {
        val columns = db.query("PRAGMA table_info(agent_snapshots)").map { it.string("name") }
        val projection = columns.joinToString(",") { if (it == "id") "?" else "\"$it\"" }
        db.execute("INSERT INTO agent_snapshots SELECT $projection FROM agent_snapshots WHERE id='snapshot-root'", listOf(id))
    }

    private fun createRun(db: SqlConnection, id: String, snapshot: String, execution: RootExecutionStamp): RunRecord {
        db.execute("INSERT INTO conversations(id,snapshot_id,title,created_at,updated_at,archived) VALUES(?,?,?,?,?,0)",
            listOf("conv-$id", snapshot, "Fixture", now, now))
        return RootRunAdmission(db) { now }.create(RunRecord(id, snapshot, "conv-$id", createdAt = now), execution)
    }
}
