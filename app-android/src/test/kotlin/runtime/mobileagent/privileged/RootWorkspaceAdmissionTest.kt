// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.PrivilegedWorkspaceBindingRepository
import runtime.mobileagent.data.RootAuthorityRepository
import runtime.mobileagent.data.RootWorkspaceLocatorRepository
import runtime.mobileagent.data.RootWorkspaceRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.PrivilegedWorkspaceBinding
import runtime.mobileagent.domain.RootExecutionStamp
import runtime.mobileagent.domain.RootRecoveryLocatorCodec
import runtime.mobileagent.domain.RootRecoveryLocatorPayload
import runtime.mobileagent.domain.RootWorkspaceOrigin
import runtime.mobileagent.domain.RootWorkspaceRootKind
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.canonicalSha256

/**
 * Synthetic local state only. These fixtures exercise the Host protocol/persistence gates;
 * they do not authenticate an OS Root session and do not prove Android Keystore behavior.
 */
class RootWorkspaceAdmissionTest {
    private val execution = RootExecutionStamp(
        PrivilegedSessionIdentity(
            Authority.ROOT_DIRECT, PrivilegeTier.ROOT, 0, 10123, 0,
            "app-instance-000001", "runtime.mobileagent", "a".repeat(64), 1,
            "service-instance-001", "session-instance-001", 4,
        ), 1, 1, 1,
    )

    private fun database(block: (SqlConnection) -> Unit) = InMemoryRootSqlConnection().use { db ->
        Migrations.apply(db)
        AuthorityPolicyRepository(db).updatePolicy(0, Authority.ROOT_DIRECT, DangerousMode.DISABLED)
        val roots = RootAuthorityRepository(db)
        val consent = roots.confirmRoot(Authority.ROOT_DIRECT, 0, 1)!!
        roots.advanceIdentityEpoch(Authority.ROOT_DIRECT, consent.revision)
        block(db)
    }

    @Test
    fun admissionCreatesFreshGenerationAndConfirmsOnlyAfterSealedLocatorPersists() = database { db ->
        val service = FakeRootWorkspaceService()
        val cipher = TestRootLocatorCipher()
        val admission = RootWorkspaceAdmission(db, service, cipher)
        val outcome = admission.admit(
            RootWorkspaceSelection("Downloads", "/storage/emulated/0/Download", writable = true),
            execution,
        )
        assertTrue(outcome is RootWorkspaceAdmissionOutcome.Admitted, outcome.toString())
        val admitted = (outcome as RootWorkspaceAdmissionOutcome.Admitted).workspace

        assertEquals(listOf("PROPOSE", "STAGE", "CONFIRM"), service.phases)
        assertTrue(service.requests.all { it.contains("\"phase\"") })

        val origin = requireNotNull(RootWorkspaceRepository(db).getOrigin(admitted.workspaceId))
        assertEquals(origin.generationId, admitted.workspaceGenerationId)
        assertTrue(origin.matches(execution.identity))
        assertEquals(origin, admitted.origin)
        assertEquals(origin, service.observedOrigin)

        val workspace = requireNotNull(WorkspaceRepository(db).get(admitted.workspaceId))
        assertEquals(WorkspaceBackendType.PRIVILEGED, workspace.backendType)
        assertEquals("root:${origin.generationId}", workspace.rootReference)
        assertTrue(workspace.writable)
        assertNull(PrivilegedWorkspaceBindingRepository(db).get(admitted.workspaceId))

        val persisted = requireNotNull(RootWorkspaceLocatorRepository(db).getCurrent(admitted.workspaceId, execution))
        assertEquals(origin, persisted.origin)
        val decrypted = requireNotNull(cipher.open(origin, persisted.encryptedEnvelopeCopy()))
        assertArrayEquals(requireNotNull(service.lastLocatorBytes), decrypted)
        assertEquals(origin.canonicalSha256(), service.lastStagedToken)
        assertEquals(RootServiceRootIdentity(1L, 7001L, 100L, 10250, RootWorkspaceRootKind.DIRECTORY), admitted.root)
    }

    @Test
    fun proposeCreatesNothingLocalAndAbortOnlyAddressesTheProvisionalHandle() = database { db ->
        val service = FakeRootWorkspaceService()
        val admission = RootWorkspaceAdmission(db, service, TestRootLocatorCipher())
        val proposed = admission.propose(
            RootWorkspaceSelection("Downloads", "/storage/emulated/0/Download"),
            execution,
        )
        assertTrue(proposed is RootWorkspaceProposeOutcome.Proposed, proposed.toString())
        db.assertNoRootWorkspaceRows()

        val released = admission.abort((proposed as RootWorkspaceProposeOutcome.Proposed).proposal)
        assertEquals(RootWorkspaceRelease.ACCEPTED, released.release)
        assertEquals(listOf("PROPOSE", "RELEASE"), service.phases)
        db.assertNoRootWorkspaceRows()
    }

    @Test
    fun stageRejectionReleasesTheHandleAndRemovesTheFreshWorkspace() = database { db ->
        val service = FakeRootWorkspaceService().apply { rejectPhase = "STAGE" }
        val outcome = rootAdmissionOutcome(RootWorkspaceAdmission(db, service, TestRootLocatorCipher()))
        assertEquals(RootWorkspaceRefusal.STAGE_REJECTED, outcome.reason)
        assertEquals("test_rejected", outcome.detail)
        assertEquals(RootWorkspaceRelease.ACCEPTED, outcome.release)
        assertEquals(RootWorkspaceRollback.COMPLETED, outcome.localRollback)
        assertEquals(listOf("PROPOSE", "STAGE", "RELEASE"), service.phases)
        db.assertNoRootWorkspaceRows()
    }

    @Test
    fun stagedTokenMismatchNeverReachesConfirm() = database { db ->
        val service = FakeRootWorkspaceService().apply { tokenOverride = "f".repeat(64) }
        val outcome = rootAdmissionOutcome(RootWorkspaceAdmission(db, service, TestRootLocatorCipher()))
        assertEquals(RootWorkspaceRefusal.STAGED_TOKEN_MISMATCH, outcome.reason)
        assertFalse(service.phases.contains("CONFIRM"))
        assertEquals(RootWorkspaceRollback.COMPLETED, outcome.localRollback)
        db.assertNoRootWorkspaceRows()
    }

    @Test
    fun locatorForAnotherDirectoryIsRefusedBeforeSealing() = database { db ->
        val service = FakeRootWorkspaceService().apply { locatorPathOverride = "/data/local/tmp" }
        val outcome = rootAdmissionOutcome(RootWorkspaceAdmission(db, service, TestRootLocatorCipher()))
        assertEquals(RootWorkspaceRefusal.LOCATOR_MISMATCH, outcome.reason)
        assertFalse(service.phases.contains("CONFIRM"))
        assertEquals(RootWorkspaceRollback.COMPLETED, outcome.localRollback)
        db.assertNoRootWorkspaceRows()
    }

    @Test
    fun rootIdentityDriftBetweenProposeAndStageIsRefused() = database { db ->
        val service = FakeRootWorkspaceService().apply { stageInodeOverride = 9999L }
        val outcome = rootAdmissionOutcome(RootWorkspaceAdmission(db, service, TestRootLocatorCipher()))
        assertEquals(RootWorkspaceRefusal.ROOT_IDENTITY_DRIFT, outcome.reason)
        assertFalse(service.phases.contains("CONFIRM"))
        db.assertNoRootWorkspaceRows()
    }

    @Test
    fun missingLocatorOrSealFailureNeverFallsBackToPlaintext() = database { db ->
        val missing = FakeRootWorkspaceService().apply { omitLocator = true }
        val missingOutcome = rootAdmissionOutcome(RootWorkspaceAdmission(db, missing, TestRootLocatorCipher()))
        assertEquals(RootWorkspaceRefusal.LOCATOR_INVALID, missingOutcome.reason)
        assertFalse(missing.phases.contains("CONFIRM"))
        db.assertNoRootWorkspaceRows()

        val refusing = FakeRootWorkspaceService()
        val sealFailure = rootAdmissionOutcome(RootWorkspaceAdmission(db, refusing, RefusingLocatorCipher()))
        assertEquals(RootWorkspaceRefusal.SEAL_FAILED, sealFailure.reason)
        assertFalse(refusing.phases.contains("CONFIRM"))
        db.assertNoRootWorkspaceRows()
    }

    @Test
    fun confirmRejectionReleasesTheStagedHandleAndRollsBack() = database { db ->
        val service = FakeRootWorkspaceService().apply { rejectPhase = "CONFIRM" }
        val outcome = rootAdmissionOutcome(RootWorkspaceAdmission(db, service, TestRootLocatorCipher()))
        assertEquals(RootWorkspaceRefusal.CONFIRM_REJECTED, outcome.reason)
        assertEquals(RootWorkspaceRelease.ACCEPTED, outcome.release)
        assertEquals(listOf("PROPOSE", "STAGE", "CONFIRM", "RELEASE"), service.phases)
        db.assertNoRootWorkspaceRows()
    }

    @Test
    fun legacyPhaseResponseCannotBecomeAnInitialBinding() = database { db ->
        val service = FakeRootWorkspaceService().apply { omitPhase = true }
        val outcome = rootAdmissionOutcome(RootWorkspaceAdmission(db, service, TestRootLocatorCipher()))
        assertEquals(RootWorkspaceRefusal.PROPOSE_RESPONSE_INVALID, outcome.reason)
        assertEquals(listOf("PROPOSE"), service.phases)
        db.assertNoRootWorkspaceRows()
    }

    @Test
    fun anExistingAdbWorkspaceIdCannotBePromotedOrDamaged() = database { db ->
        WorkspaceRepository(db).save(Workspace("old-adb", "Old ADB", WorkspaceBackendType.PRIVILEGED, "opaque-adb"))
        PrivilegedWorkspaceBindingRepository(db).save(
            PrivilegedWorkspaceBinding("old-adb", Authority.SHIZUKU, byteArrayOf(1), ByteArray(12), aadAppInstanceId = "app-adb"),
        )
        val outcome = rootAdmissionOutcome(
            RootWorkspaceAdmission(
                db, FakeRootWorkspaceService(), TestRootLocatorCipher(),
                origins = RootWorkspaceRepository(db) { "old-adb" },
            ),
        )
        assertEquals(RootWorkspaceRefusal.LOCAL_WORKSPACE_INVALID, outcome.reason)
        assertEquals(RootWorkspaceRollback.NOT_NEEDED, outcome.localRollback)
        assertNotNull(WorkspaceRepository(db).get("old-adb"))
        assertNotNull(PrivilegedWorkspaceBindingRepository(db).get("old-adb"))
        assertTrue(db.query("SELECT * FROM root_workspace_origins").isEmpty())
    }

    private fun RootWorkspaceAdmission.admitForTest(): RootWorkspaceAdmissionOutcome =
        admit(RootWorkspaceSelection("Downloads", "/storage/emulated/0/Download"), execution)

    private fun rootAdmissionOutcome(admission: RootWorkspaceAdmission): RootWorkspaceAdmissionOutcome.Refused {
        val outcome = admission.admitForTest()
        assertTrue(outcome is RootWorkspaceAdmissionOutcome.Refused, outcome.toString())
        return outcome as RootWorkspaceAdmissionOutcome.Refused
    }

    private fun SqlConnection.assertNoRootWorkspaceRows() {
        assertTrue(query("SELECT * FROM workspaces").isEmpty())
        assertTrue(query("SELECT * FROM root_workspace_origins").isEmpty())
        assertTrue(query("SELECT * FROM root_workspace_locators").isEmpty())
    }
}

private class RefusingLocatorCipher : RootLocatorCipher {
    override fun seal(
        origin: RootWorkspaceOrigin,
        plaintext: ByteArray,
        locatorVersion: Int,
        keyVersion: Int,
        aadVersion: Int,
    ): ByteArray? = null

    override fun open(
        origin: RootWorkspaceOrigin,
        ciphertext: ByteArray,
        locatorVersion: Int,
        keyVersion: Int,
        aadVersion: Int,
    ): ByteArray? = null
}

/** Minimal stand-in for the documented service phases; it never touches a real Root process. */
private class FakeRootWorkspaceService : RootWorkspaceAdmissionTransport {
    val phases = mutableListOf<String>()
    val requests = mutableListOf<String>()
    var rejectPhase: String? = null
    var omitLocator = false
    var tokenOverride: String? = null
    var locatorPathOverride: String? = null
    var stageInodeOverride: Long? = null
    var omitPhase = false
    var observedOrigin: RootWorkspaceOrigin? = null
    var lastLocatorBytes: ByteArray? = null
    var lastStagedToken: String? = null
    private var rootPath = ""
    private var rootKind = RootWorkspaceRootKind.DIRECTORY

    override fun bindWorkspace(requestJson: String): RootServiceBindWorkspaceResponse {
        requests += requestJson
        val request = WIRE.decodeFromString(WireSelection.serializer(), requestJson)
        phases += request.phase
        if (omitPhase) return accepted().copy(phase = null)
        if (rejectPhase == request.phase) {
            return RootServiceBindWorkspaceResponse(false, errorReason = "test_rejected", phase = request.phase)
        }
        return when (request.phase) {
            "PROPOSE" -> {
                rootPath = request.workspaceRootPath
                rootKind = RootWorkspaceRootKind.valueOf(request.rootKindName)
                accepted().copy(phase = "PROPOSE", provisionalHandle = PROVISIONAL_HANDLE)
            }
            "STAGE" -> {
                val origin = requireNotNull(request.origin)
                observedOrigin = origin
                lastStagedToken = origin.canonicalSha256()
                val bytes = RootRecoveryLocatorCodec.encode(
                    RootRecoveryLocatorPayload(
                        originSha256 = origin.canonicalSha256(),
                        workspaceId = origin.workspaceId,
                        workspaceGenerationId = origin.generationId,
                        authority = origin.authority,
                        rootKind = rootKind,
                        absoluteRootPath = locatorPathOverride ?: rootPath,
                        rootDeviceId = OBSERVED.deviceId,
                        rootInode = OBSERVED.inode,
                        rootMountId = OBSERVED.mountId,
                    ),
                )
                lastLocatorBytes = bytes.copyOf()
                accepted(stageInodeOverride ?: OBSERVED.inode).copy(
                    phase = "STAGE",
                    workspaceSessionHandle = SESSION_HANDLE,
                    stagedToken = tokenOverride ?: origin.canonicalSha256(),
                    locatorBytes = if (omitLocator) null else bytes,
                )
            }
            "CONFIRM" -> accepted().copy(phase = "CONFIRM", workspaceSessionHandle = SESSION_HANDLE)
            else -> accepted().copy(phase = "RELEASE", workspaceSessionHandle = request.workspaceSessionHandle)
        }
    }

    private fun accepted(inode: Long = OBSERVED.inode) = RootServiceBindWorkspaceResponse(
        accepted = true,
        rootInode = inode,
        rootDeviceId = OBSERVED.deviceId,
        rootMountId = OBSERVED.mountId,
        rootOwnerUid = OBSERVED.ownerUid,
        rootKindName = OBSERVED.kind.name,
    )

    private companion object {
        val WIRE = Json { ignoreUnknownKeys = true }
        const val PROVISIONAL_HANDLE = "ws-prov-0123456789abcdef0123"
        const val SESSION_HANDLE = "ws-sess-0123456789abcdef0123"
        val OBSERVED = RootServiceRootIdentity(1L, 7001L, 100L, 10250, RootWorkspaceRootKind.DIRECTORY)
    }
}

@Serializable
private data class WireSelection(
    val phase: String = "",
    val workspaceRootPath: String = "",
    val rootKindName: String = "",
    val provisionalHandle: String = "",
    val workspaceSessionHandle: String = "",
    @Serializable(with = RootWorkspaceOriginWireSerializer::class)
    val origin: RootWorkspaceOrigin? = null,
)
