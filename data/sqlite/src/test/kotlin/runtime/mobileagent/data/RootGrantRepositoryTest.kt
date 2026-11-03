// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

class RootGrantRepositoryTest {
    private val now = "2026-10-29T08:00:00Z"
    private fun execution() = RootExecutionStamp(
        PrivilegedSessionIdentity(Authority.ROOT_DIRECT,PrivilegeTier.ROOT,0,10123,0,
            "app-instance-000001","runtime.mobileagent","a".repeat(64),1,
            "service-instance-001","session-instance-001",4),1,1,1,
    )
    private fun setup(db: SqlConnection) {
        Migrations.apply(db)
        AuthorityPolicyRepository(db).updatePolicy(0,Authority.ROOT_DIRECT,DangerousMode.DISABLED)
        val roots = RootAuthorityRepository(db)
        val consent = roots.confirmRoot(Authority.ROOT_DIRECT,0,1)!!
        roots.advanceIdentityEpoch(Authority.ROOT_DIRECT,consent.revision)
        RootWorkspaceRepository(db) { "root-workspace" }.createNew("Root fixture",true,execution())
    }
    private fun grant(id: String = "root-grant") = CapabilityGrant(
        id,"agent-root",CapabilityId(CapabilityId.FILE_READ_TEXT),workspaceId="root-workspace",policyVersion=1,
    )

    @Test fun freshCanonicalGrantAndScopeAreCommittedTogether() {
        JdbcSqlConnection().use { db ->
            setup(db)
            val repo = RootGrantRepository(db) { now }
            val binding = repo.create(grant(),execution())
            assertEquals(binding,RootGrantRepository(db).get(binding.grantId))
            val canonical = CapabilityGrantRepository(db).get(binding.grantId)!!
            assertTrue(binding.matches(canonical,execution()))
            assertFalse(binding.matches(canonical.copy(revision=2),execution()))
            assertFalse(binding.matches(canonical.copy(workspaceId="other-workspace"),execution()))
            assertFalse(binding.matches(canonical,execution().copy(identity=execution().identity.copy(authority=Authority.SHIZUKU_ROOT))))
            assertFalse(binding.matches(canonical,execution().copy(identity=execution().identity.copy(appInstanceId="different-app-00001"))))
            assertFalse(binding.matches(canonical,execution().copy(identity=execution().identity.copy(packageName="another.application"))))
            assertFalse(binding.matches(canonical,execution().copy(consentRevision=2)))
            assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test fun oldGrantCannotBePromotedAndFailedBindingRollsBackCanonicalInsert() {
        JdbcSqlConnection().use { db ->
            setup(db)
            val canonical = CapabilityGrantRepository(db) { now }
            canonical.save(grant("old-adb-grant"))
            val repo = RootGrantRepository(db) { now }
            assertThrows(IllegalArgumentException::class.java) { repo.create(grant("old-adb-grant"),execution()) }
            assertNull(repo.get("old-adb-grant"))
            db.execute("CREATE TRIGGER reject_root_grant BEFORE INSERT ON root_grant_bindings BEGIN SELECT RAISE(ABORT,'fixture'); END")
            assertThrows(Exception::class.java) { repo.create(grant(),execution()) }
            assertNull(canonical.get("root-grant"))
        }
    }

    @Test fun existingAdbWorkspaceCannotBePromotedToARootGrant() {
        JdbcSqlConnection().use { db ->
            setup(db)
            WorkspaceRepository(db).save(Workspace("adb-workspace","ADB fixture",WorkspaceBackendType.PRIVILEGED,"opaque-adb"))
            PrivilegedWorkspaceBindingRepository(db).save(PrivilegedWorkspaceBinding(
                "adb-workspace",Authority.SHIZUKU,byteArrayOf(1),ByteArray(12),aadAppInstanceId="app-adb",
            ))
            assertThrows(IllegalArgumentException::class.java) {
                RootGrantRepository(db) { now }.create(grant("new-root-grant").copy(workspaceId="adb-workspace"),execution())
            }
            assertNull(CapabilityGrantRepository(db).get("new-root-grant"))
            assertEquals(Authority.SHIZUKU,PrivilegedWorkspaceBindingRepository(db).get("adb-workspace")!!.authority)
        }
    }

    @Test fun weakerDraftBindingsArePreservedButNeverBackfilledIntoRootTrust() {
        JdbcSqlConnection().use { db ->
            setup(db)
            val repo = RootGrantRepository(db) { now }
            repo.create(grant(),execution())
            // Restore the prior WIP columns while retaining its PK/FK/check constraints.
            db.execute("ALTER TABLE root_grant_bindings DROP COLUMN workspace_generation_id")
            db.execute("ALTER TABLE root_grant_bindings DROP COLUMN package_name")
            Migrations.apply(db)
            assertEquals(1,db.query("SELECT * FROM root_grant_bindings").size)
            assertEquals("",db.query("SELECT workspace_generation_id FROM root_grant_bindings").single().string("workspace_generation_id"))
            assertNull(repo.get("root-grant"))
            assertThrows(IllegalArgumentException::class.java) { repo.create(grant(),execution()) }
            assertNotNull(CapabilityGrantRepository(db).get("root-grant"))
            val fresh = repo.create(grant("fresh-after-upgrade"),execution())
            assertEquals(fresh,repo.get("fresh-after-upgrade"))
        }
    }

    @Test fun policyRevocationAndDegradationRejectNewBindings() {
        JdbcSqlConnection().use { db ->
            setup(db)
            val repo = RootGrantRepository(db) { now }
            val roots = RootAuthorityRepository(db)
            val root = roots.get(Authority.ROOT_DIRECT)
            roots.markDegraded(Authority.ROOT_DIRECT,root.revision,1)
            assertThrows(IllegalArgumentException::class.java) { repo.create(grant(),execution()) }
            assertNull(CapabilityGrantRepository(db).get("root-grant"))
            AuthorityPolicyRepository(db).updatePolicy(1,Authority.SHIZUKU,DangerousMode.DISABLED)
            assertThrows(IllegalArgumentException::class.java) { repo.create(grant(),execution()) }
            assertTrue(db.query("SELECT * FROM root_grant_bindings").isEmpty())
        }
    }

    @Test fun readOnlyWorkspaceUnsupportedCapabilityAndShellWithoutFiveWayGateAreRejected() {
        JdbcSqlConnection().use { db ->
            setup(db)
            val repo = RootGrantRepository(db) { now }
            RootWorkspaceRepository(db) { "ro-root-workspace" }.createNew("Read-only Root", false, execution())
            assertNotNull(repo.create(grant("ro-read-grant").copy(workspaceId = "ro-root-workspace"), execution()))
            assertThrows(IllegalArgumentException::class.java) {
                repo.create(
                    grant("ro-write-grant").copy(
                        workspaceId = "ro-root-workspace",
                        capability = CapabilityId(CapabilityId.FILE_WRITE_TEXT),
                    ),
                    execution(),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                repo.create(
                    grant("unsupported-cap-grant").copy(capability = CapabilityId("memory.read")),
                    execution(),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                repo.create(
                    grant("shell-without-consent").copy(capability = CapabilityId(CapabilityId.SHELL_EXECUTE)),
                    execution(),
                )
            }
            val roots = RootAuthorityRepository(db)
            val current = roots.get(Authority.ROOT_DIRECT)
            val withShell = roots.setShellConsent(Authority.ROOT_DIRECT, current.revision, true, 1)!!
            val shellStamp = execution().copy(shellConsentRevision = withShell.consent.shellRevision)
            assertThrows(IllegalArgumentException::class.java) {
                repo.create(
                    grant("shell-dangerous-disabled").copy(capability = CapabilityId(CapabilityId.SHELL_EXECUTE)),
                    shellStamp,
                )
            }
            AuthorityPolicyRepository(db).updatePolicy(1, Authority.ROOT_DIRECT, DangerousMode.ENABLED_CONFIRM_HIGH_RISK)
            val consent2 = roots.confirmRoot(Authority.ROOT_DIRECT, withShell.revision, 2)!!
            val withShell2 = roots.setShellConsent(Authority.ROOT_DIRECT, consent2.revision, true, 2)!!
            roots.advanceIdentityEpoch(Authority.ROOT_DIRECT, withShell2.revision)
            val readyShellStamp = execution().copy(
                identity = execution().identity.copy(identityEpoch = 2),
                consentRevision = withShell2.consent.revision,
                shellConsentRevision = withShell2.consent.shellRevision,
                policyVersion = 2,
            )
            assertNotNull(
                repo.create(
                    grant("shell-allowed").copy(
                        capability = CapabilityId(CapabilityId.SHELL_EXECUTE),
                        policyVersion = 2,
                    ),
                    readyShellStamp,
                ),
            )
        }
    }
}
