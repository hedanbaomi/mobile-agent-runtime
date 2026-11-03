// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RootAuthorityConsent
import runtime.mobileagent.domain.RootAuthorityRecord
import runtime.mobileagent.domain.RootHostControlGate

/**
 * Deterministic self-tests for the passive Shizuku watcher/fence (F11): unknown samples fail
 * closed under the same lock, slow/old observations never touch a replacement session, and a
 * closed coordinator ignores late observations. These do not replace the live-device acceptance.
 */
class RootShizukuWatcherFenceTest {
    private fun identity(sessionId: String = "shizuku-session-0001", epoch: Long = 7L) = PrivilegedSessionIdentity(
        authority = Authority.SHIZUKU_ROOT,
        privilegeTier = PrivilegeTier.ROOT,
        serviceUid = 0,
        serviceInstanceId = "shizuku-service-instance-0001",
        sessionId = sessionId,
        identityEpoch = epoch,
        appUid = 10148,
        androidUserId = 0,
        appInstanceId = "shizuku-app-instance-0001",
        packageName = "runtime.mobileagent",
        signingSha256 = "a".repeat(64),
        protocolVersion = PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION,
    )

    private fun record(epoch: Long = 7L) = RootAuthorityRecord(
        consent = RootAuthorityConsent(
            authority = Authority.SHIZUKU_ROOT,
            enabled = true,
            shellEnabled = false,
            revision = 3L,
            shellRevision = 1L,
            policyVersion = 1L,
        ),
        revision = 5L,
        identityEpoch = epoch,
    )

    private inner class Harness(
        var currentIdentity: PrivilegedSessionIdentity?,
        var currentRecord: RootAuthorityRecord?,
        var closed: Boolean = false,
    ) {
        val detaches = AtomicInteger()
        val invalidations = AtomicInteger()
        val degraded = AtomicInteger()
        val hostGate = RootHostControlGate(identity())

        val fence = RootShizukuPlatformDenialFence(
            lock = Any(),
            isClosed = { closed },
            record = { currentRecord!! },
            invalidateAcquisitions = { invalidations.incrementAndGet() },
            hostGate = { hostGate },
            markDegraded = { _, _ -> degraded.incrementAndGet() },
            detach = { detaches.incrementAndGet() },
        )

        var currentTransport: Any? = "transport-live"
        fun observe(
            captured: PrivilegedSessionIdentity?,
            revision: Long,
            epoch: Long,
            facts: RootShizukuLiveFacts?,
            capturedTransport: Any? = currentTransport,
        ): Boolean =
            fence.applyObserved(
                capturedTransport = capturedTransport,
                capturedIdentity = captured,
                recordRevision = revision,
                recordEpoch = epoch,
                facts = facts,
                closed = { closed },
                currentTransport = { currentTransport },
                currentIdentity = { currentIdentity },
                currentStamp = { currentRecord?.let { it.revision to it.identityEpoch } },
            )
    }

    private fun deniedFacts() = RootShizukuLiveFacts(
        binderAlive = true, preV11 = false, permissionGranted = false, serverUid = null, serverVersion = 13,
    )

    private fun allowedFacts() = RootShizukuLiveFacts(
        binderAlive = true, preV11 = false, permissionGranted = true, serverUid = 0, serverVersion = 13,
    )

    @Test
    fun explicitDenialFencesTheCapturedSession() {
        val h = Harness(identity(), record())
        assertTrue(h.observe(identity(), 5L, 7L, deniedFacts()))
        assertEquals(1, h.invalidations.get(), "the authority's acquisitions must be invalidated")
        assertEquals(1, h.degraded.get(), "the authority must be marked degraded")
        assertEquals(1, h.detaches.get(), "the session must be retired")
        assertTrue(h.hostGate.isRevoked(), "the host gate must be revoked before detach")
        assertTrue(h.fence.lastDenies())
    }

    @Test
    fun unknownObservationFailsClosedUnderTheSameFence() {
        val h = Harness(identity(), record())
        assertTrue(h.observe(identity(), 5L, 7L, null), "unknown/timeout must fence")
        assertEquals(1, h.invalidations.get())
        assertEquals(1, h.degraded.get())
        assertEquals(1, h.detaches.get())
        assertTrue(h.hostGate.isRevoked())
        assertTrue(h.fence.lastDenies(), "unknown must not authorize dispatch")
    }

    @Test
    fun allowedObservationKeepsTheSessionWithoutFencing() {
        val h = Harness(identity(), record())
        assertFalse(h.observe(identity(), 5L, 7L, allowedFacts()))
        assertEquals(0, h.invalidations.get())
        assertEquals(0, h.degraded.get())
        assertEquals(0, h.detaches.get())
        assertFalse(h.hostGate.isRevoked())
        assertFalse(h.fence.lastDenies())
    }

    /**
     * Direct `applyObserved` fencing check (not a latch-driven slow real watcher): an observation
     * captured for the previous session/epoch must not retire the replacement.
     */
    @Test
    fun slowOldObservationCannotRetireAReplacementSession() {
        val h = Harness(identity("shizuku-session-0002", 8L), record(epoch = 8L))
        assertFalse(h.observe(identity("shizuku-session-0001", 7L), 5L, 7L, deniedFacts()))
        assertEquals(0, h.invalidations.get())
        assertEquals(0, h.detaches.get())
        assertFalse(h.hostGate.isRevoked())
    }

    @Test
    fun slowObservationWithAReplacedTransportIsIgnored() {
        val h = Harness(identity(), record())
        assertFalse(h.observe(identity(), 5L, 7L, deniedFacts(), capturedTransport = "transport-old"))
        assertEquals(0, h.detaches.get())
        assertFalse(h.fence.lastDenies())
    }

    @Test
    fun closedCoordinatorIgnoresLateObservations() {
        val h = Harness(identity(), record(), closed = true)
        assertFalse(h.observe(identity(), 5L, 7L, deniedFacts()))
        assertEquals(0, h.detaches.get())
        assertFalse(h.fence.lastDenies())
    }

    @Test
    fun explicitStateUsesTheRealPolicyFields() {
        val h = Harness(identity(), record())
        val granted = runtime.mobileagent.shizuku.ShizukuAuthorityState(
            installedHint = true,
            binderAlive = true,
            permissionGranted = true,
            apiVersion = 13,
            serverUid = 0,
            userServiceAlive = false,
            ready = false,
            errorCode = "SERVER_UID_UNTRUSTED",
            userServiceUid = null,
        )
        assertFalse(h.fence.applyExplicitState(granted), "a UID0 server with ready=false is not a denial")
        val shellUid = granted.copy(serverUid = 2000)
        assertTrue(h.fence.applyExplicitState(shellUid), "a UID2000 switch is a denial")
        assertEquals(1, h.detaches.get())
    }

    @Test
    fun concurrentSamplerWaitsForLockThenSamplesFresh() {
        val readCalls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit(Callable {
                RootShizukuLiveSampler.sample(2_000L) {
                    readCalls.incrementAndGet()
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    allowedFacts()
                }
            })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val releaser = Thread({
                Thread.sleep(100L)
                release.countDown()
            }, "sampler-release").apply { isDaemon = true; start() }
            val second = RootShizukuLiveSampler.sample(2_000L) {
                readCalls.incrementAndGet()
                allowedFacts()
            }
            val firstResult = first.get(3, TimeUnit.SECONDS)
            assertTrue(firstResult?.permissionGranted == true)
            assertTrue(second?.permissionGranted == true, "the waiting caller must not be rejected")
            assertEquals(2, readCalls.get(), "the waiter must run its own fresh sample after the lock is free")
            releaser.join(1_000L)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun waitingSamplerTimesOutWithinItsOwnBudgetWithoutStartingAWorker() {
        val readCalls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit(Callable {
                RootShizukuLiveSampler.sample(3_000L) {
                    readCalls.incrementAndGet()
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    allowedFacts()
                }
            })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val started = System.nanoTime()
            val second = RootShizukuLiveSampler.sample(120L) {
                readCalls.incrementAndGet()
                allowedFacts()
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            assertNull(second)
            assertEquals(1, readCalls.get(), "a waiting caller must not start another worker")
            assertTrue(elapsedMs < 1_000L, "the waiting budget must remain bounded")
            release.countDown()
            first.get(3, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
