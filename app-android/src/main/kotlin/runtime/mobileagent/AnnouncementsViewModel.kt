// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import runtime.mobileagent.announcements.AnnouncementPresentation
import runtime.mobileagent.announcements.CachedAnnouncement
import runtime.mobileagent.announcements.ClientContext
import runtime.mobileagent.announcements.DisplayMode
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

class AnnouncementsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MobileAgentApp
    private val repo get() = app.container.announcements
    val records = mutableStateOf<List<CachedAnnouncement>>(emptyList())
    val status = mutableStateOf("")
    val filter = mutableStateOf(Filter.UNREAD)
    val selected = mutableStateOf<CachedAnnouncement?>(null)
    val baseUrl = mutableStateOf("")
    val publicKeyHex = mutableStateOf("")
    val statsEnabled = mutableStateOf(false)

    enum class Filter { UNREAD, ALL, HISTORY }

    init {
        reload()
        refresh(force = false)
    }

    fun client(): ClientContext = ClientContext(
        platform = "android",
        channel = "stable",
        versionCode = BuildConfig.VERSION_CODE,
        locale = Locale.getDefault().toLanguageTag(),
        installId = repo.installId(),
    )

    fun reload() {
        records.value = repo.records()
        baseUrl.value = repo.baseUrl()
        publicKeyHex.value = repo.publicKeyHex()
        statsEnabled.value = repo.statsEnabled()
    }

    fun visible(): List<CachedAnnouncement> {
        val all = records.value
        return when (filter.value) {
            Filter.UNREAD -> AnnouncementPresentation.visibleInCenter(all, unreadOnly = true)
            Filter.ALL -> AnnouncementPresentation.visibleInCenter(all, unreadOnly = false)
            Filter.HISTORY -> AnnouncementPresentation.history(all)
        }
    }

    fun banner(): CachedAnnouncement? = AnnouncementPresentation.banner(records.value)

    fun modal(): CachedAnnouncement? = AnnouncementPresentation.modal(records.value)

    fun saveEndpoint(url: String, keyHex: String) {
        repo.setBaseUrl(url)
        repo.setPublicKeyHex(keyHex)
        reload()
    }

    fun setStats(enabled: Boolean) {
        repo.setStatsEnabled(enabled)
        statsEnabled.value = enabled
    }

    fun markRead(item: CachedAnnouncement) {
        repo.markRead(item.item.id, item.item.revision)
        reload()
    }

    fun markAllRead() {
        repo.markAllRead(records.value)
        reload()
    }

    fun dismiss(item: CachedAnnouncement) {
        repo.markDismissed(item.item.id, item.item.revision)
        reload()
    }

    fun acknowledge(item: CachedAnnouncement) {
        repo.markAcknowledged(item.item.id, item.item.revision)
        repo.markDisplayed(item.item.id, item.item.revision)
        emit("announcement_acknowledged", item)
        reload()
    }

    fun open(item: CachedAnnouncement) {
        repo.markDisplayed(item.item.id, item.item.revision)
        if (item.item.displayMode != DisplayMode.MODAL) {
            repo.markRead(item.item.id, item.item.revision)
        }
        selected.value = item
        emit("announcement_opened", item)
        reload()
    }

    fun refresh(force: Boolean) {
        viewModelScope.launch {
            val url = repo.baseUrl()
            if (url.isBlank() || repo.publicKeys().isEmpty()) {
                status.value = "Configure the local announcement URL and public key to fetch a signed feed."
                return@launch
            }
            if (!force && recentlyChecked()) {
                status.value = "Using cached announcements. Automatic checks wait 6 hours."
                return@launch
            }
            status.value = "Checking announcements..."
            val outcome = withContext(Dispatchers.IO) {
                repo.markAttempt()
                runCatching { app.container.announcementFetcher.fetch(url, client(), repo.etag()) }
                    .getOrElse { FetchOutcome.Failed(it.message ?: "fetch failed") }
            }
            when (outcome) {
                is FetchOutcome.NotModified -> status.value = "Announcements are up to date."
                is FetchOutcome.Failed -> status.value = "Fetch failed. Chat and knowledge stay available. ${outcome.message}"
                is FetchOutcome.Body -> {
                    val reason = withContext(Dispatchers.IO) {
                        repo.applyEnvelope(outcome.envelopeJson, outcome.etag, client())
                    }
                    status.value = if (reason == null) {
                        "Signed announcements updated."
                    } else {
                        "Signature rejected ($reason). Previous cache kept."
                    }
                }
            }
            reload()
        }
    }

    private fun emit(type: String, item: CachedAnnouncement) {
        if (!repo.statsEnabled()) return
        val url = repo.baseUrl()
        if (url.isBlank()) return
        val client = client()
        viewModelScope.launch(Dispatchers.IO) {
            val body =
                """{"events":[{"eventId":"${java.util.UUID.randomUUID()}","type":"$type","installId":"${client.installId}","platform":"${client.platform}","channel":"${client.channel}","versionCode":${client.versionCode},"locale":"${client.locale}","announcementId":"${item.item.id}","revision":${item.item.revision},"occurredAt":"${Instant.now()}"}]}"""
            runCatching { app.container.announcementFetcher.postEvents(url, true, body) }
        }
    }

    private fun recentlyChecked(): Boolean {
        val last = repo.lastAttemptAt() ?: return false
        val instant = runCatching { Instant.parse(last) }.getOrNull() ?: return false
        return Instant.now().toEpochMilli() - instant.toEpochMilli() < TimeUnit.HOURS.toMillis(6)
    }
}
