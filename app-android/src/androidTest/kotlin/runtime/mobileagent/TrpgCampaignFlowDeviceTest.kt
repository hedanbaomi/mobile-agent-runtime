// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.data.CreateConversationUseCase
import runtime.mobileagent.domain.*
import runtime.mobileagent.domain.trpg.*
import runtime.mobileagent.ui.*

/** End-to-end local UI -> repository -> reloaded state; no provider or fabricated tool response. */
class TrpgCampaignFlowDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    private lateinit var app: MobileAgentApp
    private lateinit var original: String
    private lateinit var source: String
    private var selected by mutableStateOf<String?>(null)
    private var tick by mutableIntStateOf(0)
    private var draft by mutableStateOf("")

    @Test fun allCampaignPanelsKeepCloseControlsReachableWithLargeText() {
        fixture(withParty = true, fontScale = 1.5f)
        listOf("campaign" to "trpg.campaign.close", "party" to "trpg.party.close",
            "story" to "trpg.story.close", "checkpoints" to "trpg.checkpoint.close",
            "atlas" to "trpg.atlas.close", "media" to "trpg.media.close").forEach { (panel, close) ->
            open(panel)
            node(close).assertIsDisplayed()
            if (panel == "party" || panel == "atlas") capture("trpg-suite-large-$panel")
            node(close).performClick()
        }
        node("trpg.chat.more").performClick()
        node("trpg.chat.export_player").performScrollTo().performClick()
        node("trpg.export.close").assertIsDisplayed().performClick()
        assertEquals(1L, app.container.trpg.get(original).revision)
    }

    @Test fun setupRequestedCheckRealRollResolutionAndFactSourcePersist() {
        fixture()
        open("campaign")
        node("trpg.campaign.player").performScrollTo().performTextReplacement("Aria")
        node("trpg.campaign.save").performClick()
        awaitRevision(2)
        assertEquals("Aria", state().playerName)
        node("trpg.campaign.tab.1").performClick()
        node("trpg.campaign.check.create").performScrollTo().performClick()
        node("trpg.campaign.check.title").performTextReplacement("Listen")
        node("trpg.campaign.check.expression").performScrollTo().performTextReplacement("1d6")
        node("trpg.campaign.check.create.confirm").performClick()
        awaitRevision(3)
        val id = state().campaign.checks.single().request.id
        assertEquals(TrpgCheckStatus.PENDING, state().campaign.checks.single().status)
        node("trpg.campaign.check.roll.$id").performScrollTo().performClick()
        awaitRevision(4)
        val roll = state().campaign.checks.single().roll!!
        node("trpg.campaign.check.summary.$id").performScrollTo().assertTextContains(roll.summary)
        node("trpg.campaign.check.note.$id").performScrollTo().performTextReplacement("Footsteps outside")
        node("trpg.campaign.check.resolve.$id").performScrollTo().performClick()
        awaitRevision(5)
        assertEquals(roll, state().campaign.checks.single().roll)
        assertEquals(TrpgCheckStatus.RESOLVED, state().campaign.checks.single().status)
        node("trpg.campaign.tab.2").performClick()
        node("trpg.campaign.fact.create").performScrollTo().performClick()
        node("trpg.campaign.fact.text").performTextReplacement("The east gate is open")
        node("trpg.campaign.fact.source.option.$source").performScrollTo().performClick()
        node("trpg.campaign.fact.apply").performClick()
        awaitRevision(6)
        assertEquals(source, state().campaign.facts.single().sourceMessageId)
        capture("trpg-suite-facts")
        assertTrue(app.container.runs.list(original).isEmpty())
    }

    @Test fun consumeRestAndFocusedHudUseTheSameActorState() {
        fixture(withParty = true)
        open("party")
        node("trpg.party.tab.1").performClick()
        node("trpg.party.item.consume.potion").performScrollTo().performClick()
        node("trpg.party.consume.apply").performClick()
        awaitRevision(2)
        assertEquals(1, state().campaign.party.items.single().quantity)
        assertEquals(5, state().campaign.party.actors.single().resources.single().current)
        node("trpg.party.tab.0").performClick()
        node("trpg.party.rest.short.hero").performScrollTo().performClick()
        awaitRevision(3)
        assertEquals(7, state().campaign.party.actors.single().resources.single().current)
        node("trpg.party.focus.hero").performScrollTo().performClick()
        awaitRevision(4)
        node("trpg.party.close").performClick()
        node("trpg.chat.toggle_hud").performClick()
        node("trpg.resource.inc.HP").performScrollTo().performClick()
        awaitRevision(5)
        assertEquals(8, state().campaign.party.actors.single().resources.single().current)
        assertEquals(10, state().resources.single().current)
        assertEquals("hero", app.container.trpg.events(original).first().resources.single().actorId)
    }

    @Test fun checkpointBranchesAtCapturedStateAndTranscriptWithoutExecutingHistory() {
        fixture()
        open("checkpoints")
        node("trpg.checkpoint.create").performClick()
        node("trpg.checkpoint.name").performTextReplacement("Before the gate")
        node("trpg.checkpoint.apply").performClick()
        awaitRevision(2)
        val checkpoint = app.container.trpgCheckpoints.list(original).single()
        compose.runOnIdle {
            app.container.trpg.update(original, 2, state().copy(scene = "After the gate", resources = listOf(TrpgResource("HP", 1, 10))))
            app.container.conversations.append(original, MessageRole.ASSISTANT, "Later event", messageId = "later-${UUID.randomUUID()}")
            tick++
        }
        awaitRevision(3)
        node("trpg.checkpoint.branch.${checkpoint.checkpointId}").performScrollTo().performClick()
        node("trpg.checkpoint.name").performTextReplacement("Alternate route")
        node("trpg.checkpoint.apply").performClick()
        compose.waitUntil(15_000) { selected != null && selected != original }
        val branch = checkNotNull(selected)
        val branchState = app.container.trpg.get(branch).state
        assertEquals("Market", branchState.scene)
        assertEquals(10, branchState.resources.single().current)
        val messages = app.container.conversations.messages(branch)
        assertEquals(1, messages.size)
        assertNotEquals(source, messages.single().id)
        assertEquals("The east gate is open", messages.single().text)
        assertTrue(app.container.runs.list(branch).all { it.state == RunStatus.COMPLETED && it.toolCalls == 0 })
        assertTrue(app.container.db.query("SELECT invocation_id FROM tool_invocations WHERE run_id IN (SELECT run_id FROM runs WHERE conversation_id=?)", listOf(branch)).isEmpty())
        assertEquals(1, app.container.trpg.get(original).state.resources.single().current)
    }

    private fun fixture(withParty: Boolean = false, fontScale: Float = 1f) {
        app = compose.activity.application as MobileAgentApp
        app.ensureHostInitialized()
        compose.runOnUiThread { androidx.core.view.WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false) }
        val c = app.container
        c.uiPreferences.edit().putBoolean(TRPG_HUD_EXPANDED, false).commit()
        val id = UUID.randomUUID().toString()
        c.profiles.createProvider(ProviderProfile("p-$id", "Campaign fixture", ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1", secretRef = "absent-fixture", revision = 1))
        c.profiles.createModel(ModelProfile("m-$id", "p-$id", ModelRole.CHAT, "fixture", setOf("stream", "tools"), contextLimit = 64000, outputLimit = 1024, revision = 1))
        val agent = c.agents.saveWithPrompt(AgentProfile("a-$id", "Host", "p", "m-$id", revision = 0, mode = AgentMode.TRPG), "Host")
        original = CreateConversationUseCase(c.db, c.conversations).create("Campaign fixture", { c.agents.createSnapshot(agent.id) }).id
        selected = original
        source = c.conversations.append(original, MessageRole.ASSISTANT, "The east gate is open").id
        val party = if (!withParty) TrpgParty() else TrpgParty(
            actors = listOf(TrpgActor("hero", "Aria", resources = listOf(TrpgResource("HP", 2, 10)), recovery = listOf(TrpgRecovery("HP", shortAmount = 2)))),
            items = listOf(TrpgItem("potion", "Potion", "hero", 2, consumable = true, effects = listOf(TrpgResourceDelta("HP", 3)))),
        )
        c.trpg.update(original, 0, TrpgState(scene = "Market", resources = listOf(TrpgResource("HP", 10, 10)), campaign = TrpgCampaign(party = party)))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
              MobileAgentTheme { Column(Modifier.width(320.dp).fillMaxSize().systemBarsPadding()) {
            TrpgChatBar(c, selected, false, false, tick, true, onOpenConversation = { selected = it }) { draft = it }
            Text(draft.ifBlank { "Campaign transcript" })
        } } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("trpg.chat.bar").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun state() = app.container.trpg.get(original).state
    private fun node(tag: String) = compose.onNodeWithTag(tag)
    private fun open(panel: String) { node("trpg.chat.more").performClick(); node("trpg.chat.open_$panel").performScrollTo().performClick() }
    private fun capture(name: String) {
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(app.cacheDir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun awaitRevision(revision: Long) {
        compose.waitUntil(10_000) { app.container.trpg.get(original).revision == revision }
        compose.waitForIdle()
    }
}
