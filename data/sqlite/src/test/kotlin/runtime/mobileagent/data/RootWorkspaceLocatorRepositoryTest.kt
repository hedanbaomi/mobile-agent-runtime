// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

class RootWorkspaceLocatorRepositoryTest {
    private fun execution() = RootExecutionStamp(
        PrivilegedSessionIdentity(Authority.ROOT_DIRECT,PrivilegeTier.ROOT,0,10123,0,
            "app-instance-000001","runtime.mobileagent","a".repeat(64),1,
            "service-instance-001","session-instance-001",4),1,1,1,
    )
    private fun setup(db: SqlConnection): RootWorkspaceOrigin {
        Migrations.apply(db)
        AuthorityPolicyRepository(db).updatePolicy(0,Authority.ROOT_DIRECT,DangerousMode.DISABLED)
        val roots = RootAuthorityRepository(db)
        val root = roots.confirmRoot(Authority.ROOT_DIRECT,0,1)!!
        roots.advanceIdentityEpoch(Authority.ROOT_DIRECT,root.revision)
        val repo = RootWorkspaceRepository(db) { "root-workspace" }
        repo.createNew("Fixture",true,execution())
        return repo.getOrigin("root-workspace")!!
    }

    @Test fun ciphertextRoundTripsDefensivelyAndLocatorCannotBeReplaced() {
        JdbcSqlConnection().use { db ->
            val origin = setup(db)
            val input = ByteArray(72) { (it+1).toByte() } // Synthetic ciphertext, never a real path.
            val locator = RootWorkspaceLocator(origin,input)
            input.fill(0)
            val repo = RootWorkspaceLocatorRepository(db)
            assertEquals(locator,repo.attachNew(locator,execution()))
            val read = repo.getCurrent(origin.workspaceId,execution())!!
            assertEquals(locator,read)
            val output = read.encryptedEnvelopeCopy()
            assertEquals(1,output[0].toInt())
            output.fill(0)
            assertEquals(locator,repo.get(origin.workspaceId))
            assertFalse(read.toString().contains(origin.appInstanceId))
            assertThrows(IllegalArgumentException::class.java) { repo.attachNew(locator,execution()) }
            assertThrows(Exception::class.java) {
                db.execute("UPDATE root_workspace_locators SET encrypted_envelope=?",listOf(byteArrayOf(5)))
            }
            Migrations.apply(db)
            assertEquals(locator,repo.get(origin.workspaceId))
            assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test fun routeInstallationGenerationAndRevocationFailClosedWithoutRemovingCiphertext() {
        JdbcSqlConnection().use { db ->
            val origin = setup(db)
            val repo = RootWorkspaceLocatorRepository(db)
            val encrypted = ByteArray(72) { 7 }
            assertThrows(IllegalArgumentException::class.java) {
                repo.attachNew(RootWorkspaceLocator(origin.copy(generationId="different-generation"),encrypted),execution())
            }
            assertNull(repo.get(origin.workspaceId))
            val locator = repo.attachNew(RootWorkspaceLocator(origin,encrypted),execution())
            val crossRoute = execution().copy(identity=execution().identity.copy(authority=Authority.SHIZUKU_ROOT))
            assertNull(repo.getCurrent(origin.workspaceId,crossRoute))
            val crossInstall = execution().copy(identity=execution().identity.copy(appInstanceId="another-app-instance"))
            assertNull(repo.getCurrent(origin.workspaceId,crossInstall))
            val roots = RootAuthorityRepository(db)
            roots.markDegraded(Authority.ROOT_DIRECT,roots.get(Authority.ROOT_DIRECT).revision,1)
            assertNull(repo.getCurrent(origin.workspaceId,execution()))
            assertEquals(locator,repo.get(origin.workspaceId))
            roots.revokeRoot(Authority.ROOT_DIRECT,roots.get(Authority.ROOT_DIRECT).revision)
            assertNull(repo.getCurrent(origin.workspaceId,execution()))
            assertEquals(locator,repo.get(origin.workspaceId))
        }
    }

    @Test fun ignoredInsertRollsBackAndOldAdbRowsNeverBecomeRootLocators() {
        JdbcSqlConnection().use { db ->
            val origin = setup(db)
            val repo = RootWorkspaceLocatorRepository(db)
            db.execute("CREATE TRIGGER ignore_locator BEFORE INSERT ON root_workspace_locators BEGIN SELECT RAISE(IGNORE); END")
            assertThrows(IllegalStateException::class.java) {
                repo.attachNew(RootWorkspaceLocator(origin,ByteArray(72) { 8 }),execution())
            }
            assertNull(repo.get(origin.workspaceId))
            db.execute("DROP TRIGGER ignore_locator")
            WorkspaceRepository(db).save(Workspace("old-adb","Old",WorkspaceBackendType.PRIVILEGED,"opaque-adb"))
            PrivilegedWorkspaceBindingRepository(db).save(PrivilegedWorkspaceBinding(
                "old-adb",Authority.SHIZUKU,byteArrayOf(1),ByteArray(12),aadAppInstanceId="app-adb",
            ))
            Migrations.apply(db)
            assertNull(repo.get("old-adb"))
            assertEquals(1,db.query("SELECT * FROM privileged_workspace_bindings").size)
            assertTrue(db.query("SELECT * FROM root_workspace_locators").isEmpty())
        }
    }
}
