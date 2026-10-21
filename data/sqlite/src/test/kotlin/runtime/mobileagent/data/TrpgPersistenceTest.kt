// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.io.ByteArrayOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*
import runtime.mobileagent.domain.trpg.*
import runtime.mobileagent.serialization.*

class TrpgPersistenceTest {
    private val at = "2026-10-20T00:00:00Z"

    @Test fun modePersistsAndCannotChangeThroughAnyWritePath() = withDatabase { db ->
        val agents = fixture(db)
        val trpg = agents.get("agent")!!
        assertEquals(AgentMode.TRPG, trpg.mode)
        assertEquals(AgentMode.TRPG, agents.getOverview("agent")!!.mode)
        assertEquals(AgentMode.TRPG, agents.listOverviews().single().mode)
        val normal = trpg.copy(mode = AgentMode.NORMAL)
        assertThrows(AppException::class.java) { agents.update(normal) }
        assertThrows(AppException::class.java) { agents.upsert(normal) }
        val promptCount = agents.prompts("agent").size
        assertThrows(AppException::class.java) { agents.saveWithPrompt(normal, "Changed") }
        assertEquals(promptCount, agents.prompts("agent").size)
        assertThrows(Exception::class.java) { db.execute("UPDATE agent_profiles SET mode='NORMAL' WHERE id='agent'") }
        val snapshot = agents.createSnapshot("agent", "snapshot")
        assertEquals(AgentMode.TRPG, snapshot.mode)
        assertEquals(snapshot, agents.resolveSnapshot("snapshot").snapshot)
        assertThrows(Exception::class.java) { db.execute("UPDATE agent_snapshots SET mode='NORMAL' WHERE id='snapshot'") }
        agents.saveWithPrompt(AgentProfile("normal", "Normal", "pending", "model", revision = 0), "Normal")
        assertThrows(AppException::class.java) { agents.saveWithPrompt(agents.get("normal")!!.copy(mode = AgentMode.TRPG), "Changed") }
    }

    @Test fun legacyModeAndSnapshotSerializationRemainNormalWithoutNewFields() = withDatabase { db ->
        val agents = fixture(db, AgentMode.NORMAL)
        val snapshot = agents.createSnapshot("agent")
        val json = Json { encodeDefaults = true }
        assertFalse(json.encodeToString(agents.get("agent")!!).contains("\"mode\""))
        assertFalse(json.encodeToString(snapshot).contains("\"mode\""))
        assertFalse(snapshot.bindingManifestJson.contains("agentMode"))
        assertEquals(AgentMode.NORMAL, json.decodeFromString<AgentSnapshot>(json.encodeToString(snapshot)).mode)
        assertEquals(AgentMode.NORMAL, agents.resolveSnapshot(snapshot.id).snapshot.mode)
    }

    @Test fun migration37PreservesAgentSnapshotAndTranscriptAndDefaultsNormal() = withDatabase { db ->
        val agents = fixture(db, AgentMode.NORMAL)
        val snapshot = agents.createSnapshot("agent", "snapshot")
        val conversations = ConversationRepository(db)
        conversations.create(snapshot.id, "Legacy", "conversation")
        val message = conversations.append("conversation", MessageRole.USER, "Keep exact text", messageId = "message", createdAt = at)
        db.execute("DROP TRIGGER agent_mode_immutable")
        db.execute("DROP TRIGGER snapshot_mode_immutable")
        db.execute("ALTER TABLE agent_profiles DROP COLUMN mode")
        db.execute("ALTER TABLE agent_snapshots DROP COLUMN mode")
        db.execute("UPDATE schema_version SET version=37")
        Migrations.apply(db)
        assertEquals(Migrations.VERSION.toLong(), db.query("SELECT version FROM schema_version").single().long("version"))
        assertEquals(AgentMode.NORMAL, agents.get("agent")!!.mode)
        assertEquals(snapshot, agents.getSnapshot("snapshot"))
        assertEquals(message, conversations.message("message"))
        assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
    }

    @Test fun sessionIsLazyBoundedIsolatedAndUsesCompareAndSet() = withDatabase { db ->
        val agents = fixture(db)
        val snapshot = agents.createSnapshot("agent", "snapshot")
        ConversationRepository(db).create(snapshot.id, "First", "first")
        ConversationRepository(db).create(snapshot.id, "Second", "second")
        val repo = TrpgRepository(db) { at }
        assertEquals(TrpgSession("first"), repo.get("first"))
        assertTrue(db.query("SELECT * FROM trpg_sessions").isEmpty())
        val state = TrpgState(ruleset = TrpgRuleset.COC_7, playerName = "Investigator", scene = "Library", resources = listOf(TrpgResource("SAN", 54, 99)))
        assertEquals(TrpgSession("first", 1, state), repo.update("first", 0, state))
        assertEquals(TrpgSession("second"), repo.get("second"))
        assertThrows(AppException::class.java) { repo.update("first", 0, TrpgState()) }
        assertThrows(AppException::class.java) { repo.update("first", 1, state.copy(notes = "x".repeat(8193))) }
        assertThrows(AppException::class.java) { repo.update("first", 1, state.copy(resources = listOf(TrpgResource("SAN", 1), TrpgResource("SAN", 2)))) }
        assertThrows(AppException::class.java) { repo.update("first", 1, state.copy(characterSheet = "\u0000".repeat(8192), notes = "\u0000".repeat(8192))) }
        assertEquals(1L, repo.get("first").revision)
        agents.saveWithPrompt(AgentProfile("normal", "Normal", "pending", "model", revision = 0), "Normal")
        ConversationRepository(db).create(agents.createSnapshot("normal").id, "Normal", "normal-conversation")
        assertThrows(AppException::class.java) { repo.get("normal-conversation") }
        assertThrows(AppException::class.java) { repo.update("normal-conversation", 0, state) }
        ConversationRepository(db).setArchived("first", true)
        assertThrows(AppException::class.java) { repo.update("first", 1, state) }
    }

    @Test fun runUpdatesRequireLiveExactBindingAndDurablyReplayOriginalResult() = withDatabase { db ->
        val agents = fixture(db)
        ConversationRepository(db).create(agents.createSnapshot("agent", "snapshot").id, "First", "first")
        ConversationRepository(db).create("snapshot", "Second", "second")
        val runs = RunRepository(db)
        runs.create(RunRecord("run", "snapshot", "first", RunStatus.TOOL_EXECUTING, "{}", createdAt = at))
        val repo = TrpgRepository(db) { at }
        val state = TrpgState(scene = "A")
        assertThrows(AppException::class.java) { repo.update("first", 0, state) }
        assertThrows(AppException::class.java) { repo.updateFromRun("second", "snapshot", "run", "call", "{}", 0, state) }
        assertThrows(AppException::class.java) { repo.updateFromRun("first", "other", "run", "call", "{}", 0, state) }
        val original = repo.updateFromRun("first", "snapshot", "run", "call", "{\"scene\":\"A\"}", 0, state)
        repo.updateFromRun("first", "snapshot", "run", "call2", "{}", 1, state.copy(scene = "B"))
        val replay = TrpgRepository(db).updateFromRun("first", "snapshot", "run", "call", "{\"scene\":\"A\"}", 0, state)
        assertEquals(original, replay)
        assertEquals(2L, repo.get("first").revision)
        assertEquals("B", repo.get("first").state.scene)
        assertEquals(2, db.query("SELECT * FROM trpg_run_receipts").size)
        assertThrows(AppException::class.java) { repo.updateFromRun("first", "snapshot", "run", "call", "{}", 0, state) }
        assertThrows(AppException::class.java) { repo.updateFromRun("first", "snapshot", "run", "call", "{\"scene\":\"A\"}", 1, state) }
        assertThrows(AppException::class.java) { repo.updateFromRun("first", "snapshot", "run", "call", "{\"scene\":\"A\"}", 0, state.copy(scene = "C")) }
        runs.save(runs.get("run")!!.copy(state = RunStatus.CANCELLED))
        assertThrows(AppException::class.java) { repo.updateFromRun("first", "snapshot", "run", "call", "{\"scene\":\"A\"}", 0, state) }
        assertThrows(AppException::class.java) { repo.updateFromRun("first", "snapshot", "run", "late", "{}", 2, state) }
        assertEquals(2L, repo.get("first").revision)
    }

    @Test fun transferredStateAndModeRoundTripAndRestoreCannotChangeExistingMode() = withDatabase { source ->
        val agents = fixture(source)
        ConversationRepository(source).create(agents.createSnapshot("agent", "snapshot").id, "Story", "story")
        val state = TrpgState(TrpgRuleset.DND_5E, "Hero", "Wizard", "Inn", "Untrusted notes", listOf(TrpgResource("HP", 8, 12)))
        val expected = TrpgRepository(source).update("story", 0, state)
        val archive = ByteArrayOutputStream()
        TransferRepository(source).exportArchive("agent", TransferOptions(includeConversations = true), archive)
        JdbcSqlConnection().use { target ->
            Migrations.apply(target)
            TransferRepository(target).importArchive(archive.toByteArray())
            assertEquals(AgentMode.TRPG, AgentRepository(target).get("agent")!!.mode)
            assertEquals(expected, TrpgRepository(target).get("story"))
        }
        JdbcSqlConnection().use { target ->
            fixture(target, AgentMode.NORMAL)
            assertThrows(AppException::class.java) { TransferRepository(target).importArchive(archive.toByteArray(), TransferConflictPolicy.KEEP_EXISTING) }
            assertEquals(AgentMode.NORMAL, AgentRepository(target).get("agent")!!.mode)
            assertTrue(ConversationRepository(target).list().isEmpty())
        }
    }

    @Test fun transferRejectsModeSpoofStateOnNormalAndWrongConversation() = withDatabase { db ->
        val agents = fixture(db)
        val snapshot = portable(agents.createSnapshot("agent", "snapshot"))
        val conversation = ConversationRepository(db).create(snapshot.id, "Story", "story")
        val bundle = TransferBundle(SchemaVersion.CURRENT, at, conversations = listOf(ConversationTransfer(conversation, snapshot, trpgSession = TrpgSession("story"))))
        TransferCodec.encode(bundle)
        assertThrows(AppException::class.java) { TransferCodec.encode(bundle.copy(conversations = listOf(bundle.conversations.single().copy(trpgSession = TrpgSession("other"))))) }
        assertThrows(AppException::class.java) { TransferCodec.encode(bundle.copy(conversations = listOf(bundle.conversations.single().copy(snapshot = snapshot.copy(mode = AgentMode.NORMAL))))) }
        val root = Json.parseToJsonElement(snapshot.bindingManifestJson) as JsonObject
        val normalSnapshot = snapshot.copy(mode = AgentMode.NORMAL, bindingManifestJson = JsonObject(root - "agentMode").toString())
        assertThrows(AppException::class.java) { TransferCodec.encode(bundle.copy(conversations = listOf(bundle.conversations.single().copy(snapshot = normalSnapshot)))) }
        assertThrows(AppException::class.java) { TransferCodec.encode(bundle.copy(conversations = listOf(bundle.conversations.single().copy(snapshot = snapshot.copy(bindingManifestJson = JsonObject(root + ("agentMode" to JsonPrimitive("INVALID"))).toString()))))) }
    }

    @Test fun receiptFailureRollsBackStateMutationAndPermitsSafeRetry() = withDatabase { db ->
        val agents = fixture(db)
        ConversationRepository(db).create(agents.createSnapshot("agent", "snapshot").id, "Story", "story")
        RunRepository(db).create(RunRecord("run", "snapshot", "story", RunStatus.TOOL_EXECUTING, createdAt = at))
        val failing = object : SqlConnection by db {
            override fun execute(sql: String, args: List<Any?>) {
                if (sql.startsWith("INSERT INTO trpg_run_receipts")) error("Injected receipt persistence failure")
                db.execute(sql, args)
            }
        }
        assertThrows(IllegalStateException::class.java) {
            TrpgRepository(failing).updateFromRun("story", "snapshot", "run", "call", "{}", 0, TrpgState(scene = "A"))
        }
        assertEquals(TrpgSession("story"), TrpgRepository(db).get("story"))
        assertTrue(db.query("SELECT * FROM trpg_run_receipts").isEmpty())
        assertEquals(1L, TrpgRepository(db).updateFromRun("story", "snapshot", "run", "call", "{}", 0, TrpgState(scene = "A")).revision)
    }

    @Test fun legacyBackupWithoutModeOrStateRestoresNormalAndTrpgMissingStateDefaultsLazily() = withDatabase { source ->
        val agents = fixture(source, AgentMode.NORMAL)
        ConversationRepository(source).create(agents.createSnapshot("agent", "snapshot").id, "Old", "old")
        val archive = ByteArrayOutputStream()
        TransferRepository(source).exportArchive("agent", TransferOptions(includeConversations = true), archive)
        JdbcSqlConnection().use { target ->
            Migrations.apply(target)
            TransferRepository(target).importArchive(archive.toByteArray())
            assertEquals(AgentMode.NORMAL, AgentRepository(target).get("agent")!!.mode)
            assertEquals(AgentMode.NORMAL, AgentRepository(target).getSnapshot("snapshot")!!.mode)
            assertTrue(target.query("SELECT * FROM trpg_sessions").isEmpty())
        }
        val trpgAgent = agents.saveWithPrompt(AgentProfile("trpg", "TRPG", "pending", "model", revision = 0, mode = AgentMode.TRPG), "TRPG")
        val snapshot = agents.createSnapshot(trpgAgent.id, "trpg-snapshot")
        val conversation = ConversationRepository(source).create(snapshot.id, "Story", "story")
        val bundle = TransferBundle(SchemaVersion.CURRENT, at, conversations = listOf(ConversationTransfer(conversation, portable(snapshot))))
        val encoded = TransferCodec.encode(bundle)
        assertFalse(encoded.contains("trpgSession"))
        assertNull(TransferCodec.decode(encoded).conversations.single().trpgSession)
        assertEquals(TrpgSession("story"), TrpgRepository(source).get("story"))
    }

    @Test fun existingConversationDeletePolicyRemovesOwnedStateAndKeepsRunHistory() = withDatabase { db ->
        val agents = fixture(db)
        val conversations = ConversationRepository(db)
        conversations.create(agents.createSnapshot("agent", "snapshot").id, "Story", "story")
        conversations.append("story", MessageRole.USER, "Removable story", messageId = "removable")
        TrpgRepository(db).update("story", 0, TrpgState(scene = "A"))
        assertTrue(conversations.delete("story"))
        assertTrue(db.query("SELECT * FROM trpg_sessions").isEmpty())
        assertNull(conversations.message("removable"))
        assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
        conversations.create("snapshot", "Retained", "retained")
        val retainedMessage = conversations.append("retained", MessageRole.USER, "Retain transcript", messageId = "retained-message")
        RunRepository(db).create(RunRecord("run", "snapshot", "retained", RunStatus.COMPLETED, createdAt = at))
        TrpgRepository(db).update("retained", 0, TrpgState(scene = "B"))
        assertFalse(conversations.delete("retained"))
        assertEquals("B", TrpgRepository(db).get("retained").state.scene)
        assertEquals(retainedMessage, conversations.message("retained-message"))
    }

    @Test fun everyStateBoundIsValidatedBeforePersistence() {
        val invalid = listOf(
            TrpgState(playerName = "x".repeat(129)), TrpgState(characterSheet = "x".repeat(8193)),
            TrpgState(scene = "x".repeat(4097)), TrpgState(notes = "x".repeat(8193)),
            TrpgState(resources = List(33) { TrpgResource("resource$it", 0) }),
            TrpgState(resources = listOf(TrpgResource("x".repeat(65), 0))),
            TrpgState(resources = listOf(TrpgResource("", 0))),
            TrpgState(resources = listOf(TrpgResource("HP", 1_000_001))),
            TrpgState(resources = listOf(TrpgResource("HP", -1_000_001))),
            TrpgState(resources = listOf(TrpgResource("HP", 0, -1))),
            TrpgState(resources = listOf(TrpgResource("HP", 0, 1_000_001))),
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { TrpgStateValidation.validate(it) } }
        TrpgStateValidation.validate(TrpgState(resources = listOf(TrpgResource("HP", -1_000_000, 1_000_000))))
    }

    private fun withDatabase(block: (JdbcSqlConnection) -> Unit) { JdbcSqlConnection().use(block) }

    private fun portable(snapshot: AgentSnapshot): AgentSnapshot {
        val manifest = Json.parseToJsonElement(snapshot.bindingManifestJson) as JsonObject
        val provider = manifest["provider"] as JsonObject
        return snapshot.copy(bindingManifestJson = JsonObject(manifest + ("provider" to JsonObject(provider - "secretRef" - "headerSecretRefs"))).toString())
    }

    private fun fixture(db: SqlConnection, mode: AgentMode = AgentMode.TRPG): AgentRepository {
        Migrations.apply(db)
        val profiles = ProfileRepository(db)
        profiles.createProvider(ProviderProfile("provider", "Fixture", ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1", secretRef = "synthetic", revision = 1))
        profiles.createModel(ModelProfile("model", "provider", ModelRole.CHAT, "synthetic", setOf("stream"), contextLimit = 4096, outputLimit = 512, revision = 1))
        return AgentRepository(db).also { it.saveWithPrompt(AgentProfile("agent", "Fixture", "pending", "model", revision = 0, mode = mode), "Fixture") }
    }
}
