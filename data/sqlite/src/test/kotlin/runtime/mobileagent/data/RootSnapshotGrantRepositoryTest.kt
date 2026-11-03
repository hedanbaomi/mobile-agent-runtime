// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.*

class RootSnapshotGrantRepositoryTest {
    private val now = "2026-10-29T11:00:00Z"
    private fun execution() = RootExecutionStamp(
        PrivilegedSessionIdentity(Authority.ROOT_DIRECT,PrivilegeTier.ROOT,0,10123,0,
            "app-instance-000001","runtime.mobileagent","a".repeat(64),1,
            "service-instance-001","session-instance-001",4),1,1,1,
    )
    private fun setup(db: SqlConnection, lifetime: GrantLifetime = GrantLifetime.PERSISTENT) {
        Migrations.apply(db)
        AuthorityPolicyRepository(db).updatePolicy(0,Authority.ROOT_DIRECT,DangerousMode.DISABLED)
        val roots = RootAuthorityRepository(db)
        val consent = roots.confirmRoot(Authority.ROOT_DIRECT,0,1)!!
        roots.advanceIdentityEpoch(Authority.ROOT_DIRECT,consent.revision)
        RootWorkspaceRepository(db) { "root-workspace" }.createNew("Root fixture",true,execution())
        RootGrantRepository(db) { now }.create(CapabilityGrant(
            "root-grant","agent-root",CapabilityId(CapabilityId.FILE_READ_TEXT),
            workspaceId="root-workspace",policyVersion=1,lifetime=lifetime,
        ),execution())
    }
    private fun insertSnapshot(db: SqlConnection, id: String, agent: String = "agent-root") {
        db.execute(
            "INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at,provider_id,chat_model_revision,parameter_overrides_json,context_policy_json,permission_settings_json,binding_manifest_json,expanded_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(id,1,agent,"prompt-1","model-1",1,"[]","[]",now,"provider-1",1,"{}","{}","{}","{}","{}"),
        )
    }
    private fun freeze(db: SqlConnection, id: String = "snapshot-root") =
        RootSnapshotGrantRepository(db) { now }.createNewSnapshot(
            id,"agent-root",setOf("root-grant"),execution(),
        ) { insertSnapshot(db,id); id }

    @Test fun newSnapshotFreezesExactCanonicalGrantAndRootGeneration() {
        JdbcSqlConnection().use { db ->
            setup(db)
            assertEquals("snapshot-root",freeze(db))
            val repo = RootSnapshotGrantRepository(db) { now }
            val frozen = repo.revalidate("snapshot-root","root-grant",execution())!!
            assertEquals("root-grant",frozen.grant.grantId)
            assertEquals(1,CapabilityGrantRepository(db).listSnapshotBindings("snapshot-root").size)
            assertEquals(frozen,RootSnapshotGrantRepository(db) { now }.revalidate("snapshot-root","root-grant",execution()))
            assertNull(repo.revalidate("snapshot-root","missing-grant",execution()))
            assertNull(repo.revalidate("snapshot-root","root-grant",execution().copy(
                identity=execution().identity.copy(appInstanceId="another-app-instance1"))))
            assertThrows(Exception::class.java) { db.execute("UPDATE root_snapshot_grants SET binding_json='{}'") }
        }
    }

    @Test fun existingSnapshotCannotAcquireRootAfterCreation() {
        JdbcSqlConnection().use { db ->
            setup(db)
            insertSnapshot(db,"old-snapshot")
            var callbackCalled = false
            assertThrows(IllegalArgumentException::class.java) {
                RootSnapshotGrantRepository(db) { now }.createNewSnapshot(
                    "old-snapshot","agent-root",setOf("root-grant"),execution(),
                ) { callbackCalled = true }
            }
            assertFalse(callbackCalled)
            assertNull(RootSnapshotGrantRepository(db) { now }.revalidate("old-snapshot","root-grant",execution()))
            assertTrue(db.query("SELECT * FROM root_snapshot_grants").isEmpty())
        }
    }

    @Test fun failedSnapshotCreationAndWrongAgentRollBackEverything() {
        JdbcSqlConnection().use { db ->
            setup(db)
            val repo = RootSnapshotGrantRepository(db) { now }
            assertThrows(IllegalArgumentException::class.java) {
                repo.createNewSnapshot("wrong-agent","agent-root",setOf("root-grant"),execution()) {
                    insertSnapshot(db,"wrong-agent","another-agent")
                }
            }
            assertTrue(db.query("SELECT * FROM agent_snapshots WHERE id='wrong-agent'").isEmpty())
            assertTrue(db.query("SELECT * FROM snapshot_grant_bindings").isEmpty())
            db.execute("CREATE TRIGGER reject_root_seal BEFORE INSERT ON root_snapshot_seals BEGIN SELECT RAISE(ABORT,'fixture'); END")
            assertThrows(Exception::class.java) { freeze(db) }
            assertTrue(db.query("SELECT * FROM agent_snapshots").isEmpty())
            assertTrue(db.query("SELECT * FROM root_snapshot_grants").isEmpty())
            assertTrue(db.query("SELECT * FROM snapshot_grant_bindings").isEmpty())
        }
    }

    @Test fun changedGrantWorkspaceOrPolicyCannotReuseFrozenAdmission() {
        JdbcSqlConnection().use { db ->
            setup(db)
            freeze(db)
            val repo = RootSnapshotGrantRepository(db) { now }
            val workspaces = WorkspaceRepository(db)
            val original = workspaces.get("root-workspace")!!
            workspaces.save(original.copy(rootReference="changed-opaque-fixture"))
            assertNull(repo.revalidate("snapshot-root","root-grant",execution()))
            workspaces.save(original)
            assertNotNull(repo.revalidate("snapshot-root","root-grant",execution()))
            CapabilityGrantRepository(db).revoke("root-grant")
            assertNull(repo.revalidate("snapshot-root","root-grant",execution()))
            AuthorityPolicyRepository(db).updatePolicy(1,Authority.SHIZUKU,DangerousMode.DISABLED)
            assertNull(repo.revalidate("snapshot-root","root-grant",execution()))
        }
    }

    @Test fun replacingTheSameNumberOfGrantsCannotChangeASealedSnapshot() {
        JdbcSqlConnection().use { db ->
            setup(db)
            freeze(db)
            val canonical = CapabilityGrantRepository(db)
            val other = canonical.get("root-grant")!!.copy(grantId="root-grant-b")
            RootGrantRepository(db) { now }.create(other,execution())
            val repo = RootSnapshotGrantRepository(db) { now }
            repo.createNewSnapshot("snapshot-b","agent-root",setOf(other.grantId),execution()) {
                insertSnapshot(db,"snapshot-b")
            }
            val payload = db.query("SELECT binding_json FROM root_snapshot_grants WHERE snapshot_id='snapshot-b'").single().string("binding_json")
            val replacement = Json.decodeFromString<RootSnapshotGrantBinding>(payload).copy(snapshotId="snapshot-root")
            canonical.bindSnapshot(SnapshotGrantBinding("snapshot-root",other.grantId,other.capability,other.workspaceId,other.pathScope,other.policyVersion))
            db.execute("DELETE FROM root_snapshot_grants WHERE snapshot_id='snapshot-root'")
            db.execute("INSERT INTO root_snapshot_grants(snapshot_id,grant_id,binding_json) VALUES(?,?,?)",
                listOf("snapshot-root",other.grantId,Json.encodeToString(replacement)))
            assertNull(repo.revalidate("snapshot-root",other.grantId,execution()))
        }
    }

    @Test fun ignoredSealWriteRollsBackAndDraftSealsCannotAcquireNewTrustOnUpgrade() {
        JdbcSqlConnection().use { db ->
            setup(db)
            db.execute("CREATE TRIGGER ignore_seal BEFORE INSERT ON root_snapshot_seals BEGIN SELECT RAISE(IGNORE); END")
            assertThrows(IllegalStateException::class.java) { freeze(db) }
            assertTrue(db.query("SELECT * FROM agent_snapshots").isEmpty())
            assertTrue(db.query("SELECT * FROM root_snapshot_grants").isEmpty())
            db.execute("DROP TRIGGER ignore_seal")
            freeze(db)
            db.execute("ALTER TABLE root_snapshot_seals DROP COLUMN binding_set_hash")
            Migrations.apply(db)
            assertEquals("",db.query("SELECT binding_set_hash FROM root_snapshot_seals").single().string("binding_set_hash"))
            assertNull(RootSnapshotGrantRepository(db) { now }.revalidate("snapshot-root","root-grant",execution()))
            assertEquals(1,db.query("SELECT * FROM root_snapshot_grants").size)
        }
    }

    @Test fun consumedOnceNeedsASeparateDispatchReceiptAndCannotReenterPreflight() {
        JdbcSqlConnection().use { db ->
            setup(db,GrantLifetime.ONCE)
            freeze(db)
            val repo = RootSnapshotGrantRepository(db) { now }
            assertNotNull(repo.revalidate("snapshot-root","root-grant",execution()))
            assertNotNull(CapabilityGrantRepository(db).consumeOnce("root-grant",1,consumedAt=now))
            assertNull(repo.revalidate("snapshot-root","root-grant",execution()))
        }
    }
}
