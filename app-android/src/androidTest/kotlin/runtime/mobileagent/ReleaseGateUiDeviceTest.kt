// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.announcements.ClientContext

/**
 * Release-gate smoke for the single-drawer information architecture. The
 * drawer is the only top-level navigation surface: every destination is
 * reachable directly, top-level pages show Menu (never Back), and only
 * feature-internal detail promotes the bar to Back. Nothing here depends on
 * the removed More hub or on timing luck.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseGateUiDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun drawerReachesEveryTopLevelDestinationDirectly() {
        waitForText("对话", "Chat")
        listOf(
            "knowledge" to ("知识" to "Knowledge"),
            "providers" to ("服务商" to "Providers"),
            "news" to ("公告" to "News"),
            "settings" to ("设置" to "Settings"),
            "mcp" to ("MCP" to "MCP"),
            "about" to ("关于" to "About"),
            "inspector" to ("请求检查器" to "Request inspector"),
        ).forEach { (route, labels) ->
            openDrawer()
            compose.onNodeWithTag("global.drawer")
                .performScrollToNode(hasTestTag("global.drawer.navigation.$route"))
            compose.onNodeWithTag("global.drawer.navigation.$route")
                .assertExists().assertHasClickAction().performClick()
            waitForText(labels.first, labels.second)
            assertMenuShownAndBackAbsent()
        }
    }

    @Test
    fun topLevelShowsMenuWhileAgentEditorShowsBack() {
        waitForText("对话", "Chat")
        assertMenuShownAndBackAbsent()

        openDrawer()
        compose.onNodeWithTag("global.drawer")
            .performScrollToNode(hasTestTag("global.drawer.navigation.agents"))
        compose.onNodeWithTag("global.drawer.navigation.agents").performClick()
        waitForText("智能体", "Agents")
        assertMenuShownAndBackAbsent()

        // The agent editor is an existing feature-internal detail seam: it
        // promotes the same top-level route to Back without duplicating Menu.
        compose.onNodeWithTag("agents.new").assertExists().assertHasClickAction().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("global.shell.navigation.back", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("global.shell.navigation.back")
            .assertExists().assertHasClickAction()
        compose.onAllNodesWithTag("global.shell.navigation.menu", useUnmergedTree = true)
            .fetchSemanticsNodes().isEmpty().let { assertTrue(it) }

        // Closing the detail returns to the Agents root with Menu restored.
        compose.onNodeWithTag("agents.editor.cancel")
            .assertExists().assertHasClickAction().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("global.shell.navigation.menu", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        waitForText("智能体", "Agents")
        assertMenuShownAndBackAbsent()

        // System back from a top-level page returns toward Chat and never
        // finishes the activity.
        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
            assertFalse(activity.isFinishing)
        }
        waitForText("对话", "Chat")
    }

    @Test
    fun systemBackFromTopLevelReturnsTowardChatWithoutFinishingActivity() {
        waitForText("对话", "Chat")
        openDrawer()
        compose.onNodeWithTag("global.drawer")
            .performScrollToNode(hasTestTag("global.drawer.navigation.providers"))
        compose.onNodeWithTag("global.drawer.navigation.providers").performClick()
        waitForText("服务商", "Providers")

        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
            assertFalse(activity.isFinishing)
        }
        waitForText("对话", "Chat")
    }

    @Test
    fun knowledgeImportEntrypointsAreReachable() {
        waitForText("对话", "Chat")
        openDrawer()
        compose.onNodeWithTag("global.drawer")
            .performScrollToNode(hasTestTag("global.drawer.navigation.knowledge"))
        compose.onNodeWithTag("global.drawer.navigation.knowledge").performClick()
        waitForText("知识", "Knowledge")
        localized("添加文件", "Add files").assertExists().assertHasClickAction()
        localized("导入文件夹", "Import folder").assertExists().assertHasClickAction()
        localized("导入 ZIP", "Import ZIP").assertExists().assertHasClickAction()
        assertMenuShownAndBackAbsent()
    }

    @Test
    fun announcementPageDoesNotLeakFeedConfiguration() {
        waitForText("对话", "Chat")
        openDrawer()
        compose.onNodeWithTag("global.drawer")
            .performScrollToNode(hasTestTag("global.drawer.navigation.news"))
        compose.onNodeWithTag("global.drawer.navigation.news").performClick()
        waitForText("公告", "News")
        assertTextAbsent("公告地址", "Feed URL")
        assertTextAbsent("公钥", "Public key")
        assertTextAbsent("保存公告设置", "Save feed settings")
    }

    @Test
    fun topBarLeadingActionNeverCoversItsTitle() {
        waitForText("对话", "Chat")
        val menuBounds = compose.onNodeWithTag("global.shell.navigation.menu")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val titleBounds = compose.onNodeWithTag("global.shell.title")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(
            "shell menu must not cover its title",
            menuBounds.right <= titleBounds.left,
        )
    }

    @Test
    fun announcementClientUsesPublicContextWithoutProviderCredentials() = runBlocking {
        var requestSeen: io.ktor.client.request.HttpRequestData? = null
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requestSeen = request
                    respond(
                        content = "{\"items\":[]}",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ETag, "fixture-etag"),
                    )
                }
            }
        }
        try {
            val outcome = AnnouncementFetcher(client).fetch(
                baseUrl = "https://announcements.invalid",
                client = ClientContext(
                    platform = "android",
                    channel = "stable",
                    versionCode = 1,
                    locale = "en-US",
                    installId = "fixture-install",
                ),
                etag = null,
            )
            assertTrue(outcome is FetchOutcome.Body)
            assertEquals("fixture-etag", (outcome as FetchOutcome.Body).etag)
            val request = requestSeen
            assertNotNull(request)
            assertEquals("fixture-install", request!!.headers["X-Install-ID"])
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["X-Api-Key"])
            assertNull(request.headers["api-key"])
            assertTrue(request.url.toString().contains("platform=android"))
            assertTrue(request.url.toString().contains("channel=stable"))
        } finally {
            client.close()
        }
    }

    private fun openDrawer() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("global.shell.navigation.menu", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("global.shell.navigation.menu").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("global.drawer.modal", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertMenuShownAndBackAbsent() {
        compose.onNodeWithTag("global.shell.navigation.menu")
            .assertExists().assertHasClickAction()
        assertTrue(
            compose.onAllNodesWithTag("global.shell.navigation.back", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun waitForText(chinese: String, english: String) {
        compose.waitUntil(15_000) { hasText(chinese, english) }
    }

    private fun localized(chinese: String, english: String): SemanticsNodeInteraction =
        if (compose.onAllNodesWithText(chinese).fetchSemanticsNodes().isNotEmpty()) {
            compose.onAllNodesWithText(chinese)[0]
        } else {
            compose.onAllNodesWithText(english)[0]
        }

    private fun hasText(chinese: String, english: String): Boolean {
        val chineseNodes = compose.onAllNodesWithText(chinese, useUnmergedTree = true).fetchSemanticsNodes()
        if (chineseNodes.isNotEmpty()) return true
        return compose.onAllNodesWithText(english, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun assertTextAbsent(chinese: String, english: String) {
        assertTrue(compose.onAllNodesWithText(chinese, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText(english, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }
}
