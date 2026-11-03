// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

class RootWorkspaceRepositoryTest {
    private fun execution() = RootExecutionStamp(
        PrivilegedSessionIdentity(Authority.ROOT_DIRECT,PrivilegeTier.ROOT,0,10123,0,
            "app-instance-000001","runtime.mobileagent","a".repeat(64),1,
            "service-instance-001","session-instance-001",4),1,1,1,
    )
    private fun setup(db: SqlConnection) {
        Migrations.apply(db)
        AuthorityPolicyRepository(db).updatePolicy(0,Authority.ROOT_DIRECT,DangerousMode.DISABLED)
        val roots = RootAuthorityRepository(db)
        val root = roots.confirmRoot(Authority.ROOT_DIRECT,0,1)!!
        roots.advanceIdentityEpoch(Authority.ROOT_DIRECT,root.revision)
    }

    @Test fun newOriginBindsRouteAndInstallationAndRejectsReusingAnyWorkspace() {
        JdbcSqlConnection().use { db ->
            setup(db)
            val repo = RootWorkspaceRepository(db) { "root-workspace" }
            val created = repo.createNew("Root fixture",true,execution())
            val origin = repo.getOrigin(created.id)!!
            assertTrue(origin.matches(execution().identity))
            assertEquals("root:"+origin.generationId,created.rootReference)
            assertFalse(WorkspaceRepository(db).delete(created.id))
            assertEquals(origin,repo.getOrigin(created.id))
            assertFalse(origin.matches(execution().identity.copy(authority=Authority.SHIZUKU_ROOT)))
            assertFalse(origin.matches(execution().identity.copy(appInstanceId="another-app-instance1")))
            assertThrows(IllegalArgumentException::class.java) { repo.createNew("Again",true,execution()) }
            assertThrows(Exception::class.java) { db.execute("UPDATE root_workspace_origins SET authority='SHIZUKU_ROOT'") }
            WorkspaceRepository(db).save(Workspace("adb-workspace","Old",WorkspaceBackendType.PRIVILEGED,"opaque-adb"))
            assertThrows(IllegalArgumentException::class.java) {
                RootWorkspaceRepository(db) { "adb-workspace" }.createNew("Cannot promote",true,execution())
            }
        }
    }

    @Test fun failedOriginInsertRollsBackWorkspaceAndRootRevocationBlocksCreation() {
        JdbcSqlConnection().use { db ->
            setup(db)
            val repo = RootWorkspaceRepository(db) { "root-workspace" }
            db.execute("CREATE TRIGGER reject_origin BEFORE INSERT ON root_workspace_origins BEGIN SELECT RAISE(ABORT,'fixture'); END")
            assertThrows(Exception::class.java) { repo.createNew("Root fixture",true,execution()) }
            assertNull(WorkspaceRepository(db).get("root-workspace"))
            db.execute("DROP TRIGGER reject_origin")
            val roots = RootAuthorityRepository(db)
            roots.revokeRoot(Authority.ROOT_DIRECT,roots.get(Authority.ROOT_DIRECT).revision)
            assertThrows(IllegalArgumentException::class.java) { repo.createNew("Revoked",true,execution()) }
            assertNull(repo.getOrigin("root-workspace"))
        }
    }
}
