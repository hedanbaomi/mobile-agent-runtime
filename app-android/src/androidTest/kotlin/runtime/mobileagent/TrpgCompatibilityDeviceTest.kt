// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.data.CharacterCardAgentImporter
import runtime.mobileagent.data.CreateConversationUseCase
import runtime.mobileagent.domain.*
import runtime.mobileagent.domain.trpg.*
import runtime.mobileagent.serialization.CharacterCardSupport
import runtime.mobileagent.serialization.charactercard.CharacterCardCodec
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.ui.AppThemeMode
import runtime.mobileagent.ui.MobileAgentTheme
import runtime.mobileagent.ui.TrpgChatBar

/** Real Chat VM, repositories, RunTools and both HTTP protocols; no external provider. */
class TrpgCompatibilityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    @Test fun characterBookAndStateUpdateCoexistThroughBothWireProtocols() {
        for (format in listOf(ApiFormat.OPENAI_COMPATIBLE, ApiFormat.OPENAI_RESPONSES)) fixture(format, tools = true) { app, server, chat ->
            val c = app.container
            val grants = c.db.query("SELECT * FROM capability_grants")
            val vm = viewModel(app, chat)
            send(app, vm, chat, "旅人进入白塔")
            assertEquals(2, server.requests.size)
            val first = text(server.requests[0])
            val second = text(server.requests[1])
            for (request in listOf(first, second)) {
                assertTrue(request, request.contains("CHARACTER_TRPG_SENTINEL"))
                assertTrue(request, request.contains("WORLD_TRPG_SENTINEL"))
                assertTrue(request, request.contains("你好旅人"))
                assertEquals(1, Regex("<untrusted-trpg-state>").findAll(request).count())
            }
            assertTrue(first, first.contains("\"current\":10"))
            assertTrue(second, second.contains("\"current\":7"))
            assertFalse(second, second.contains("\"current\":10"))
            assertEquals(2L, c.trpg.get(chat).revision)
            assertEquals(7, c.trpg.get(chat).state.resources.single().current)
            val run = c.runs.list(chat).last()
            assertEquals(1, c.db.query("SELECT * FROM trpg_run_receipts WHERE run_id=?", listOf(run.runId)).size)
            assertEquals(grants, c.db.query("SELECT * FROM capability_grants"))
            val toolMessage = c.conversations.messages(chat).single { it.role == MessageRole.TOOL }
            assertTrue(parseTrpgToolCard(toolMessage) is runtime.mobileagent.feature.chat.ChatTrpgToolCardUi.CampaignState)
            assertFalse(c.conversations.messages(chat).any { "<untrusted-trpg-state>" in it.text || "WORLD_TRPG_SENTINEL" in it.text })
        }
    }

    @Test fun compactionKeepsCampaignProjectionOutOfSummaryAndManualChangesReachNextSend() =
        fixture(ApiFormat.OPENAI_COMPATIBLE, compact = true) { app, server, chat ->
            val c = app.container
            repeat(12) { index ->
                c.conversations.append(chat, MessageRole.USER, if (index == 0) "白塔" else "Earlier question $index")
                c.conversations.append(chat, MessageRole.ASSISTANT, "Earlier answer $index")
            }
            val originals = c.conversations.messages(chat)
            val vm = viewModel(app, chat)
            send(app, vm, chat, "继续探索")
            assertEquals(2, server.requests.size)
            val summary = text(server.requests.first())
            assertFalse(summary, summary.contains("<untrusted-trpg-state>"))
            assertFalse(summary, summary.contains("STATE_TRPG_SENTINEL"))
            assertFalse(summary, summary.contains("WORLD_TRPG_SENTINEL"))
            val answering = text(server.requests.last())
            assertTrue(answering, answering.contains("Conversation summary:"))
            assertTrue(answering, answering.contains("WORLD_TRPG_SENTINEL"))
            assertEquals(1, Regex("<untrusted-trpg-state>").findAll(answering).count())
            val old = c.trpg.get(chat)
            c.trpg.update(chat, old.revision, old.state.copy(scene = "NEW_SCENE_TRPG", resources = listOf(TrpgResource("HP", 9, 10))))
            send(app, vm, chat, "查看当前场景")
            val latest = text(server.requests.last())
            assertTrue(latest, latest.contains("NEW_SCENE_TRPG"))
            assertTrue(latest, latest.contains("\"current\":9"))
            assertEquals(originals, c.conversations.messages(chat).filter { row -> originals.any { it.id == row.id } })
            assertFalse(c.conversations.messages(chat).any { "<untrusted-trpg-state>" in it.text || "WORLD_TRPG_SENTINEL" in it.text })
        }

    @Test fun agentsViewModelCreatesAndUpdatesTrpgAgentPreservingMode() =
        fixture(ApiFormat.OPENAI_COMPATIBLE) { app, _, _ ->
            val c = app.container
            val modelId = c.profiles.listModels().first { it.role == ModelRole.CHAT }.id
            lateinit var agentsVm: AgentsViewModel
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                agentsVm = AgentsViewModel(app, SavedStateHandle())
                agentsVm.openEditor(null)
                val draft = checkNotNull(agentsVm.state.value.editor)
                agentsVm.edit(
                    draft.copy(
                        name = "TRPG KP VM",
                        prompt = "You are the Keeper.",
                        chatModelId = modelId,
                        mode = AgentMode.TRPG,
                    ),
                )
                agentsVm.save()
            }
            val deadline = System.currentTimeMillis() + 15_000
            while (agentsVm.saving.value && System.currentTimeMillis() < deadline) Thread.sleep(20)
            val createdId = checkNotNull(agentsVm.state.value.selectedAgentId)
            assertEquals(AgentMode.TRPG, c.agents.get(createdId)?.mode)
            assertEquals(AgentMode.TRPG, agentsVm.state.value.summary?.mode)

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                agentsVm.openEditor(createdId)
                val existing = checkNotNull(agentsVm.state.value.editor)
                assertEquals(AgentMode.TRPG, existing.mode)
                agentsVm.edit(existing.copy(name = "TRPG KP VM Updated", prompt = "Updated Keeper prompt."))
                agentsVm.save()
            }
            while (agentsVm.saving.value && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertNull(agentsVm.state.value.error)
            val updated = checkNotNull(c.agents.get(createdId))
            assertEquals("TRPG KP VM Updated", updated.name)
            assertEquals(AgentMode.TRPG, updated.mode)
        }

    @Test fun trpgChatBarSheetAndDiceRollerRespectSnapshotModeAndCas() =
        fixture(ApiFormat.OPENAI_COMPATIBLE) { app, _, trpgChatId ->
            val c = app.container
            c.uiPreferences.edit().remove(runtime.mobileagent.ui.TRPG_HUD_EXPANDED).commit()
            val modelId = c.profiles.listModels().first { it.role == ModelRole.CHAT }.id
            val normalAgent = c.agents.saveWithPrompt(
                AgentProfile("normal-${UUID.randomUUID()}", "Normal Agent", "p", modelId, revision = 1, mode = AgentMode.NORMAL),
                "Normal prompt",
            )
            val normalChat = CreateConversationUseCase(c.db, c.conversations)
                .create("Normal chat", { c.agents.createSnapshot(normalAgent.id) })

            var activeConversationId by mutableStateOf<String?>(normalChat.id)
            var insertedDraft = ""
            compose.runOnUiThread {
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            }
            compose.setContent {
                MobileAgentTheme(mode = AppThemeMode.CC66FF) {
                    Box(Modifier.systemBarsPadding()) {
                    TrpgChatBar(
                        container = c,
                        conversationId = activeConversationId,
                        streaming = false,
                        archived = false,
                        refreshTick = 0,
                        chinese = true,
                        onInsertDraft = { insertedDraft = it },
                    )
                    }
                }
            }
            compose.waitForIdle()
            assertEquals(0, compose.onAllNodesWithTag("trpg.chat.bar").fetchSemanticsNodes().size)

            compose.runOnIdle { activeConversationId = trpgChatId }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("trpg.chat.bar").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("trpg.chat.bar").assertIsDisplayed()
            compose.onNodeWithTag("trpg.chat.hud_body").assertDoesNotExist()
            compose.onNodeWithTag("trpg.chat.toggle_hud").performClick()
            compose.onNodeWithTag("trpg.resource.dec.HP").performClick()
            compose.waitUntil(10_000) { c.trpg.get(trpgChatId).revision == 2L }
            assertEquals(9, c.trpg.get(trpgChatId).state.resources.single().current)

            compose.onNodeWithTag("trpg.chat.open_sheet").performClick()
            compose.onNodeWithTag("trpg.sheet.dialog").assertIsDisplayed()
            compose.onNodeWithTag("trpg.sheet.scene").performTextReplacement("密大图书馆")
            compose.onNodeWithTag("trpg.sheet.save").performClick()
            compose.waitUntil(10_000) { c.trpg.get(trpgChatId).revision == 3L }
            assertEquals("密大图书馆", c.trpg.get(trpgChatId).state.scene)

            compose.onNodeWithTag("trpg.chat.open_dice").performClick()
            compose.onNodeWithTag("trpg.dice.dialog").assertIsDisplayed()
            compose.onNodeWithTag("trpg.dice.roll").performClick()
            compose.onNodeWithTag("trpg.dice.result").assertIsDisplayed()
            compose.waitUntil(10_000) { c.trpg.get(trpgChatId).revision == 4L }
            compose.waitForIdle()
            compose.onNodeWithTag("trpg.dice.insert_draft").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { insertedDraft.isNotEmpty() }
            assertTrue("Inserted draft: $insertedDraft", insertedDraft.contains("1d20"))
            assertTrue(c.runs.list(trpgChatId).isEmpty())
        }

    private fun fixture(format: ApiFormat, tools: Boolean = false, compact: Boolean = false,
        block: (MobileAgentApp, WorldBookLoopback, String) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val c = app.container
        val id = UUID.randomUUID().toString()
        val next = TrpgState(playerName = "旅人", characterSheet = "STATE_TRPG_SENTINEL", scene = "白塔",
            resources = listOf(TrpgResource("HP", 7, 10)))
        val arguments = buildJsonObject {
            put("expectedRevision", 1)
            put("state", Json { encodeDefaults = true }.encodeToJsonElement(TrpgState.serializer(), next))
        }.toString()
        WorldBookLoopback(format, tools, ToolCall("campaign-update", "session_state_update", arguments)).use { server ->
            val ref = "trpg-compatibility-$id"
            c.secrets.put(ref, "synthetic-trpg-fixture".toCharArray())
            try {
                val provider = ProviderProfile("p-$id", "TRPG fixture", format, "http://127.0.0.1:${server.port}/v1", secretRef = ref, revision = 1)
                val model = ModelProfile("m-$id", provider.id, ModelRole.CHAT, "trpg-fixture",
                    if (tools) setOf("stream", "tools") else setOf("stream"), contextLimit = 64000, outputLimit = 1024, revision = 1)
                c.profiles.createProvider(provider); c.profiles.createModel(model)
                val imported = CharacterCardCodec.decode(CARD.toByteArray(), "fixture.json")
                val card = ImportedCharacterCard(imported.document, "旅人", imported.document.firstMessage)
                val policy = if (compact) "{\"modelAwareCompaction\":false,\"maxHistoryMessages\":20,\"maxHistoryTurns\":10,\"keepRecentTurns\":2}" else "{}"
                val saved = CharacterCardAgentImporter(c.db).saveNew(AgentProfile("a-$id", "TRPG host", "pending", model.id,
                    revision = 0, mode = AgentMode.TRPG, characterCard = card, contextPolicyJson = policy),
                    CharacterCardSupport.prompt(card), imported.worldBook).profile
                val chat = CreateConversationUseCase(c.db, c.conversations).create("TRPG test", { c.agents.createSnapshot(saved.id) })
                c.trpg.update(chat.id, 0, next.copy(resources = listOf(TrpgResource("HP", 10, 10))))
                block(app, server, chat.id)
                server.assertHealthy()
            } finally { c.db.execute("DELETE FROM secrets WHERE ref=?", listOf(ref)) }
        }
    }

    private fun viewModel(app: MobileAgentApp, chat: String): ChatViewModel {
        lateinit var vm: ChatViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = ChatViewModel(app, SavedStateHandle()); vm.selectSession(chat)
        }
        return vm
    }

    private fun send(app: MobileAgentApp, vm: ChatViewModel, chat: String, input: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.input(input); vm.send() }
        val deadline = System.currentTimeMillis() + 30_000
        while (vm.state.value.streaming && System.currentTimeMillis() < deadline) Thread.sleep(30)
        assertFalse("Run did not settle: ${vm.state.value.status}", vm.state.value.streaming)
        assertEquals(vm.state.value.status, RunStatus.COMPLETED, app.container.runs.list(chat).last().state)
    }

    private fun text(element: JsonElement): String = when (element) {
        is JsonObject -> element.values.joinToString("\n", transform = ::text)
        is JsonArray -> element.joinToString("\n", transform = ::text)
        is JsonPrimitive -> element.content
    }

    companion object {
        private const val CARD = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"主持人","description":"CHARACTER_TRPG_SENTINEL","first_mes":"你好{{user}}","mes_example":"","character_book":{"name":"白塔","scan_depth":30,"entries":[{"id":1,"keys":["白塔"],"content":"WORLD_TRPG_SENTINEL","enabled":true,"insertion_order":100}]}}}"""
    }
}
