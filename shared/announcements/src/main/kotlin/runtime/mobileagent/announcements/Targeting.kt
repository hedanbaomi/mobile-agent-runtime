// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.announcements

data class Target(
    val platform: String,
    val channel: String,
    val minVersionCode: Int? = null,
    val maxVersionCode: Int? = null,
    val locales: Set<String> = emptySet(),
    val rolloutPercent: Int = 100,
    val rolloutSalt: String = "default",
)

data class ClientContext(
    val platform: String,
    val channel: String,
    val versionCode: Int,
    val locale: String,
    val installId: String,
)

object Targeting {
    fun matches(target: Target, client: ClientContext, announcementId: String): Boolean {
        if (target.platform != "all" && target.platform != client.platform) return false
        if (target.channel != "all" && target.channel != client.channel) return false
        if (target.minVersionCode != null && client.versionCode < target.minVersionCode) return false
        if (target.maxVersionCode != null && client.versionCode > target.maxVersionCode) return false
        if (target.locales.isNotEmpty() && client.locale !in target.locales) return false
        return Rollout.hits(announcementId, target.rolloutSalt, client.installId, target.rolloutPercent)
    }

    fun localeFallback(requested: String): List<String> {
        val parts = requested.split('-')
        val chain = mutableListOf(requested)
        if (parts.size >= 3) chain += parts[0] + "-" + parts[2]
        if (parts.size >= 2) chain += parts[0] + "-" + parts[1]
        chain += parts[0]
        chain += "default"
        return chain.distinct()
    }
}
