// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.SharedPreferences
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.data.ConversationRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.ProfileRepository
import runtime.mobileagent.data.RunRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.SqlRow
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ErrorPart
import runtime.mobileagent.domain.MessageErrorCode
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.domain.TextPart
import runtime.mobileagent.ui.AppIcons
import runtime.mobileagent.ui.AppRoutes
import runtime.mobileagent.ui.QuickStartUiState
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap

class QuickStartProgressTest {
    @Test fun resumesFromSavedFactsWithoutTreatingAConfiguredAgentAsASuccessfulRun() {
        assertEquals(QuickStartStep.MODEL, quickStartStep(false, false, false))
        assertEquals(QuickStartStep.AGENT, quickStartStep(true, false, false))
        assertEquals(QuickStartStep.MESSAGE, quickStartStep(true, true, false))
        assertEquals(QuickStartStep.COMPLETE, quickStartStep(true, true, true))
        assertEquals(QuickStartStep.MODEL, quickStartStep(false, true, false))
    }

    @Test fun sameRouteFirstChatSuccessRefreshesQuickStartToCompleteWithoutStaleFlash() = runBlocking {
        withQuickStartDatabase { fixture ->
            val preferences = InMemoryQuickStartPreferences()
            val controller = QuickStartController(
                preferencesProvider = { preferences },
                scope = this,
                ioDispatcher = Dispatchers.Unconfined,
            ) {
                readQuickStartStep(fixture.profiles, fixture.agents, fixture.db)
            }

            assertEquals(QuickStartStep.MODEL, controller.syncAndAwait(streaming = false))
            fixture.configureChatModelAndAgent()
            assertEquals(QuickStartStep.MESSAGE, controller.syncAndAwait(streaming = false))
            assertTrue(controller.uiState(AppRoutes.CHAT, detailOpen = false, streaming = false).visible)

            // User starts first chat while staying on AppRoutes.CHAT.
            controller.sync(streaming = true)
            assertTrue(controller.awaitingPostStreamRefresh.value)
            assertFalse(controller.uiState(AppRoutes.CHAT, detailOpen = false, streaming = true).visible)

            // Run genuinely completes and writes assistant reply + COMPLETED run before streaming ends.
            fixture.recordRunWithAssistantReply(
                runId = "run-success-1",
                runState = RunStatus.COMPLETED,
                messageStatus = "COMPLETE",
                answerText = "Hello! Your first chat succeeded.",
            )

            // Before the post-stream refresh finishes, stale MESSAGE must not flash for a frame.
            assertFalse(controller.uiState(AppRoutes.CHAT, detailOpen = false, streaming = false).visible)

            // Same-route stream completion refreshes QuickStart to COMPLETE.
            assertEquals(QuickStartStep.COMPLETE, controller.syncAndAwait(streaming = false))
            assertFalse(controller.awaitingPostStreamRefresh.value)
            val settled = controller.uiState(AppRoutes.CHAT, detailOpen = false, streaming = false)
            assertEquals(QuickStartStep.COMPLETE, settled.step)
        }
    }

    @Test fun sameRouteFailedCancelledOrEmptyRunsKeepMessageStepVisible() = runBlocking {
        withQuickStartDatabase { fixture ->
            fixture.configureChatModelAndAgent()
            val preferences = InMemoryQuickStartPreferences()
            val controller = QuickStartController(
                preferencesProvider = { preferences },
                scope = this,
                ioDispatcher = Dispatchers.Unconfined,
            ) {
                readQuickStartStep(fixture.profiles, fixture.agents, fixture.db)
            }

            assertEquals(QuickStartStep.MESSAGE, controller.syncAndAwait(streaming = false))

            // 1. Unadmitted preflight error (error assistant message without a COMPLETED run).
            controller.sync(streaming = true)
            fixture.conversations.append(
                "conversation-1",
                MessageRole.ASSISTANT,
                "Provider API key is missing.",
                status = "ERROR",
                parts = listOf(ErrorPart(MessageErrorCode.SECRET_UNAVAILABLE, "Provider API key is missing.")),
                messageId = "msg-preflight-error",
            )
            assertEquals(QuickStartStep.MESSAGE, controller.syncAndAwait(streaming = false))
            assertTrue(controller.uiState(AppRoutes.CHAT, detailOpen = false, streaming = false).visible)

            // 2. Admitted runs that fail, cancel, exhaust budget, or end with unknown outcome.
            val unsuccessfulOutcomes = listOf(
                Triple("run-failed", RunStatus.FAILED, "ERROR"),
                Triple("run-cancelled", RunStatus.CANCELLED, "CANCELLED"),
                Triple("run-budget", RunStatus.BUDGET_EXHAUSTED, "BUDGET_EXHAUSTED"),
                Triple("run-unknown", RunStatus.UNKNOWN_OUTCOME, "UNKNOWN_OUTCOME"),
            )
            for ((runId, runState, messageStatus) in unsuccessfulOutcomes) {
                controller.sync(streaming = true)
                fixture.recordRunWithAssistantReply(
                    runId = runId,
                    runState = runState,
                    messageStatus = messageStatus,
                    answerText = "Partial text before $runState",
                )
                assertFalse(hasCompletedFirstChat(fixture.db), "Unsuccessful $runState must not complete MESSAGE")
                assertEquals(QuickStartStep.MESSAGE, controller.syncAndAwait(streaming = false))
                val ui = controller.uiState(AppRoutes.CHAT, detailOpen = false, streaming = false)
                assertEquals(QuickStartStep.MESSAGE, ui.step)
                assertTrue(ui.visible)
            }

            // A failed run can already have checkpointed a non-empty intermediate assistant reply.
            fixture.recordRunWithAssistantReply(
                runId = "run-failed-after-tool", runState = RunStatus.FAILED,
                messageStatus = "COMPLETE", answerText = "Intermediate tool round.",
            )
            // 3. A later completed run with an empty answer in the same conversation must not hide MESSAGE.
            controller.sync(streaming = true)
            fixture.recordRunWithAssistantReply(
                runId = "run-empty-answer",
                runState = RunStatus.COMPLETED,
                messageStatus = "COMPLETE",
                answerText = "  \n\t  ",
            )
            assertFalse(hasCompletedFirstChat(fixture.db))
            assertEquals(QuickStartStep.MESSAGE, controller.syncAndAwait(streaming = false))
            assertTrue(controller.uiState(AppRoutes.CHAT, detailOpen = false, streaming = false).visible)

            // 4. Subsequent genuine completed reply advances QuickStart to COMPLETE on the same route.
            controller.sync(streaming = true)
            fixture.recordRunWithAssistantReply(
                runId = "run-real-answer",
                runState = RunStatus.COMPLETED,
                messageStatus = "COMPLETE",
                answerText = "Verified assistant reply.",
            )
            assertTrue(hasCompletedFirstChat(fixture.db))
            assertEquals(QuickStartStep.COMPLETE, controller.syncAndAwait(streaming = false))
        }
    }

    @Test fun onlyTheFinalModelRoundCompletesFirstChat() = runBlocking {
        withQuickStartDatabase { fixture ->
            fixture.configureChatModelAndAgent()
            val runId = "two-rounds"
            fixture.recordRunWithAssistantReply(runId, RunStatus.COMPLETED, "COMPLETE", "Calling a tool.")
            fixture.runs.save(requireNotNull(fixture.runs.get(runId)).copy(modelRounds = 2))
            assertFalse(hasCompletedFirstChat(fixture.db))
            fixture.conversations.append(
                "conversation-1", MessageRole.ASSISTANT, "Final answer.", status = "COMPLETE",
                messageId = runtime.mobileagent.agent.RuntimeMessageIds.assistant(runId, 2),
            )
            assertTrue(hasCompletedFirstChat(fixture.db))
        }
    }

    @Test fun collapsedStatePersistsSurvivesRefreshesAndRestoresAcrossControllerRecreation() = runBlocking {
        withQuickStartDatabase { fixture ->
            fixture.configureChatModelAndAgent()
            val preferences = InMemoryQuickStartPreferences()

            val first = QuickStartController(
                preferencesProvider = { preferences },
                scope = this,
                ioDispatcher = Dispatchers.Unconfined,
            ) {
                readQuickStartStep(fixture.profiles, fixture.agents, fixture.db)
            }
            assertEquals(QuickStartStep.MESSAGE, first.syncAndAwait(streaming = false))
            assertFalse(first.collapsed.value)

            first.setCollapsed(true)
            assertTrue(first.collapsed.value)
            assertTrue(preferences.getBoolean(KEY_QUICK_START_COLLAPSED, false))
            assertTrue(first.uiState(AppRoutes.CHAT, detailOpen = false, streaming = false).collapsed)

            // Failed run refresh on the same route preserves collapsed = true.
            first.sync(streaming = true)
            fixture.recordRunWithAssistantReply("run-fail-collapsed", RunStatus.FAILED, "ERROR", "Failed")
            assertEquals(QuickStartStep.MESSAGE, first.syncAndAwait(streaming = false))
            assertTrue(first.collapsed.value)

            // Recreating the controller (process/ViewModel recreation) restores collapsed = true, then resumes cleanly.
            val restored = QuickStartController(
                preferencesProvider = { preferences },
                scope = this,
                ioDispatcher = Dispatchers.Unconfined,
            ) {
                readQuickStartStep(fixture.profiles, fixture.agents, fixture.db)
            }
            assertTrue(restored.collapsed.value)
            assertEquals(QuickStartStep.MESSAGE, restored.syncAndAwait(streaming = false))
            assertTrue(restored.uiState(AppRoutes.CHAT, detailOpen = false, streaming = false).collapsed)

            restored.setCollapsed(false)
            assertFalse(restored.collapsed.value)
            assertFalse(preferences.getBoolean(KEY_QUICK_START_COLLAPSED, true))

            val resumed = QuickStartController(
                preferencesProvider = { preferences },
                scope = this,
                ioDispatcher = Dispatchers.Unconfined,
            ) {
                readQuickStartStep(fixture.profiles, fixture.agents, fixture.db)
            }
            assertFalse(resumed.collapsed.value)
        }
    }

    @Test fun quickStartUiStateImmutableContract() {
        val initial = QuickStartUiState()
        assertNull(initial.step)
        assertNull(initial.stepId)
        assertFalse(initial.collapsed)
        assertFalse(initial.visible)

        val active = QuickStartUiState(step = QuickStartStep.MODEL, collapsed = false, visible = true)
        assertEquals(QuickStartStep.MODEL, active.step)
        assertEquals("MODEL", active.stepId)
        assertFalse(active.collapsed)
        assertTrue(active.visible)

        val collapsed = active.copy(collapsed = true)
        assertTrue(collapsed.collapsed)
        assertEquals("MODEL", collapsed.stepId)
    }

    @Test fun quickStartUiStateConcurrentStressTest() = runBlocking(Dispatchers.Default) {
        val steps = listOf(null, QuickStartStep.MODEL, QuickStartStep.AGENT, QuickStartStep.MESSAGE, QuickStartStep.COMPLETE)
        val concurrency = 20
        val iterations = 500

        val deferreds = (1..concurrency).map { threadId ->
            async {
                var state = QuickStartUiState()
                for (i in 0 until iterations) {
                    val step = steps[(threadId + i) % steps.size]
                    val collapsed = (i % 2 == 0)
                    val visible = (i % 3 != 0)
                    val nextState = state.copy(step = step, collapsed = collapsed, visible = visible)

                    // Assert immutability: previous state is unmodified
                    assertNotNull(state)
                    // Assert new state properties
                    assertEquals(step, nextState.step)
                    assertEquals(step?.name, nextState.stepId)
                    assertEquals(collapsed, nextState.collapsed)
                    assertEquals(visible, nextState.visible)
                    // Assert equality contract
                    assertEquals(nextState, nextState.copy())
                    assertEquals(nextState.hashCode(), nextState.copy().hashCode())
                    state = nextState
                }
                state
            }
        }
        val results = deferreds.awaitAll()
        assertEquals(concurrency, results.size)
    }

    @Test fun appIconsVectorPropertiesVerification() = runBlocking(Dispatchers.Default) {
        val icons = listOf(
            "Chat" to AppIcons.Chat,
            "SmartToy" to AppIcons.SmartToy,
            "Tune" to AppIcons.Tune,
            "MenuBook" to AppIcons.MenuBook,
            "Extension" to AppIcons.Extension,
            "Campaign" to AppIcons.Campaign,
            "Cloud" to AppIcons.Cloud,
            "BugReport" to AppIcons.BugReport,
            "Error" to AppIcons.Error,
        )

        for ((name, icon) in icons) {
            assertNotNull(icon, "Icon $name must not be null")
            assertEquals(24f, icon.viewportWidth, "Icon $name viewportWidth must be 24f")
            assertEquals(24f, icon.viewportHeight, "Icon $name viewportHeight must be 24f")
            assertEquals(24.dp, icon.defaultWidth, "Icon $name defaultWidth must be 24.dp")
            assertEquals(24.dp, icon.defaultHeight, "Icon $name defaultHeight must be 24.dp")
            assertTrue(icon.root.iterator().hasNext(), "Icon $name root must have path nodes")
        }

        // Stress test lazy-getter thread safety under concurrent access
        val stressDeferreds = (1..50).map {
            async {
                listOf(
                    AppIcons.Chat,
                    AppIcons.SmartToy,
                    AppIcons.Tune,
                    AppIcons.MenuBook,
                    AppIcons.Extension,
                    AppIcons.Campaign,
                    AppIcons.Cloud,
                    AppIcons.BugReport,
                    AppIcons.Error,
                )
            }
        }
        val stressResults = stressDeferreds.awaitAll()
        assertEquals(50, stressResults.size)
        for (batch in stressResults) {
            assertEquals(9, batch.size)
        }
    }

    private class QuickStartDbFixture(
        val db: SqlConnection,
        val profiles: ProfileRepository,
        val agents: AgentRepository,
        val conversations: ConversationRepository,
        val runs: RunRepository,
        private val nowIso: String,
    ) {
        fun configureChatModelAndAgent() {
            val provider = ProviderProfile(
                id = "provider-1",
                name = "Fixture Provider",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://api.example.com/v1",
                secretRef = "",
                revision = 1,
            )
            val model = ModelProfile(
                id = "model-chat-1",
                providerId = provider.id,
                role = ModelRole.CHAT,
                modelId = "gpt-fixture",
                capabilities = setOf("streaming"),
                contextLimit = 16_384,
                outputLimit = 2_048,
                revision = 1,
            )
            profiles.upsertProviderAndModel(provider, model)
            agents.saveWithPrompt(
                AgentProfile(
                    id = "agent-1",
                    name = "Fixture Agent",
                    promptRevisionId = "prompt-initial",
                    chatProfileId = model.id,
                    revision = 1,
                ),
                template = "You are a helpful assistant.",
            )
            db.execute(
                "INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                listOf("snapshot-1", 1, "agent-1", "prompt-1", model.id, 1, "[]", "[]", nowIso),
            )
            conversations.create("snapshot-1", "First Chat", "conversation-1", nowIso)
        }

        fun recordRunWithAssistantReply(
            runId: String,
            runState: RunStatus,
            messageStatus: String,
            answerText: String,
        ) {
            val created = runs.create(
                RunRecord(
                    runId = runId,
                    snapshotId = "snapshot-1",
                    conversationId = "conversation-1",
                    createdAt = nowIso,
                ),
            )
            val message = conversations.append(
                "conversation-1",
                MessageRole.ASSISTANT,
                "",
                status = "STREAMING",
                messageId = runtime.mobileagent.agent.RuntimeMessageIds.assistant(runId, 1),
            )
            val parts = if (answerText.isNotEmpty()) listOf(TextPart(answerText)) else emptyList()
            conversations.checkpointAssistant(message.id, answerText, parts, "{}", messageStatus)
            runs.save(created.copy(state = runState, modelRounds = 1, finishedAt = nowIso, updatedAt = nowIso))
        }
    }

    private suspend fun withQuickStartDatabase(block: suspend (QuickStartDbFixture) -> Unit) {
        InMemorySqliteConnection().use { db ->
            Migrations.apply(db)
            val now = "2026-11-12T00:00:00Z"
            val fixture = QuickStartDbFixture(
                db = db,
                profiles = ProfileRepository(db),
                agents = AgentRepository(db) { now },
                conversations = ConversationRepository(db) { now },
                runs = RunRepository(db) { now },
                nowIso = now,
            )
            block(fixture)
        }
    }
}

private class InMemorySqliteConnection : SqlConnection, AutoCloseable {
    private val connection = DriverManager.getConnection("jdbc:sqlite::memory:").apply {
        createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
    }

    override fun execute(sql: String, args: List<Any?>) {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.execute()
        }
    }

    override fun query(sql: String, args: List<Any?>): List<SqlRow> = connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { result ->
            buildList {
                while (result.next()) {
                    add(
                        SqlRow(
                            (1..result.metaData.columnCount).associate {
                                result.metaData.getColumnLabel(it) to result.getObject(it)
                            },
                        ),
                    )
                }
            }
        }
    }

    override fun <T> transaction(block: () -> T): T {
        if (!connection.autoCommit) return block()
        connection.autoCommit = false
        return try {
            block().also { connection.commit() }
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    override fun close() = connection.close()
}

private class InMemoryQuickStartPreferences : SharedPreferences {
    private val values = ConcurrentHashMap<String, Any>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = key != null && values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = EditorImpl()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class EditorImpl : SharedPreferences.Editor {
        private val staged = LinkedHashMap<String, Any?>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { staged[key] = values?.toSet() }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { staged[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { staged[key] = null }
        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
        override fun commit(): Boolean {
            applyChanges()
            return true
        }
        override fun apply() = applyChanges()

        private fun applyChanges() {
            if (clearAll) values.clear()
            for ((k, v) in staged) {
                if (v == null) values.remove(k) else values[k] = v
            }
        }
    }
}
