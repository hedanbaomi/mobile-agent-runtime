// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.storage

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import runtime.mobileagent.MobileAgentApp
import runtime.mobileagent.TrpgSessionToolExecutor
import runtime.mobileagent.agent.AgentRun
import runtime.mobileagent.agent.RunState
import runtime.mobileagent.boundBuiltinTools
import runtime.mobileagent.data.*
import runtime.mobileagent.domain.*
import runtime.mobileagent.domain.trpg.*
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult

/** Android bundled-SQLite and Host tool boundaries only; no frontend acceptance claim. */
class TrpgBackendDeviceTest {
    private val at = "2026-10-20T00:00:00Z"
    private fun file() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "trpg-${UUID.randomUUID()}.db")
    private fun fixture(db: SqlConnection): AgentRepository {
        Migrations.apply(db)
        val profiles = ProfileRepository(db)
        profiles.createProvider(ProviderProfile("p", "fixture", ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1", secretRef = "synthetic", revision = 1))
        profiles.createModel(ModelProfile("m", "p", ModelRole.CHAT, "fixture", setOf("stream", "tools"), contextLimit = 4096, outputLimit = 512, revision = 1))
        val agents = AgentRepository(db)
        agents.saveWithPrompt(AgentProfile("a", "host", "pending", "m", revision = 0, mode = AgentMode.TRPG), "Host")
        ConversationRepository(db).create(agents.createSnapshot("a", "s").id, "Campaign", "c")
        return agents
    }

    @Test fun modeAndStateSurviveReopenAndCannotBecomeNormal() {
        val path = file()
        BundledSqliteConnection(path.absolutePath).use { db ->
            val agents = fixture(db)
            TrpgRepository(db).update("c", 0, TrpgState(ruleset = TrpgRuleset.COC_7, scene = "图书馆", resources = listOf(TrpgResource("SAN", 58, 99))))
            try { agents.saveWithPrompt(agents.get("a")!!.copy(mode = AgentMode.NORMAL), "Changed"); fail("Mode changed") }
            catch (_: AppException) { }
            assertEquals(AgentMode.TRPG, agents.resolveSnapshot("s").snapshot.mode)
        }
        BundledSqliteConnection(path.absolutePath).use { db ->
            Migrations.apply(db)
            assertEquals(Migrations.VERSION.toLong(), db.query("SELECT version FROM schema_version").single().long("version"))
            val session = TrpgRepository(db).get("c")
            assertEquals(1L, session.revision)
            assertEquals("图书馆", session.state.scene)
            assertEquals(58, session.state.resources.single().current)
            assertTrue(ConversationRepository(db).delete("c"))
            assertTrue(db.query("SELECT * FROM trpg_sessions").isEmpty())
        }
    }

    @Test fun schema38UpgradePreservesExistingStateAndAddsEmptyHistoryStores() {
        val path = file()
        BundledSqliteConnection(path.absolutePath).use { db ->
            fixture(db)
            ConversationRepository(db).append("c", MessageRole.USER, "Legacy transcript", messageId = "legacy", createdAt = at)
            TrpgRepository(db).update("c", 0, TrpgState(scene = "Legacy room"))
            db.execute("DROP TABLE trpg_checkpoints")
            db.execute("DROP TABLE trpg_events")
            db.execute("DROP TABLE trpg_undo")
            db.execute("UPDATE schema_version SET version=38")
        }
        BundledSqliteConnection(path.absolutePath).use { db ->
            Migrations.apply(db)
            val repo = TrpgRepository(db)
            assertEquals(1L, repo.get("c").revision)
            assertEquals("Legacy room", repo.get("c").state.scene)
            assertEquals("Legacy transcript", ConversationRepository(db).message("legacy")!!.text)
            assertTrue(repo.events("c").isEmpty())
            assertTrue(TrpgCheckpointRepository(db).list("c").isEmpty())
            assertNull(repo.undoableEventId("c"))
            repo.update("c", 1, repo.get("c").state.copy(scene = "Continued"))
            assertEquals(2L, repo.events("c").single().revision)
            assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test fun runUpdateIsDurableIdempotentAndCancellationRejectsReplay() = runBlocking<Unit> {
        BundledSqliteConnection(file().absolutePath).use { db ->
            val agents = fixture(db)
            RunRepository(db).create(RunRecord("r", "s", "c", RunStatus.TOOL_EXECUTING, "{}", createdAt = at))
            val run = AgentRun("r", "s", "c")
            val repository = TrpgRepository(db)
            val executor = TrpgSessionToolExecutor(agents.getSnapshot("s")!!, run, { repository.get("c") },
                { call, revision, state -> repository.updateFromRun("c", "s", "r", call.callId, call.argumentsJson, revision, state) }, { true })
            val call = ToolCall("host-invocation", "session_state_update", """{"expectedRevision":0,"state":{"ruleset":"DND_3R","playerName":"玩家","characterSheet":"战士","scene":"营地","notes":"已受到伤害","resources":[{"name":"HP","current":7,"maximum":10}]}}""")
            val result = executor.invoke(call)
            assertTrue(result is ToolResult.Value)
            assertEquals(result, executor.invoke(call))
            assertEquals(1L, repository.get("c").revision)
            assertEquals(1, db.query("SELECT * FROM trpg_run_receipts").size)
            run.state = RunState.CANCELLED
            db.execute("UPDATE runs SET state='CANCELLED' WHERE run_id='r'")
            assertFalse(executor.authorizeReplay(call))
            assertTrue(executor.invoke(call) is ToolResult.Denied)
            assertEquals(7, repository.get("c").state.resources.single().current)
        }
    }

    @Test fun normalAgentDoesNotExposeOrInvokeDiceAndTrpgDoes() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val snapshot = AgentSnapshot("snapshot-fixture", 1, "agent-fixture", "prompt", "model", 1, emptyList(), emptyList(), at)
        val normal = boundBuiltinTools(app.container, snapshot)
        assertFalse(normal.specs.any { it.name == "dice_roll" })
        assertTrue(normal.specs.any { it.name == "random_number" })
        assertTrue(normal.invoke(ToolCall("test", "dice_roll", "{\"expression\":\"8d6\"}")) is ToolResult.Invalid)
        assertTrue(boundBuiltinTools(app.container, snapshot.copy(mode = AgentMode.TRPG)).specs.any { it.name == "dice_roll" })
    }

    @Test fun schema37RepairsLegacyOffsetKeysWithoutChangingMessagesOrInvalidValues() {
        BundledSqliteConnection(file().absolutePath).use { db ->
            fixture(db)
            val conversations = ConversationRepository(db)
            conversations.create("s", "Invalid history", "invalid")
            repeat(131) { conversations.append("invalid", MessageRole.USER, "legacy $it", createdAt = "legacy-invalid-$it") }
            val times = listOf("2026-10-13T00:00:00.100Z", "2026-10-13T08:00:00.1+08:00")
            val inserted = (0..120).map { conversations.append("c", MessageRole.USER, "tied $it", createdAt = times[it % 2]) }
            db.execute("UPDATE messages SET chronology_key='1'")
            db.execute("UPDATE schema_version SET version=37")
            val sql = "SELECT rowid,id,created_at,text,parts_json,metadata_json,chronology_ordinal FROM messages ORDER BY rowid"
            val original = db.query(sql)
            Migrations.apply(db)
            assertEquals(original, db.query(sql))
            assertEquals(131, db.query("SELECT id FROM messages WHERE chronology_key='1'").size)
            val first = conversations.messagePage("c")
            assertEquals(inserted.takeLast(50), first.messages)
            assertEquals(inserted.subList(21, 71), conversations.messagePage("c", first.nextBeforeMessageId).messages)
            assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
        }
    }
}
