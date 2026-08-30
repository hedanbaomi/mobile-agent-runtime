// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
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
 * Small release-gate smoke for the phone shell. It intentionally stops at the
 * system-picker entry points so the test does not depend on DocumentsUI's
 * OEM-specific surface; the existing import device tests exercise the worker
 * and persistence paths with controlled fixtures.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseGateUiDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun navigationProviderAnnouncementAndImportEntrypoints_areReachable() {
        waitForText("对话", "Chat")

        clickText("知识", "Knowledge")
        waitForText("知识", "Knowledge")
        localized("添加文件", "Add files").assertExists().assertHasClickAction()
        localized("导入文件夹", "Import folder").assertExists().assertHasClickAction()
        localized("导入 ZIP", "Import ZIP").assertExists().assertHasClickAction()

        clickText("更多", "More")
        waitForText("更多", "More")
        clickText("服务商", "Providers")
        waitForText("服务商", "Providers")
        localized("返回", "Back").assertExists().assertHasClickAction().performClick()
        waitForText("更多", "More")

        clickText("公告", "News")
        waitForText("公告", "News")
        assertTextAbsent("公告地址", "Feed URL")
        assertTextAbsent("公钥", "Public key")
        assertTextAbsent("保存公告设置", "Save feed settings")
    }

    @Test
    fun allMoreDestinationsExposeAnInAppBackAffordance() {
        waitForText("对话", "Chat")
        listOf(
            "服务商" to "Providers",
            "公告" to "News",
            "MCP" to "MCP",
            "设置" to "Settings",
            "关于" to "About",
            "请求检查器" to "Request inspector",
        ).forEach { (chinese, english) ->
            clickText("更多", "More")
            waitForText("更多", "More")
            clickText(chinese, english)
            localized("返回", "Back").assertExists().assertHasClickAction().performClick()
            waitForText("更多", "More")
        }
    }

    @Test
    fun systemBackFromMoreChildReturnsToMoreWithoutFinishingActivity() {
        waitForText("对话", "Chat")
        clickText("更多", "More")
        clickText("服务商", "Providers")
        waitForText("服务商", "Providers")

        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
            assertFalse(activity.isFinishing)
        }
        waitForText("更多", "More")
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

    private fun waitForText(chinese: String, english: String) {
        compose.waitUntil(15_000) { hasText(chinese, english) }
    }

    private fun clickText(chinese: String, english: String) {
        compose.waitUntil(15_000) { hasText(chinese, english) }
        val candidates = if (compose.onAllNodesWithText(chinese).fetchSemanticsNodes().isNotEmpty()) {
            compose.onAllNodesWithText(chinese)
        } else {
            compose.onAllNodesWithText(english)
        }
        val index = candidates.fetchSemanticsNodes().indexOfFirst { it.config.contains(SemanticsActions.OnClick) }
        check(index >= 0) { "Localized navigation target is not clickable" }
        candidates[index].performClick()
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
