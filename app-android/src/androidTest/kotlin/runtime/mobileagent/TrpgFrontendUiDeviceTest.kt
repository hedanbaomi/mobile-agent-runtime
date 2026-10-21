// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.data.CreateConversationUseCase
import runtime.mobileagent.domain.*
import runtime.mobileagent.domain.trpg.*
import runtime.mobileagent.ui.*
import runtime.mobileagent.feature.chat.*

/** Production Compose dialogs and real SQLite CAS, with synthetic offline profiles. */
class TrpgFrontendUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    private lateinit var app: MobileAgentApp
    private lateinit var chat: String
    private lateinit var agentId: String
    private var archived by mutableStateOf(false)
    private var streaming by mutableStateOf(false)
    private var tick by mutableIntStateOf(0)
    private var selected by mutableStateOf<String?>(null)
    private var inserted = ""
    private var input by mutableStateOf("未发送的草稿")
    private val restoration = StateRestorationTester(compose)

    @Test fun collapsedHudPreservesChatSpaceDraftAndChoiceAcrossReentryAndRestoration() {
        screen(TrpgState(resources = (1..32).map { TrpgResource("Resource $it", it, 40) }),
            startExpanded = false, realChat = true)
        node("trpg.chat.hud_body").assertDoesNotExist()
        assertBarHeightAtMost(64)
        node("conversation.composer.input").assertIsDisplayed().assertTextContains("未发送的草稿")
        node("conversation.message.last").assertIsDisplayed()
        val collapsedSpace = node("fixture.conversation").fetchSemanticsNode().boundsInRoot.height
        node("trpg.chat.toggle_hud").performClick()
        waitForHud()
        node("trpg.resource.inc.Resource 32").performScrollTo().assertIsDisplayed()
        val expandedSpace = node("fixture.conversation").fetchSemanticsNode().boundsInRoot.height
        assertTrue(collapsedSpace > expandedSpace + 100)
        node("conversation.composer.input").assertIsDisplayed()
        compose.runOnIdle { selected = null }
        node("trpg.chat.bar").assertDoesNotExist()
        compose.runOnIdle { selected = chat }
        waitForBar()
        node("trpg.chat.hud_body").assertIsDisplayed()
        val c = app.container
        val secondChat = CreateConversationUseCase(c.db, c.conversations)
            .create("Second campaign", { c.agents.createSnapshot(agentId) }).id
        compose.runOnIdle { selected = secondChat }
        waitForBar()
        node("trpg.chat.hud_body").assertIsDisplayed()
        node("trpg.chat.toggle_hud").performClick()
        compose.runOnIdle { selected = chat }
        waitForBar()
        node("trpg.chat.hud_body").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        waitForBar()
        node("trpg.chat.hud_body").assertDoesNotExist()
        node("conversation.composer.input").assertTextContains("未发送的草稿")
        assertEquals(1L, app.container.trpg.get(chat).revision)
        assertTrue(app.container.runs.list(chat).isEmpty())
        capture("trpg-compact-chat")
    }

    @Test fun keyboardTemporarilyCollapsesHudWithoutChangingPreferenceOrDraft() {
        screen(TrpgState(resources = listOf(TrpgResource("HP", 7, 15))), realChat = true)
        node("trpg.chat.hud_body").assertIsDisplayed()
        node("conversation.composer.input").performClick().performTextInput("继续")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("trpg.chat.hud_body").fetchSemanticsNodes().isEmpty() }
        node("conversation.composer.input").assertIsDisplayed().assertIsFocused()
        assertTrue(app.container.uiPreferences.getBoolean(TRPG_HUD_EXPANDED, false))
        capture("trpg-keyboard-chat")
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 5_000)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("trpg.chat.hud_body").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(app.container.uiPreferences.getBoolean(TRPG_HUD_EXPANDED, false))
        node("conversation.composer.input").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("trpg.chat.hud_body").fetchSemanticsNodes().isEmpty() }
        // Expanding from the compact header dismisses the IME, preserving the input.
        node("trpg.chat.toggle_hud").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("trpg.chat.hud_body").fetchSemanticsNodes().isNotEmpty() }
        node("conversation.composer.input").assertTextContains("继续", substring = true)
        assertEquals(1L, app.container.trpg.get(chat).revision)
    }

    @Test fun compactControlsAndConversationRemainReachableInShortWindowWithLargeText() {
        screen(TrpgState(playerName = "长名字".repeat(10), scene = "场景".repeat(40),
            resources = (1..32).map { TrpgResource("Resource $it", it, 40) }),
            startExpanded = false, realChat = true, height = 400, fontScale = 1.5f)
        assertBarHeightAtMost(100)
        node("trpg.chat.open_dice").assertIsDisplayed()
        node("trpg.chat.open_sheet").assertIsDisplayed()
        node("conversation.composer.input").assertIsDisplayed()
        node("conversation.message.last").assertIsDisplayed()
        node("trpg.chat.toggle_hud").performClick()
        node("trpg.sheet.dialog").assertIsDisplayed()
        node("trpg.sheet.res.remove.Resource 32").performScrollTo().assertIsDisplayed()
        node("trpg.sheet.close").performClick()
        node("trpg.chat.hud_body").assertDoesNotExist()
        compose.waitUntil(10_000) { node("conversation.composer.input").isDisplayed() }
        node("conversation.composer.input").assertIsDisplayed().assertTextContains("未发送的草稿")
        val inputBounds = node("conversation.composer.input").getUnclippedBoundsInRoot()
        assertTrue("Large-text composer must retain its full minimum height", inputBounds.bottom - inputBounds.top >= 56.dp)
        assertEquals(1L, app.container.trpg.get(chat).revision)
        capture("trpg-short-large-text")
    }

    @Test fun conflictRetainsDraftUntilExplicitReloadAndSavesAgainstNewRevision() {
        screen(TrpgState(notes = "Original", resources = listOf(TrpgResource("HP", 15, 10))))
        node("trpg.resource.inc.HP").performClick()
        awaitRevision(2)
        assertEquals(16, app.container.trpg.get(chat).state.resources.single().current)
        node("trpg.resource.dec.HP").performClick()
        awaitRevision(3)
        assertEquals(15, app.container.trpg.get(chat).state.resources.single().current)

        node("trpg.chat.open_sheet").performClick()
        node("trpg.sheet.tab.notes").performClick()
        node("trpg.sheet.notes").performScrollTo().performTextReplacement("Local draft survives")
        val current = app.container.trpg.get(chat)
        app.container.trpg.update(chat, current.revision, current.state.copy(notes = "Remote update"))
        node("trpg.sheet.save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("trpg.sheet.conflict").fetchSemanticsNodes().isNotEmpty() }
        node("trpg.sheet.notes").performScrollTo().assertTextContains("Local draft survives")
        assertEquals("Remote update", app.container.trpg.get(chat).state.notes)
        node("trpg.sheet.reload").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("trpg.sheet.conflict").fetchSemanticsNodes().isEmpty()
        }
        node("trpg.sheet.notes").performScrollTo().assertTextContains("Remote update")
        node("trpg.sheet.notes").performTextReplacement("Merged explicitly")
        node("trpg.sheet.save").performClick()
        awaitRevision(5)
        assertEquals("Merged explicitly", app.container.trpg.get(chat).state.notes)
    }

    @Test fun invalidMaximumKeepsRawInputAndCorePresetPreservesCustomValues() {
        screen(TrpgState(resources = listOf(TrpgResource("HP", 7, 15), TrpgResource("Soul debt", -2))))
        node("trpg.chat.open_sheet").performClick()
        node("trpg.sheet.res.new_name").performScrollTo().performTextReplacement("Morale")
        node("trpg.sheet.res.new_cur").performTextReplacement("9")
        node("trpg.sheet.res.new_max").performTextReplacement("abc")
        finishResourceInput()
        node("trpg.sheet.res.add").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { node("trpg.sheet.error").isDisplayed() }
        capture("trpg-resource-invalid")
        node("trpg.sheet.error").assertIsDisplayed()
        node("trpg.sheet.res.new_name").performScrollTo().assertTextContains("Morale")
        node("trpg.sheet.res.new_max").assertTextContains("abc").performTextReplacement("")
        finishResourceInput()
        node("trpg.sheet.res.add").performScrollTo().assertIsDisplayed().performClick()
        node("trpg.sheet.res.remove.Morale").performScrollTo().assertIsDisplayed()
        node("trpg.sheet.apply_framework_core").performScrollTo().performClick()
        node("trpg.sheet.save").performClick()
        awaitRevision(2)
        val resources = app.container.trpg.get(chat).state.resources
        assertEquals(7, resources.single { it.name == "HP" }.current)
        assertEquals(-2, resources.single { it.name == "Soul debt" }.current)
        assertNull(resources.single { it.name == "Morale" }.maximum)
        assertEquals(9, resources.single { it.name == "Morale" }.current)
    }

    @Test fun diceValidationNoteOverflowAndArchivedReadOnlyDoNotStartARun() {
        screen(TrpgState(notes = "x".repeat(8192)), theme = AppThemeMode.DARK)
        node("trpg.chat.open_dice").performClick()
        node("trpg.dice.expression").performScrollTo().performTextReplacement("8d6")
        node("trpg.dice.dc").performTextReplacement("invalid")
        node("trpg.dice.roll").performScrollTo().performClick()
        node("trpg.dice.error").performScrollTo().assertIsDisplayed()
        node("trpg.dice.result").assertDoesNotExist()
        node("trpg.dice.dc").performScrollTo().performTextReplacement("20")
        node("trpg.dice.roll").performScrollTo().performClick()
        awaitRevision(2)
        node("trpg.dice.result").performScrollTo().assertIsDisplayed()
        node("trpg.dice.append_notes").performScrollTo().performClick()
        node("trpg.dice.save_error").performScrollTo().assertIsDisplayed()
        assertEquals(2L, app.container.trpg.get(chat).revision)
        assertEquals(8192, app.container.trpg.get(chat).state.notes.length)
        assertTrue(app.container.runs.list(chat).isEmpty())
        assertTrue(app.container.conversations.setArchived(chat, true))
        compose.runOnIdle { archived = true }
        node("trpg.dice.insert_draft").performScrollTo().assertIsNotEnabled()
        node("trpg.dice.append_notes").assertIsNotEnabled()
        node("trpg.dice.close").performClick()
        waitForHud()
        node("trpg.chat.insert_last_roll").performScrollTo().assertIsNotEnabled()
        assertEquals("", inserted)
        assertTrue(app.container.runs.list(chat).isEmpty())
    }

    @Test fun fullResourceHudRemainsBoundedAt320DpAndNormalChatsHaveNoTrpgControls() {
        screen(TrpgState(resources = (1..32).map { TrpgResource("Resource $it", it, 40) }), theme = AppThemeMode.CC66FF)
        node("fixture.chat_input").assertIsDisplayed()
        node("trpg.chat.open_dice").assertIsDisplayed()
        node("trpg.resource.inc.Resource 32").performScrollTo().assertIsDisplayed()
        node("fixture.chat_input").assertIsDisplayed()
        capture("trpg-32-resources")
        node("trpg.chat.open_sheet").performClick()
        compose.runOnIdle { streaming = true }
        node("trpg.sheet.save").assertIsNotEnabled()
        val c = app.container
        val model = c.profiles.listModels().first { it.role == ModelRole.CHAT }.id
        val normal = c.agents.saveWithPrompt(AgentProfile("normal-${UUID.randomUUID()}", "Normal", "p", model, revision = 0), "Normal")
        val normalChat = CreateConversationUseCase(c.db, c.conversations).create("Normal", { c.agents.createSnapshot(normal.id) })
        compose.runOnIdle { selected = normalChat.id; streaming = false }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("trpg.chat.bar").fetchSemanticsNodes().isEmpty() }
        node("trpg.sheet.dialog").assertDoesNotExist()
        node("fixture.chat_input").assertIsDisplayed()
        assertEquals(1L, c.trpg.get(chat).revision)
    }

    @Test fun emptyHudFrameworksAreReachableAndManualNoteSaveIsVisibleAfterReload() {
        screen(TrpgState(), theme = AppThemeMode.SYSTEM)
        node("trpg.preset.custom").performScrollTo().assertIsDisplayed()
        node("trpg.preset.coc7").performScrollTo().performClick()
        awaitRevision(2)
        assertEquals(TrpgRuleset.COC_7, app.container.trpg.get(chat).state.ruleset)
        node("trpg.chat.open_dice").performClick()
        node("trpg.dice.roll_coc").performScrollTo().performClick()
        awaitRevision(3)
        node("trpg.dice.append_notes").performScrollTo().performClick()
        awaitRevision(4)
        assertTrue(app.container.trpg.get(chat).state.notes.contains("CoC7"))
        node("trpg.dice.close").performClick()
        node("trpg.chat.open_sheet").performClick()
        node("trpg.sheet.tab.notes").performClick()
        node("trpg.sheet.notes").performScrollTo().assertTextContains("CoC7", substring = true)
        capture("trpg-coc-notes")
        assertTrue(app.container.runs.list(chat).isEmpty())
    }

    private fun screen(state: TrpgState, theme: AppThemeMode = AppThemeMode.LIGHT,
        startExpanded: Boolean = true, realChat: Boolean = false, height: Int = 640, fontScale: Float = 1f) {
        app = compose.activity.application as MobileAgentApp
        app.ensureHostInitialized()
        val c = app.container
        c.uiPreferences.edit().remove(TRPG_HUD_EXPANDED).commit()
        compose.runOnUiThread {
            // Match MainActivity: legacy IME inference depends on the window's inset policy.
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
        }
        val id = UUID.randomUUID().toString()
        val provider = ProviderProfile("p-$id", "Offline fixture", ApiFormat.OPENAI_COMPATIBLE,
            "https://example.invalid/v1", secretRef = "absent-fixture", revision = 1)
        val model = ModelProfile("m-$id", provider.id, ModelRole.CHAT, "fixture", setOf("stream", "tools"),
            contextLimit = 64000, outputLimit = 1024, revision = 1)
        c.profiles.createProvider(provider); c.profiles.createModel(model)
        val agent = c.agents.saveWithPrompt(AgentProfile("a-$id", "Host", "p", model.id, revision = 0, mode = AgentMode.TRPG), "Host")
        agentId = agent.id
        chat = CreateConversationUseCase(c.db, c.conversations).create("UI campaign", { c.agents.createSnapshot(agent.id) }).id
        c.trpg.update(chat, 0, state)
        selected = chat
        restoration.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            MobileAgentTheme(mode = theme) {
                Column(Modifier.width(320.dp).height(height.dp).systemBarsPadding().imePadding()) {
                    if (realChat) WorldBookChatBar(c, selected, streaming, tick, true) {}
                    TrpgChatBar(c, selected, streaming, archived, tick, true) { inserted = it }
                    if (realChat) {
                        ConversationScreen(
                            state = ChatUiState(selectedSessionId = selected,
                                messages = listOf(ChatMessageUi("last", "assistant", "你来到旅馆门前，下一步做什么？"))),
                            input = { input }, actions = ChatActions(onInput = { input = it }),
                            showGlobalMenu = false,
                            modifier = Modifier.weight(1f).testTag("fixture.conversation"),
                        )
                    } else {
                    Text("Campaign conversation", Modifier.weight(1f))
                    OutlinedTextField("", {}, modifier = Modifier.fillMaxWidth().testTag("fixture.chat_input"))
                    }
                }
            }
            }
        }
        waitForBar()
        // Apply after setContent has installed the test window, as in the production manifest.
        compose.runOnIdle {
            compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        if (startExpanded) {
            node("trpg.chat.toggle_hud").performClick()
            waitForHud()
        }
    }

    private fun waitForBar() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("trpg.chat.bar").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForHud() {
        try {
            compose.waitUntil(10_000) { node("trpg.chat.hud_body").isDisplayed() }
        } catch (failure: Throwable) {
            capture("trpg-hud-expansion-failure")
            throw failure
        }
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag)
    private fun assertBarHeightAtMost(max: Int) {
        val bounds = node("trpg.chat.bar").getUnclippedBoundsInRoot()
        val actual = bounds.bottom - bounds.top
        assertTrue("HUD height $actual exceeds ${max}dp", actual <= max.dp)
    }
    private fun finishResourceInput() {
        // API26 window panning/IME transitions occur outside Compose idleness.
        // SetText semantics alone does not focus the edited field: focus it as a
        // user would, then dismiss any visible keyboard while that input session
        // is still active. A semantic Done can clear focus before its window hides.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val info = automation.serviceInfo
        val previousFlags = info.flags
        info.flags = previousFlags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        fun keyboardVisible() = automation.windows.any { window ->
            if (window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) false
            else window.root?.let { root ->
                try { root.refresh() && root.isVisibleToUser } finally { root.recycle() }
            } ?: false
        }
        try {
            automation.waitForIdle(250, 5_000)
            node("trpg.sheet.res.new_max").performScrollTo().assertIsDisplayed().performClick().assertIsFocused()
            automation.waitForIdle(250, 5_000)
            // A hardware keyboard can leave logical input active without an IME
            // window. Back is safe only when a real keyboard window is visible.
            if (keyboardVisible()) InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { !keyboardVisible() }
            automation.waitForIdle(250, 5_000)
            // The dialog's surface can extend outside the viewport while the
            // system pans its window. The caller checks the actual Add target.
            node("trpg.sheet.dialog").assertExists()
        } finally {
            info.flags = previousFlags
            automation.serviceInfo = info
        }
    }
    private fun awaitRevision(revision: Long) {
        compose.waitUntil(10_000) { app.container.trpg.get(chat).revision == revision }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(app.cacheDir, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
