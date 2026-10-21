// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*
import runtime.mobileagent.domain.trpg.*
import runtime.mobileagent.serialization.*

class TrpgJournalTest {
    private val at = "2026-10-21T00:00:00Z"

    @Test fun manualActionsAreIdempotentAndChangedArgumentsConflict() = fixture { db, repo ->
        val state = TrpgState(resources = listOf(TrpgResource("HP", 10, 12)))
        val first = repo.update("story", 0, state, "edit-1")
        assertEquals(first, repo.update("story", 0, state, "edit-1"))
        assertEquals(1, repo.events("story").size)
        assertThrows(AppException::class.java) { repo.update("story", 0, state.copy(scene = "changed"), "edit-1") }
        assertEquals(first, repo.get("story"))
        assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
    }

    @Test fun undoIsCompensationAndNeverErasesOrRerollsDice() = fixture { _, repo ->
        val original = TrpgState(resources = listOf(TrpgResource("HP", 10, 12)))
        repo.update("story", 0, original, "setup")
        repo.update("story", 1, original.copy(resources = listOf(TrpgResource("HP", 6, 12))), "damage")
        val target = repo.undoableEventId("story")!!
        val rolled = repo.recordRoll("story", 2, "roll", "1d20 = 17", "d20:17")
        assertEquals(rolled, repo.recordRoll("story", 2, "roll", "1d20 = 17", "d20:17"))
        assertThrows(AppException::class.java) { repo.recordRoll("story", 2, "roll", "1d20 = 18", "d20:18") }
        assertEquals(target, repo.undoableEventId("story"))
        val undone = repo.undo("story", 3, target, "undo")
        assertEquals(original, undone.state)
        assertEquals(4, undone.revision)
        assertEquals(undone, repo.undo("story", 3, target, "undo"))
        assertNull(repo.undoableEventId("story"))
        assertThrows(AppException::class.java) { repo.undo("story", 4, target, "another-undo") }
        assertEquals(1, repo.events("story").count { it.kind == TrpgEventKind.ROLL })
        assertEquals("1d20 = 17", repo.events("story").single { it.kind == TrpgEventKind.ROLL }.title)
        assertEquals(target, repo.events("story").first().targetId)
    }

    @Test fun oldTargetsAndConcurrentEditsCannotBeUndone() = fixture { _, repo ->
        repo.update("story", 0, TrpgState(scene = "A"))
        val old = repo.undoableEventId("story")!!
        repo.update("story", 1, TrpgState(scene = "B"))
        val latest = repo.undoableEventId("story")!!
        assertThrows(AppException::class.java) { repo.undo("story", 2, old, "old") }
        assertThrows(AppException::class.java) { repo.undo("story", 1, latest, "stale") }
        assertEquals("B", repo.get("story").state.scene)
    }

    @Test fun savingAnUnchangedSheetKeepsTheLatestUndoableChange() = fixture { _, repo ->
        val state = TrpgState(scene = "A")
        repo.update("story", 0, state)
        val eventId = repo.undoableEventId("story")!!
        repo.update("story", 1, state)
        assertEquals(eventId, repo.undoableEventId("story"))
        assertEquals(TrpgState(), repo.undo("story", 2, eventId, "undo").state)
    }

    @Test fun eventFailureRollsBackStateAndUndoSlot() = fixture { db, repo ->
        repo.update("story", 0, TrpgState(scene = "A"))
        val before = repo.exportJournal("story")
        val failing = object : SqlConnection by db {
            override fun execute(sql: String, args: List<Any?>) {
                if (sql.startsWith("INSERT INTO trpg_events")) error("Injected event failure")
                db.execute(sql, args)
            }
        }
        assertThrows(IllegalStateException::class.java) { TrpgRepository(failing).update("story", 1, TrpgState(scene = "B")) }
        assertEquals("A", repo.get("story").state.scene)
        assertEquals(1, repo.get("story").revision)
        assertEquals(before, repo.exportJournal("story"))
    }

    @Test fun toolReplayAddsOneEventAndManualMutationsRejectActiveOrArchivedRuns() = fixture { db, repo ->
        val runs = RunRepository(db)
        runs.create(RunRecord("run", "snapshot", "story", RunStatus.TOOL_EXECUTING, createdAt = at))
        val result = repo.updateFromRun("story", "snapshot", "run", "call", "{}", 0, TrpgState(scene = "A"))
        assertEquals(result, repo.updateFromRun("story", "snapshot", "run", "call", "{}", 0, TrpgState(scene = "A")))
        assertEquals(1, repo.events("story").size)
        assertEquals("run", repo.events("story").single().runId)
        assertThrows(AppException::class.java) { repo.recordRoll("story", 1, "roll", "1d20 = 2", "2") }
        assertThrows(AppException::class.java) { repo.undo("story", 1, repo.undoableEventId("story")!!, "undo") }
        runs.save(runs.get("run")!!.copy(state = RunStatus.COMPLETED))
        ConversationRepository(db).setArchived("story", true)
        assertThrows(AppException::class.java) { repo.recordRoll("story", 1, "roll", "1d20 = 2", "2") }
        assertEquals(1, repo.events("story").size)
    }

    @Test fun archiveStreamsHistoryAndRestoresUndoWithoutExecutableReceipts() = fixture { db, repo ->
        repo.update("story", 0, TrpgState(scene = "Inn"), "edit")
        repo.recordRoll("story", 1, "roll", "1d20 = 9", "d20:9")
        val archive = ByteArrayOutputStream()
        TransferRepository(db).exportArchive("agent", TransferOptions(includeConversations = true), archive)
        val contents = mutableMapOf<String, String>()
        ZipInputStream(archive.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                contents[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        val manifest = contents.entries.single { it.key.endsWith("manifest.json") }.value
        assertFalse(manifest.contains("\"trpgJournal\":"))
        assertTrue(manifest.contains("trpgJournalRevision"))
        assertTrue(contents.entries.single { it.key.startsWith("conversations/") }.value.contains("1d20 = 9"))
        JdbcSqlConnection().use { target ->
            Migrations.apply(target)
            TransferRepository(target).importArchive(archive.toByteArray())
            val restored = TrpgRepository(target)
            assertEquals(repo.get("story"), restored.get("story"))
            assertEquals(repo.exportJournal("story"), restored.exportJournal("story"))
            assertEquals(TrpgState(), restored.undo("story", 2, restored.undoableEventId("story")!!, "undo").state)
            assertTrue(target.query("SELECT * FROM trpg_run_receipts").isEmpty())
        }
    }

    @Test fun version38UpgradePreservesStateAndStartsJournalAtNextRevision() = fixture { db, repo ->
        repo.update("story", 0, TrpgState(scene = "Legacy"))
        db.execute("DROP TABLE trpg_events")
        db.execute("DROP TABLE trpg_undo")
        db.execute("DROP TABLE trpg_checkpoints")
        db.execute("UPDATE schema_version SET version=38")
        Migrations.apply(db)
        assertEquals("Legacy", repo.get("story").state.scene)
        assertTrue(repo.events("story").isEmpty())
        assertNull(repo.undoableEventId("story"))
        repo.update("story", 1, TrpgState(scene = "Continued"))
        assertEquals(2, repo.events("story").single().revision)
        assertTrue(ConversationRepository(db).delete("story"))
        assertTrue(db.query("SELECT * FROM trpg_events").isEmpty())
        assertTrue(db.query("SELECT * FROM trpg_undo").isEmpty())
    }

    private fun fixture(block: (SqlConnection, TrpgRepository) -> Unit) = JdbcSqlConnection().use { db ->
        Migrations.apply(db)
        val profiles = ProfileRepository(db)
        profiles.createProvider(ProviderProfile("provider", "Fixture", ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1", secretRef = "synthetic", revision = 1))
        profiles.createModel(ModelProfile("model", "provider", ModelRole.CHAT, "synthetic", setOf("stream"), contextLimit = 4096, outputLimit = 512, revision = 1))
        val agents = AgentRepository(db)
        agents.saveWithPrompt(AgentProfile("agent", "Fixture", "pending", "model", revision = 0, mode = AgentMode.TRPG), "Fixture")
        ConversationRepository(db).create(agents.createSnapshot("agent", "snapshot").id, "Story", "story")
        block(db, TrpgRepository(db) { at })
    }
}
