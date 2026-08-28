// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.announcements

import kotlinx.serialization.Serializable

@Serializable
enum class AnnouncementCategory { GENERAL, FEATURE, MAINTENANCE, SERVICE_INCIDENT, UPDATE, SECURITY, DEPRECATION }

@Serializable
enum class Severity { INFO, NOTICE, WARNING, CRITICAL }

@Serializable
enum class DisplayMode { CENTER_ONLY, BANNER, MODAL }

@Serializable
data class AnnouncementAction(
    val type: String,
    val key: String,
    val label: String,
    val url: String? = null,
)

@Serializable
data class AnnouncementItem(
    val id: String,
    val revision: Int,
    val category: AnnouncementCategory,
    val severity: Severity,
    val displayMode: DisplayMode,
    val title: String,
    val summary: String,
    val bodyMarkdown: String,
    val mustAcknowledge: Boolean,
    val dismissible: Boolean,
    val pinned: Boolean,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val publishedAt: String? = null,
    val locale: String = "default",
    val actions: List<AnnouncementAction> = emptyList(),
)

object AnnouncementActions {
    val allowedTypes = setOf("OPEN_HTTPS_URL", "OPEN_APP_ROUTE", "DISMISS", "ACKNOWLEDGE")
    val allowedRoutes = setOf(
        "app://settings/providers",
        "app://settings/knowledge",
        "app://announcements",
        "app://about",
        "app://update",
    )

    fun allowed(action: AnnouncementAction): Boolean {
        if (action.type !in allowedTypes) return false
        return when (action.type) {
            "OPEN_HTTPS_URL" -> action.url?.startsWith("https://") == true
            "OPEN_APP_ROUTE" -> action.url in allowedRoutes
            else -> true
        }
    }
}
