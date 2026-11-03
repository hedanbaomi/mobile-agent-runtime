// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.AuthorityPreferencesRepository
import runtime.mobileagent.data.RootAuthorityRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.domain.*
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.shizuku.ShizukuAuthorityState
import runtime.mobileagent.skills.tooling.*
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.integration.SqliteAuthorityStateStore
import runtime.mobileagent.tooling.AuthorityManager
import runtime.mobileagent.tooling.InMemoryAuthorityStateStore

class RootCoordinatorReviewRegressionTest {
    @Test
    fun passiveRefreshNeverAcquiresRootEvenWithPersistedConsent() = fixture { f ->
        f.enable()
        val acquired = f.acquisitions
        val epoch = f.coordinator.rootAuthorityRepository.get(Authority.ROOT_DIRECT).identityEpoch
        f.coordinator.refreshLiveSessions(null)
        assertEquals(acquired, f.acquisitions, "A status refresh must not launch a new su process")
        assertEquals(epoch, f.coordinator.rootAuthorityRepository.get(Authority.ROOT_DIRECT).identityEpoch)
        assertTrue(f.coordinator.isAuthorityReadyForDispatch(Authority.ROOT_DIRECT))
        f.coordinator.detachSession(Authority.ROOT_DIRECT, "test_disconnect")
        f.coordinator.refreshLiveSessions(null)
        assertEquals(acquired, f.acquisitions, "Offline consent must not trigger background reconnect")
    }

    @Test
    fun unverifiedRootIntegrationDoesNotRegisterProductionBackends() = fixture { f ->
        val shells = mutableMapOf<ElevatedAuthority, ShellExecutor>()
        val workspaces = mutableMapOf<ElevatedAuthority, WorkspaceBackend>()
        val providers = mutableMapOf<Authority, PrivilegedWorkspaceProvider>()
        f.coordinator.registerBackends(shells, workspaces, providers, object : FullDeviceFilesGrantStore {
            override fun load(workspaceId: String): FullDeviceFilesGrant? = null
            override fun save(grant: FullDeviceFilesGrant): WorkspaceResult<Unit> = error("unexpected grant")
            override fun revoke(workspaceId: String, expectedRevision: Long): WorkspaceResult<Unit> = error("unexpected revoke")
        })
        assertTrue(shells.isEmpty() && workspaces.isEmpty() && providers.isEmpty(),
            "Incomplete bootstrap, FD binding and Run approval integration must not expose Root tools")
    }

    @Test
    fun attachCannotReplaceAnEpochOrForgeAServiceFromHostDto() = fixture { f ->
        f.enable()
        val identity = f.coordinator.currentExecutionStamp(Authority.ROOT_DIRECT)!!.identity
        val caller = RootCallerObservation(10148, 0, "runtime.mobileagent", 0, "a".repeat(64))
        assertNull(f.coordinator.attachVerifiedRootSession(Authority.ROOT_DIRECT,
            identity.copy(sessionId = "replacement-session-0001"), caller, f.transport, f.boundary))
        assertEquals(identity, f.coordinator.currentExecutionStamp(Authority.ROOT_DIRECT)!!.identity)
        f.coordinator.detachSession(Authority.ROOT_DIRECT, "test_disconnect")
        assertNull(f.coordinator.attachVerifiedRootSession(Authority.ROOT_DIRECT, identity, caller,
            transport = null, observedBoundary = f.boundary), "An identity DTO is not a UID 0 transport")
        assertNull(f.coordinator.attachVerifiedRootSession(Authority.ROOT_DIRECT, identity,
            caller.copy(callingUid = 42424), f.transport, f.boundary))
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val dir = Files.createTempDirectory("root-review-regression")
        InMemoryRootSqlConnection().use { db ->
            Migrations.apply(db)
            val fixture = Fixture(db, dir.toFile())
            try { block(fixture) } finally { fixture.coordinator.close(); dir.toFile().deleteRecursively() }
        }
    }

    private class Fixture(val db: InMemoryRootSqlConnection, dir: java.io.File) {
        var acquisitions = 0

        /** When set, the connector returns an identity whose epoch no longer matches the record. */
        @Volatile
        var forgeEpoch = false
        val manager = AuthorityManager(InMemoryAuthorityStateStore())
        val policy = AuthorityPolicyRepository(db).updatePolicy(
            0, Authority.ROOT_DIRECT, DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
        )
        val boundary = RootControlPlaneBoundary(
            "/data/user/0/runtime.mobileagent", node(9001),
            "/data/user_de/0/runtime.mobileagent", node(9002),
        )
        @Volatile
        var transportClosed = false
        val transport = object : RootChannelTransport {
            override fun handshake(request: RootServiceHandshakeRequest): RootServiceHandshakeResponse? = null
            override fun bindWorkspace(request: RootServiceBindWorkspaceRequest): RootServiceBindWorkspaceResponse? = null
            override fun submitEnvelope(controlSequence: Long, rawEnvelopeJson: String): RootServiceExecuteResponse? = null
            override fun executeAccepted(requestId: String, payloadBytes: ByteArray): RootServiceExecuteResponse? = null
            override fun submitAndExecute(controlSequence: Long, rawEnvelopeJson: String, payloadBytes: ByteArray): RootServiceExecuteResponse? = null
            override fun cancelCommand(requestId: String) = false
            override fun revokeAndShutdown(controlSequence: Long, reason: String) = "REVOKED"

            // Fixture double for the non-interactive manager observation RPC.
            override fun queryManagerAuthorization(requestJson: String): String {
                val request = RootPrivilegedServiceEngine.json.decodeFromString<RootManagerAuthorizationRequest>(requestJson)
                return RootPrivilegedServiceEngine.json.encodeToString(
                    RootManagerAuthorizationResponse.serializer(),
                    RootManagerAuthorizationResponse(
                        provider = RootManagerAuthorizationPolicy.PROVIDER_MAGISK,
                        result = RootManagerAuthorization.GRANTED.name,
                        serviceInstanceId = request.serviceInstanceId,
                        sessionId = request.sessionId,
                        identityEpoch = request.identityEpoch,
                        reason = "fixture",
                    ),
                )
            }
            override fun close() {
                transportClosed = true
            }
        }
        val coordinator = RootRuntimeCoordinator(
            context = FakeTestContext(dir), db = db, controlPlaneBoundaryOverride = boundary,
            sessionConnector = object : RootSessionConnector {
                override fun tryAcquireSession(authority: Authority, identityEpoch: Long, appInstanceId: String,
                    shizukuState: ShizukuAuthorityState?): RootAcquiredSession {
                    acquisitions++
                    val identity = PrivilegedSessionIdentity(
                        authority = authority, privilegeTier = PrivilegeTier.ROOT, serviceUid = 0,
                        serviceInstanceId = "service-instance-$acquisitions", sessionId = "service-session-$acquisitions",
                        identityEpoch = if (forgeEpoch) identityEpoch + 7L else identityEpoch, appUid = 10148, androidUserId = 0,
                        appInstanceId = appInstanceId, packageName = "runtime.mobileagent", signingSha256 = "a".repeat(64),
                        protocolVersion = PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION,
                    )
                    return RootAcquiredSession(identity,
                        RootCallerObservation(10148, 0, "runtime.mobileagent", 0, "a".repeat(64)), transport, boundary)
                }
            },
        )
        fun enable() = coordinator.setUserIntent(Authority.ROOT_DIRECT, true, manager,
            DangerousMode.ENABLED_CONFIRM_HIGH_RISK, null)
        companion object {
            fun node(inode: Long) = RootFdNodeIdentity(1, inode, 77, true, false, false, 2, 448, 10148, 10148, "label")
        }
    }

    /** A valid handshake whose attach is refused must close the transport and leave no session. */
    @Test
    fun refusedAttachClosesTheTransportAndLeavesNoActiveSession() = fixture { f ->
        f.enable()
        f.coordinator.detachSession(Authority.ROOT_DIRECT, "test_disconnect")
        f.forgeEpoch = true
        assertFalse(
            f.coordinator.requestSessionFromForeground(Authority.ROOT_DIRECT, null),
            "a mismatched epoch must be refused before publishing",
        )
        assertTrue(f.transportClosed, "the refused acquisition transport must be closed")
        assertFalse(f.coordinator.isAuthorityReadyForDispatch(Authority.ROOT_DIRECT))
    }


    /**
     * F14: the real foreground enable sequence (intent -> coordinator consent -> UI select) must
     * leave the consent's policyVersion equal to the live policy version, so a fresh user gets an
     * execution stamp and can dispatch typed work. A stale binding silently yields null.
     *
     * Uses the production SQLite store (not the in-memory test store), because the version bump
     * under test only exists there.
     */
    @Test
    fun freshForegroundEnableKeepsConsentPolicyVersionAlignedAndYieldsAStamp() = fixture { f ->
        val sqliteManager = AuthorityManager(
            SqliteAuthorityStateStore(
                AuthorityPolicyRepository(f.db),
                AuthorityPreferencesRepository(f.db),
            ),
        )
        f.coordinator.setUserIntent(
            Authority.ROOT_DIRECT,
            true,
            sqliteManager,
            DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
            null,
        )
        assertTrue(sqliteManager.selectAuthority(ElevatedAuthority.ROOT_DIRECT))
        val record = RootAuthorityRepository(f.db).get(Authority.ROOT_DIRECT)
        val policy = AuthorityPolicyRepository(f.db).getPolicy()
        assertEquals(
            policy.policyVersion,
            record.consent.policyVersion,
            "consent must bind the current policy version after the foreground enable + select",
        )
        assertNotNull(
            f.coordinator.currentExecutionStamp(Authority.ROOT_DIRECT, false),
            "a fresh enable must yield a current execution stamp",
        )
    }

}
