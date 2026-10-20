// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

/** Production Compose dialogs and real SQLite CAS, with synthetic offline profiles. */
class TrpgFrontendUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    private lateinit var app: MobileAgentApp
    private lateinit var chat: String
    private var archived by mutableStateOf(false)
    private var streaming by mutableStateOf(false)
    private var tick by mutableIntStateOf(0)
    private var selected by mutableStateOf<String?>(null)
    private var inserted = ""

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
        node("trpg.dice.result").performScrollTo().assertIsDisplayed()
        node("trpg.dice.append_notes").performScrollTo().performClick()
        node("trpg.dice.save_error").performScrollTo().assertIsDisplayed()
        assertEquals(1L, app.container.trpg.get(chat).revision)
        assertEquals(8192, app.container.trpg.get(chat).state.notes.length)
        assertTrue(app.container.runs.list(chat).isEmpty())
        assertTrue(app.container.conversations.setArchived(chat, true))
        compose.runOnIdle { archived = true }
        node("trpg.dice.insert_draft").performScrollTo().assertIsNotEnabled()
        node("trpg.dice.append_notes").assertIsNotEnabled()
        node("trpg.dice.close").performClick()
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
        node("trpg.dice.append_notes").performScrollTo().performClick()
        awaitRevision(3)
        assertTrue(app.container.trpg.get(chat).state.notes.contains("CoC7"))
        node("trpg.dice.close").performClick()
        node("trpg.chat.open_sheet").performClick()
        node("trpg.sheet.tab.notes").performClick()
        node("trpg.sheet.notes").performScrollTo().assertTextContains("CoC7", substring = true)
        capture("trpg-coc-notes")
        assertTrue(app.container.runs.list(chat).isEmpty())
    }

    private fun screen(state: TrpgState, theme: AppThemeMode = AppThemeMode.LIGHT) {
        app = compose.activity.application as MobileAgentApp
        app.ensureHostInitialized()
        val c = app.container
        val id = UUID.randomUUID().toString()
        val provider = ProviderProfile("p-$id", "Offline fixture", ApiFormat.OPENAI_COMPATIBLE,
            "https://example.invalid/v1", secretRef = "absent-fixture", revision = 1)
        val model = ModelProfile("m-$id", provider.id, ModelRole.CHAT, "fixture", setOf("stream", "tools"),
            contextLimit = 64000, outputLimit = 1024, revision = 1)
        c.profiles.createProvider(provider); c.profiles.createModel(model)
        val agent = c.agents.saveWithPrompt(AgentProfile("a-$id", "Host", "p", model.id, revision = 0, mode = AgentMode.TRPG), "Host")
        chat = CreateConversationUseCase(c.db, c.conversations).create("UI campaign", { c.agents.createSnapshot(agent.id) }).id
        c.trpg.update(chat, 0, state)
        selected = chat
        compose.setContent {
            MobileAgentTheme(mode = theme) {
                Column(Modifier.width(320.dp).height(640.dp)) {
                    TrpgChatBar(c, selected, streaming, archived, tick, true) { inserted = it }
                    Text("Campaign conversation", Modifier.weight(1f))
                    OutlinedTextField("", {}, modifier = Modifier.fillMaxWidth().testTag("fixture.chat_input"))
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("trpg.chat.bar").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag)
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
