// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import runtime.mobileagent.announcements.AnnouncementPresentation
import runtime.mobileagent.announcements.AnnouncementAction
import runtime.mobileagent.announcements.AnnouncementActions
import runtime.mobileagent.announcements.CachedAnnouncement
import runtime.mobileagent.announcements.ClientContext
import runtime.mobileagent.announcements.DisplayMode
import java.util.Locale

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
        records.value = repo.records(client = client())
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
        app.container.announcementRefreshCoordinator.flushTelemetry()
        reload()
    }

    fun setStats(enabled: Boolean) {
        app.container.announcementRefreshCoordinator.setStatsEnabled(enabled)
        reload()
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
        if (repo.markDisplayed(item.item.id, item.item.revision)) {
            emit("announcement_displayed", item)
        }
        emit("announcement_acknowledged", item)
        reload()
    }

    fun open(item: CachedAnnouncement) {
        val displayed = repo.markDisplayed(item.item.id, item.item.revision)
        if (item.item.displayMode != DisplayMode.MODAL) {
            repo.markRead(item.item.id, item.item.revision)
        }
        selected.value = item
        if (displayed) emit("announcement_displayed", item)
        emit("announcement_opened", item)
        reload()
    }

    /** Record a validated action click without ever sending the action URL or body text. */
    fun actionClicked(item: CachedAnnouncement, action: AnnouncementAction) {
        if (!AnnouncementActions.allowed(action)) return
        app.container.announcementRefreshCoordinator.recordEvent(
            type = "action_clicked",
            client = client(),
            announcementId = item.item.id,
            revision = item.item.revision,
            actionId = action.key,
        )
    }

    fun refresh(force: Boolean) {
        viewModelScope.launch {
            status.value = "Checking announcements..."
            val result = try {
                app.container.announcementRefreshCoordinator.refresh(force = force).await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A coordinator/storage fault must not make the chat shell unavailable.
                AnnouncementRefreshResult.Failed("announcement refresh unavailable")
            }
            status.value = when (result) {
                AnnouncementRefreshResult.ConfigurationUnavailable ->
                    "Configure the local announcement URL and public key to fetch a signed feed."
                AnnouncementRefreshResult.Skipped ->
                    "Using cached announcements. Automatic checks wait 6 hours."
                AnnouncementRefreshResult.NotModified -> "Announcements are up to date."
                AnnouncementRefreshResult.Updated -> "Signed announcements updated."
                is AnnouncementRefreshResult.Failed ->
                    "Fetch failed. Chat and knowledge stay available. ${result.message}"
                is AnnouncementRefreshResult.Rejected ->
                    "Signature rejected (${result.reason}). Previous cache kept."
            }
            reload()
        }
    }

    private fun emit(type: String, item: CachedAnnouncement) {
        app.container.announcementRefreshCoordinator.recordEvent(
            type = type,
            client = client(),
            announcementId = item.item.id,
            revision = item.item.revision,
        )
    }
}
