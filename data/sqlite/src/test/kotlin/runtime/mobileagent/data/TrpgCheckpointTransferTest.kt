// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.io.ByteArrayOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*
import runtime.mobileagent.domain.trpg.*
import runtime.mobileagent.domain.worldbook.*
import runtime.mobileagent.serialization.*

class TrpgCheckpointTransferTest {
    private val at = "2026-10-21T00:00:00Z"

    @Test fun restoreBudgetsTheReencodedPayloadAndRollsBackEveryCheckpointOnOverflow() = fixture { source ->
        val checkpoints = TrpgCheckpointRepository(source)
        checkpoints.create("story", 0, "Boundary", "cp")
        val entry = checkpoints.export("story").single()
        val codec = TrpgCheckpointPayloadCodec()
        val initial = codec.decode(entry.payloadJson)
        val summaryText = ContextSummary.canonicalize("""{"goals":["Continue"],"constraints":[],"decisions":[],"pending":[],"results":[]}""")
        val prototype = ContextCompactionRecord("sum", "story", "snapshot", "run", listOf("m1"),
            "input", "model", "m".repeat(2048), "a".repeat(2048), "Length", ContextCompactionState.SUCCEEDED,
            summaryJson = summaryText, createdAt = at, updatedAt = at,
            sourceContentHash = ContextCompactionRepository(source).sourceContentHash("story", listOf("m1")))
        // Ten compact incoming payloads fit, but defaults restored by the strict codec cross 8 MiB.
        val perEntryBudget = 8 * 1024 * 1024 / 10
        val summaries = mutableListOf<ContextCompactionRecord>()
        var full: String
        do {
            val index = summaries.size
            summaries += prototype.copy(id = "s$index", parentId = if (index == 0) null else "s${index - 1}")
            full = codec.encode(initial.copy(summaries = summaries))
        } while (full.toByteArray().size <= perEntryBudget)
        val compact = Json { encodeDefaults = false; explicitNulls = false }.encodeToString(initial.copy(summaries = summaries))
        assertTrue(compact.toByteArray().size * 10L <= 8 * 1024 * 1024)
        assertTrue(full.toByteArray().size * 10L > 8 * 1024 * 1024)
        fixture { target ->
            val session = TrpgRepository(source).get("story")
            TrpgRepository(target).restore(session)
            TrpgRepository(target).restoreJournal(session, TrpgRepository(source).exportJournal("story"))
            assertThrows(AppException::class.java) {
                TrpgCheckpointRepository(target).restore("story", List(10) { entry.copy(checkpointId = "cp$it", payloadJson = compact) })
            }
            assertTrue(TrpgCheckpointRepository(target).list("story").isEmpty())
            assertEquals(session, TrpgRepository(target).get("story"))
        }
    }

    @Test fun archiveIncludesBooksReferencedOnlyByHistoricalCheckpoints() = fixture { source ->
        val books = WorldBookRepository(source)
        books.save(WorldBookDocument("Old book", listOf(WorldBookEntry("0", listOf("old"), "Old lore"))), "a")
        books.save(WorldBookDocument("New book", listOf(WorldBookEntry("0", listOf("new"), "New lore"))), "b")
        val initial = books.conversationConfig("story")
        books.saveConversationConfig(initial.copy(bookIds = listOf("a")), initial.revision)
        val checkpoint = TrpgCheckpointRepository(source).create("story", 0, "Old book", "cp")
        val changed = books.conversationConfig("story")
        books.saveConversationConfig(changed.copy(bookIds = listOf("b")), changed.revision)
        roundTrip(source) { target ->
            assertNotNull(WorldBookRepository(target).get("a"))
            val branch = TrpgCheckpointRepository(target).branch("story", checkpoint.checkpointId, "Restored branch")
            assertEquals(listOf("a"), WorldBookRepository(target).conversationConfig(branch.id).bookIds)
            assertEquals(listOf("b"), WorldBookRepository(target).conversationConfig("story").bookIds)
        }
    }

    @Test fun legacyTextWithSummaryRemainsBranchableAfterActualArchiveRoundTrip() = fixture { source ->
        source.execute("UPDATE messages SET parts_json='[]' WHERE id='m1'")
        summary(source, "sum")
        val cp = TrpgCheckpointRepository(source).create("story", 0, "Legacy", "cp")
        val beforeHash = ContextCompactionRepository(source).get("sum")!!.sourceContentHash
        roundTrip(source) { target ->
            assertNotEquals("[]", target.query("SELECT parts_json FROM messages WHERE id='m1'").single().string("parts_json"))
            val entry = TrpgCheckpointRepository(target).export("story").single()
            val restored = TrpgCheckpointPayloadCodec().decode(entry.payloadJson).summaries.single()
            assertNotEquals(beforeHash, restored.sourceContentHash)
            val branch = TrpgCheckpointRepository(target).branch("story", cp.checkpointId, "Restored branch")
            val copied = ContextCompactionRepository(target).list(branch.id).single()
            assertTrue(ContextCompactionRepository(target).validateSources(copied))
            assertEquals("model-fp", copied.modelFingerprint)
            assertEquals(listOf("Legacy text"), ConversationRepository(target).messages(branch.id).map { it.text })
            target.execute("UPDATE messages SET text='Tampered' WHERE id='m1'")
            assertThrows(AppException::class.java) { TrpgCheckpointRepository(target).branch("story", cp.checkpointId, "Reject") }
        }
    }

    @Test fun restoreRejectsSelfForwardResetAndCyclicParentsAndBlankProvenance() = fixture { source ->
        summary(source, "one")
        summary(source, "two", "one")
        val checkpoints = TrpgCheckpointRepository(source)
        checkpoints.create("story", 0, "Chain", "cp")
        val entry = checkpoints.export("story").single()
        val codec = TrpgCheckpointPayloadCodec()
        val payload = codec.decode(entry.payloadJson)
        val one = payload.summaries[0]
        val two = payload.summaries[1]
        val invalid = listOf(
            listOf(one.copy(parentId = "one"), two),
            listOf(one.copy(parentId = "two"), two),
            listOf(one, two.copy(parentId = null)),
            listOf(one.copy(parentId = "two"), two.copy(parentId = "one")),
            listOf(one.copy(modelFingerprint = ""), two),
            listOf(one, two.copy(authorizationFingerprint = "")),
        )
        fixture { target ->
            val session = TrpgRepository(source).get("story")
            TrpgRepository(target).restore(session)
            TrpgRepository(target).restoreJournal(session, TrpgRepository(source).exportJournal("story"))
            invalid.forEach { records ->
                assertThrows(AppException::class.java) {
                    TrpgCheckpointRepository(target).restore("story", listOf(entry.copy(payloadJson = codec.encode(payload.copy(summaries = records)))))
                }
                assertTrue(TrpgCheckpointRepository(target).list("story").isEmpty())
            }
            TrpgCheckpointRepository(target).restore("story", listOf(entry))
            val branch = TrpgCheckpointRepository(target).branch("story", "cp", "Valid")
            val copied = ContextCompactionRepository(target).list(branch.id)
            assertNull(copied.first().parentId)
            assertEquals(copied.first().id, copied.last().parentId)
        }
    }

    private fun summary(db: SqlConnection, id: String, parent: String? = null) {
        val runs = RunRepository(db)
        runs.create(RunRecord("run-$id", "snapshot", "story", RunStatus.MODEL_STREAMING, createdAt = at))
        val compactions = ContextCompactionRepository(db)
        compactions.create(ContextCompactionRecord(id, "story", "snapshot", "run-$id", listOf("m1"),
            "input", "model", "model-fp", "auth-fp", "Length", parentId = parent))
        compactions.transition(id, ContextCompactionState.DISPATCHED)
        compactions.transition(id, ContextCompactionState.SUCCEEDED, summaryJson = ContextSummary.canonicalize(
            """{"goals":["Continue"],"constraints":[],"decisions":[],"pending":[],"results":[]}"""))
        runs.save(runs.get("run-$id")!!.copy(state = RunStatus.COMPLETED))
    }

    private fun roundTrip(source: SqlConnection, block: (JdbcSqlConnection) -> Unit) {
        val archive = ByteArrayOutputStream()
        TransferRepository(source).exportArchive("agent", TransferOptions(includeConversations = true, includeWorldBookContent = true), archive)
        JdbcSqlConnection().use { target ->
            Migrations.apply(target)
            TransferRepository(target).importArchive(archive.toByteArray())
            block(target)
        }
    }

    private fun fixture(block: (JdbcSqlConnection) -> Unit) = JdbcSqlConnection().use { db ->
        Migrations.apply(db)
        val profiles = ProfileRepository(db)
        profiles.createProvider(ProviderProfile("provider", "Fixture", ApiFormat.OPENAI_COMPATIBLE,
            "https://example.invalid/v1", secretRef = "synthetic", revision = 1))
        profiles.createModel(ModelProfile("model", "provider", ModelRole.CHAT, "synthetic", setOf("stream"),
            contextLimit = 4096, outputLimit = 512, revision = 1))
        val agents = AgentRepository(db)
        agents.saveWithPrompt(AgentProfile("agent", "Fixture", "pending", "model", revision = 0, mode = AgentMode.TRPG), "Fixture")
        agents.createSnapshot("agent", "snapshot")
        val conversations = ConversationRepository(db, clock = { at })
        conversations.create("snapshot", "Story", "story")
        conversations.append("story", MessageRole.USER, "Legacy text", messageId = "m1", createdAt = at)
        block(db)
    }
}
