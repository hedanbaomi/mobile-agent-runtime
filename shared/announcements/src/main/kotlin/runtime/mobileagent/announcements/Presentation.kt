// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.announcements

data class AnnouncementLocalState(
    val readAt: String? = null,
    val displayedAt: String? = null,
    val dismissedAt: String? = null,
    val acknowledgedAt: String? = null,
)

data class CachedAnnouncement(
    val item: AnnouncementItem,
    val state: AnnouncementLocalState,
    val withdrawn: Boolean,
    val signatureExpired: Boolean,
)

object AnnouncementPresentation {
    fun visibleInCenter(records: List<CachedAnnouncement>, unreadOnly: Boolean): List<CachedAnnouncement> {
        return records
            .filter { !it.withdrawn }
            .filter { !unreadOnly || it.state.readAt == null }
            .sortedWith(compareBy({ severityRank(it.item.severity) }, { if (it.item.pinned) 0 else 1 }, { it.item.id }))
    }

    fun history(records: List<CachedAnnouncement>): List<CachedAnnouncement> =
        records.filter { it.withdrawn || it.state.readAt != null || it.signatureExpired }

    fun banner(records: List<CachedAnnouncement>): CachedAnnouncement? =
        records.firstOrNull {
            !it.withdrawn &&
                !it.signatureExpired &&
                it.item.displayMode == DisplayMode.BANNER &&
                it.state.dismissedAt == null
        }

    fun modal(records: List<CachedAnnouncement>): CachedAnnouncement? =
        records.firstOrNull {
            !it.withdrawn &&
                !it.signatureExpired &&
                it.item.displayMode == DisplayMode.MODAL &&
                it.state.acknowledgedAt == null
        }

    private fun severityRank(severity: Severity): Int = when (severity) {
        Severity.CRITICAL -> 0
        Severity.WARNING -> 1
        Severity.NOTICE -> 2
        Severity.INFO -> 3
    }
}
