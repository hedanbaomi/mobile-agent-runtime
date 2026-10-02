// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.announcements.ClientContext
import runtime.mobileagent.data.AnnouncementTelemetryBatch
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler

class AnnouncementRefreshThreadingTest {
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

    @Test
    fun foregroundAndFlushNeverReadStorageOnTheirCallingThread() = runBlocking {
        val caller = Thread.currentThread()
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "announcement-storage-test")
        }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val touched = CopyOnWriteArrayList<Thread>()
        val activeRecorded = CompletableDeferred<Unit>()
        val store = FakeStore(stats = true, probe = { touched += Thread.currentThread() },
            activeRecorded = { activeRecorded.complete(Unit) })
        val fetcher = object : AnnouncementFetchPort {
            override suspend fun fetch(baseUrl: String, client: ClientContext, etag: String?) =
                FetchOutcome.Failed("offline")
            override suspend fun postEvents(baseUrl: String, consent: Boolean, eventsJson: String) = false
        }
        try {
            val coordinator = AnnouncementRefreshCoordinator(store, fetcher, scope, clock = { NOW })
            coordinator.foreground().await()
            withTimeout(5_000) { activeRecorded.await() }
            coordinator.flushTelemetry().join()
            assertTrue(touched.isNotEmpty())
            assertTrue(touched.none { it === caller }, "a lifecycle caller must never wait on storage")
        } finally {
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun telemetryStorageFailureIsContainedWithoutAnUnhandledCoroutine() = runBlocking {
        val failures = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, error -> failures += error })
        val store = FakeStore(probe = { throw IllegalStateException("storage unavailable") })
        val fetcher = object : AnnouncementFetchPort {
            override suspend fun fetch(baseUrl: String, client: ClientContext, etag: String?) =
                FetchOutcome.Failed("offline")
            override suspend fun postEvents(baseUrl: String, consent: Boolean, eventsJson: String) = false
        }
        try {
            AnnouncementRefreshCoordinator(store, fetcher, scope).flushTelemetry().join()
            assertTrue(failures.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    private class FakeStore(
        stats: Boolean = false,
        private val probe: () -> Unit = {},
        private val activeRecorded: () -> Unit = {},
    ) : AnnouncementRefreshStore {
        @Volatile var stats = stats
        var acknowledged = 0
        val requests = CopyOnWriteArrayList<Pair<Boolean, Boolean>>()
        private var statsListener: ((Boolean) -> Unit)? = null
        private val batch = AnnouncementTelemetryBatch("{\"events\":[]}", setOf("event-1"))

        override fun client(): ClientContext { probe(); return CLIENT }
        override fun baseUrl(): String { probe(); return "https://announcements.invalid" }
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
        override fun statsEnabled(): Boolean { probe(); return stats }
        override fun telemetryIdentity(): String? { probe(); return if (stats) "11111111-1111-4111-8111-111111111111" else null }
        override fun recordInstallSeen(client: ClientContext, now: Instant): Boolean { probe(); return false }
        override fun recordAppActive(client: ClientContext, now: Instant): Boolean { probe(); activeRecorded(); return false }
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
