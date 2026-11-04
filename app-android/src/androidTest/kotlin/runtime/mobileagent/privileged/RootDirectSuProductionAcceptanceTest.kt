// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.MobileAgentApp
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.tooling.AuthorityManager
import runtime.mobileagent.tooling.InMemoryAuthorityStateStore

/**
 * N4 production acceptance: the app process requests Root through the real `su` manager.
 *
 * `AndroidRootSessionConnector(context)` is constructed **without** `directTransportFactory`, so
 * the only path is `SuRootDaemonTransport.startSuProcess` → the manager's `su` → UID0 service.
 * The manager decision is external (the emulator harness sets the Magisk policy before each run):
 *
 * - `granted`     → acquisition succeeds with a UID0 identity;
 * - `denied` / `revoked` → a **new** acquisition fails closed;
 * - `pending`     → an unanswered prompt times out with a real `handshake_timeout` reason;
 * - `cancel`      → an in-flight acquisition can be cancelled and returns bounded;
 * - `live-revoke` → observes whether an **already connected** session is affected after the
 *   harness revokes the manager policy (records the product's real behaviour).
 */
@RunWith(AndroidJUnit4::class)
class RootDirectSuProductionAcceptanceTest {
    private fun connector(context: Context) = AndroidRootSessionConnector(context)

    private fun acquire(connector: AndroidRootSessionConnector): RootAcquiredSession? =
        connector.tryAcquireSession(
            authority = Authority.ROOT_DIRECT,
            identityEpoch = 1L,
            appInstanceId = "n4-production-${UUID.randomUUID()}",
            shizukuState = null,
        )

    @Test
    fun productionConnectorFollowsTheRealSuManagerDecision() {
        val mode = InstrumentationRegistry.getArguments().getString("expectSuManager") ?: "granted"
        val runToken = InstrumentationRegistry.getArguments().getString("runToken") ?: ""
        val context = ApplicationProvider.getApplicationContext<Context>()
        (context as MobileAgentApp).ensureHostInitialized()
        val connector = connector(context)
        try {
            // Unique per-run token so the harness can correlate logcat lines with this exact run.
            println("N4 run-token=$runToken mode=$mode")
            val started = System.nanoTime()
            when (mode) {
                "cancel" -> runCancelCase(connector, started)
                "live-revoke" -> runLiveRevokeCase(connector, started)
                "live-revoke-coordinator" -> runCoordinatorRevokeCase(context, started, runToken)
                else -> runDecisionCase(connector, mode, started)
            }
        } finally {
            connector.close()
        }
    }

    private fun runDecisionCase(
        connector: AndroidRootSessionConnector,
        mode: String,
        started: Long,
    ) {
        val acquired = acquire(connector)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        when (mode) {
            "granted" -> {
                assertNotNull(
                    "production su acquisition failed: ${connector.lastAcquireFailureReason}",
                    acquired,
                )
                assertEquals(0, acquired!!.identity.serviceUid)
                assertEquals(PrivilegeTier.ROOT, acquired.identity.privilegeTier)
                assertEquals(Authority.ROOT_DIRECT, acquired.identity.authority)
            }
            "denied", "revoked" -> assertNull(
                "a denied/revoked manager decision must fail closed: ${connector.lastAcquireFailureReason}",
                acquired,
            )
            "pending" -> {
                assertNull("an unanswered prompt must not acquire", acquired)
                val reason = connector.lastAcquireFailureReason.orEmpty()
                assertTrue(
                    "pending must report a real timeout reason, got '$reason'",
                    reason.startsWith("handshake_timeout:"),
                )
                // The transport budget is 5s; allow process start + instrumentation overhead.
                assertTrue(
                    "timeout must respect the transport budget (5s), took ${elapsedMs}ms (reason=$reason)",
                    elapsedMs in 4_000L..30_000L,
                )
            }
            else -> error("unknown expectSuManager mode: $mode")
        }
        assertTrue("acquisition must stay bounded, took ${elapsedMs}ms", elapsedMs < 120_000L)
    }

    private fun runCancelCase(connector: AndroidRootSessionConnector, started: Long) {
        val result = AtomicReference<RootAcquiredSession?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                result.set(acquire(connector))
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }.apply { isDaemon = true }
        worker.start()
        Thread.sleep(4_000L)
        assertTrue(
            "a pending acquisition must be cancellable",
            connector.cancelPendingAcquisition(Authority.ROOT_DIRECT),
        )
        worker.join(5_000L)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertFalse("cancelled acquisition must return promptly", worker.isAlive)
        // A thread exception must never look like a clean "no session" result.
        assertNull("the cancel worker must not throw: ${failure.get()}", failure.get())
        assertNull(result.get())
        assertEquals("acquisition_cancelled", connector.lastAcquireFailureReason)
        assertNull("no transport may stay attached after cancellation", connector.activeTransport(Authority.ROOT_DIRECT))
        assertTrue("cancellation must stay bounded, took ${elapsedMs}ms", elapsedMs < 15_000L)
    }


    /**
     * Product-level revocation: a live coordinator session must be retired once the manager stops
     * authorizing the app. The harness flips the Magisk policy to deny while this test is waiting,
     * then the coordinator's authorization watchdog must detach. Idle work does not need recovery.
     */
    private fun runCoordinatorRevokeCase(context: Context, started: Long, runToken: String) {
        val app = context as MobileAgentApp
        app.ensureHostInitialized()
        val db = app.container.db
        val coordinator = RootRuntimeCoordinator(context, db)
        try {
            coordinator.setUserIntent(
                authority = Authority.ROOT_DIRECT,
                enabled = true,
                authorityManager = AuthorityManager(InMemoryAuthorityStateStore()),
                dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
                shizukuState = null,
            )
            assertTrue(
                "coordinator could not acquire a direct su session",
                coordinator.requestSessionFromForeground(Authority.ROOT_DIRECT, null),
            )
            assertTrue(coordinator.isAuthorityReadyForDispatch(Authority.ROOT_DIRECT))
            // Prove the *reason* is a bound GRANTED observation, not an incidental UNKNOWN.
            assertTrue(
                "the live session must be armed by a bound GRANTED observation, got " +
                    "${coordinator.lastManagerObservation}",
                coordinator.lastManagerObservation.orEmpty().startsWith("GRANTED:"),
            )
            println(
                "N4 run-token=$runToken coordinator-revoke armed observation=${coordinator.lastManagerObservation} " +
                    "after ${(System.nanoTime() - started) / 1_000_000L}ms",
            )

            // The harness revokes the manager policy while we wait.
            val deadline = System.currentTimeMillis() + 30_000L
            while (System.currentTimeMillis() < deadline &&
                coordinator.isAuthorityReadyForDispatch(Authority.ROOT_DIRECT)
            ) {
                Thread.sleep(200L)
            }
            assertFalse(
                "a live direct session must stop dispatching after manager revocation",
                coordinator.isAuthorityReadyForDispatch(Authority.ROOT_DIRECT),
            )
            assertFalse(
                "idle revocation must not create an unproven remote-work obligation",
                runtime.mobileagent.data.RootAuthorityRepository(db).get(Authority.ROOT_DIRECT).degraded,
            )
            assertNull(coordinator.currentExecutionStamp(Authority.ROOT_DIRECT))
            assertFalse("the revoked manager must reject a fresh foreground acquisition",
                coordinator.requestSessionFromForeground(Authority.ROOT_DIRECT, null))
            // The retirement must be caused by an explicit REVOKED observation of the live session.
            val revokedObservation = coordinator.lastManagerObservation.orEmpty()
            assertTrue(
                "expected a bound REVOKED observation, got '$revokedObservation'",
                revokedObservation.startsWith("REVOKED:"),
            )
            println("N4 run-token=$runToken coordinator-revoke retired observation=$revokedObservation")
            println("N4 coordinator-revoke retired after ${(System.nanoTime() - started) / 1_000_000L}ms")
        } finally {
            runCatching { coordinator.close() }
        }
    }

    /**
     * Establishes a live UID0 session, then (the harness revokes the manager policy afterwards)
     * checks whether the *existing* session is still served. This records the product's actual
     * behaviour: a Magisk policy revocation does not terminate an already-running root process,
     * so a live session surviving is a real gap unless the coordinator revalidates.
     */
    private fun runLiveRevokeCase(connector: AndroidRootSessionConnector, started: Long) {
        val acquired = acquire(connector)
        assertNotNull("live-revoke needs an initial grant: ${connector.lastAcquireFailureReason}", acquired)
        val transport = requireNotNull(acquired!!.transport) { "acquired session must carry a transport" }
        val liveIdentity = acquired.identity
        try {
            val beforeRevoke = transport.queryQuiescence(
                runtime.mobileagent.domain.RootQuiescenceQueryRequest(
                    targetAuthority = liveIdentity.authority,
                    targetServiceInstanceId = liveIdentity.serviceInstanceId,
                    targetSessionId = liveIdentity.sessionId,
                    targetIdentityEpoch = liveIdentity.identityEpoch,
                ),
            )
            assertNotNull("the live session must answer before the revocation", beforeRevoke)
            println(
                "N4 live-revoke before: accepted=${beforeRevoke!!.accepted} pid=${beforeRevoke.servicePid} " +
                    "elapsedMs=${(System.nanoTime() - started) / 1_000_000L}",
            )

            // The harness flips the manager policy to deny right now; wait for it to land.
            Thread.sleep(6_000L)
            val afterRevoke = runCatching {
                transport.queryQuiescence(
                    runtime.mobileagent.domain.RootQuiescenceQueryRequest(
                        targetAuthority = liveIdentity.authority,
                        targetServiceInstanceId = liveIdentity.serviceInstanceId,
                        targetSessionId = liveIdentity.sessionId,
                        targetIdentityEpoch = liveIdentity.identityEpoch,
                    ),
                )
            }.getOrNull()
            println(
                "N4 live-revoke after: answered=${afterRevoke != null} accepted=${afterRevoke?.accepted} " +
                    "pid=${afterRevoke?.servicePid} reason=${connector.lastAcquireFailureReason}",
            )
            // A new acquisition under the revoked policy must fail closed.
            val fresh = acquire(connector)
            assertNull("a new acquisition after revocation must fail closed", fresh)
            // Product expectation: the live session must not keep serving. Record, do not fake.
            assertNull(
                "the already connected UID0 session kept serving after manager revocation " +
                    "(transport still answers queries=$afterRevoke)",
                afterRevoke,
            )
        } finally {
            runCatching { transport.close() }
        }
    }
}
