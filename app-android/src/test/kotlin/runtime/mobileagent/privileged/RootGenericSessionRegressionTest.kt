// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.file.Files
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.RootAuthorityRepository
import runtime.mobileagent.data.RootPendingDrainLoad
import runtime.mobileagent.data.RootPendingDrainRepository
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RootCallerObservation
import runtime.mobileagent.domain.RootControlPlaneBoundary
import runtime.mobileagent.domain.RootFdNodeIdentity
import runtime.mobileagent.shizuku.ShizukuAuthorityState

class RootGenericSessionRegressionTest {
    private val authority = Authority.ROOT_DIRECT

    @Test
    fun magiskObserverCapabilityIsOptionalAndLimitedToSupportedMagiskVersions() {
        (26..31).forEach { major ->
            assertTrue(
                RootDirectProvider("/system/bin/su", "$major.0:MAGISKSU", major).observeMagiskPolicy,
                "Magisk $major should use the supported policy observer",
            )
        }
        assertFalse(RootDirectProvider("/system/bin/su", "unknown", 0).observeMagiskPolicy)
        assertFalse(RootDirectProvider("/system/bin/su", "KernelSU 1.0", 0).observeMagiskPolicy)
        assertFalse(RootDirectProvider("/system/bin/su", "APatch 10683", 0).observeMagiskPolicy)
        assertFalse(RootDirectProvider("/system/bin/su", "25.0:MAGISKSU", 25).observeMagiskPolicy)
        assertFalse(RootDirectProvider("/system/bin/su", "32.0:MAGISKSU", 32).observeMagiskPolicy)
    }

    @Test
    fun authenticatedGenericSuSessionGrantedObservationIsAccepted() = fixture(managerDecision = null) { _, coordinator, connector ->
        val genericProvider = RootDirectProvider("/system/bin/su", "KernelSU 1.0", 0)
        assertFalse(genericProvider.observeMagiskPolicy)

        assertTrue(coordinator.requestSessionFromForeground(authority, null))

        assertEquals(1, connector.acquisitions)
        assertEquals(1, connector.transports.single().managerQueries)
        assertTrue(coordinator.isAuthorityReadyForDispatch(authority))
        assertNotNull(coordinator.currentExecutionStamp(authority))
    }

    @Test
    fun idleDeadTransportWithoutPendingWorkAllowsForegroundReacquisition() = fixture(managerDecision = null) { db, coordinator, connector ->
        assertTrue(coordinator.requestSessionFromForeground(authority, null))
        val oldTransport = connector.transports.single()
        oldTransport.alive = false
        assertTrue(RootPendingDrainRepository(db).load(authority) is RootPendingDrainLoad.Missing)

        assertTrue(coordinator.requestSessionFromForeground(authority, null))

        assertEquals(2, connector.acquisitions)
        assertNotSame(oldTransport, connector.transports.last())
        assertEquals(1, connector.transports.last().managerQueries)
        assertFalse(RootAuthorityRepository(db).get(authority).degraded)
        assertTrue(RootPendingDrainRepository(db).load(authority) is RootPendingDrainLoad.Missing)
        assertTrue(coordinator.isAuthorityReadyForDispatch(authority))
        assertEquals(2L, coordinator.currentExecutionStamp(authority)?.identity?.identityEpoch)
    }

    @Test
    fun attemptedUnknownManagerObservationRemainsGated() = fixture(managerDecision = RootManagerAuthorization.UNKNOWN) { db, coordinator, connector ->
        assertFalse(coordinator.requestSessionFromForeground(authority, null))

        assertEquals(1, connector.acquisitions)
        assertEquals(1, connector.transports.single().managerQueries)
        assertFalse(coordinator.isAuthorityReadyForDispatch(authority))
        assertTrue(RootPendingDrainRepository(db).load(authority) is RootPendingDrainLoad.Missing)
        assertFalse(RootAuthorityRepository(db).get(authority).degraded)
        assertTrue(connector.transports.single().closed)
    }

    private fun fixture(
        managerDecision: RootManagerAuthorization?,
        block: (runtime.mobileagent.data.SqlConnection, RootRuntimeCoordinator, Connector) -> Unit,
    ) {
        val dir = Files.createTempDirectory("root-generic-session").toFile()
        InMemoryRootSqlConnection().use { db ->
            Migrations.apply(db)
            val policies = AuthorityPolicyRepository(db)
            policies.updatePolicy(0, authority, DangerousMode.DISABLED)
            val roots = RootAuthorityRepository(db)
            val policy = policies.getPolicy()
            roots.confirmRoot(authority, roots.get(authority).revision, policy.policyVersion)
                ?: error("Root test consent setup failed")

            val connector = Connector(managerDecision)
            val coordinator = RootRuntimeCoordinator(
                FakeTestContext(dir),
                db,
                sessionConnector = connector,
                controlPlaneBoundaryOverride = testBoundary(),
                locatorCipher = TestRootLocatorCipher(),
            ).apply { managerObservationIntervalMs = 60_000L }
            try {
                block(db, coordinator, connector)
            } finally {
                coordinator.close()
            }
        }
        dir.deleteRecursively()
    }

    private class Connector(private val managerDecision: RootManagerAuthorization?) : RootSessionConnector {
        var acquisitions = 0
        val transports = mutableListOf<SyntheticRootSessionTransport>()

        override fun tryAcquireSession(
            authority: Authority,
            identityEpoch: Long,
            appInstanceId: String,
            shizukuState: ShizukuAuthorityState?,
        ): RootAcquiredSession {
            acquisitions++
            val identity = PrivilegedSessionIdentity(
                authority = authority,
                privilegeTier = PrivilegeTier.ROOT,
                serviceUid = 0,
                appUid = 10148,
                androidUserId = 0,
                appInstanceId = appInstanceId,
                packageName = "runtime.mobileagent",
                signingSha256 = "b".repeat(64),
                identityEpoch = identityEpoch,
                serviceInstanceId = "generic-root-service-$acquisitions",
                sessionId = "generic-root-session-$acquisitions",
                protocolVersion = PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION,
            )
            val transport = SyntheticRootSessionTransport(identity, managerDecision)
            transports += transport
            return RootAcquiredSession(
                identity = identity,
                caller = RootCallerObservation(
                    callingUid = identity.appUid,
                    observedServiceUid = 0,
                    packageName = identity.packageName,
                    androidUserId = identity.androidUserId,
                    signingSha256 = identity.signingSha256,
                ),
                transport = transport,
                controlPlane = testBoundary(),
                attemptToken = acquisitions.toLong(),
                kernelBootId = UUID(0L, 20L).toString(),
                kernelElapsedRealtimeMs = 200_000L,
            )
        }
    }

    private companion object {
        fun testBoundary() = RootControlPlaneBoundary(
            "/data/user/0/runtime.mobileagent",
            RootFdNodeIdentity(1, 101, 11, true, false, false, 1, 0x41ed, 0, 0),
            "/data/user_de/0/runtime.mobileagent",
            RootFdNodeIdentity(2, 201, 12, true, false, false, 1, 0x41ed, 0, 0),
        )
    }
}

/** A fake connector's already-authenticated UID0 session and bound manager response. */
internal class SyntheticRootSessionTransport(
    private val identity: PrivilegedSessionIdentity,
    private val managerDecision: RootManagerAuthorization? = null,
) : RootChannelTransport {
    @Volatile
    var alive = true

    @Volatile
    var closed = false
        private set

    var closeCalls = 0
        private set

    var managerQueries = 0
        private set

    override fun isAlive(): Boolean = alive && !closed

    override fun handshake(request: RootServiceHandshakeRequest): RootServiceHandshakeResponse? = null

    override fun queryManagerAuthorization(requestJson: String): String {
        managerQueries++
        return RootManagerAuthorizationReader.authorizationJson(
            sessionIdentity = identity,
            observedCallingUid = identity.appUid,
            liveServiceUid = 0,
            requestJson = requestJson,
            override = managerDecision?.let { decision -> { decision } },
            observationEnabled = managerDecision != null,
        )
    }

    override fun submitEnvelope(controlSequence: Long, rawEnvelopeJson: String): RootServiceExecuteResponse? = null

    override fun executeAccepted(requestId: String, payloadBytes: ByteArray): RootServiceExecuteResponse? = null

    override fun submitAndExecute(
        controlSequence: Long,
        rawEnvelopeJson: String,
        payloadBytes: ByteArray,
    ): RootServiceExecuteResponse? = null

    override fun cancelCommand(requestId: String): Boolean = false

    override fun revokeAndShutdown(controlSequence: Long, reason: String): String = "DISCONNECTED"

    override fun close() {
        closeCalls++
        closed = true
        alive = false
    }
}
