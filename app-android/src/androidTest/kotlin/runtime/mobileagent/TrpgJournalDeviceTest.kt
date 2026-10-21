// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.data.CreateConversationUseCase
import runtime.mobileagent.domain.*
import runtime.mobileagent.domain.trpg.*
import runtime.mobileagent.ui.MobileAgentTheme
import runtime.mobileagent.ui.TRPG_HUD_EXPANDED
import runtime.mobileagent.ui.TrpgChatBar

class TrpgJournalDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    private lateinit var app: MobileAgentApp
    private lateinit var chat: String
    private var shown by mutableStateOf(true)

    @Test fun realRollSurvivesPanelReentryAndUndoRetainsItsExactResult() {
        fixture(damage = true)
        node("trpg.chat.open_dice").performClick()
        node("trpg.dice.roll").performScrollTo().performClick()
        awaitRevision(3)
        val roll = app.container.trpg.events(chat).single { it.kind == TrpgEventKind.ROLL }
        node("trpg.dice.close").performClick()
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        compose.runOnIdle { shown = true }
        awaitBar()
        node("trpg.chat.toggle_hud").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("trpg.chat.hud_body").fetchSemanticsNodes().isNotEmpty() }
        node("trpg.chat.last_roll").performScrollTo().assertTextContains(roll.title)
        node("trpg.chat.more").performClick()
        node("trpg.chat.open_journal").performClick()
        node("trpg.journal.undo").performScrollTo().performClick()
        awaitRevision(4)
        assertEquals(10, app.container.trpg.get(chat).state.resources.single().current)
        assertEquals(roll, app.container.trpg.events(chat).single { it.kind == TrpgEventKind.ROLL })
        assertNull(app.container.trpg.undoableEventId(chat))
        assertTrue(app.container.runs.list(chat).isEmpty())
    }

    @Test fun directResourceInputRejectsInvalidResultAndSavesNegativeValues() {
        fixture()
        node("trpg.chat.open_sheet").performClick()
        node("trpg.sheet.res.value.HP").performScrollTo().performClick()
        node("trpg.sheet.res.value.input").performTextReplacement("1000001")
        node("trpg.sheet.res.value.apply").performClick()
        node("trpg.sheet.res.value.error").assertIsDisplayed()
        node("trpg.sheet.res.value.input").assertTextContains("1000001")
        assertEquals(1L, app.container.trpg.get(chat).revision)
        node("trpg.sheet.res.value.input").performTextReplacement("-4")
        node("trpg.sheet.res.value.apply").performClick()
        node("trpg.sheet.save").performClick()
        awaitRevision(2)
        assertEquals(TrpgResource("HP", -4, 12), app.container.trpg.get(chat).state.resources.single())
    }

    private fun fixture(damage: Boolean = false) {
        app = compose.activity.application as MobileAgentApp
        app.ensureHostInitialized()
        compose.runOnUiThread {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
        }
        val c = app.container
        c.uiPreferences.edit().putBoolean(TRPG_HUD_EXPANDED, false).commit()
        val id = UUID.randomUUID().toString()
        val provider = ProviderProfile("p-$id", "Journal fixture", ApiFormat.OPENAI_COMPATIBLE,
            "https://example.invalid/v1", secretRef = "absent-fixture", revision = 1)
        val model = ModelProfile("m-$id", provider.id, ModelRole.CHAT, "fixture", setOf("stream", "tools"),
            contextLimit = 64000, outputLimit = 1024, revision = 1)
        c.profiles.createProvider(provider)
        c.profiles.createModel(model)
        val agent = c.agents.saveWithPrompt(AgentProfile("a-$id", "Host", "p", model.id, revision = 0, mode = AgentMode.TRPG), "Host")
        chat = CreateConversationUseCase(c.db, c.conversations).create("Journal fixture", { c.agents.createSnapshot(agent.id) }).id
        val original = TrpgState(resources = listOf(TrpgResource("HP", 10, 12)))
        c.trpg.update(chat, 0, original)
        if (damage) c.trpg.update(chat, 1, original.copy(resources = listOf(TrpgResource("HP", 6, 12))))
        compose.setContent {
            MobileAgentTheme {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    if (shown) TrpgChatBar(c, chat, false, false, 0, true) {}
                    Text("Campaign transcript")
                }
            }
        }
        awaitBar()
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag)
    private fun awaitBar() = compose.waitUntil(10_000) { compose.onAllNodesWithTag("trpg.chat.bar").fetchSemanticsNodes().isNotEmpty() }
    private fun awaitRevision(revision: Long) {
        compose.waitUntil(10_000) { app.container.trpg.get(chat).revision == revision }
        compose.waitForIdle()
    }
}
