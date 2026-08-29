// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.ui.AppRoutes
import runtime.mobileagent.ui.defaultAppDestinations
import runtime.mobileagent.ui.moreHubItems
import runtime.mobileagent.ui.phonePrimaryDestinations

/** Regression checks for the route shape used by the route-scoped NavHost. */
@RunWith(AndroidJUnit4::class)
class NavigationScopeTest {
    @Test
    fun phoneNavigationKeepsSecondaryPagesInMore() {
        assertEquals(
            listOf(AppRoutes.CHAT, AppRoutes.AGENTS, AppRoutes.KNOWLEDGE, AppRoutes.SKILLS, AppRoutes.MORE),
            phonePrimaryDestinations(false).map { it.route },
        )
        assertTrue(moreHubItems(false).map { it.route }.containsAll(
            listOf(AppRoutes.PROVIDERS, AppRoutes.NEWS, AppRoutes.MCP, AppRoutes.SETTINGS, AppRoutes.ABOUT, AppRoutes.INSPECTOR),
        ))
    }

    @Test
    fun wideNavigationKeepsSevenProductDestinations() {
        assertEquals(
            listOf(AppRoutes.CHAT, AppRoutes.AGENTS, AppRoutes.PROVIDERS, AppRoutes.KNOWLEDGE, AppRoutes.SKILLS, AppRoutes.NEWS, AppRoutes.SETTINGS),
            defaultAppDestinations(false).map { it.route },
        )
    }
}
