// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.ui.AppRoutes
import runtime.mobileagent.ui.appBackTarget
import runtime.mobileagent.ui.defaultAppDestinations
import runtime.mobileagent.ui.isMoreChildRoute
import runtime.mobileagent.ui.isShellScopedLongRunningRoute
import runtime.mobileagent.ui.moreHubItems
import runtime.mobileagent.ui.phonePrimaryDestinations
import runtime.mobileagent.ui.requestInspectorAvailability
import runtime.mobileagent.ui.ShellNavigationAffordance
import runtime.mobileagent.ui.appShellTitle
import runtime.mobileagent.ui.shellNavigationAffordance
import runtime.mobileagent.feature.chat.ChatRequestInspectorAvailability
import runtime.mobileagent.feature.chat.ChatUiState

/** Regression checks for the route shape used by the route-scoped NavHost. */
@RunWith(AndroidJUnit4::class)
class NavigationScopeTest {
    @Test
    fun shellNavigationPolicyMakesMenuAndBackMutuallyExclusive() {
        listOf(
            AppRoutes.CHAT,
            AppRoutes.AGENTS,
            AppRoutes.PROVIDERS,
            AppRoutes.KNOWLEDGE,
            AppRoutes.SKILLS,
            AppRoutes.SETTINGS,
        ).forEach { route ->
            assertEquals(ShellNavigationAffordance.MENU, shellNavigationAffordance(route))
        }
        listOf(AppRoutes.ABOUT, AppRoutes.INSPECTOR).forEach { route ->
            assertEquals(ShellNavigationAffordance.BACK, shellNavigationAffordance(route))
        }
        assertEquals(ShellNavigationAffordance.BACK, shellNavigationAffordance(AppRoutes.MCP))
        assertEquals(
            ShellNavigationAffordance.BACK,
            shellNavigationAffordance(AppRoutes.AGENTS, childDetailOpen = true),
        )
        assertEquals(
            ShellNavigationAffordance.BACK,
            shellNavigationAffordance(AppRoutes.MCP, childDetailOpen = true),
        )
    }

    @Test
    fun shellTitlePolicyCoversTopLevelChildrenAndWorkspaceDetail() {
        assertEquals("对话", appShellTitle(AppRoutes.CHAT, chinese = true))
        assertEquals("Providers", appShellTitle(AppRoutes.PROVIDERS, chinese = false))
        assertEquals("关于", appShellTitle(AppRoutes.ABOUT, chinese = true))
        assertEquals("Request inspector", appShellTitle(AppRoutes.INSPECTOR, chinese = false))
        assertEquals(
            "选择工作区",
            appShellTitle(AppRoutes.CHAT, chinese = true, childDetailOpen = true),
        )
    }

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

    @Test
    fun phoneMoreChildrenUseHistoryAndRestoredRootsFallBackToChat() {
        listOf(AppRoutes.PROVIDERS, AppRoutes.NEWS, AppRoutes.MCP, AppRoutes.SETTINGS, AppRoutes.ABOUT, AppRoutes.INSPECTOR)
            .forEach { route ->
                assertTrue(isMoreChildRoute(route))
                if (route == AppRoutes.INSPECTOR) {
                    assertEquals(AppRoutes.MORE, appBackTarget(compact = true, currentRoute = route, hasPreviousEntry = true))
                    assertEquals(AppRoutes.MORE, appBackTarget(compact = true, currentRoute = route, hasPreviousEntry = false))
                } else {
                    assertNull(appBackTarget(compact = true, currentRoute = route, hasPreviousEntry = true))
                    assertEquals(AppRoutes.CHAT, appBackTarget(compact = true, currentRoute = route, hasPreviousEntry = false))
                }
            }
    }

    @Test
    fun inspectorBackPreservesItsOpeningSourceAndRootFallbackIsChat() {
        assertEquals(AppRoutes.CHAT, appBackTarget(true, AppRoutes.INSPECTOR, AppRoutes.CHAT, hasPreviousEntry = false))
        assertEquals(AppRoutes.MORE, appBackTarget(true, AppRoutes.INSPECTOR, AppRoutes.MORE, hasPreviousEntry = false))
        assertEquals(AppRoutes.CHAT, appBackTarget(false, AppRoutes.PROVIDERS, hasPreviousEntry = false))
        assertNull(appBackTarget(true, AppRoutes.CHAT, hasPreviousEntry = false))
    }

    @Test
    fun knowledgeImportAndChatRunStayOwnedByTheShell() {
        assertTrue(isShellScopedLongRunningRoute(AppRoutes.CHAT))
        assertTrue(isShellScopedLongRunningRoute(AppRoutes.KNOWLEDGE))
        assertTrue(!isShellScopedLongRunningRoute(AppRoutes.AGENTS))
    }

    @Test
    fun inspectorRoutePreservesDisabledNotPreparedContextLostAndReadyStates() {
        assertEquals(
            ChatRequestInspectorAvailability.DISABLED,
            requestInspectorAvailability(ChatUiState(), inspectorEnabled = false),
        )
        assertEquals(
            ChatRequestInspectorAvailability.DISABLED,
            requestInspectorAvailability(
                ChatUiState(requestInspectorAvailability = ChatRequestInspectorAvailability.READY),
                inspectorEnabled = false,
            ),
        )
        assertEquals(
            ChatRequestInspectorAvailability.NOT_PREPARED,
            requestInspectorAvailability(ChatUiState(), inspectorEnabled = true),
        )
        assertEquals(
            ChatRequestInspectorAvailability.CONTEXT_LOST,
            requestInspectorAvailability(
                ChatUiState(requestInspectorAvailability = ChatRequestInspectorAvailability.CONTEXT_LOST),
                inspectorEnabled = true,
            ),
        )
        assertEquals(
            ChatRequestInspectorAvailability.READY,
            requestInspectorAvailability(
                ChatUiState(requestInspectorAvailability = ChatRequestInspectorAvailability.READY),
                inspectorEnabled = true,
            ),
        )
    }
}
