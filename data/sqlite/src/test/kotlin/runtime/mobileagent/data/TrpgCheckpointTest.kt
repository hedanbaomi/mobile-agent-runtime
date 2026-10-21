// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*
import runtime.mobileagent.domain.trpg.*

class TrpgCheckpointTest {
    private val at = "2026-10-21T00:00:00Z"

    @Test fun branchKeepsOldPrefixAndStateAfterLaterMessagesAndStateChanges() = withDatabase { env ->
        env.conversations.append("story", MessageRole.USER, "First", messageId = "m1", createdAt = at)
        env.conversations.append("story", MessageRole.ASSISTANT, "Second", messageId = "m2", parentMessageId = "m1", createdAt = at)
        env.trpg.update("story", 0, TrpgState(scene = "Hall"))
        val checkpoint = env.checkpoints.create("story", 1, "Hall")
        assertEquals(2L, checkpoint.revision)

        env.conversations.append("story", MessageRole.USER, "Third", messageId = "m3", parentMessageId = "m2", createdAt = at)
        env.trpg.update("story", 2, TrpgState(scene = "Dungeon"))

        val branch = env.checkpoints.branch("story", checkpoint.checkpointId, "Branch")
        val messages = env.conversations.messages(branch.id)
        assertEquals(listOf("First", "Second"), messages.map { it.text })
        assertEquals(2, messages.map { it.id }.distinct().size)
        assertTrue(messages.none { it.id in setOf("m1", "m2", "m3") })
        assertNull(messages.first().parentMessageId)
        assertEquals(messages.first().id, messages.last().parentMessageId)

        val session = env.trpg.get(branch.id)
        assertEquals(2L, session.revision)
        assertEquals("Hall", session.state.scene)
        // The source conversation is untouched and keeps its later message and state.
        assertEquals("Dungeon", env.trpg.get("story").state.scene)
        assertEquals(3, env.conversations.messages("story").size)
        assertEquals(2, env.conversations.list().size)
    }

    @Test fun branchRemapsFactAndStorySourcesAndRecordsItsOrigin() = withDatabase { env ->
        env.conversations.append("story", MessageRole.ASSISTANT, "The door is blue.", messageId = "source", createdAt = at)
        env.trpg.command("story", 0, "fact", TrpgCommand(TrpgOperation.UPSERT_FACT,
            fact = TrpgFact("fact", "Blue door", TrpgFactStatus.CONFIRMED, "source")))
        env.trpg.command("story", 1, "track", TrpgCommand(TrpgOperation.STORY,
            story = TrpgStoryCommand(TrpgStoryOperation.UPSERT_TRACK,
                track = TrpgTrack("clue", TrpgTrackKind.CLUE, "Blue door", sourceMessageId = "source"))))
        val checkpoint = env.checkpoints.create("story", 2, "Sources")
        val branch = env.checkpoints.branch("story", checkpoint.checkpointId, "Branch")

        val branchMessages = env.conversations.messages(branch.id)
        val newSource = branchMessages.single().id
        assertNotEquals("source", newSource)
        val session = env.trpg.get(branch.id)
        assertEquals(newSource, session.state.campaign.facts.single().sourceMessageId)
        assertEquals(newSource, session.state.campaign.story.tracks.single().sourceMessageId)
        assertEquals(TrpgBranchOrigin("story", checkpoint.checkpointId, checkpoint.revision), session.state.campaign.origin)

        // No source run, invocation or replay receipt becomes executable state in the branch.
        assertEquals(0, RunRepository(env.db).list(branch.id).size)
        assertEquals(0L, count(env.db, "trpg_run_receipts", "conversation_id=?", branch.id))
        assertEquals(0L, count(env.db, "tool_invocations",
            "run_id IN (SELECT run_id FROM runs WHERE conversation_id=?)", branch.id))
        assertEquals(0, env.trpg.events(branch.id).count { it.kind == TrpgEventKind.TOOL })
    }

    @Test fun checkpointKeepsOnlyAJournalBoundaryAndBranchCopiesThePrefix() = withDatabase { env ->
        env.conversations.append("story", MessageRole.USER, "First", messageId = "m1", createdAt = at)
        env.trpg.update("story", 0, TrpgState(scene = "Hall"))
        val checkpoint = env.checkpoints.create("story", 1, "Start")
        env.trpg.update("story", 2, TrpgState(scene = "Vault"))

        val entry = env.checkpoints.export("story").single()
        // The append-only journal lives once in the conversation entry, never inside the payload.
        assertFalse(entry.payloadJson.contains("\"events\""))
        assertFalse(entry.payloadJson.contains("Vault"))
        assertTrue(entry.payloadJson.length < 4096)

        val branch = env.checkpoints.branch("story", checkpoint.checkpointId, "Branch")
        val branchEvents = TrpgRepository(env.db).events(branch.id)
        assertEquals(2, branchEvents.size)
        // events() returns newest first; the prefix ends with the checkpoint's own event.
        assertEquals(TrpgEventKind.CHECKPOINT, branchEvents.first().kind)
        assertNull(TrpgRepository(env.db).undoableEventId(branch.id))
        assertEquals(3, TrpgRepository(env.db).events("story").size)

        // Tampering with the append-only prefix must fail the boundary digest and roll back.
        env.db.execute("UPDATE trpg_events SET event_json=replace(event_json,'State edited','State revised') " +
            "WHERE conversation_id='story' AND revision=1")
        assertThrows(AppException::class.java) { env.checkpoints.branch("story", checkpoint.checkpointId, "Tampered") }
        assertEquals(2, env.conversations.list().size)
    }

    @Test fun checkpointWriteRejectsActiveRunActiveCompactionStaleRevisionAndArchive() = withDatabase { env ->
        env.conversations.append("story", MessageRole.USER, "First", messageId = "m1", createdAt = at)
        env.trpg.update("story", 0, TrpgState(scene = "Hall"))

        env.conversations.append("story", MessageRole.ASSISTANT, "Pending", messageId = "m2",
            parentMessageId = "m1", status = "STREAMING", createdAt = at)
        assertThrows(AppException::class.java) { env.checkpoints.create("story", 1, "Mid-stream") }
        env.conversations.updateMessageStatus("m2", "COMPLETE")

        RunRepository(env.db).create(RunRecord("live", "snapshot", "story", RunStatus.MODEL_STREAMING, createdAt = at))
        assertThrows(AppException::class.java) { env.checkpoints.create("story", 1, "Blocked by run") }
        RunRepository(env.db).save(RunRecord("live", "snapshot", "story", RunStatus.COMPLETED, createdAt = at))

        env.db.execute(PREPARED_COMPACTION_SQL, listOf(
            "compact", "story", "snapshot", "live", "[\"m1\"]", "0".repeat(64), "input", "model",
            "model-fp", "auth-fp", "test", "PREPARED", null, null, 1, 0, 0, 0, 0, at, at,
        ))
        assertThrows(AppException::class.java) { env.checkpoints.create("story", 1, "Blocked by compaction") }
        env.db.execute("DELETE FROM context_compactions WHERE id='compact'")

        assertThrows(AppException::class.java) { env.checkpoints.create("story", 0, "Stale") }
        assertTrue(env.conversations.setArchived("story", true))
        assertThrows(AppException::class.java) { env.checkpoints.create("story", 1, "Archived") }
        assertTrue(env.conversations.setArchived("story", false))

        val checkpoint = env.checkpoints.create("story", 1, "Ok")
        assertEquals(2L, checkpoint.revision)
        assertEquals(1, env.checkpoints.list("story").size)
    }

    @Test fun sameCheckpointIdentityReturnsTheOriginalAndChangedArgumentsAreRejected() = withDatabase { env ->
        env.conversations.append("story", MessageRole.USER, "First", messageId = "m1", createdAt = at)
        env.trpg.update("story", 0, TrpgState(scene = "Hall"))
        val first = env.checkpoints.create("story", 1, "Save", checkpointId = "cp")
        val revision = env.trpg.get("story").revision
        assertEquals(first, env.checkpoints.create("story", 1, "Save", checkpointId = "cp"))
        assertEquals(revision, env.trpg.get("story").revision)
        assertEquals(1, env.checkpoints.list("story").size)

        assertThrows(AppException::class.java) { env.checkpoints.create("story", 1, "Other", checkpointId = "cp") }
        assertThrows(AppException::class.java) { env.checkpoints.create("story", 2, "Save", checkpointId = "cp") }
        assertEquals(1, env.checkpoints.list("story").size)
        assertEquals(2L, env.trpg.get("story").revision)
    }

    @Test fun checkpointCountAndConversationPayloadBudgetAreBounded() = withDatabase { env ->
        env.conversations.append("story", MessageRole.USER, "First", messageId = "m1", createdAt = at)
        env.trpg.update("story", 0, TrpgState(scene = "Hall"))
        env.checkpoints.create("story", 1, "One", checkpointId = "cp0")
        for (index in 1 until 32) {
            env.db.execute(
                "INSERT INTO trpg_checkpoints(conversation_id,checkpoint_id,name,created_at,payload_json) VALUES(?,?,?,?,?)",
                listOf("story", "filler$index", "Filler $index", at, "{}"),
            )
        }
        assertThrows(AppException::class.java) { env.checkpoints.create("story", 2, "Too many") }

        env.db.execute("DELETE FROM trpg_checkpoints WHERE checkpoint_id LIKE 'filler%'")
        val large = "x".repeat(2 * 1024 * 1024)
        for (index in 0 until 4) {
            env.db.execute(
                "INSERT INTO trpg_checkpoints(conversation_id,checkpoint_id,name,created_at,payload_json) VALUES(?,?,?,?,?)",
                listOf("story", "large$index", "Large $index", at, large),
            )
        }
        assertThrows(AppException::class.java) { env.checkpoints.create("story", 2, "Over budget") }
        assertEquals(5L, count(env.db, "trpg_checkpoints", "conversation_id=?", "story"))
        // The rejected write rolled its CHECKPOINT event back as well.
        assertEquals(2L, env.trpg.get("story").revision)
    }

    @Test fun oversizedSinglePayloadIsRejected() = withDatabase { env ->
        env.conversations.append("story", MessageRole.USER, "First", messageId = "m1", createdAt = at)
        env.trpg.update("story", 0, TrpgState(scene = "Hall"))
        env.db.execute(
            "INSERT INTO trpg_checkpoints(conversation_id,checkpoint_id,name,created_at,payload_json) VALUES(?,?,?,?,?)",
            listOf("story", "huge", "Huge", at, "x".repeat(2 * 1024 * 1024 + 1)),
        )
        assertThrows(AppException::class.java) { env.checkpoints.export("story") }
    }

    @Test fun missingImageFailsClosedAndABranchRollsBackCompletely() = withDatabase { env ->
        env.conversations.append("story", MessageRole.USER, "Picture", messageId = "m1", createdAt = at,
            parts = listOf(ImagePart("chat-upload:p1", "image/png", "hash1")))
        env.trpg.update("story", 0, TrpgState(scene = "Hall"))
        assertThrows(AppException::class.java) { env.checkpoints.create("story", 1, "No verifier") }
        assertTrue(env.checkpoints.list("story").isEmpty())
        assertEquals(1L, env.trpg.get("story").revision)

        val permissive = TrpgCheckpointRepository(env.db, clock = { at }, imageValidator = { _, _ -> })
        val checkpoint = permissive.create("story", 1, "With verifier")
        val failing = TrpgCheckpointRepository(env.db, clock = { at }, imageValidator = { _, _ ->
            throw IllegalStateException("image is missing")
        })
        assertThrows(AppException::class.java) { failing.branch("story", checkpoint.checkpointId, "Broken") }

        // Every write of the failed branch is gone: conversation, messages, run/state rows.
        assertEquals(1, env.conversations.list().size)
        assertEquals(1, env.conversations.messages("story").size)
        assertEquals(0, RunRepository(env.db).list().size)
        assertEquals(1, env.checkpoints.list("story").size)
        assertEquals(2L, env.trpg.get("story").revision)
    }

    @Test fun summaryChainIsMappedIntoTheBranchAndAForgedDigestIsRejected() = withDatabase { env ->
        env.conversations.append("story", MessageRole.USER, "First", messageId = "m1", createdAt = at)
        env.conversations.append("story", MessageRole.ASSISTANT, "Second", messageId = "m2", parentMessageId = "m1", createdAt = at)
        env.trpg.update("story", 0, TrpgState(scene = "Hall"))
        RunRepository(env.db).create(RunRecord("run", "snapshot", "story", RunStatus.MODEL_STREAMING, createdAt = at))
        val summaryJson = ContextSummary.canonicalize(
            """{"goals":["Find the door"],"constraints":[],"decisions":[],"pending":[],"results":[]}""",
        )
        val compactions = ContextCompactionRepository(env.db, clock = { at })
        compactions.create(ContextCompactionRecord(
            id = "sum", conversationId = "story", snapshotId = "snapshot", runId = "run",
            sourceMessageIds = listOf("m1", "m2"), inputHash = "input", modelId = "model",
            modelFingerprint = "model-fp", authorizationFingerprint = "auth-fp", reason = "Length",
            state = ContextCompactionState.PREPARED,
        ))
        compactions.transition("sum", ContextCompactionState.DISPATCHED)
        compactions.transition("sum", ContextCompactionState.SUCCEEDED, summaryJson = summaryJson,
            afterUnits = 10, inputTokens = 5, outputTokens = 3)
        RunRepository(env.db).save(RunRecord("run", "snapshot", "story", RunStatus.COMPLETED, createdAt = at))

        val checkpoint = env.checkpoints.create("story", 1, "With summary")
        val branch = env.checkpoints.branch("story", checkpoint.checkpointId, "Branch")
        val branchMessages = env.conversations.messages(branch.id)
        val copied = compactions.list(branch.id).single()
        assertEquals(branchMessages.map { it.id }, copied.sourceMessageIds)
        assertEquals(summaryJson, copied.summaryJson)
        assertEquals("model-fp", copied.modelFingerprint)
        assertEquals("auth-fp", copied.authorizationFingerprint)
        assertTrue(compactions.validateSources(copied))
        assertEquals(copied.id, compactions.latestSucceeded(branch.id, "snapshot", "model-fp", "auth-fp")?.id)
        assertNotEquals("run", copied.runId)

        // The FK-required run is an explicit, terminal historical record with no spend or tool call.
        val synthetic = RunRepository(env.db).get(copied.runId)!!
        assertEquals(RunStatus.COMPLETED, synthetic.state)
        assertEquals("historical-branch", synthetic.stopReason)
        assertEquals(0, synthetic.toolCalls)
        assertEquals(0, synthetic.inputTokens)
        assertEquals(0, RunRepository(env.db).invocations(copied.runId).size)
        assertTrue(RunRepository(env.db).list(branch.id).all { it.state == RunStatus.COMPLETED })

        // Forging the stored source digest must refuse the branch and leave no half-copied state.
        env.db.execute("UPDATE context_compactions SET source_content_hash=? WHERE id='sum'", listOf("0".repeat(64)))
        assertThrows(AppException::class.java) { env.checkpoints.branch("story", checkpoint.checkpointId, "Forged") }
        assertEquals(2, env.conversations.list().size)
    }

    @Test fun exportRestoreRoundTripAndConversationDeleteCascades() {
        openEnvironment().use { first ->
            first.conversations.append("story", MessageRole.USER, "First", messageId = "m1", createdAt = at)
            first.trpg.update("story", 0, TrpgState(scene = "Hall"))
            val checkpoint = first.checkpoints.create("story", 1, "Save")
            val entries = first.checkpoints.export("story")
            assertEquals(1, entries.size)

            openEnvironment().use { second ->
                second.conversations.append("story", MessageRole.USER, "First", messageId = "m1", createdAt = at)
                val session = TrpgSession("story", 2, TrpgState(scene = "Hall"))
                second.trpg.restore(session)
                second.trpg.restoreJournal(session, first.trpg.exportJournal("story"))
                second.checkpoints.restore("story", entries)
                assertEquals(first.checkpoints.list("story"), second.checkpoints.list("story"))

                val branch = second.checkpoints.branch("story", checkpoint.checkpointId, "Branch")
                assertEquals(listOf("First"), second.conversations.messages(branch.id).map { it.text })
                assertEquals("Hall", second.trpg.get(branch.id).state.scene)
                assertEquals(2L, second.trpg.get(branch.id).revision)
            }

            assertTrue(first.conversations.delete("story"))
            assertEquals(0L, first.db.query("SELECT COUNT(*) AS n FROM trpg_checkpoints").single().long("n"))
        }
    }

    @Test fun restoreRejectsForeignOversizedDuplicateAndNewerEntries() {
        openEnvironment().use { first ->
            first.conversations.append("story", MessageRole.USER, "First", messageId = "m1", createdAt = at)
            first.trpg.update("story", 0, TrpgState(scene = "Hall"))
            val checkpoint = first.checkpoints.create("story", 1, "Save")
            val entries = first.checkpoints.export("story")
            first.trpg.update("story", 2, TrpgState(scene = "Vault"))
            first.checkpoints.create("story", 3, "Later")
            val laterEntries = first.checkpoints.export("story")

            openEnvironment().use { second ->
                second.conversations.append("story", MessageRole.USER, "First", messageId = "m1", createdAt = at)
                val session = TrpgSession("story", 2, TrpgState(scene = "Hall"))
                second.trpg.restore(session)
                second.trpg.restoreJournal(session, first.trpg.exportJournal("story").let { journal ->
                    TrpgJournal(journal.events.filter { it.revision <= 2 })
                })

                assertThrows(AppException::class.java) {
                    second.checkpoints.restore("story", listOf(entries.single().copy(conversationId = "other")))
                }
                assertThrows(AppException::class.java) {
                    second.checkpoints.restore("story", listOf(entries.single().copy(payloadJson = "x".repeat(2 * 1024 * 1024 + 1))))
                }
                assertThrows(AppException::class.java) {
                    second.checkpoints.restore("story", List(33) { entries.single().copy(checkpointId = "cp$it") })
                }
                assertThrows(AppException::class.java) {
                    second.checkpoints.restore("story", listOf(entries.single().copy(revision = 99)))
                }
                assertThrows(AppException::class.java) {
                    second.checkpoints.restore("story", listOf(laterEntries.last()))
                }

                second.checkpoints.restore("story", entries)
                assertEquals(1, second.checkpoints.list("story").size)
                assertEquals(checkpoint.checkpointId, second.checkpoints.list("story").single().checkpointId)
                assertThrows(AppException::class.java) { second.checkpoints.restore("story", entries) }
            }
        }
    }

    @Test fun restoreRequiresTheJournalBoundaryAndSourcesInsideThePrefix() {
        openEnvironment().use { first ->
            first.conversations.append("story", MessageRole.USER, "First", messageId = "m1", createdAt = at)
            first.trpg.command("story", 0, "fact", TrpgCommand(TrpgOperation.UPSERT_FACT,
                fact = TrpgFact("fact", "Blue door", TrpgFactStatus.CONFIRMED, "m1")))
            first.checkpoints.create("story", 1, "Save")
            val entry = first.checkpoints.export("story").single()

            openEnvironment().use { second ->
                second.conversations.append("story", MessageRole.USER, "First", messageId = "m1", createdAt = at)
                val session = TrpgSession("story", 2, TrpgState())
                second.trpg.restore(session)
                // The transfer wiring restores state and journal before checkpoint restore; a
                // checkpoint cannot verify its boundary without the conversation journal.
                assertThrows(AppException::class.java) { second.checkpoints.restore("story", listOf(entry)) }

                second.trpg.restoreJournal(session, first.trpg.exportJournal("story").let { TrpgJournal(it.events) })
                var tampered = entry.copy(payloadJson = entry.payloadJson.replace(
                    "\"sourceMessageId\":\"m1\"", "\"sourceMessageId\":\"outside\"",
                ))
                assertThrows(AppException::class.java) { second.checkpoints.restore("story", listOf(tampered)) }

                tampered = entry.copy(payloadJson = entry.payloadJson.replace("\"eventCount\":2", "\"eventCount\":1"))
                assertThrows(AppException::class.java) { second.checkpoints.restore("story", listOf(tampered)) }

                second.checkpoints.restore("story", emptyList())
                second.checkpoints.restore("story", listOf(entry))
                assertEquals(1, second.checkpoints.list("story").size)
            }
        }
    }

    private class Env(
        val db: JdbcSqlConnection,
        val conversations: ConversationRepository,
        val trpg: TrpgRepository,
        val checkpoints: TrpgCheckpointRepository,
    ) : AutoCloseable {
        override fun close() = db.close()
    }

    private fun withDatabase(block: (Env) -> Unit) = openEnvironment().use(block)

    private fun openEnvironment(): Env {
        val db = JdbcSqlConnection()
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
        return Env(db, conversations, TrpgRepository(db, clock = { at }), TrpgCheckpointRepository(db, clock = { at }))
    }

    private fun count(db: SqlConnection, table: String, where: String, argument: String): Long =
        db.query("SELECT COUNT(*) AS n FROM $table WHERE $where", listOf(argument)).single().long("n")

    private companion object {
        const val PREPARED_COMPACTION_SQL =
            "INSERT INTO context_compactions(id,conversation_id,snapshot_id,run_id,source_message_ids_json,source_content_hash,input_hash,model_id,model_fingerprint,authorization_fingerprint,reason,state,summary_json,parent_id,version,before_units,after_units,input_tokens,output_tokens,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
    }
}
