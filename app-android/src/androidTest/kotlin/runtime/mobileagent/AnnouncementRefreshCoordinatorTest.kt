// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.announcements.ClientContext
import runtime.mobileagent.data.AnnouncementTelemetryBatch
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AnnouncementRefreshCoordinatorTest {
    @Test
    fun callersShareOneInFlightAndForegroundStartsFreshAfterItCompletes() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val store = FakeStore()
        val fetcher = object : AnnouncementFetchPort {
            override suspend fun fetch(baseUrl: String, client: ClientContext, etag: String?): FetchOutcome {
                calls.incrementAndGet()
                release.await()
                return FetchOutcome.Failed("offline")
            }

            override suspend fun postEvents(baseUrl: String, consent: Boolean, eventsJson: String): Boolean = true
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = AnnouncementRefreshCoordinator(store, fetcher, scope, clock = { NOW })
            val first = coordinator.refresh()
            withTimeout(5_000) { while (calls.get() == 0) yield() }
            assertSame(first, coordinator.refresh(force = true, foreground = true))

            release.complete(Unit)
            first.await()
            coordinator.foreground().await()

            assertEquals(2, calls.get())
            assertTrue(store.requests.any { !it.first && !it.second })
            assertTrue(store.requests.any { !it.first && it.second })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun disablingStatsCancelsUploadAndDoesNotAcknowledgeInFlightBatch() = runBlocking {
        val uploadStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = FakeStore(stats = true)
        val fetcher = object : AnnouncementFetchPort {
            override suspend fun fetch(baseUrl: String, client: ClientContext, etag: String?): FetchOutcome =
                FetchOutcome.Failed("offline")

            override suspend fun postEvents(baseUrl: String, consent: Boolean, eventsJson: String): Boolean {
                uploadStarted.complete(Unit)
                release.await()
                return true
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = AnnouncementRefreshCoordinator(store, fetcher, scope, clock = { NOW })
            val upload = coordinator.flushTelemetry()
            uploadStarted.await()
            coordinator.setStatsEnabled(false)
            release.complete(Unit)
            upload.join()

            assertEquals(false, store.stats)
            assertEquals(0, store.acknowledged)
        } finally {
            scope.cancel()
        }
    }

    private class FakeStore(stats: Boolean = false) : AnnouncementRefreshStore {
        var stats = stats
        var acknowledged = 0
        val requests = mutableListOf<Pair<Boolean, Boolean>>()
        private var statsListener: ((Boolean) -> Unit)? = null
        private val batch = AnnouncementTelemetryBatch("{\"events\":[]}", setOf("event-1"))

        override fun client() = CLIENT
        override fun baseUrl() = "https://announcements.invalid"
        override fun hasSigningKey() = true
        override fun etag(client: ClientContext): String? = null
        override fun shouldFetch(client: ClientContext, now: Instant, force: Boolean, foreground: Boolean, failureBackoff: Duration): Boolean {
            requests += force to foreground
            return true
        }
        override fun markAttempt(client: ClientContext, now: Instant) = Unit
        override fun applyEnvelope(envelopeJson: String, etag: String, client: ClientContext, now: Instant): String? = null
        override fun recordFetchFailure(client: ClientContext, now: Instant, reason: String) = Unit
        override fun recordFetchSuccess(client: ClientContext, now: Instant) = Unit
        override fun hasCachedFeed(client: ClientContext, now: Instant) = true
        override fun statsEnabled() = stats
        override fun telemetryIdentity(): String? = if (stats) "11111111-1111-4111-8111-111111111111" else null
        override fun recordInstallSeen(client: ClientContext, now: Instant) = false
        override fun recordAppActive(client: ClientContext, now: Instant) = false
        override fun pendingTelemetryBatch() = if (stats) batch else null
        override fun acknowledgeTelemetry(eventIds: Set<String>) { acknowledged += eventIds.size }
        override fun setStatsEnabled(enabled: Boolean) {
            stats = enabled
            statsListener?.invoke(enabled)
        }
        override fun setStatsChangeListener(listener: ((Boolean) -> Unit)?) { statsListener = listener }
        override fun recordAnnouncementEvent(
            type: String,
            client: ClientContext,
            announcementId: String?,
            revision: Int?,
            actionId: String?,
            now: Instant,
        ) = false
    }

    private companion object {
        val NOW = Instant.parse("2026-08-29T00:00:00Z")
        val CLIENT = ClientContext("android", "stable", 1, "en", "00000000-0000-4000-8000-000000000002")
    }
}
