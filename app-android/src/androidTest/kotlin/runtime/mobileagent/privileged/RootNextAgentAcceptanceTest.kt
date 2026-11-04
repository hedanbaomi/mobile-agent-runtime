// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.MainActivity
import runtime.mobileagent.MobileAgentApp
import runtime.mobileagent.RunCoordinator
import runtime.mobileagent.SettingsViewModel
import runtime.mobileagent.WorkspacePickerTarget
import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.AuthorityPreferencesRepository
import runtime.mobileagent.data.CapabilityGrantRepository
import runtime.mobileagent.data.ProfileRepository
import runtime.mobileagent.data.RootAuthorityRepository
import runtime.mobileagent.data.RunRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.AuthorityUserIntent
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.domain.ToolInvocation
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.integration.RootPermissionEntry
import runtime.mobileagent.integration.RootWorkspaceUiController
import runtime.mobileagent.integration.SqliteAuthorityStateStore
import runtime.mobileagent.integration.WorkspaceAccessResult
import runtime.mobileagent.shizuku.ShizukuAuthorityBridge
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.Availability
import runtime.mobileagent.skills.tooling.Connection
import runtime.mobileagent.skills.tooling.ElevatedAuthority
import runtime.mobileagent.skills.tooling.PlatformGrant
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.tooling.AuthorityManager
import runtime.mobileagent.tooling.ToolExecutionContext

/**
 * Production device acceptance for `docs/plans/root-next-agent-20261102.md`:
 * - Item 1: Real `SHIZUKU_ROOT` two-step foreground UI entry on a fresh unauthorized app.
 * - Item 2: Live `SHIZUKU_ROOT` platform permission revocation, UserService/server death & recovery,
 *   and UID 0 -> legacy `SHIZUKU` `SERVER_UID_UNTRUSTED` + cross-channel isolation.
 * - Item 4: Default helper (`directTransportFactory = null`) real handshake blocking (timeout,
 *   cancel, late completion fence) and service death / drain recovery.
 */
@RunWith(AndroidJUnit4::class)
class RootNextAgentAcceptanceTest {
    @Test
    fun shizukuRootTwoStepForegroundEntryAndLegacyUid0Rejection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as MobileAgentApp
        app.ensureHostInitialized()
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val db = app.container.db
        val port = runtime.mobileagent.settingsAuthorityPort(app)
        val coordinator = app.container.runtimeIntegration.rootCoordinator
        lateinit var vm: SettingsViewModel
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { vm = SettingsViewModel(app) }

        val initialFacts = waitForShizukuBinder(timeoutMs = 15_000L)
        assertNotNull("Shizuku UID 0 binder must be reachable", initialFacts)
        assertTrue("Shizuku binder must be alive", initialFacts!!.binderAlive)
        assertFalse(
            "Precondition: app must start WITHOUT Shizuku permission granted",
            initialFacts.permissionGranted,
        )

        val initialSnap = port.refresh()
        assertEquals(Authority.NONE, initialSnap.selectedAuthority)
        assertEquals(AuthorityUserIntent.NONE, initialSnap.shizukuRoot.userIntent)
        assertFalse(initialSnap.shizukuRoot.configured)
        val initialRecord = RootAuthorityRepository(db).get(Authority.SHIZUKU_ROOT)
        assertFalse(initialRecord.consent.enabled)
        assertFalse(initialRecord.degraded)
        assertEquals(0L, initialRecord.identityEpoch)
        assertFalse(coordinator.isAuthorityReadyForDispatch(Authority.SHIZUKU_ROOT))

        // Step 1 click: fresh unauthorized app clicks "Enable & Select" on SHIZUKU_ROOT.
        instrumentation.runOnMainSync { vm.enableShizukuRoot() }
        assertNull("First foreground click must request platform permission even before its UID can be read", vm.error.value)
        val afterStep1Snap = port.snapshot()
        assertEquals(
            "Step 1 click on unauthorized app must NOT select SHIZUKU_ROOT",
            Authority.NONE,
            afterStep1Snap.selectedAuthority,
        )
        assertEquals(
            "Step 1 click on unauthorized app must NOT enable SHIZUKU_ROOT intent",
            AuthorityUserIntent.NONE,
            afterStep1Snap.shizukuRoot.userIntent,
        )
        assertFalse(
            "Step 1 click on unauthorized app must NOT configure SHIZUKU_ROOT",
            afterStep1Snap.shizukuRoot.configured,
        )
        assertFalse(
            "Step 1 click must NOT enable Root consent in SQLite",
            RootAuthorityRepository(db).get(Authority.SHIZUKU_ROOT).consent.enabled,
        )
        assertFalse(
            "Step 1 click must NOT acquire Root session",
            coordinator.isAuthorityReadyForDispatch(Authority.SHIZUKU_ROOT),
        )
        println("NEXT_AGENT_TOKEN=SHIZUKU_STEP1_REQUESTED")

        // Wait for the external user/harness to grant the Shizuku permission dialog.
        val grantedFacts = waitForShizukuGrant(expectedGranted = true, timeoutMs = 25_000L)
        assertNotNull("Shizuku permission grant was not observed within budget", grantedFacts)
        assertTrue(grantedFacts!!.permissionGranted)
        assertEquals("Shizuku server must be UID 0", 0, grantedFacts.serverUid)

        // Callback / refresh after permission grant must only update platform state and NEVER auto-acquire.
        val afterGrantSnap = port.refresh()
        assertEquals(
            "Permission grant + refresh must NOT auto-select SHIZUKU_ROOT",
            Authority.NONE,
            afterGrantSnap.selectedAuthority,
        )
        assertEquals(
            "Permission grant + refresh must NOT auto-enable SHIZUKU_ROOT intent",
            AuthorityUserIntent.NONE,
            afterGrantSnap.shizukuRoot.userIntent,
        )
        assertFalse(
            "Permission grant + refresh must NOT auto-configure SHIZUKU_ROOT",
            afterGrantSnap.shizukuRoot.configured,
        )
        assertFalse(
            "Permission grant + refresh must NOT configure legacy SHIZUKU channel",
            afterGrantSnap.shizuku.configured,
        )
        assertFalse(
            "Permission grant + refresh must NOT auto-start RootShizukuUserService",
            coordinator.isAuthorityReadyForDispatch(Authority.SHIZUKU_ROOT),
        )
        assertNull(
            "Execution stamp must remain null before Step 2 click",
            coordinator.currentExecutionStamp(Authority.SHIZUKU_ROOT, requireShellConsent = false),
        )

        // Verify legacy SHIZUKU shell bridge explicitly rejects UID 0 server as SERVER_UID_UNTRUSTED.
        ShizukuAuthorityBridge(context).use { bridge ->
            val legacyState = bridge.refresh()
            assertEquals(0, legacyState.serverUid)
            assertFalse("Legacy SHIZUKU must not be ready on UID 0 server", legacyState.ready)
            assertFalse("Legacy SHIZUKU must not bind shell service on UID 0 server", legacyState.userServiceAlive)
            assertEquals(
                "Legacy SHIZUKU must reject UID 0 server with SERVER_UID_UNTRUSTED",
                ShizukuAuthorityBridge.SERVER_UID_UNTRUSTED,
                legacyState.errorCode,
            )
        }

        // Step 2 explicit click: user clicks "Enable & Select" after permission is granted.
        instrumentation.runOnMainSync { vm.enableShizukuRoot() }
        // The UI action returns before the blocking Root handshake completes on IO.
        // Keep the original policy/readiness assertions after bounded observable completion.
        val readyDeadline = android.os.SystemClock.elapsedRealtime() + 15_000L
        var afterStep2Snap = port.snapshot()
        while (android.os.SystemClock.elapsedRealtime() < readyDeadline &&
            (afterStep2Snap.selectedAuthority != Authority.SHIZUKU_ROOT ||
                !afterStep2Snap.shizukuRoot.configured ||
                afterStep2Snap.shizukuRoot.availability != Availability.READY ||
                afterStep2Snap.shizukuRoot.connection != Connection.CONNECTED)
        ) {
            Thread.sleep(50L)
            afterStep2Snap = port.snapshot()
        }
        assertEquals(Authority.SHIZUKU_ROOT, afterStep2Snap.selectedAuthority)
        assertEquals(AuthorityUserIntent.SHIZUKU_ROOT, afterStep2Snap.shizukuRoot.userIntent)
        assertTrue(afterStep2Snap.shizukuRoot.configured)
        assertEquals(PlatformGrant.GRANTED, afterStep2Snap.shizukuRoot.platformGrant)
        assertEquals(Availability.READY, afterStep2Snap.shizukuRoot.availability)
        assertEquals(Connection.CONNECTED, afterStep2Snap.shizukuRoot.connection)

        val policy = AuthorityPolicyRepository(db).getPolicy()
        val activeRecord = RootAuthorityRepository(db).get(Authority.SHIZUKU_ROOT)
        assertTrue(activeRecord.consent.enabled)
        assertFalse(activeRecord.consent.shellEnabled)
        assertEquals(
            "F14: Step 2 must bind RootConsent.policyVersion to current SQLite policyVersion",
            policy.policyVersion,
            activeRecord.consent.policyVersion,
        )
        assertTrue(coordinator.isAuthorityReadyForDispatch(Authority.SHIZUKU_ROOT))
        val stamp = requireNotNull(
            coordinator.currentExecutionStamp(Authority.SHIZUKU_ROOT, requireShellConsent = false),
        )
        assertEquals(0, stamp.identity.serviceUid)
        assertEquals(Process.myUid(), stamp.identity.appUid)
        assertEquals(Authority.SHIZUKU_ROOT, stamp.identity.authority)
        assertEquals(PrivilegeTier.ROOT, stamp.identity.privilegeTier)

        // Cross-channel isolation: ROOT_DIRECT and legacy SHIZUKU remain unenabled/not ready.
        assertFalse(RootAuthorityRepository(db).get(Authority.ROOT_DIRECT).consent.enabled)
        assertFalse(coordinator.isAuthorityReadyForDispatch(Authority.ROOT_DIRECT))
        assertFalse(afterStep2Snap.shizuku.configured)
        println(
            "NEXT_AGENT_TOKEN=SHIZUKU_STEP2_READY sessionId=${stamp.identity.sessionId} " +
                "epoch=${stamp.identity.identityEpoch} policyVersion=${stamp.policyVersion}",
        )
    }

    @Test
    fun shizukuRootLivePermissionRevocationFencesSessionAndBlocksOldApproval() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as MobileAgentApp
        app.ensureHostInitialized()
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val db = app.container.db
        val facts = waitForShizukuGrant(expectedGranted = true, timeoutMs = 15_000L)
        assertNotNull("Precondition: UID 0 Shizuku server with granted permission required", facts)
        assertEquals(0, facts!!.serverUid)

        val authorityManager = AuthorityManager(
            SqliteAuthorityStateStore(AuthorityPolicyRepository(db), AuthorityPreferencesRepository(db)),
        )
        val coordinator = RootRuntimeCoordinator(context, db)
        coordinator.shizukuObservationIntervalMs = 250L
        var executor: RootToolExecutor? = null
        try {
            assertTrue(authorityManager.selectAuthority(ElevatedAuthority.SHIZUKU_ROOT))
            val liveState = RootPermissionEntry.resolveState(null) { facts }
            coordinator.setUserIntent(
                authority = Authority.SHIZUKU_ROOT,
                enabled = true,
                authorityManager = authorityManager,
                dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
                shizukuState = liveState,
            )
            assertTrue(coordinator.isAuthorityReadyForDispatch(Authority.SHIZUKU_ROOT))
            val stamp = requireNotNull(
                coordinator.currentExecutionStamp(Authority.SHIZUKU_ROOT, requireShellConsent = false),
            )
            assertEquals(0, stamp.identity.serviceUid)

            val harness = prepareToolHarness(
                db = db,
                coordinator = coordinator,
                authority = Authority.SHIZUKU_ROOT,
                workspacePath = "/data/adb/root-typed-acceptance",
                policyVersion = stamp.policyVersion,
            )
            executor = harness.executor
            val metaArgs = buildJsonObject {
                put("workspace_id", harness.workspaceId)
                put("relative_path", "")
            }.toString()

            // Prove live UID 0 execution works before revocation.
            val initialCallId = "shizuku-pre-revoke-ok-${UUID.randomUUID()}"
            assertEquals(
                ToolResult.NeedsApproval,
                runBlocking { executor.invoke(ToolCall(initialCallId, "file_get_metadata", metaArgs)) },
            )
            recordWaitingInvocation(db, harness.runs, harness.runId, initialCallId, "file_get_metadata", metaArgs)
            val initialApproved = runBlocking { executor.approve(initialCallId) }
            assertTrue("Initial metadata read must succeed: $initialApproved", initialApproved is ToolResult.Value)

            // Queue a second invocation and leave it WAITING_APPROVAL across the live revocation.
            val staleCallId = "shizuku-stale-across-revoke-${UUID.randomUUID()}"
            assertEquals(
                ToolResult.NeedsApproval,
                runBlocking { executor.invoke(ToolCall(staleCallId, "file_get_metadata", metaArgs)) },
            )
            recordWaitingInvocation(db, harness.runs, harness.runId, staleCallId, "file_get_metadata", metaArgs)
            println("NEXT_AGENT_TOKEN=SHIZUKU_REVOKE_ARMED callId=$staleCallId")

            val deadline = System.currentTimeMillis() + 25_000L
            while (System.currentTimeMillis() < deadline &&
                (coordinator.isAuthorityReadyForDispatch(Authority.SHIZUKU_ROOT) ||
                    coordinator.currentExecutionStamp(Authority.SHIZUKU_ROOT, requireShellConsent = false) != null)
            ) {
                Thread.sleep(150L)
            }
            assertFalse(
                "SHIZUKU_ROOT must stop being ready after live platform permission revocation",
                coordinator.isAuthorityReadyForDispatch(Authority.SHIZUKU_ROOT),
            )
            val degradedRecord = RootAuthorityRepository(db).get(Authority.SHIZUKU_ROOT)
            assertFalse("A waiting approval has no remote work requiring degraded recovery", degradedRecord.degraded)
            assertNull(
                "Execution stamp must be null after live permission revocation",
                coordinator.currentExecutionStamp(Authority.SHIZUKU_ROOT, requireShellConsent = false),
            )

            // Old approval created before revocation must be rejected even if the UID 0 process was alive.
            val lateApprove = runBlocking { executor.approve(staleCallId) }
            assertFalse("Pre-revocation approval must not succeed after revocation: $lateApprove", lateApprove is ToolResult.Value)
            assertEquals(
                ToolResult.Denied(ToolErrorCode.AUTHORITY_NOT_GRANTED.name),
                lateApprove,
            )
            println("NEXT_AGENT_TOKEN=SHIZUKU_REVOKE_FENCED degraded=${degradedRecord.degraded}")
        } finally {
            runCatching { executor?.let { runBlocking { it.close() } } }
            runCatching { coordinator.close() }
        }
    }

    @Test
    fun shizukuRootCleanServiceDeathReconnectsAndRejectsOldApproval() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as MobileAgentApp
        app.ensureHostInitialized()
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val db = app.container.db
        val facts1 = waitForShizukuGrant(expectedGranted = true, timeoutMs = 15_000L)
        assertNotNull("Precondition: UID 0 Shizuku server with granted permission required", facts1)
        assertEquals(0, facts1!!.serverUid)

        val authorityManager = AuthorityManager(
            SqliteAuthorityStateStore(AuthorityPolicyRepository(db), AuthorityPreferencesRepository(db)),
        )
        val coordinator = RootRuntimeCoordinator(context, db)
        coordinator.shizukuObservationIntervalMs = 250L
        var executor1: RootToolExecutor? = null
        try {
            assertTrue(authorityManager.selectAuthority(ElevatedAuthority.SHIZUKU_ROOT))
            coordinator.setUserIntent(
                authority = Authority.SHIZUKU_ROOT,
                enabled = true,
                authorityManager = authorityManager,
                dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
                shizukuState = RootPermissionEntry.resolveState(null) { facts1 },
            )
            assertTrue(coordinator.isAuthorityReadyForDispatch(Authority.SHIZUKU_ROOT))
            val stamp1 = requireNotNull(
                coordinator.currentExecutionStamp(Authority.SHIZUKU_ROOT, requireShellConsent = false),
            )
            assertEquals(1L, stamp1.identity.identityEpoch)

            val harness1 = prepareToolHarness(
                db = db,
                coordinator = coordinator,
                authority = Authority.SHIZUKU_ROOT,
                workspacePath = "/data/adb/root-typed-acceptance",
                policyVersion = stamp1.policyVersion,
            )
            executor1 = harness1.executor
            val metaArgs = buildJsonObject {
                put("workspace_id", harness1.workspaceId)
                put("relative_path", "")
            }.toString()

            val initialCallId = "shizuku-epoch1-ok-${UUID.randomUUID()}"
            assertEquals(
                ToolResult.NeedsApproval,
                runBlocking { executor1.invoke(ToolCall(initialCallId, "file_get_metadata", metaArgs)) },
            )
            recordWaitingInvocation(db, harness1.runs, harness1.runId, initialCallId, "file_get_metadata", metaArgs)
            val initialRes = runBlocking { executor1.approve(initialCallId) }
            assertTrue("Initial epoch 1 metadata read must succeed: $initialRes", initialRes is ToolResult.Value)

            val staleCallId = "shizuku-epoch1-stale-${UUID.randomUUID()}"
            assertEquals(
                ToolResult.NeedsApproval,
                runBlocking { executor1.invoke(ToolCall(staleCallId, "file_get_metadata", metaArgs)) },
            )
            recordWaitingInvocation(db, harness1.runs, harness1.runId, staleCallId, "file_get_metadata", metaArgs)
            println("NEXT_AGENT_TOKEN=SHIZUKU_KILL_READY sessionId=${stamp1.identity.sessionId}")

            // Wait for harness to kill the UID 0 UserService + Shizuku server and for shizukuWatcher to fence.
            val fenceDeadline = System.currentTimeMillis() + 20_000L
            while (System.currentTimeMillis() < fenceDeadline &&
                (coordinator.isAuthorityReadyForDispatch(Authority.SHIZUKU_ROOT) ||
                    coordinator.currentExecutionStamp(Authority.SHIZUKU_ROOT, requireShellConsent = false) != null)
            ) {
                Thread.sleep(150L)
            }
            assertFalse(coordinator.isAuthorityReadyForDispatch(Authority.SHIZUKU_ROOT))
            assertFalse("No request crossed the wire at service death", RootAuthorityRepository(db).get(Authority.SHIZUKU_ROOT).degraded)
            assertNull(coordinator.currentExecutionStamp(Authority.SHIZUKU_ROOT, requireShellConsent = false))

            // Wait for the restarted UID 0 Shizuku server to become reachable again.
            val facts2 = waitForShizukuGrant(expectedGranted = true, timeoutMs = 25_000L)
            assertNotNull("Restarted UID 0 Shizuku server must become reachable", facts2)
            assertEquals(0, facts2!!.serverUid)
            val state2 = RootPermissionEntry.resolveState(null) { facts2 }
            coordinator.refreshLiveSessions(state2)

            // Only WAITING_APPROVAL work existed at death; no remote drain proof is required.
            // The separate boot-phase test keeps the unresolved-work same-boot refusal case.
            assertTrue(coordinator.requestSessionFromForeground(Authority.SHIZUKU_ROOT, state2))
            val stamp2 = requireNotNull(coordinator.currentExecutionStamp(Authority.SHIZUKU_ROOT))
            assertTrue(stamp2.identity.identityEpoch > stamp1.identity.identityEpoch)
            assertFalse(stamp2.identity.sessionId == stamp1.identity.sessionId)
            assertFalse(RootAuthorityRepository(db).get(Authority.SHIZUKU_ROOT).degraded)
            // A new authenticated session cannot revive the previous session's pending approval.
            val oldEpochApprove = runBlocking { executor1.approve(staleCallId) }
            assertEquals(
                "Old approval must not execute after service reconnection: $oldEpochApprove",
                ToolResult.Denied(ToolErrorCode.AUTHORITY_NOT_GRANTED.name),
                oldEpochApprove,
            )

            println("NEXT_AGENT_TOKEN=SHIZUKU_DEATH_FENCED")
        } finally {
            runCatching { executor1?.let { runBlocking { it.close() } } }
            runCatching { coordinator.close() }
        }
    }

    @Test
    fun rootDirectDefaultHelperHandshakeAndServiceDeathRecovery() {
        val mode = InstrumentationRegistry.getArguments().getString("helperMode") ?: "service_death_recovery"
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as MobileAgentApp
        app.ensureHostInitialized()
        when (mode) {
            "timeout" -> runDefaultHelperTimeout(context)
            "cancel_late" -> runDefaultHelperCancelAndLateCompletion(context)
            "service_death_recovery" -> runDefaultHelperServiceDeathRecovery(context, app.container.db)
            else -> error("Unknown helperMode: $mode")
        }
    }

    private fun runDefaultHelperTimeout(context: Context) {
        val connector = AndroidRootSessionConnector(context)
        try {
            val started = System.nanoTime()
            val acquired = connector.tryAcquireSession(
                authority = Authority.ROOT_DIRECT,
                identityEpoch = 1L,
                appInstanceId = "helper-timeout-${UUID.randomUUID()}",
                shizukuState = null,
            )
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            assertNull("Blocked default helper handshake must time out and return null", acquired)
            val reason = connector.lastAcquireFailureReason.orEmpty()
            assertTrue("Expected handshake_timeout reason, got '$reason'", reason.startsWith("handshake_timeout:"))
            assertNull(connector.activeTransport(Authority.ROOT_DIRECT))
            assertTrue("Timeout must stay bounded (4s..20s), took ${elapsedMs}ms", elapsedMs in 4_000L..20_000L)
            println("NEXT_AGENT_TOKEN=HELPER_TIMEOUT_OK reason=$reason elapsedMs=$elapsedMs")
        } finally {
            connector.close()
        }
    }

    private fun runDefaultHelperCancelAndLateCompletion(context: Context) {
        val connector = AndroidRootSessionConnector(context)
        try {
            val result = AtomicReference<RootAcquiredSession?>(null)
            val failure = AtomicReference<Throwable?>(null)
            val started = System.nanoTime()
            val worker = Thread({
                try {
                    result.set(
                        connector.tryAcquireSession(
                            authority = Authority.ROOT_DIRECT,
                            identityEpoch = 1L,
                            appInstanceId = "helper-cancel-${UUID.randomUUID()}",
                            shizukuState = null,
                        ),
                    )
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }, "helper-cancel-worker").apply { isDaemon = true; start() }

            Thread.sleep(2_000L)
            assertTrue(
                "Pending default helper acquisition must be cancellable",
                connector.cancelPendingAcquisition(Authority.ROOT_DIRECT),
            )
            worker.join(8_000L)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            assertFalse("Cancelled worker must finish promptly", worker.isAlive)
            assertNull("Cancelled worker must not throw: ${failure.get()}", failure.get())
            assertNull("Cancelled attempt must never publish a session even on late completion", result.get())
            assertEquals("acquisition_cancelled", connector.lastAcquireFailureReason)
            assertNull("No transport may remain attached after cancellation", connector.activeTransport(Authority.ROOT_DIRECT))
            println("NEXT_AGENT_TOKEN=HELPER_CANCEL_LATE_OK elapsedMs=$elapsedMs")
        } finally {
            connector.close()
        }
    }

    private fun runDefaultHelperServiceDeathRecovery(context: Context, db: SqlConnection) {
        val authorityManager = AuthorityManager(
            SqliteAuthorityStateStore(AuthorityPolicyRepository(db), AuthorityPreferencesRepository(db)),
        )
        val coordinator = RootRuntimeCoordinator(context, db)
        coordinator.managerObservationIntervalMs = 250L
        var executor1: RootToolExecutor? = null
        var executor2: RootToolExecutor? = null
        try {
            assertTrue(authorityManager.selectAuthority(ElevatedAuthority.ROOT_DIRECT))
            coordinator.setUserIntent(
                authority = Authority.ROOT_DIRECT,
                enabled = true,
                authorityManager = authorityManager,
                dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
                shizukuState = null,
            )
            assertTrue(coordinator.isAuthorityReadyForDispatch(Authority.ROOT_DIRECT))
            assertTrue(coordinator.lastManagerObservation.orEmpty().startsWith("GRANTED:"))
            val stamp1 = requireNotNull(
                coordinator.currentExecutionStamp(Authority.ROOT_DIRECT, requireShellConsent = false),
            )
            assertEquals(1L, stamp1.identity.identityEpoch)

            val harness1 = prepareToolHarness(
                db = db,
                coordinator = coordinator,
                authority = Authority.ROOT_DIRECT,
                workspacePath = "/data/adb/root-typed-acceptance",
                policyVersion = stamp1.policyVersion,
            )
            executor1 = harness1.executor
            val metaArgs1 = buildJsonObject {
                put("workspace_id", harness1.workspaceId)
                put("relative_path", "")
            }.toString()

            val initialCallId = "direct-epoch1-ok-${UUID.randomUUID()}"
            assertEquals(
                ToolResult.NeedsApproval,
                runBlocking { executor1.invoke(ToolCall(initialCallId, "file_get_metadata", metaArgs1)) },
            )
            recordWaitingInvocation(db, harness1.runs, harness1.runId, initialCallId, "file_get_metadata", metaArgs1)
            val initialRes = runBlocking { executor1.approve(initialCallId) }
            assertTrue("Initial epoch 1 metadata read must succeed: $initialRes", initialRes is ToolResult.Value)

            val staleCallId = "direct-epoch1-stale-${UUID.randomUUID()}"
            assertEquals(
                ToolResult.NeedsApproval,
                runBlocking { executor1.invoke(ToolCall(staleCallId, "file_get_metadata", metaArgs1)) },
            )
            recordWaitingInvocation(db, harness1.runs, harness1.runId, staleCallId, "file_get_metadata", metaArgs1)
            println("NEXT_AGENT_TOKEN=DIRECT_KILL_READY sessionId=${stamp1.identity.sessionId}")

            // Wait for harness to kill the UID 0 RootPrivilegedProcessMain helper and for managerWatcher to fence.
            val fenceDeadline = System.currentTimeMillis() + 20_000L
            while (System.currentTimeMillis() < fenceDeadline &&
                (coordinator.isAuthorityReadyForDispatch(Authority.ROOT_DIRECT) ||
                    !RootAuthorityRepository(db).get(Authority.ROOT_DIRECT).degraded ||
                    coordinator.currentExecutionStamp(Authority.ROOT_DIRECT, requireShellConsent = false) != null)
            ) {
                Thread.sleep(150L)
            }
            assertFalse(coordinator.isAuthorityReadyForDispatch(Authority.ROOT_DIRECT))
            assertTrue(RootAuthorityRepository(db).get(Authority.ROOT_DIRECT).degraded)
            assertNull(coordinator.currentExecutionStamp(Authority.ROOT_DIRECT, requireShellConsent = false))

            val recovered = coordinator.acknowledgeDegradedRecovery(
                authority = Authority.ROOT_DIRECT,
                userConfirmed = true,
                authorityManager = authorityManager,
                shizukuState = null,
            )
            assertNotNull("Degraded recovery acknowledgement must succeed without SQL hacks", recovered)
            assertFalse(recovered!!.degraded)
            assertEquals(2L, recovered.identityEpoch)
            assertEquals(1L, recovered.recoveryAckEpoch)

            assertTrue(
                "Foreground re-acquisition of ROOT_DIRECT must succeed",
                coordinator.requestSessionFromForeground(Authority.ROOT_DIRECT, null),
            )
            assertTrue(coordinator.isAuthorityReadyForDispatch(Authority.ROOT_DIRECT))
            val stamp2 = requireNotNull(
                coordinator.currentExecutionStamp(Authority.ROOT_DIRECT, requireShellConsent = false),
            )
            assertEquals(3L, stamp2.identity.identityEpoch)
            assertNotEquals(stamp1.identity.sessionId, stamp2.identity.sessionId)

            // Old epoch 1 approval must fail on epoch 2.
            val staleRes = runBlocking { executor1.approve(staleCallId) }
            assertEquals(
                "Epoch 1 approval must be rejected on epoch 2: $staleRes",
                ToolResult.Denied(ToolErrorCode.AUTHORITY_NOT_GRANTED.name),
                staleRes,
            )

            // New Run & invocation on epoch 2 must succeed.
            val harness2 = prepareToolHarness(
                db = db,
                coordinator = coordinator,
                authority = Authority.ROOT_DIRECT,
                workspacePath = "/data/adb/root-typed-acceptance",
                policyVersion = stamp2.policyVersion,
            )
            executor2 = harness2.executor
            val metaArgs2 = buildJsonObject {
                put("workspace_id", harness2.workspaceId)
                put("relative_path", "")
            }.toString()
            val freshCallId = "direct-epoch2-ok-${UUID.randomUUID()}"
            assertEquals(
                ToolResult.NeedsApproval,
                runBlocking { executor2.invoke(ToolCall(freshCallId, "file_get_metadata", metaArgs2)) },
            )
            recordWaitingInvocation(db, harness2.runs, harness2.runId, freshCallId, "file_get_metadata", metaArgs2)
            val freshRes = runBlocking { executor2.approve(freshCallId) }
            assertTrue("Fresh epoch 2 invocation must succeed: $freshRes", freshRes is ToolResult.Value)
            println("NEXT_AGENT_TOKEN=DIRECT_RECOVERED epoch1=1 epoch2=${stamp2.identity.identityEpoch}")
        } finally {
            runCatching { executor1?.let { runBlocking { it.close() } } }
            runCatching { executor2?.let { runBlocking { it.close() } } }
            runCatching { coordinator.close() }
        }
    }

    private data class ToolHarness(
        val workspaceId: String,
        val runId: String,
        val runs: RunRepository,
        val executor: RootToolExecutor,
    )

    private fun prepareToolHarness(
        db: SqlConnection,
        coordinator: RootRuntimeCoordinator,
        authority: Authority,
        workspacePath: String,
        policyVersion: Long,
    ): ToolHarness {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val agentId = "agent-next-$suffix"
        val providerId = "provider-next-$suffix"
        val modelId = "model-next-$suffix"
        val snapshotId = "snapshot-next-$suffix"
        val conversationId = "conv-next-$suffix"
        val runId = "run-next-$suffix"
        val profiles = ProfileRepository(db)
        profiles.createProvider(
            ProviderProfile(providerId, "Next Provider", ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1", revision = 1),
        )
        profiles.createModel(
            ModelProfile(modelId, providerId, ModelRole.CHAT, "synthetic", emptySet(), contextLimit = 16_384, outputLimit = 512, revision = 1),
        )
        AgentRepository(db).saveWithPrompt(
            AgentProfile(agentId, "Next Agent", "pending", modelId, revision = 0),
            "Synthetic prompt",
        )
        val attached = RootWorkspaceUiController(db, coordinator).attach(
            authority = authority,
            path = workspacePath,
            writable = true,
            metadata = true,
            shell = false,
            target = WorkspacePickerTarget(agentId),
        )
        assertTrue("Workspace attach failed: $attached", attached is WorkspaceAccessResult.Success)
        val wsId = (attached as WorkspaceAccessResult.Success).workspace.workspaceId
        val workspace = requireNotNull(WorkspaceRepository(db).get(wsId))
        val grants = CapabilityGrantRepository(db).forAgent(agentId, includeRevoked = false)
        val snapshot = coordinator.sealNewSnapshot(
            authority = authority,
            snapshotId = snapshotId,
            agentId = agentId,
            grantIds = grants.mapTo(mutableSetOf()) { it.grantId },
        ) {
            AgentRepository(db).createSnapshot(agentId, snapshotId)
        }
        val now = Utc.nowIso()
        db.execute(
            "INSERT INTO conversations(id,snapshot_id,agent_snapshot_id,title,created_at,updated_at) VALUES(?,?,?,?,?,?)",
            listOf(conversationId, snapshot.id, snapshot.id, "Next Acceptance", now, now),
        )
        val runs = RunRepository(db)
        RunCoordinator(runs, coordinator::admitNewRun).prepare(
            RunRecord(runId, snapshot.id, conversationId, createdAt = now),
            "owner",
        )
        db.execute("UPDATE runs SET state=? WHERE run_id=?", listOf(RunStatus.WAITING_TOOL_APPROVAL.name, runId))
        val execContext = ToolExecutionContext(
            agentId,
            snapshot.id,
            "model-next",
            conversationId,
            runId,
            "cfg-next",
            policyVersion,
            canonicalGrants = grants,
            snapshotGrantBindings = CapabilityGrantRepository(db).listSnapshotBindings(snapshot.id),
        )
        val executor = requireNotNull(RootToolExecutorAdmission.create(db, coordinator, execContext))
        return ToolHarness(workspace.id, runId, runs, executor)
    }

    private fun recordWaitingInvocation(
        db: SqlConnection,
        runs: RunRepository,
        runId: String,
        invocationId: String,
        toolName: String,
        argumentsJson: String,
    ) {
        db.execute("UPDATE runs SET state=? WHERE run_id=?", listOf(RunStatus.WAITING_TOOL_APPROVAL.name, runId))
        runs.addInvocation(
            ToolInvocation(
                invocationId = invocationId,
                runId = runId,
                callId = invocationId,
                name = toolName,
                argumentsJson = argumentsJson,
                state = "WAITING_APPROVAL",
                permissionDecision = "PENDING",
                createdAt = Utc.nowIso(),
            ),
        )
    }

    private fun waitForShizukuBinder(timeoutMs: Long): RootShizukuLiveFacts? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val facts = RootShizukuLiveSampler.sample(1_000L)
            if (facts != null && facts.binderAlive) return facts
            Thread.sleep(200L)
        }
        return null
    }

    private fun waitForShizukuGrant(expectedGranted: Boolean, timeoutMs: Long): RootShizukuLiveFacts? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val facts = RootShizukuLiveSampler.sample(1_000L)
            if (facts != null && facts.binderAlive && facts.permissionGranted == expectedGranted) {
                return facts
            }
            Thread.sleep(200L)
        }
        return null
    }
}
