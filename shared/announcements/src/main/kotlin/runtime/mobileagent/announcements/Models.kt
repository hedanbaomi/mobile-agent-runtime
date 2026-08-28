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
data class AnnouncementTargetDto(
    val platform: String = "all",
    val channel: String = "all",
    val minVersionCode: Int? = null,
    val maxVersionCode: Int? = null,
    val locales: List<String> = emptyList(),
    val rolloutPercent: Int = 100,
    val rolloutSalt: String = "default",
) {
    fun toTarget(): Target = Target(
        platform = platform,
        channel = channel,
        minVersionCode = minVersionCode,
        maxVersionCode = maxVersionCode,
        locales = locales.toSet(),
        rolloutPercent = rolloutPercent,
        rolloutSalt = rolloutSalt,
    )
}

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
    val target: AnnouncementTargetDto = AnnouncementTargetDto(),
    val image: String? = null,
)

@Serializable
data class SignedEnvelope(
    val schemaVersion: Int,
    val keyId: String,
    val payloadBase64: String,
    val signatureBase64: String,
)

@Serializable
data class RequestTarget(
    val platform: String,
    val channel: String,
    val versionCode: Int,
    val locale: String,
)

@Serializable
data class WithdrawnRef(
    val id: String,
    val revision: Int,
)

@Serializable
data class FeedPayload(
    val feedVersion: Long,
    val issuedAt: String,
    val expiresAt: String,
    val requestTarget: RequestTarget,
    val audienceHash: String,
    val complete: Boolean,
    val items: List<AnnouncementItem> = emptyList(),
    val withdrawn: List<WithdrawnRef> = emptyList(),
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
            "OPEN_HTTPS_URL" -> action.url?.startsWith("https://") == true &&
                action.url.none { it.isISOControl() }
            "OPEN_APP_ROUTE" -> action.url in allowedRoutes
            else -> true
        }
    }
}

object AnnouncementContentGuard {
    private val html = Regex("<[a-zA-Z/!]")
    private val blockedScheme = Regex("javascript:|intent:|file:", RegexOption.IGNORE_CASE)

    fun allowedMarkdown(text: String): Boolean =
        text.length <= 32 * 1024 && !html.containsMatchIn(text) && !blockedScheme.containsMatchIn(text)

    fun allowedImage(url: String?): Boolean = url == null || url.startsWith("https://")
}

object FeedLimits {
    const val MAX_ENVELOPE_CHARS = 1_400_000
    const val MAX_PAYLOAD_BYTES = 1024 * 1024
    const val MAX_ITEMS = 100
    const val MAX_ACTIONS = 4
}
