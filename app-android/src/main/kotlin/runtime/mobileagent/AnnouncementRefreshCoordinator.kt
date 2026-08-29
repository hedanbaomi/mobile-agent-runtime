// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import runtime.mobileagent.announcements.ClientContext
import runtime.mobileagent.data.AnnouncementRepository
import runtime.mobileagent.data.AnnouncementTelemetryBatch
import java.time.Duration
import java.time.Instant

/** The small port used by the coordinator so foreground/single-flight behavior is JVM-testable. */
interface AnnouncementRefreshStore {
    fun client(): ClientContext
    fun baseUrl(): String
    fun hasSigningKey(): Boolean
    fun etag(client: ClientContext): String?
    fun shouldFetch(client: ClientContext, now: Instant, force: Boolean, foreground: Boolean, failureBackoff: Duration): Boolean
    fun markAttempt(client: ClientContext, now: Instant)
    fun applyEnvelope(envelopeJson: String, etag: String, client: ClientContext, now: Instant): String?
    fun recordFetchFailure(client: ClientContext, now: Instant, reason: String)
    fun recordFetchSuccess(client: ClientContext, now: Instant)
    fun hasCachedFeed(client: ClientContext, now: Instant): Boolean
    fun statsEnabled(): Boolean
    fun telemetryIdentity(): String?
    fun recordInstallSeen(client: ClientContext, now: Instant): Boolean
    fun recordAppActive(client: ClientContext, now: Instant): Boolean
    fun pendingTelemetryBatch(): AnnouncementTelemetryBatch?
    fun acknowledgeTelemetry(eventIds: Set<String>)
    fun setStatsEnabled(enabled: Boolean)
    fun setStatsChangeListener(listener: ((Boolean) -> Unit)?) {}
    fun recordAnnouncementEvent(
        type: String,
        client: ClientContext,
        announcementId: String? = null,
        revision: Int? = null,
        actionId: String? = null,
        now: Instant,
    ): Boolean
}

/** Production adapter around the SQLite repository and application-specific client context. */
class RepositoryAnnouncementRefreshStore(
    private val repository: AnnouncementRepository,
    private val clientProvider: () -> ClientContext,
) : AnnouncementRefreshStore {
    override fun client(): ClientContext = clientProvider()
    override fun baseUrl(): String = repository.baseUrl()
    override fun hasSigningKey(): Boolean = repository.publicKeys().isNotEmpty()
    override fun etag(client: ClientContext): String? = repository.etag(client)
    override fun shouldFetch(client: ClientContext, now: Instant, force: Boolean, foreground: Boolean, failureBackoff: Duration): Boolean =
        repository.shouldFetch(client, now, force, foreground, failureBackoff)
    override fun markAttempt(client: ClientContext, now: Instant) = repository.markAttempt(client, now)
    override fun applyEnvelope(envelopeJson: String, etag: String, client: ClientContext, now: Instant): String? =
        repository.applyEnvelope(envelopeJson, etag, client, now)
    override fun recordFetchFailure(client: ClientContext, now: Instant, reason: String) =
        repository.recordFetchFailure(client, now, reason)
    override fun recordFetchSuccess(client: ClientContext, now: Instant) = repository.recordFetchSuccess(client, now)
    override fun hasCachedFeed(client: ClientContext, now: Instant): Boolean = repository.hasCachedFeed(client, now)
    override fun statsEnabled(): Boolean = repository.statsEnabled()
    override fun telemetryIdentity(): String? = repository.telemetryIdentity()
    override fun recordInstallSeen(client: ClientContext, now: Instant): Boolean = repository.recordInstallSeen(client, now)
    override fun recordAppActive(client: ClientContext, now: Instant): Boolean = repository.recordAppActive(client, now)
    override fun pendingTelemetryBatch(): AnnouncementTelemetryBatch? = repository.pendingTelemetryBatch()
    override fun acknowledgeTelemetry(eventIds: Set<String>) = repository.acknowledgeTelemetry(eventIds)
    override fun setStatsEnabled(enabled: Boolean) = repository.setStatsEnabled(enabled)
    override fun setStatsChangeListener(listener: ((Boolean) -> Unit)?) = repository.setStatsChangeListener(listener)
    override fun recordAnnouncementEvent(
        type: String,
        client: ClientContext,
        announcementId: String?,
        revision: Int?,
        actionId: String?,
        now: Instant,
    ): Boolean = repository.recordAnnouncementEvent(type, client, announcementId, revision, actionId, now)
}

/** A transport port; the concrete Ktor client is kept separate from Provider credentials. */
interface AnnouncementFetchPort {
    suspend fun fetch(baseUrl: String, client: ClientContext, etag: String?): FetchOutcome
    suspend fun postEvents(baseUrl: String, consent: Boolean, eventsJson: String): Boolean
}

sealed class AnnouncementRefreshResult {
    data object Skipped : AnnouncementRefreshResult()
    data object ConfigurationUnavailable : AnnouncementRefreshResult()
    data object NotModified : AnnouncementRefreshResult()
    data object Updated : AnnouncementRefreshResult()
    data class Failed(val message: String) : AnnouncementRefreshResult()
    data class Rejected(val reason: String) : AnnouncementRefreshResult()
}

/**
 * Process-owned announcement refresh coordinator.
 *
 * An ordinary request is success-throttled, a foreground request is always fresh unless a recent
 * failure backoff applies, and an explicit force request bypasses both. All callers receive the
 * same in-flight Deferred. The process lifecycle, not a screen's ViewModel construction, drives
 * foreground refreshes.
 */
class AnnouncementRefreshCoordinator(
    private val store: AnnouncementRefreshStore,
    private val fetcher: AnnouncementFetchPort,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = { Instant.now() },
    private val failureBackoff: Duration = AnnouncementRepository.DEFAULT_FAILURE_BACKOFF,
) : DefaultLifecycleObserver {
    private val requestLock = Any()
    private val telemetryMutex = Mutex()
    private var inFlight: Deferred<AnnouncementRefreshResult>? = null
    private var telemetryJob: Job? = null
    private var lifecycle: Lifecycle? = null

    init {
        // Settings may update the repository directly. Registering here makes withdrawal cancel
        // telemetry even when the settings screen does not have a ViewModel reference to us.
        store.setStatsChangeListener { enabled ->
            if (!enabled) cancelTelemetry()
        }
    }

    /** Attach once to ProcessLifecycleOwner; adding while STARTED triggers an initial onStart. */
    fun start(ownerLifecycle: Lifecycle) {
        synchronized(requestLock) {
            if (lifecycle === ownerLifecycle) return
            lifecycle?.removeObserver(this)
            lifecycle = ownerLifecycle
            ownerLifecycle.addObserver(this)
        }
    }

    fun stop() {
        synchronized(requestLock) {
            lifecycle?.removeObserver(this)
            lifecycle = null
            cancelTelemetry()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        foreground()
    }

    /** Trigger an actual foreground cycle and enqueue opt-in activity telemetry. */
    fun foreground(): Deferred<AnnouncementRefreshResult> {
        val client = store.client()
        val now = clock()
        if (store.statsEnabled()) {
            store.recordInstallSeen(client, now)
            store.recordAppActive(client, now)
            scheduleTelemetryFlush()
        }
        return refresh(force = false, foreground = true)
    }

    /** Request a refresh. Existing callers join the same in-flight operation. */
    fun refresh(force: Boolean = false, foreground: Boolean = false): Deferred<AnnouncementRefreshResult> =
        synchronized(requestLock) {
            val existing = inFlight
            if (existing != null && !existing.isCompleted) return@synchronized existing
            // Start immediately: ProcessLifecycleOwner does not retain the returned Deferred, so a
            // lazy operation here would silently turn a real foreground callback into a no-op.
            val created = scope.async { perform(force, foreground) }
            inFlight = created
            created.invokeOnCompletion {
                synchronized(requestLock) {
                    if (inFlight === created) inFlight = null
                }
            }
            created
        }

    /** Explicitly change consent and cancel any pending telemetry upload. */
    fun setStatsEnabled(enabled: Boolean) {
        store.setStatsEnabled(enabled)
        if (!enabled) cancelTelemetry()
    }

    /** Queue a client event and arrange a best-effort upload without affecting feed availability. */
    fun recordEvent(
        type: String,
        client: ClientContext,
        announcementId: String? = null,
        revision: Int? = null,
        actionId: String? = null,
    ) {
        if (store.recordAnnouncementEvent(type, client, announcementId, revision, actionId, clock())) {
            scheduleTelemetryFlush()
        }
    }

    /** Exposed for deterministic JVM/instrumentation tests and explicit retry after connectivity. */
    fun flushTelemetry(): Job = scheduleTelemetryFlush() ?: completedJob()

    private fun scheduleTelemetryFlush(): Job? {
        if (!store.statsEnabled() || store.telemetryIdentity() == null || store.baseUrl().isBlank()) return null
        synchronized(requestLock) {
            // Re-check after taking the same lock used by the withdrawal callback. Without this
            // second check, disable could win between the initial check and launch a new upload.
            if (!store.statsEnabled() || store.telemetryIdentity() == null || store.baseUrl().isBlank()) {
                return null
            }
            val current = telemetryJob
            if (current?.isActive == true) return current
            val created = scope.launch {
                telemetryMutex.withLock { flushTelemetryInternal() }
            }
            telemetryJob = created
            created.invokeOnCompletion {
                synchronized(requestLock) {
                    if (telemetryJob === created) telemetryJob = null
                }
            }
            return created
        }
    }

    private fun cancelTelemetry() {
        synchronized(requestLock) {
            telemetryJob?.cancel()
            telemetryJob = null
        }
    }

    private suspend fun flushTelemetryInternal() {
        repeat(MAX_TELEMETRY_BATCHES_PER_FLUSH) {
            if (!store.statsEnabled()) return
            val identity = store.telemetryIdentity() ?: return
            val batch = store.pendingTelemetryBatch() ?: return
            val baseUrl = store.baseUrl()
            if (baseUrl.isBlank()) return
            val sent = try {
                fetcher.postEvents(baseUrl, true, batch.json)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            }
            if (!sent) return
            // Consent may have been withdrawn while the request was in flight. Never acknowledge
            // a batch under a newly generated identity or after withdrawal.
            if (!store.statsEnabled() || store.telemetryIdentity() != identity) return
            store.acknowledgeTelemetry(batch.eventIds)
        }
    }

    private suspend fun perform(force: Boolean, foreground: Boolean): AnnouncementRefreshResult {
        val client = store.client()
        val now = clock()
        val baseUrl = store.baseUrl()
        if (baseUrl.isBlank() || !store.hasSigningKey()) {
            scheduleTelemetryFlush()
            return AnnouncementRefreshResult.ConfigurationUnavailable
        }
        if (!store.shouldFetch(client, now, force, foreground, failureBackoff)) {
            scheduleTelemetryFlush()
            return AnnouncementRefreshResult.Skipped
        }
        store.markAttempt(client, now)
        val outcome = try {
            fetcher.fetch(baseUrl, client, store.etag(client))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            FetchOutcome.Failed("network unavailable")
        }
        val result = when (outcome) {
            is FetchOutcome.NotModified -> {
                if (store.hasCachedFeed(client, now)) {
                    store.recordFetchSuccess(client, now)
                    store.recordAnnouncementEvent("announcement_fetched", client, now = now)
                    AnnouncementRefreshResult.NotModified
                } else {
                    store.recordFetchFailure(client, now, "304 without cached feed")
                    AnnouncementRefreshResult.Failed("304 without cached feed")
                }
            }
            is FetchOutcome.Body -> {
                val reason = runCatching { store.applyEnvelope(outcome.envelopeJson, outcome.etag, client, now) }
                    .getOrElse { "invalid signed feed" }
                if (reason == null) {
                    store.recordFetchSuccess(client, now)
                    store.recordAnnouncementEvent("announcement_fetched", client, now = now)
                    AnnouncementRefreshResult.Updated
                } else {
                    store.recordFetchFailure(client, now, reason)
                    AnnouncementRefreshResult.Rejected(reason)
                }
            }
            is FetchOutcome.Failed -> {
                store.recordFetchFailure(client, now, outcome.message)
                AnnouncementRefreshResult.Failed(outcome.message)
            }
        }
        scheduleTelemetryFlush()
        return result
    }

    private fun completedJob(): Job = Job().also { it.complete() }

    companion object {
        private const val MAX_TELEMETRY_BATCHES_PER_FLUSH = 4
    }
}
