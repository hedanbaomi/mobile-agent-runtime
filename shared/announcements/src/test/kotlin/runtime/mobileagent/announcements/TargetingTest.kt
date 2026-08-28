// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.announcements

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TargetingTest {
    private val client = ClientContext(
        platform = "android",
        channel = "stable",
        versionCode = 10,
        locale = "en",
        installId = "00000000-0000-4000-8000-000000000002",
    )

    @Test
    fun versionChannelAndLocaleAudienceDoNotUseTranslationFallback() {
        val base = Target(platform = "android", channel = "stable", minVersionCode = 10, maxVersionCode = 10)
        assertTrue(Targeting.matches(base, client, "security-demo"))
        assertFalse(Targeting.matches(base.copy(minVersionCode = 11), client, "security-demo"))
        assertFalse(Targeting.matches(base.copy(channel = "beta"), client, "security-demo"))
        assertFalse(Targeting.matches(base.copy(locales = setOf("zh-CN")), client, "security-demo"))
        assertTrue(Targeting.matches(base.copy(locales = setOf("en")), client, "security-demo"))
        assertEquals(
            listOf("zh-Hans-CN", "zh-CN", "zh-Hans", "zh", "default"),
            Targeting.localeFallback("zh-Hans-CN"),
        )
    }
}
