// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode

class RootAuthorityRepositoryTest {
    @Test
    fun oldPreferencesNeverCreateRootConsentAndAllAuthoritiesCanBeSaved() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val policies = AuthorityPolicyRepository(db)
            policies.setUserIntent(Authority.SHIZUKU, true)
            policies.updatePolicy(0, Authority.SHIZUKU, DangerousMode.ENABLED_AUTONOMOUS)
            // This is the persistence seam used by AuthorityManager normalization.
            Authority.entries.forEach { policies.setUserIntent(it, true) }
            val roots = RootAuthorityRepository(db)
            listOf(Authority.ROOT_DIRECT, Authority.SHIZUKU_ROOT).forEach {
                assertFalse(roots.get(it).consent.enabled)
                assertFalse(roots.get(it).consent.shellEnabled)
                assertEquals(0, roots.get(it).revision)
            }
            assertEquals(Authority.entries.size, policies.listPreferences().size)
            assertTrue(db.query("SELECT * FROM root_authority_state").isEmpty())
        }
    }

    @Test
    fun consentUsesStorageCasAndSeparateRootAndShellRevisions() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repo = RootAuthorityRepository(db) { "now" }
            val first = repo.confirmRoot(Authority.ROOT_DIRECT, 0, 0)!!
            assertTrue(first.consent.enabled)
            assertFalse(first.consent.shellEnabled)
            assertNull(repo.confirmRoot(Authority.ROOT_DIRECT, 0, 0))
            val shell = repo.setShellConsent(Authority.ROOT_DIRECT, first.revision, true, 0)!!
            assertEquals(first.consent.revision, shell.consent.revision)
            assertTrue(shell.consent.shellRevision > first.consent.shellRevision)
            val revoked = repo.revokeRoot(Authority.ROOT_DIRECT, shell.revision)!!
            assertFalse(revoked.consent.enabled)
            assertFalse(revoked.consent.shellEnabled)
            assertTrue(revoked.consent.revision > shell.consent.revision)
            assertEquals(revoked, RootAuthorityRepository(db).get(Authority.ROOT_DIRECT))
            assertFalse(repo.get(Authority.SHIZUKU_ROOT).consent.enabled)
        }
    }

    @Test
    fun stalePolicyCannotConfirmRootOrShell() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repo = RootAuthorityRepository(db)
            val root = repo.confirmRoot(Authority.ROOT_DIRECT, 0, 0)!!
            AuthorityPolicyRepository(db).updatePolicy(0, Authority.ROOT_DIRECT, DangerousMode.DISABLED)
            assertThrows(IllegalArgumentException::class.java) {
                repo.setShellConsent(Authority.ROOT_DIRECT, root.revision, true, 0)
            }
            assertThrows(IllegalArgumentException::class.java) {
                repo.confirmRoot(Authority.SHIZUKU_ROOT, 0, 0)
            }
            assertEquals(root, repo.get(Authority.ROOT_DIRECT))
            assertEquals(0, repo.get(Authority.SHIZUKU_ROOT).revision)
        }
    }

    @Test
    fun degradedSurvivesRepositoryRecreationConsentAndReconnect() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            var repo = RootAuthorityRepository(db)
            val identity = repo.advanceIdentityEpoch(Authority.ROOT_DIRECT, 0)!!
            var state = repo.markDegraded(Authority.ROOT_DIRECT, identity.revision, identity.identityEpoch)!!
            repo = RootAuthorityRepository(db)
            assertTrue(repo.get(Authority.ROOT_DIRECT).degraded)
            state = repo.confirmRoot(Authority.ROOT_DIRECT, state.revision, 0)!!
            assertTrue(state.degraded)
            assertThrows(IllegalArgumentException::class.java) {
                repo.acknowledgeRecovery(Authority.ROOT_DIRECT, state.revision, state.identityEpoch, true)
            }
            state = repo.advanceIdentityEpoch(Authority.ROOT_DIRECT, state.revision)!!
            assertTrue(state.degraded)
            assertThrows(IllegalArgumentException::class.java) {
                repo.acknowledgeRecovery(Authority.ROOT_DIRECT, state.revision, state.identityEpoch, false)
            }
            state = repo.acknowledgeRecovery(Authority.ROOT_DIRECT, state.revision, state.identityEpoch, true)!!
            assertFalse(state.degraded)
        }
    }

    @Test
    fun ignoredWritesAndCounterOverflowDoNotReportSuccess() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repo = RootAuthorityRepository(db)
            val root = repo.confirmRoot(Authority.ROOT_DIRECT, 0, 0)!!
            val changes = repo.changes.value
            db.execute("CREATE TRIGGER ignore_root BEFORE UPDATE ON root_authority_state BEGIN SELECT RAISE(IGNORE); END")
            assertThrows(IllegalStateException::class.java) {
                repo.revokeRoot(Authority.ROOT_DIRECT, root.revision)
            }
            assertEquals(root, repo.get(Authority.ROOT_DIRECT))
            assertEquals(changes, repo.changes.value)
            db.execute("DROP TRIGGER ignore_root")
            db.execute("UPDATE root_authority_state SET identity_epoch = ?", listOf(Long.MAX_VALUE))
            assertThrows(ArithmeticException::class.java) {
                repo.advanceIdentityEpoch(Authority.ROOT_DIRECT, root.revision)
            }
            assertEquals(Long.MAX_VALUE, repo.get(Authority.ROOT_DIRECT).identityEpoch)
        }
    }

    @Test
    fun v39MigrationPreservesShellPolicyAndDoesNotInferRootTrust() {
        JdbcSqlConnection().use { db ->
            legacy39(db)
            Migrations.apply(db)
            val policy = AuthorityPolicyRepository(db).getPolicy()
            assertEquals(Authority.SHIZUKU, policy.selectedAuthority)
            assertEquals(DangerousMode.ENABLED_AUTONOMOUS, policy.dangerousMode)
            assertEquals(7, policy.policyVersion)
            assertTrue(AuthorityPolicyRepository(db).getPreference(Authority.SHIZUKU).userIntentEnabled)
            assertFalse(RootAuthorityRepository(db).get(Authority.ROOT_DIRECT).consent.enabled)
            assertTrue(db.query("SELECT * FROM root_authority_state").isEmpty())
            AuthorityPolicyRepository(db).updatePolicy(7, Authority.SHIZUKU_ROOT, DangerousMode.DISABLED)
            Migrations.apply(db)
            assertEquals(Authority.SHIZUKU_ROOT, AuthorityPolicyRepository(db).getPolicy().selectedAuthority)
            assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test
    fun unknownLegacyObjectsAbortAtomicallyAndPreserveVersionAndRows() {
        JdbcSqlConnection().use { db ->
            legacy39(db)
            db.execute("CREATE INDEX legacy_authority_index ON authority_preferences(updated_at)")
            assertThrows(IllegalStateException::class.java) { Migrations.apply(db) }
            assertEquals(39, db.query("SELECT version FROM schema_version").single().long("version"))
            assertEquals("SHIZUKU", db.query("SELECT selected_authority FROM authority_policy").single().string("selected_authority"))
            assertTrue(db.query("SELECT name FROM sqlite_master WHERE name='legacy_authority_index'").isNotEmpty())
            assertTrue(db.query("SELECT name FROM sqlite_master WHERE name LIKE '%_root_v40'").isEmpty())
            assertThrows(Exception::class.java) {
                db.execute("UPDATE authority_policy SET selected_authority='ROOT_DIRECT'")
            }
        }
    }

    @Test
    fun lateUncertainDescendantsFromAnOldSessionStillPersistDegraded() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repo = RootAuthorityRepository(db)
            val old = repo.advanceIdentityEpoch(Authority.ROOT_DIRECT, 0)!!
            val fresh = repo.advanceIdentityEpoch(Authority.ROOT_DIRECT, old.revision)!!
            val degraded = repo.markDegraded(Authority.ROOT_DIRECT, fresh.revision, old.identityEpoch)!!
            assertTrue(degraded.degraded)
            assertEquals(old.identityEpoch, degraded.degradedEpoch)
            assertTrue(RootAuthorityRepository(db).get(Authority.ROOT_DIRECT).degraded)
            assertThrows(IllegalArgumentException::class.java) {
                repo.markDegraded(Authority.ROOT_DIRECT, degraded.revision, fresh.identityEpoch + 1)
            }
        }
    }

    @Test
    fun overlappingDegradationRequiresAnIdentityAfterTheNewestUncertainSession() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repo = RootAuthorityRepository(db)
            var state = repo.advanceIdentityEpoch(Authority.ROOT_DIRECT, 0)!!
            state = repo.markDegraded(Authority.ROOT_DIRECT, state.revision, 1)!!
            state = repo.advanceIdentityEpoch(Authority.ROOT_DIRECT, state.revision)!!
            state = repo.advanceIdentityEpoch(Authority.ROOT_DIRECT, state.revision)!!
            state = repo.markDegraded(Authority.ROOT_DIRECT, state.revision, 3)!!
            assertEquals(3L, state.degradedEpoch)
            state = repo.markDegraded(Authority.ROOT_DIRECT, state.revision, 1)!!
            assertEquals(3L, state.degradedEpoch) // A late old report cannot weaken recovery.
            assertThrows(IllegalArgumentException::class.java) {
                repo.acknowledgeRecovery(Authority.ROOT_DIRECT, state.revision, 3, true)
            }
            assertTrue(repo.get(Authority.ROOT_DIRECT).degraded)
            state = repo.advanceIdentityEpoch(Authority.ROOT_DIRECT, state.revision)!!
            assertFalse(repo.acknowledgeRecovery(Authority.ROOT_DIRECT, state.revision, 4, true)!!.degraded)
        }
    }

    @Test fun explicitRecoveryPersistsAMonotonicWatermarkAndIgnoresOnlyCoveredLateEpochs() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            var repo = RootAuthorityRepository(db)
            var state = repo.confirmRoot(Authority.ROOT_DIRECT,0,0)!!
            state = repo.advanceIdentityEpoch(Authority.ROOT_DIRECT,state.revision)!!
            state = repo.markDegraded(Authority.ROOT_DIRECT,state.revision,1)!!
            state = repo.advanceIdentityEpoch(Authority.ROOT_DIRECT,state.revision)!!
            state = repo.advanceIdentityEpoch(Authority.ROOT_DIRECT,state.revision)!!
            assertEquals(0,state.recoveryAckEpoch)
            assertThrows(IllegalArgumentException::class.java) {
                repo.acknowledgeRecovery(Authority.ROOT_DIRECT,state.revision,3,false)
            }
            state = repo.acknowledgeRecovery(Authority.ROOT_DIRECT,state.revision,3,true)!!
            assertEquals(2,state.recoveryAckEpoch)
            assertFalse(state.degraded)
            repo = RootAuthorityRepository(db)
            assertEquals(state,repo.get(Authority.ROOT_DIRECT))
            assertEquals(state,repo.markDegraded(Authority.ROOT_DIRECT,state.revision,1))
            assertEquals(state,repo.markDegraded(Authority.ROOT_DIRECT,state.revision,2))
            state = repo.markDegraded(Authority.ROOT_DIRECT,state.revision,3)!!
            assertEquals(3,state.degradedEpoch)
            assertEquals(2,state.recoveryAckEpoch)
            state = repo.advanceIdentityEpoch(Authority.ROOT_DIRECT,state.revision)!!
            state = repo.acknowledgeRecovery(Authority.ROOT_DIRECT,state.revision,4,true)!!
            assertEquals(3,state.recoveryAckEpoch)
            assertEquals(state,repo.markDegraded(Authority.ROOT_DIRECT,state.revision,3))
            assertEquals(0,repo.get(Authority.SHIZUKU_ROOT).recoveryAckEpoch)
        }
    }

    @Test fun oldDraftStateAddsZeroWatermarkWithoutAcknowledgingExistingUncertainty() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repo = RootAuthorityRepository(db)
            var state = repo.confirmRoot(Authority.ROOT_DIRECT,0,0)!!
            state = repo.advanceIdentityEpoch(Authority.ROOT_DIRECT,state.revision)!!
            state = repo.markDegraded(Authority.ROOT_DIRECT,state.revision,1)!!
            // Removing this column restores the prior unpublished table without losing its PK/CHECK.
            db.execute("ALTER TABLE root_authority_state DROP COLUMN recovery_ack_epoch")
            Migrations.apply(db)
            assertEquals(state,repo.get(Authority.ROOT_DIRECT))
            assertTrue(repo.get(Authority.ROOT_DIRECT).degraded)
            assertEquals(0,repo.get(Authority.ROOT_DIRECT).recoveryAckEpoch)
        }
    }

    @Test fun recoveryBetweenTheTwoReadsCannotBeOverwrittenByALateDegradedCas() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val writer = RootAuthorityRepository(db)
            var state = writer.confirmRoot(Authority.ROOT_DIRECT,0,0)!!
            state = writer.advanceIdentityEpoch(Authority.ROOT_DIRECT,state.revision)!!
            state = writer.markDegraded(Authority.ROOT_DIRECT,state.revision,1)!!
            state = writer.advanceIdentityEpoch(Authority.ROOT_DIRECT,state.revision)!!
            var reads = 0
            val interleaved = object : SqlConnection by db {
                override fun query(sql: String, args: List<Any?>): List<SqlRow> {
                    if (sql.startsWith("SELECT * FROM root_authority_state") && ++reads == 2) {
                        writer.acknowledgeRecovery(Authority.ROOT_DIRECT,state.revision,2,true)
                    }
                    return db.query(sql,args)
                }
            }
            assertNull(RootAuthorityRepository(interleaved).markDegraded(Authority.ROOT_DIRECT,state.revision,1))
            val recovered = writer.get(Authority.ROOT_DIRECT)
            assertFalse(recovered.degraded)
            assertEquals(1,recovered.recoveryAckEpoch)
            assertEquals(state.revision+1,recovered.revision)
        }
    }

    private fun legacy39(db: SqlConnection) {
        // Restore all Root additions to the actual pre-v40 shape; preserve the v39-compatible
        // surrounding schema (world-book revisions etc.), then restore actual v39 constraints.
        Migrations.apply(db)
        db.execute("DROP TABLE authority_policy")
        db.execute("DROP TABLE authority_preferences")
        db.execute("DROP TABLE root_dispatch_receipts")
        db.execute("DROP TABLE root_audit_bindings")
        db.execute("DROP TABLE root_approval_bindings")
        db.execute("DROP TABLE root_snapshot_grants")
        db.execute("DROP TABLE root_snapshot_seals")
        db.execute("DROP TABLE root_grant_bindings")
        db.execute("DROP TABLE root_workspace_locators")
        db.execute("DROP TABLE root_workspace_origins")
        db.execute("DROP TABLE root_authority_state")
        db.execute("DROP TABLE approval_records")
        db.execute("DROP TABLE tool_audit_details")
        db.execute(RootAuthoritySchema.APPROVAL_SQL.replace(",'ROOT_DIRECT','SHIZUKU_ROOT'", ""))
        db.execute(RootAuthoritySchema.AUDIT_SQL.replace(",'ROOT_DIRECT','SHIZUKU_ROOT'", ""))
        db.execute("UPDATE schema_version SET version=39")
        db.execute(RootAuthoritySchema.POLICY_SQL.replace(",'ROOT_DIRECT','SHIZUKU_ROOT'", ""))
        db.execute(RootAuthoritySchema.PREFERENCES_SQL.replace(",'ROOT_DIRECT','SHIZUKU_ROOT'", ""))
        db.execute("INSERT INTO authority_policy VALUES(1,'SHIZUKU','ENABLED_AUTONOMOUS',7,'legacy')")
        db.execute("INSERT INTO authority_preferences VALUES('SHIZUKU',1,1,'legacy')")
    }
}
