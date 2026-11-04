// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

class RootBootRecoveryTest {
    private fun boot(value: Long) = java.util.UUID(0L, value).toString()
    private val fixture = RootApprovalFixture()
    private val authority = Authority.ROOT_DIRECT
    private val arguments = """{"relativePath":"private-scope-marker/file.txt","workspaceId":"root-workspace"}"""

    private fun setup(db: SqlConnection): RootReceiptRecoveryCoordinator {
        fixture.setup(db, "private-scope-marker", GrantLifetime.PERSISTENT)
        db.execute("UPDATE tool_invocations SET arguments_json=?", listOf(arguments))
        return RootReceiptRecoveryCoordinator(db) { fixture.now }
    }

    private fun leaveUnknown(db: SqlConnection, remoteQuiescent: Boolean = false): String {
        val approvals = RootApprovalRepository(db, clock = { fixture.now })
        val request = fixture.request().copy(normalizedArgumentsSha256 = RootReceiptDigests.text(arguments))
        val approved = approvals.approve(approvals.createPending(request).approval.approvalId)
        val dispatch = RootDispatchRepository(db) { fixture.now }
        val permit = dispatch.claim(approved.approval.approvalId, approved.request)
        dispatch.takeAttempt(dispatch.beginAttempt(permit), arguments)
        dispatch.finish(permit, RootDispatchOutcome.UNKNOWN_OUTCOME, remoteQuiescent = remoteQuiescent)
        dispatch.closePermit(permit, false)
        return permit.requestId
    }

    @Test fun monotonicDecreaseProvesRestartWhenKernelUuidIsUnavailable() {
        JdbcSqlConnection().use { db ->
            val recovery = setup(db)
            assertTrue(recovery.recordSessionBoot(authority, null, 100_000L))
            val id = leaveUnknown(db)
            val before = RootDispatchRepository(db).getReceipt(id)!!
            val revision = RootAuthorityRepository(db).get(authority).revision
            assertNull(recovery.acknowledgeDeviceRestart(authority, null, true, revision, 101_000L))
            val recovered = recovery.acknowledgeDeviceRestart(authority, null, true, revision, 5_000L)!!
            assertFalse(recovered.degraded)
            assertEquals(before, RootDispatchRepository(db).getReceipt(id))
        }
    }

    @Test fun largerElapsedTimeIsNotARestartButStillMovesTheBaselineForALaterDecrease() {
        JdbcSqlConnection().use { db ->
            val recovery = setup(db)
            assertTrue(recovery.recordSessionBoot(authority, null, 100_000L))
            leaveUnknown(db)
            val revision = RootAuthorityRepository(db).get(authority).revision
            assertNull(
                recovery.acknowledgeDeviceRestart(authority, null, true, revision, 150_000L),
                "A larger uptime says nothing about a new boot",
            )
            assertTrue(RootAuthorityRepository(db).get(authority).degraded)
            // The larger observation becomes the new monotonic baseline; a later decrease still
            // proves that another boot happened, without using any per-process/PID inference.
            assertNotNull(recovery.acknowledgeDeviceRestart(authority, null, true, revision, 50_000L))
            assertFalse(RootAuthorityRepository(db).get(authority).degraded)
        }
    }

    @Test fun sameKernelUuidCannotBeOverriddenByInconsistentElapsedTime() {
        JdbcSqlConnection().use { db ->
            val recovery = setup(db)
            assertTrue(recovery.recordSessionBoot(authority, boot(10), 100_000L))
            leaveUnknown(db)
            val revision = RootAuthorityRepository(db).get(authority).revision
            assertNull(recovery.acknowledgeDeviceRestart(authority, boot(10), true, revision, 5_000L))
            assertTrue(RootAuthorityRepository(db).get(authority).degraded)
        }
    }

    @Test fun provenDrainKeepsUnknownEffectsWithoutDegradingTheChannelOrBackfillingDrain() {
        JdbcSqlConnection().use { db ->
            val recovery = setup(db)
            val id = leaveUnknown(db, remoteQuiescent = true)
            assertFalse(RootAuthorityRepository(db).get(authority).degraded)
            val receipt = RootDispatchRepository(db).getReceipt(id)!!
            assertEquals(RootDispatchOutcome.UNKNOWN_OUTCOME, receipt.outcome)
            assertEquals(RootDisclosureState.WITHHELD, receipt.disclosure)
            recovery.recoverInterrupted()
            assertTrue(RootPendingDrainRepository(db).load(authority) is RootPendingDrainLoad.Missing)
            val roots = RootAuthorityRepository(db)
            val current = roots.get(authority)
            roots.markDegraded(authority, current.revision, current.identityEpoch)
            recovery.recoverInterrupted()
            assertTrue(RootPendingDrainRepository(db).load(authority) is RootPendingDrainLoad.Missing)
        }
    }

    @Test fun laterDeviceBootRecoversWithoutRewritingUnknownOrDisclosingOutput() {
        JdbcSqlConnection().use { db ->
            val recovery = setup(db)
            assertTrue(recovery.recordSessionBoot(authority, boot(10)))
            val id = leaveUnknown(db)
            val before = RootDispatchRepository(db).getReceipt(id)!!
            assertNull(recovery.acknowledgeDeviceRestart(authority, boot(10), true, RootAuthorityRepository(db).get(authority).revision))
            assertNull(recovery.acknowledgeDeviceRestart(authority, "9", true, RootAuthorityRepository(db).get(authority).revision))
            assertNull(recovery.acknowledgeDeviceRestart(authority, boot(11), false, RootAuthorityRepository(db).get(authority).revision))
            val recovered = recovery.acknowledgeDeviceRestart(authority, boot(11), true, RootAuthorityRepository(db).get(authority).revision)!!
            assertFalse(recovered.degraded)
            assertEquals(before, RootDispatchRepository(db).getReceipt(id))
            assertEquals(RootDispatchOutcome.UNKNOWN_OUTCOME, before.outcome)
            assertNotEquals(RootDisclosureState.RELEASED, before.disclosure)
            assertTrue(RootPendingDrainRepository(db).load(authority) is RootPendingDrainLoad.Missing)
            assertEquals(recovered, recovery.acknowledgeDeviceRestart(authority, boot(11), true, RootAuthorityRepository(db).get(authority).revision))
            assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test fun newerHealthySessionReplacesOldBootBeforeItCanBecomeDegraded() {
        JdbcSqlConnection().use { db ->
            val recovery = setup(db)
            assertTrue(recovery.recordSessionBoot(authority, boot(10)))
            assertTrue(recovery.recordSessionBoot(authority, boot(11)))
            leaveUnknown(db)
            assertFalse(recovery.recordSessionBoot(authority, boot(12)))
            assertNull(recovery.acknowledgeDeviceRestart(authority, boot(11), true, RootAuthorityRepository(db).get(authority).revision))
            assertNotNull(recovery.acknowledgeDeviceRestart(authority, boot(12), true, RootAuthorityRepository(db).get(authority).revision))
        }
    }

    @Test fun legacyMissingBootProofSeedsButDoesNotUnlockOnAppRestart() {
        JdbcSqlConnection().use { db ->
            val recovery = setup(db)
            leaveUnknown(db)
            assertNull(recovery.acknowledgeDeviceRestart(authority, null, true, RootAuthorityRepository(db).get(authority).revision))
            assertNull(recovery.acknowledgeDeviceRestart(authority, boot(20), true, RootAuthorityRepository(db).get(authority).revision))
            assertNull(RootReceiptRecoveryCoordinator(db) { fixture.now }.acknowledgeDeviceRestart(authority, boot(20), true, RootAuthorityRepository(db).get(authority).revision))
            assertNotNull(recovery.acknowledgeDeviceRestart(authority, boot(21), true, RootAuthorityRepository(db).get(authority).revision))
        }
    }

    @Test fun missingKernelIdentityCannotLeaveAnOlderHealthyMarkerAsProof() {
        JdbcSqlConnection().use { db ->
            val recovery = setup(db)
            recovery.recordSessionBoot(authority, boot(10))
            recovery.recordSessionBoot(authority, null)
            leaveUnknown(db)
            assertNull(recovery.acknowledgeDeviceRestart(authority, boot(11), true, RootAuthorityRepository(db).get(authority).revision))
            assertNotNull(recovery.acknowledgeDeviceRestart(authority, boot(12), true, RootAuthorityRepository(db).get(authority).revision))
        }
    }

    @Test fun corruptMarkerAndFailedEpochRotationRetainDrainEvidence() {
        JdbcSqlConnection().use { db ->
            val recovery = setup(db)
            recovery.recordSessionBoot(authority, boot(10))
            leaveUnknown(db)
            val before = RootPendingDrainRepository(db).load(authority)
            val key = RootPendingDrainRepository.KERNEL_BOOT_PREFIX + authority.name
            db.execute("UPDATE app_prefs SET value=? WHERE key=?", listOf("damaged", key))
            assertNull(recovery.acknowledgeDeviceRestart(authority, boot(11), true, RootAuthorityRepository(db).get(authority).revision))
            db.execute("UPDATE app_prefs SET value=? WHERE key=?", listOf(boot(10), key))
            db.execute("CREATE TRIGGER reject_boot_recovery BEFORE UPDATE ON root_authority_state BEGIN SELECT RAISE(ABORT,'blocked'); END")
            assertThrows(Exception::class.java) { recovery.acknowledgeDeviceRestart(authority, boot(11), true, RootAuthorityRepository(db).get(authority).revision) }
            assertEquals(before, RootPendingDrainRepository(db).load(authority))
            assertTrue(RootAuthorityRepository(db).get(authority).degraded)
        }
    }
}
