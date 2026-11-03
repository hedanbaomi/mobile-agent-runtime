// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.CapabilityGrantRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.PrivilegedWorkspaceBindingRepository
import runtime.mobileagent.data.ProfileRepository
import runtime.mobileagent.data.RootAuthorityRepository
import runtime.mobileagent.data.RootGrantRepository
import runtime.mobileagent.data.RootSnapshotGrantRepository
import runtime.mobileagent.data.RootWorkspaceRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.PrivilegedWorkspaceBinding
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.RootExecutionStamp
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.domain.WorkspaceBackendType

/**
 * Synthetic local state only. These fixtures prove grant/snapshot persistence gates and the real
 * `AgentRepository.createSnapshot` seam; they do not authenticate an OS Root session.
 */
class RootGrantSnapshotAdmissionTest {
    private val now = "2026-10-30T10:00:00Z"
    private val execution = RootExecutionStamp(
        PrivilegedSessionIdentity(
            Authority.ROOT_DIRECT, PrivilegeTier.ROOT, 0, 10123, 0,
            "app-instance-000001", "runtime.mobileagent", "a".repeat(64), 1,
            "service-instance-001", "session-instance-001", 4,
        ), 1, 1, 1,
    )
    private val read = RootGrantRequest(CapabilityId(CapabilityId.FILE_READ_TEXT))
    private val write = RootGrantRequest(CapabilityId(CapabilityId.FILE_WRITE_TEXT))

    private fun database(block: (SqlConnection) -> Unit) = InMemoryRootSqlConnection().use { db ->
        Migrations.apply(db)
        AuthorityPolicyRepository(db).updatePolicy(0, Authority.ROOT_DIRECT, DangerousMode.DISABLED)
        val roots = RootAuthorityRepository(db)
        val consent = roots.confirmRoot(Authority.ROOT_DIRECT, 0, 1)!!
        roots.advanceIdentityEpoch(Authority.ROOT_DIRECT, consent.revision)
        RootWorkspaceRepository(db) { "root-workspace" }.createNew("Root fixture", true, execution)
        block(db)
    }

    private fun agentFixture(db: SqlConnection) {
        val profiles = ProfileRepository(db)
        profiles.createProvider(
            ProviderProfile("provider", "Fixture", ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1", revision = 1),
        )
        profiles.createModel(
            ModelProfile("model", "provider", ModelRole.CHAT, "synthetic-text", emptySet(),
                contextLimit = 16_384, outputLimit = 512, revision = 1),
        )
        AgentRepository(db).saveWithPrompt(
            AgentProfile("agent-root", "Root Agent", "pending", "model", revision = 0),
            "Synthetic prompt",
        )
    }

    @Test
    fun preparedGrantsUseRandomIdsBoundToTheWorkspaceGeneration() = database { db ->
        val admission = RootGrantSnapshotAdmission(db)
        val prepared = admission.prepareGrants("agent-root", "root-workspace", listOf(read, write), execution)
        assertTrue(prepared is RootGrantPreparation.Prepared, prepared.toString())
        val bindings = (prepared as RootGrantPreparation.Prepared).bindings
        val origin = requireNotNull(RootWorkspaceRepository(db).getOrigin("root-workspace"))
        assertEquals(origin.generationId, prepared.workspaceGenerationId)
        assertEquals(2, bindings.size)
        assertEquals(2, bindings.map { it.grantId }.distinct().size)
        assertEquals(
            setOf(CapabilityId(CapabilityId.FILE_READ_TEXT), CapabilityId(CapabilityId.FILE_WRITE_TEXT)),
            bindings.map { requireNotNull(CapabilityGrantRepository(db).get(it.grantId)).capability }.toSet(),
        )
        bindings.forEach { binding ->
            assertEquals(binding, RootGrantRepository(db).get(binding.grantId))
            assertEquals("root-workspace", binding.workspaceId)
            assertEquals(origin.generationId, binding.workspaceGenerationId)
            assertEquals(execution.policyVersion, binding.policyVersion)
            assertTrue(binding.matches(requireNotNull(CapabilityGrantRepository(db).get(binding.grantId)), execution))
        }
        assertTrue(db.query("SELECT * FROM agent_snapshots").isEmpty())

        val again = admission.prepareGrants("agent-root", "root-workspace", listOf(read, write), execution)
        val secondIds = (again as RootGrantPreparation.Prepared).bindings.map { it.grantId }.toSet()
        assertTrue(secondIds.intersect(bindings.map { it.grantId }.toSet()).isEmpty())
    }

    @Test
    fun implicitMissingOrMismatchedCapabilitiesAreRefusedWithoutWrites() = database { db ->
        val admission = RootGrantSnapshotAdmission(db)
        assertEquals(RootGrantRefusal.NO_CAPABILITIES, refusedPreparation(admission.prepareGrants("agent-root", "root-workspace", emptyList(), execution)).reason)
        assertEquals(
            RootGrantRefusal.UNSUPPORTED_CAPABILITY,
            refusedPreparation(
                admission.prepareGrants(
                    "agent-root", "root-workspace",
                    listOf(RootGrantRequest(CapabilityId(CapabilityId.DEVICE_PROCESS_READ))), execution,
                ),
            ).reason,
        )
        assertEquals(
            RootGrantRefusal.DUPLICATE_CAPABILITY,
            refusedPreparation(admission.prepareGrants("agent-root", "root-workspace", listOf(read, read), execution)).reason,
        )
        assertEquals(
            RootGrantRefusal.INVALID_LIFETIME_OWNER,
            refusedPreparation(
                admission.prepareGrants(
                    "agent-root", "root-workspace",
                    listOf(read.copy(sessionId = "session-owner-1")), execution,
                ),
            ).reason,
        )
        assertTrue(db.query("SELECT * FROM capability_grants").isEmpty())
    }

    @Test
    fun adbOrReadOnlyWorkspacesCannotBecomeRootGrantWorkspaces() = database { db ->
        WorkspaceRepository(db).save(Workspace("old-adb", "Old ADB", WorkspaceBackendType.PRIVILEGED, "opaque-adb"))
        PrivilegedWorkspaceBindingRepository(db).save(
            PrivilegedWorkspaceBinding("old-adb", Authority.SHIZUKU, byteArrayOf(1), ByteArray(12), aadAppInstanceId = "app-adb"),
        )
        RootWorkspaceRepository(db) { "root-readonly" }.createNew("Read only", false, execution)
        val admission = RootGrantSnapshotAdmission(db)
        assertEquals(
            RootGrantRefusal.WORKSPACE_NOT_ROOT,
            refusedPreparation(admission.prepareGrants("agent-root", "old-adb", listOf(read), execution)).reason,
        )
        assertEquals(
            RootGrantRefusal.WORKSPACE_NOT_WRITABLE,
            refusedPreparation(admission.prepareGrants("agent-root", "root-readonly", listOf(write), execution)).reason,
        )
        val allowed = admission.prepareGrants("agent-root", "root-readonly", listOf(read), execution)
        assertTrue(allowed is RootGrantPreparation.Prepared, allowed.toString())
        assertEquals(1, db.query("SELECT * FROM capability_grants").size)
    }

    @Test
    fun wrappedSnapshotCreationSealsTheRealAgentSnapshot() = database { db ->
        agentFixture(db)
        val admission = RootGrantSnapshotAdmission(db)
        val prepared = admission.prepareGrants("agent-root", "root-workspace", listOf(read, write), execution)
            as RootGrantPreparation.Prepared
        val grantIds = prepared.bindings.map { it.grantId }.toSet()
        val outcome = admission.wrapSnapshotCreation("agent-root", grantIds, execution)
        assertTrue(outcome is RootSnapshotOutcome.Sealed, outcome.toString())
        val sealed = (outcome as RootSnapshotOutcome.Sealed).admission
        assertNotNull(AgentRepository(db).getSnapshot(sealed.snapshotId))
        assertEquals("agent-root", sealed.snapshot.agentId)
        assertEquals(grantIds.sorted(), sealed.grantIds)
        assertEquals(listOf(prepared.workspaceGenerationId), sealed.workspaceGenerationIds)
        grantIds.forEach { id ->
            assertNotNull(RootSnapshotGrantRepository(db).revalidate(sealed.snapshotId, id, execution))
        }
        assertEquals(2, db.query("SELECT * FROM root_snapshot_grants WHERE snapshot_id=?", listOf(sealed.snapshotId)).size)
        assertEquals(2, CapabilityGrantRepository(db).listSnapshotBindings(sealed.snapshotId).size)
        assertEquals(
            64,
            db.query("SELECT binding_set_hash FROM root_snapshot_seals WHERE snapshot_id=?", listOf(sealed.snapshotId))
                .single().string("binding_set_hash").length,
        )
    }

    @Test
    fun placeholderOrMismatchedCallbacksNeverSealAndNeverPersistTheSnapshot() = database { db ->
        agentFixture(db)
        val admission = RootGrantSnapshotAdmission(db)
        val grantIds = (admission.prepareGrants("agent-root", "root-workspace", listOf(read), execution)
            as RootGrantPreparation.Prepared).bindings.map { it.grantId }.toSet()

        val placeholder = refusedSnapshot(
            admission.createSnapshot("agent-root", "snap-placeholder", grantIds, execution) { id ->
                AgentSnapshot(
                    id = id, schemaVersion = 1, agentId = "agent-root", promptRevisionId = "prompt",
                    chatModelId = "model", providerRevision = 1, knowledgeBaseIds = emptyList(),
                    skillIds = emptyList(), createdAt = now,
                )
            },
        )
        assertEquals(RootGrantRefusal.SNAPSHOT_CALLBACK_INVALID, placeholder.reason)
        assertEquals("placeholder", placeholder.detail)

        val mismatched = refusedSnapshot(
            admission.createSnapshot("agent-root", "snap-mismatch", grantIds, execution) {
                AgentRepository(db).createSnapshot("agent-root", "snap-other")
            },
        )
        assertEquals(RootGrantRefusal.SNAPSHOT_CALLBACK_INVALID, mismatched.reason)
        assertEquals("identity", mismatched.detail)

        assertTrue(db.query("SELECT * FROM agent_snapshots").isEmpty())
        assertTrue(db.query("SELECT * FROM root_snapshot_grants").isEmpty())
        assertTrue(db.query("SELECT * FROM root_snapshot_seals").isEmpty())
        assertTrue(db.query("SELECT * FROM snapshot_grant_bindings").isEmpty())
    }

    @Test
    fun existingSnapshotsCannotAcquireRootGrantsAfterCreation() = database { db ->
        agentFixture(db)
        AgentRepository(db).createSnapshot("agent-root", "snap-old")
        val admission = RootGrantSnapshotAdmission(db)
        val grantIds = (admission.prepareGrants("agent-root", "root-workspace", listOf(read), execution)
            as RootGrantPreparation.Prepared).bindings.map { it.grantId }.toSet()
        assertEquals(
            RootGrantRefusal.SNAPSHOT_EXISTS,
            refusedSnapshot(admission.createSnapshot("agent-root", "snap-old", grantIds, execution) { id ->
                AgentRepository(db).createSnapshot("agent-root", id)
            }).reason,
        )
        assertTrue(db.query("SELECT * FROM root_snapshot_grants").isEmpty())
        assertTrue(db.query("SELECT * FROM root_snapshot_seals").isEmpty())
    }

    @Test
    fun snapshotCallbackFailureRollsBackSealingButKeepsExplicitGrants() = database { db ->
        val admission = RootGrantSnapshotAdmission(db)
        val grantIds = (admission.prepareGrants("agent-root", "root-workspace", listOf(read), execution)
            as RootGrantPreparation.Prepared).bindings.map { it.grantId }.toSet()
        assertEquals(
            RootGrantRefusal.SNAPSHOT_REJECTED,
            refusedSnapshot(admission.createSnapshot("agent-root", "snap-fails", grantIds, execution) {
                error("snapshot creation failed")
            }).reason,
        )
        assertTrue(db.query("SELECT * FROM agent_snapshots").isEmpty())
        assertTrue(db.query("SELECT * FROM root_snapshot_seals").isEmpty())
        assertTrue(db.query("SELECT * FROM root_snapshot_grants").isEmpty())
        grantIds.forEach { assertNotNull(RootGrantRepository(db).get(it)) }
    }

    @Test
    fun foreignOrMissingGrantsCannotBeSealed() = database { db ->
        agentFixture(db)
        CapabilityGrantRepository(db).save(
            CapabilityGrant(
                grantId = "plain-grant", agentId = "agent-root",
                capability = CapabilityId(CapabilityId.FILE_READ_TEXT),
                workspaceId = "root-workspace", policyVersion = 1,
            ),
        )
        val admission = RootGrantSnapshotAdmission(db)
        assertEquals(
            RootGrantRefusal.GRANT_NOT_ROOT,
            refusedSnapshot(
                admission.wrapSnapshotCreation("agent-root", setOf("plain-grant", "missing-grant"), execution),
            ).reason,
        )
        assertTrue(db.query("SELECT * FROM agent_snapshots").isEmpty())
        assertTrue(db.query("SELECT * FROM root_snapshot_seals").isEmpty())
    }

    @Test
    fun prepareAndSealIsTheSingleCallPathForUiConversationCreation() = database { db ->
        agentFixture(db)
        val outcome = RootGrantSnapshotAdmission(db).prepareAndSeal(
            "agent-root", "root-workspace", listOf(read, write), execution,
        ) { id -> AgentRepository(db).createSnapshot("agent-root", id) }
        assertTrue(outcome is RootSnapshotOutcome.Sealed, outcome.toString())
        val admission = (outcome as RootSnapshotOutcome.Sealed).admission
        assertEquals(2, admission.grantIds.size)
        assertEquals(1, admission.workspaceGenerationIds.size)
        assertNotNull(RootSnapshotGrantRepository(db).revalidate(admission.snapshotId, admission.grantIds.first(), execution))
        assertFalse(admission.snapshot.bindingManifestJson.isBlank())
    }

    private fun refusedPreparation(preparation: RootGrantPreparation): RootGrantPreparation.Refused {
        assertTrue(preparation is RootGrantPreparation.Refused, preparation.toString())
        return preparation as RootGrantPreparation.Refused
    }

    private fun refusedSnapshot(outcome: RootSnapshotOutcome): RootSnapshotOutcome.Refused {
        assertTrue(outcome is RootSnapshotOutcome.Refused, outcome.toString())
        return outcome as RootSnapshotOutcome.Refused
    }
}
