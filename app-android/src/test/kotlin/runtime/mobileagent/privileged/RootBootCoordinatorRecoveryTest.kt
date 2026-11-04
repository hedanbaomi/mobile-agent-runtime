// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.file.Files
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.data.*
import runtime.mobileagent.domain.*
import runtime.mobileagent.shizuku.ShizukuAuthorityState

class RootBootCoordinatorRecoveryTest {
    private val authority = Authority.ROOT_DIRECT
    private val oldBoot = UUID(0L, 1L).toString()
    private val nextBoot = UUID(0L, 2L).toString()
    private val signing = "a".repeat(64)

    private fun fixture(block: (SqlConnection, RootRuntimeCoordinator, Probe) -> Unit) {
        val dir = Files.createTempDirectory("root-boot-recovery").toFile()
        InMemoryRootSqlConnection().use { db ->
            Migrations.apply(db)
            val roots = RootAuthorityRepository(db)
            val consent = roots.confirmRoot(authority, 0, 0)!!
            val shell = roots.setShellConsent(authority, consent.revision, enabled = true, policyVersion = 0)!!
            val state = roots.advanceIdentityEpoch(authority, shell.revision)!!
            val identity = identity(state.identityEpoch)
            RootReceiptRecoveryCoordinator(db).recordSessionBoot(authority, oldBoot, 50_000L)
            roots.markDegraded(authority, state.revision, state.identityEpoch)
            RootPendingDrainRepository(db).record(identity, null, state.identityEpoch)
            val probe = Probe(identity, oldBoot)
            val coordinator = RootRuntimeCoordinator(
                FakeTestContext(dir),
                db,
                sessionConnector = probe,
                controlPlaneBoundaryOverride = boundary(),
                locatorCipher = TestRootLocatorCipher(),
            ).apply { managerObservationIntervalMs = 60_000L }
            try {
                block(db, coordinator, probe)
            } finally {
                coordinator.close()
            }
        }
        dir.deleteRecursively()
    }

    @Test
    fun sameKernelBootAndMissingUserConfirmationDoNotUnlock() = fixture { db, coordinator, probe ->
        val before = RootAuthorityRepository(db).get(authority)
        assertNull(coordinator.acknowledgeDegradedRecovery(authority, false))
        assertEquals(0, probe.acquisitions)

        assertNull(coordinator.acknowledgeDegradedRecovery(authority, true))
        assertEquals(1, probe.acquisitions)
        assertEquals(0, probe.probeCalls, "recovery uses the acquired session once; it does not probe then relaunch")
        assertEquals(1, probe.completions, "the acquired session is transferred once before boot proof is committed")
        assertEquals(1, probe.aborts)
        assertTrue(probe.transports.single().closed)
        assertEquals(before.consent, RootAuthorityRepository(db).get(authority).consent)
        assertTrue(RootAuthorityRepository(db).get(authority).degraded)
    }

    @Test
    fun newKernelBootUsesOneAcquisitionAndAttachesThatSameSession() = fixture { db, coordinator, probe ->
        probe.boot = nextBoot

        val recovered = coordinator.acknowledgeDegradedRecovery(authority, true)

        assertNotNull(recovered)
        assertFalse(RootAuthorityRepository(db).get(authority).degraded)
        assertEquals(1, probe.acquisitions)
        assertEquals(0, probe.probeCalls)
        assertEquals(1, probe.completions)
        assertEquals(0, probe.aborts)
        assertEquals(1, probe.transports.single().managerQueries)
        assertFalse(probe.transports.single().closed)
        assertEquals(recovered!!.identityEpoch, coordinator.currentExecutionStamp(authority)?.identity?.identityEpoch)
        assertTrue(coordinator.isAuthorityReadyForDispatch(authority), "authenticated SESSION_GRANTED observation is accepted")
    }

    @Test
    fun missingDrainDoesNotSubstituteForSameBootQuiescence() = fixture { db, coordinator, probe ->
        db.execute("DELETE FROM app_prefs WHERE key = ?", listOf(RootPendingDrainRepository.KEY_PREFIX + authority.name))

        assertNull(coordinator.acknowledgeDegradedRecovery(authority, true))
        assertEquals(1, probe.acquisitions)
        assertEquals(1, probe.completions)
        assertEquals(1, probe.aborts)
        assertTrue(RootAuthorityRepository(db).get(authority).degraded)
        assertTrue(probe.transports.single().closed)

        probe.boot = nextBoot
        assertNotNull(coordinator.acknowledgeDegradedRecovery(authority, true))
        assertEquals(2, probe.acquisitions)
        assertEquals(2, probe.completions)
        assertEquals(1, probe.aborts)
        assertEquals(0, probe.probeCalls)
        assertTrue(coordinator.isAuthorityReadyForDispatch(authority))
    }

    @Test
    fun legacyMissingProofSeedsAndRequiresAnActualLaterBoot() = fixture { db, coordinator, probe ->
        db.execute("DELETE FROM app_prefs WHERE key = ? OR key = ?", listOf(
            RootPendingDrainRepository.KEY_PREFIX + authority.name,
            RootPendingDrainRepository.KERNEL_BOOT_PREFIX + authority.name,
        ))

        assertNull(coordinator.acknowledgeDegradedRecovery(authority, true))
        assertNull(coordinator.acknowledgeDegradedRecovery(authority, true))
        assertEquals(2, probe.acquisitions)
        assertEquals(2, probe.completions)
        assertEquals(2, probe.aborts)
        assertEquals(0, probe.probeCalls)
        assertTrue(RootAuthorityRepository(db).get(authority).degraded)
        assertTrue(probe.transports.take(2).all { it.closed })

        probe.boot = nextBoot
        val recovered = coordinator.acknowledgeDegradedRecovery(authority, true)
        assertNotNull(recovered)
        assertEquals(3, probe.acquisitions)
        assertEquals(3, probe.completions)
        assertEquals(2, probe.aborts)
        assertTrue(coordinator.isAuthorityReadyForDispatch(authority))
    }

    @Test
    fun revocationDuringAcquisitionCannotCommitRecoveryOrReenableConsent() = fixture { db, coordinator, probe ->
        probe.boot = nextBoot
        probe.beforeReturn = {
            val roots = RootAuthorityRepository(db)
            roots.revokeRoot(authority, roots.get(authority).revision)
        }

        assertNull(coordinator.acknowledgeDegradedRecovery(authority, true, enableAfterRecovery = true))
        val after = RootAuthorityRepository(db).get(authority)
        assertTrue(after.degraded)
        assertFalse(after.consent.enabled)
        assertFalse(after.consent.shellEnabled)
        assertEquals(1, probe.acquisitions)
        assertEquals(0, probe.completions)
        assertEquals(1, probe.aborts)
        assertTrue(probe.transports.single().closed)
    }

    @Test
    fun canceledAcquisitionCannotAdvanceRecoveryOrTouchConsent() = fixture { db, coordinator, probe ->
        probe.boot = nextBoot
        probe.acquisitionCurrent = false
        val before = RootAuthorityRepository(db).get(authority)

        assertNull(coordinator.acknowledgeDegradedRecovery(authority, true, enableAfterRecovery = true))

        assertEquals(before, RootAuthorityRepository(db).get(authority))
        assertEquals(1, probe.acquisitions)
        assertEquals(0, probe.probeCalls)
        assertEquals(0, probe.completions)
        assertEquals(1, probe.aborts)
        assertTrue(probe.transports.single().closed)
    }

    @Test
    fun closedCoordinatorRejectsLateAcquisitionAndLeavesConsentUntouched() = fixture { db, coordinator, probe ->
        probe.boot = nextBoot
        probe.beforeReturn = { coordinator.close() }
        val before = RootAuthorityRepository(db).get(authority)

        assertNull(coordinator.acknowledgeDegradedRecovery(authority, true, enableAfterRecovery = true))

        assertEquals(before, RootAuthorityRepository(db).get(authority))
        assertEquals(1, probe.acquisitions)
        assertEquals(0, probe.probeCalls)
        assertEquals(0, probe.completions)
        assertEquals(1, probe.aborts)
        assertTrue(probe.transports.single().closed)
    }

    @Test
    fun wrongAcquiredIdentityCannotClearDegradedStateOrChangeConsent() = fixture { db, coordinator, probe ->
        probe.boot = nextBoot
        probe.epochOffset = 1L
        val before = RootAuthorityRepository(db).get(authority)

        assertNull(coordinator.acknowledgeDegradedRecovery(authority, true, enableAfterRecovery = true))

        assertEquals(before, RootAuthorityRepository(db).get(authority))
        assertEquals(1, probe.acquisitions)
        assertEquals(0, probe.probeCalls)
        assertEquals(0, probe.completions)
        assertEquals(1, probe.aborts)
        assertTrue(probe.transports.single().closed)
    }

    @Test
    fun acquiredSessionForAnotherAuthorityCannotClearDegradedState() = fixture { db, coordinator, probe ->
        probe.boot = nextBoot
        probe.authorityOverride = Authority.SHIZUKU_ROOT
        val before = RootAuthorityRepository(db).get(authority)

        assertNull(coordinator.acknowledgeDegradedRecovery(authority, true, enableAfterRecovery = true))

        assertEquals(before, RootAuthorityRepository(db).get(authority))
        assertEquals(1, probe.acquisitions)
        assertEquals(0, probe.probeCalls)
        assertEquals(0, probe.completions)
        assertEquals(1, probe.aborts)
        assertTrue(probe.transports.single().closed)
    }

    @Test
    fun refusedAcquisitionOwnershipCannotCommitRecoveryOrConsent() = fixture { db, coordinator, probe ->
        probe.boot = nextBoot
        probe.completionAccepted = false
        val before = RootAuthorityRepository(db).get(authority)

        assertNull(coordinator.acknowledgeDegradedRecovery(authority, true, enableAfterRecovery = true))

        assertEquals(before, RootAuthorityRepository(db).get(authority))
        assertEquals(1, probe.acquisitions)
        assertEquals(0, probe.probeCalls)
        assertEquals(1, probe.completions)
        assertEquals(1, probe.aborts)
        assertTrue(probe.transports.single().closed)
    }

    @Test
    fun failedSameBootRecoveryCannotReenablePreviouslyRevokedConsent() = fixture { db, coordinator, probe ->
        val roots = RootAuthorityRepository(db)
        roots.revokeRoot(authority, roots.get(authority).revision)
        val before = roots.get(authority)

        assertNull(coordinator.acknowledgeDegradedRecovery(authority, true, enableAfterRecovery = true))

        assertEquals(before, roots.get(authority))
        assertFalse(roots.get(authority).consent.enabled)
        assertEquals(1, probe.acquisitions)
        assertEquals(1, probe.completions)
        assertEquals(1, probe.aborts)
        assertTrue(probe.transports.single().closed)
    }

    @Test
    fun publicationFailureAbortsTheAcquiredTransport() = fixture { db, _, probe ->
        var aborted = false
        val transport = SyntheticRootSessionTransport(probe.identity)
        val acquired = RootAcquiredSession(probe.identity, caller(probe.identity), transport)
        val connector = object : RootSessionConnector {
            override fun tryAcquireSession(
                authority: Authority,
                identityEpoch: Long,
                appInstanceId: String,
                shizukuState: ShizukuAuthorityState?,
            ) = acquired

            override fun abortAcquisition(authority: Authority, attemptToken: Long) {
                aborted = true
            }
        }
        val healthy = RootAuthorityRepository(db).get(authority).copy(degradedEpoch = null)
        val acquisition = RootForegroundAcquisition(
            connector = connector,
            currentRecord = { healthy },
            hasActiveSession = { false },
            isReady = { false },
            advanceEpoch = { healthy },
            nextGeneration = { 1L },
            currentGeneration = { 1L },
            isClosed = { false },
            completeAndAttach = { _, _ -> error("Injected acquired-session publication failure") },
            verifyAuthorization = { true },
        )

        assertFalse(acquisition.request(authority, probe.identity.appInstanceId, null))
        assertTrue(aborted)
        assertTrue(transport.closed)
    }

    private class Probe(val identity: PrivilegedSessionIdentity, var boot: String) : RootSessionConnector {
        var acquisitions = 0
        var probeCalls = 0
        var completions = 0
        var aborts = 0
        var epochOffset = 0L
        var authorityOverride: Authority? = null
        var acquisitionCurrent = true
        var completionAccepted = true
        var beforeReturn: () -> Unit = {}
        val transports = mutableListOf<SyntheticRootSessionTransport>()

        override fun tryAcquireSession(
            authority: Authority,
            identityEpoch: Long,
            appInstanceId: String,
            shizukuState: ShizukuAuthorityState?,
        ): RootAcquiredSession {
            acquisitions++
            val acquiredIdentity = identity.copy(
                authority = authorityOverride ?: identity.authority,
                identityEpoch = identityEpoch + epochOffset,
                appInstanceId = appInstanceId,
            )
            val transport = SyntheticRootSessionTransport(acquiredIdentity)
            transports += transport
            beforeReturn()
            return RootAcquiredSession(
                identity = acquiredIdentity,
                caller = caller(acquiredIdentity),
                transport = transport,
                controlPlane = boundary(),
                attemptToken = acquisitions.toLong(),
                kernelBootId = boot,
                kernelElapsedRealtimeMs = 100_000L,
            )
        }

        override fun probeBootIdentity(
            authority: Authority,
            identityEpoch: Long,
            appInstanceId: String,
            shizukuState: ShizukuAuthorityState?,
        ): RootBootObservation {
            probeCalls++
            return RootBootObservation(identity.copy(identityEpoch = identityEpoch, appInstanceId = appInstanceId), boot)
        }

        override fun isAcquisitionCurrent(authority: Authority, attemptToken: Long): Boolean = acquisitionCurrent

        override fun completeAcquisition(authority: Authority, attemptToken: Long): Boolean {
            completions++
            return completionAccepted
        }

        override fun abortAcquisition(authority: Authority, attemptToken: Long) {
            aborts++
            transports.lastOrNull()?.close()
        }

        override fun cancelPendingAcquisition(authority: Authority): Boolean = false
    }

    private companion object {
        fun identity(epoch: Long) = PrivilegedSessionIdentity(
            Authority.ROOT_DIRECT, PrivilegeTier.ROOT, 0, 10148, 0,
            "boot-test-app-instance", "runtime.mobileagent", "a".repeat(64), epoch,
            "boot-test-service-instance", "boot-test-session-instance",
            PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION,
        )

        fun caller(identity: PrivilegedSessionIdentity) = RootCallerObservation(
            identity.appUid, 0, identity.packageName, identity.androidUserId, identity.signingSha256,
        )

        fun boundary() = RootControlPlaneBoundary(
            "/data/user/0/runtime.mobileagent",
            RootFdNodeIdentity(1, 101, 11, true, false, false, 1, 0x41ed, 0, 0),
            "/data/user_de/0/runtime.mobileagent",
            RootFdNodeIdentity(2, 201, 12, true, false, false, 1, 0x41ed, 0, 0),
        )
    }
}
