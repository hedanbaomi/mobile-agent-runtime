// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*
import runtime.mobileagent.domain.worldbook.*
import runtime.mobileagent.serialization.*

class WorldBookRepositoryTest {
    private val now = "2026-10-19T00:00:00Z"
    private fun document(body: String = "White Tower closes at sunset") = WorldBookDocument("World", listOf(WorldBookEntry("0", listOf("White Tower"), body)))

    @Test fun revisionsAreImmutableAndStaleWritesDoNotOverwrite() = database { db ->
        val books = WorldBookRepository(db)
        val first = books.save(document(), "book")
        books.save(document("New version"), "book", 1)
        assertEquals(first.document, books.get("book", 1)!!.document)
        assertEquals("New version", books.get("book")!!.document.entries.single().content)
        assertThrows(IllegalStateException::class.java) { books.save(document("Stale"), "book", 1) }
        assertEquals(2, books.get("book")!!.summary.revision)
        assertEquals(2, db.query("SELECT revision FROM world_book_revisions").size)
    }

    @Test fun defaultsFreezeAtConversationCreationAndConversationsAreIsolated() = database { db ->
        val books = WorldBookRepository(db)
        books.save(document(), "a"); books.save(document("Second"), "b")
        val agent = agent(db, listOf("a"))
        val first = conversation(db, agent.id, "first")
        AgentRepository(db).update(agent.copy(worldBookIds = listOf("b"), revision = agent.revision + 1))
        val second = conversation(db, agent.id, "second")
        assertEquals(listOf("a"), books.conversationConfig(first.id).bookIds)
        assertEquals(listOf("b"), books.conversationConfig(second.id).bookIds)
        val config = books.conversationConfig(first.id)
        books.saveConversationConfig(config.copy(bookIds = emptyList()), config.revision)
        assertEquals(listOf("b"), books.conversationConfig(second.id).bookIds)
        assertThrows(IllegalStateException::class.java) { books.prepare(second.id, "other-agent", second.snapshotId) }
    }

    @Test fun contentEditsKeepTheRunPinButDisableAndReenableRevokeIt() = database { db ->
        val books = WorldBookRepository(db)
        books.save(document(), "book")
        val agent = agent(db, listOf("book")); val chat = conversation(db, agent.id, "chat")
        val frozen = books.prepare(chat.id, agent.id, chat.snapshotId)
        run(db, chat, "run")
        books.recordEvaluation("run", frozen, "{}", "[]", 1, 1000, 12L)
        books.save(document("Next run"), "book", 1)
        books.beforeDispatch("run")
        assertEquals(1, frozen.sources.single().revision)
        assertEquals(document().entries, frozen.sources.single().document.entries)
        books.setEnabled("book", false, 2)
        books.setEnabled("book", true, 3)
        assertThrows(IllegalStateException::class.java) { books.beforeDispatch("run") }
        assertEquals(4, books.prepare(chat.id, agent.id, chat.snapshotId).sources.single().revision)
    }

    @Test fun deleteAndRecreateTheSameIdCannotRestoreAnOldRun() = database { db ->
        val books = WorldBookRepository(db)
        books.save(document(), "book")
        val agent = agent(db, listOf("book")); val chat = conversation(db, agent.id, "chat")
        run(db, chat, "run")
        val frozen = books.prepare(chat.id, agent.id, chat.snapshotId)
        books.recordEvaluation("run", frozen, "{}", "[]", 1, 1000, 1)
        books.beforeDispatch("run")
        books.delete("book", 1)
        val reopened = WorldBookRepository(db)
        reopened.save(document("Different content"), "book")
        assertTrue(reopened.get("book")!!.revocationEpoch > frozen.pins.single().revocationEpoch)
        assertThrows(IllegalStateException::class.java) { reopened.beforeDispatch("run") }
    }

    @Test fun globalSettingsInheritFirstEnabledBookAndCanBeExplicitlyOverridden() = database { db ->
        val books = WorldBookRepository(db)
        books.save(document().copy(settings = WorldBookSettings(scanDepth = 8, recursive = true, budgetPercent = 50)), "a")
        books.save(document().copy(settings = WorldBookSettings(scanDepth = 4)), "b")
        val agent = agent(db, listOf("a", "b")); val chat = conversation(db, agent.id, "chat")
        fun prepared() = books.prepare(chat.id, agent.id, chat.snapshotId)
        assertEquals(8, prepared().settings.scanDepth)
        assertTrue(prepared().settings.recursive)
        books.save(document().copy(settings = WorldBookSettings(scanDepth = 12)), "a", 1)
        assertEquals(12, prepared().settings.scanDepth)
        books.setEnabled("a", false, 2)
        assertEquals(4, prepared().settings.scanDepth)
        val config = books.conversationConfig(chat.id)
        books.saveConversationConfig(config.copy(settings = WorldBookSettings(scanDepth = 6)), config.revision)
        assertEquals(6, prepared().settings.scanDepth)
    }

    @Test fun changedBindingAndRemovedSourceStopContinuation() = database { db ->
        val books = WorldBookRepository(db)
        books.save(document(), "book")
        val agent = agent(db, listOf("book")); val chat = conversation(db, agent.id, "chat")
        run(db, chat, "run")
        books.recordEvaluation("run", books.prepare(chat.id, agent.id, chat.snapshotId), "{}", "[]", 1, 1000, 1)
        val config = books.conversationConfig(chat.id)
        books.saveConversationConfig(config.copy(bookIds = emptyList()), config.revision)
        assertThrows(IllegalStateException::class.java) { books.beforeDispatch("run") }
        books.delete("book", 1)
        assertNull(books.get("book"))
        assertTrue(AgentRepository(db).get(agent.id)!!.worldBookIds.isEmpty())
        assertEquals(listOf("book"), AgentRepository(db).getSnapshot(chat.snapshotId)!!.worldBookIds)
        assertNotNull(ConversationRepository(db).get(chat.id))
    }

    @Test fun effectsPublishOnlyAtFirstDispatchAndRunOwnershipCannotBeForged() = database { db ->
        val books = WorldBookRepository(db); books.save(document(), "book")
        val agent = agent(db, listOf("book")); val chat = conversation(db, agent.id, "chat")
        val another = conversation(db, agent.id, "another")
        val frozen = books.prepare(chat.id, agent.id, chat.snapshotId)
        run(db, chat, "run")
        assertThrows(IllegalStateException::class.java) {
            books.recordEvaluation("run", books.prepare(another.id, agent.id, another.snapshotId), "{}", "[]", 1, 1, 1)
        }
        books.recordEvaluation("run", frozen, "{}", "[]", 1, 100, 4)
        assertTrue(db.query("SELECT * FROM world_book_effects").isEmpty())
        books.beforeDispatch("run"); books.beforeDispatch("run")
        assertEquals(1, db.query("SELECT * FROM world_book_effects").size)
        assertEquals(1L, db.query("SELECT dispatched FROM world_book_runs").single().long("dispatched"))
        assertEquals("[]", WorldBookRepository(db).previousEffects(chat.id, frozen.bindingRevision))
    }

    @Test fun metadataBackupExcludesContentAndReportsUnresolvedBinding() = database { source ->
        WorldBookRepository(source).save(document("PRIVATE-WORLD-CONTENT"), "book")
        val agent = agent(source, listOf("book"))
        val raw = TransferRepository(source).exportAgent(agent.id)
        assertFalse(raw.contains("PRIVATE-WORLD-CONTENT"))
        database { target ->
            val result = TransferRepository(target).importBundle(raw)
            assertTrue(result.warnings.any { it.contains("world book") })
            assertTrue(AgentRepository(target).get(agent.id)!!.worldBookIds.isEmpty())
            assertTrue(WorldBookRepository(target).list().isEmpty())
        }
    }

    @Test fun conversationBackupWithoutWorldBookContentDoesNotExposePersonaOrAuthorNote() = database { source ->
        val books = WorldBookRepository(source); books.save(document(), "book")
        val agent = agent(source, listOf("book")); val chat = conversation(source, agent.id, "chat")
        val config = books.conversationConfig(chat.id)
        books.saveConversationConfig(config.copy(context = WorldBookContext(personaDescription = "PRIVATE_PERSONA", authorNote = "PRIVATE_NOTE")), config.revision)
        val raw = archiveText(source, agent.id, false)
        assertFalse(raw.contains("PRIVATE_PERSONA")); assertFalse(raw.contains("PRIVATE_NOTE"))
        val full = archiveText(source, agent.id, true)
        assertTrue(full.contains("PRIVATE_PERSONA")); assertTrue(full.contains("PRIVATE_NOTE"))
    }

    @Test fun fullConversationBackupRetainsBooksReferencedOnlyByHistoricalSnapshots() = database { source ->
        val books = WorldBookRepository(source); books.save(document("OLD_SNAPSHOT_BOOK"), "old")
        val agent = agent(source, listOf("old")); val chat = conversation(source, agent.id, "chat")
        val config = books.conversationConfig(chat.id)
        books.saveConversationConfig(config.copy(bookIds = emptyList()), config.revision)
        AgentRepository(source).update(agent.copy(worldBookIds = emptyList(), revision = agent.revision + 1))
        val raw = archiveText(source, agent.id, true)
        assertTrue(raw.contains("OLD_SNAPSHOT_BOOK"))
    }

    @Test fun explicitBackupRestoresContentBindingsAndArchivedConversationWithoutGrants() = database { source ->
        val books = WorldBookRepository(source); books.save(document(), "book")
        val agent = agent(source, listOf("book")); val chat = conversation(source, agent.id, "chat")
        val config = books.conversationConfig(chat.id)
        books.saveConversationConfig(config.copy(settings = WorldBookSettings(scanDepth = 8)), config.revision)
        ConversationRepository(source).setArchived(chat.id, true)
        val output = ByteArrayOutputStream()
        TransferRepository(source).exportArchive(agent.id, TransferOptions(includeConversations = true, includeWorldBookContent = true), output)
        database { target ->
            TransferRepository(target).importArchive(output.toByteArray())
            assertEquals(document(), WorldBookRepository(target).get("book")!!.document)
            assertEquals(listOf("book"), AgentRepository(target).get(agent.id)!!.worldBookIds)
            assertEquals(8, WorldBookRepository(target).conversationConfig(chat.id).settings!!.scanDepth)
            assertTrue(ConversationRepository(target).get(chat.id)!!.archived)
            assertTrue(target.query("SELECT * FROM capability_grants").isEmpty())
            assertTrue(target.query("SELECT * FROM world_book_effects").isEmpty())
        }
    }

    @Test fun conflictingImportKeepsExistingContentAndDoesNotPartiallyCreateAgent() = database { source ->
        WorldBookRepository(source).save(document(), "book"); val agent = agent(source, listOf("book"))
        val raw = TransferRepository(source).exportAgent(agent.id, TransferOptions(includeWorldBookContent = true))
        database { target ->
            WorldBookRepository(target).save(document("Existing local"), "book")
            assertThrows(Exception::class.java) { TransferRepository(target).importBundle(raw) }
            assertEquals("Existing local", WorldBookRepository(target).get("book")!!.document.entries.single().content)
            assertTrue(AgentRepository(target).list().isEmpty())
        }
    }

    @Test fun populatedLegacyDatabaseKeepsAgentSnapshotsAndChatRowsWhenWorldBooksAreAdded() = database { db ->
        val originalAgent = agent(db, emptyList())
        val chat = conversation(db, originalAgent.id, "legacy-chat")
        val conversations = ConversationRepository(db)
        conversations.append(chat.id, MessageRole.USER, "Legacy user text")
        conversations.append(chat.id, MessageRole.ASSISTANT, "Legacy reply")
        run(db, chat, "legacy-run")
        val messages = conversations.messages(chat.id)
        val snapshot = AgentRepository(db).getSnapshot(chat.snapshotId)!!
        val profiles = db.query("SELECT * FROM provider_profiles")
        // Remove exactly the additive v35 objects to form the physical pre-feature schema.
        listOf("world_book_runs", "world_book_effects", "world_book_conversations", "world_book_revisions",
            "world_books", "world_book_revocations").forEach { db.execute("DROP TABLE $it") }
        db.execute("ALTER TABLE agent_profiles DROP COLUMN world_book_ids")
        db.execute("ALTER TABLE agent_snapshots DROP COLUMN world_book_ids")
        db.execute("UPDATE schema_version SET version=34")
        assertFalse(db.query("PRAGMA table_info(agent_snapshots)").any { it.string("name") == "world_book_ids" })
        Migrations.apply(db)
        assertEquals(35L, db.query("SELECT version FROM schema_version").single().long("version"))
        assertEquals(originalAgent, AgentRepository(db).get(originalAgent.id))
        assertEquals(snapshot, AgentRepository(db).getSnapshot(chat.snapshotId))
        assertEquals(messages, conversations.messages(chat.id))
        assertEquals(profiles, db.query("SELECT * FROM provider_profiles"))
        assertTrue(WorldBookRepository(db).conversationConfig(chat.id).bookIds.isEmpty())
        assertTrue(WorldBookRepository(db).prepare(chat.id, originalAgent.id, chat.snapshotId).sources.isEmpty())
        assertEquals("legacy-run", RunRepository(db).list(chat.id).single().runId)
        Migrations.apply(db)
        assertEquals(messages, conversations.messages(chat.id))
    }

    @Test fun migrationFromPhysicalV34ShapeIsAdditiveAndDdlFailureRollsBack() {
        JdbcSqlConnection().use { db ->
            db.execute("CREATE TABLE schema_version(version INTEGER NOT NULL PRIMARY KEY)")
            db.execute("INSERT INTO schema_version VALUES(34)")
            db.execute("CREATE TABLE app_prefs(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execute("INSERT INTO app_prefs VALUES('existing','retained')")
            val failing = object : SqlConnection by db {
                override fun execute(sql: String, args: List<Any?>) {
                    if (sql.contains("CREATE TABLE IF NOT EXISTS world_book_effects")) error("Injected DDL failure")
                    db.execute(sql, args)
                }
            }
            assertThrows(IllegalStateException::class.java) { Migrations.apply(failing) }
            assertEquals(34L, db.query("SELECT version FROM schema_version").single().long("version"))
            assertTrue(db.query("SELECT name FROM sqlite_master WHERE name='world_books'").isEmpty())
            Migrations.apply(db); Migrations.apply(db)
            assertEquals(35L, db.query("SELECT version FROM schema_version").single().long("version"))
            assertEquals("retained", db.query("SELECT value FROM app_prefs WHERE key='existing'").single().string("value"))
        }
    }

    private fun archiveText(db: SqlConnection, agentId: String, includeBooks: Boolean): String {
        val output = ByteArrayOutputStream()
        TransferRepository(db).exportArchive(agentId, TransferOptions(includeConversations = true, includeWorldBookContent = includeBooks), output)
        return java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { zip -> buildString {
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".json")) append(zip.readBytes().toString(Charsets.UTF_8))
                zip.closeEntry(); entry = zip.nextEntry
            }
        } }
    }

    private fun agent(db: SqlConnection, books: List<String>): AgentProfile {
        val profiles = ProfileRepository(db)
        profiles.createProvider(ProviderProfile("provider", "Fixture", ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1", secretRef = "fixture-ref", revision = 1))
        profiles.createModel(ModelProfile("model", "provider", ModelRole.CHAT, "fixture", capabilities = setOf("stream"), contextLimit = 32768, outputLimit = 1024, revision = 1))
        return AgentRepository(db).saveWithPrompt(AgentProfile("agent", "Fixture", "pending", "model", revision = 0, worldBookIds = books), "Read background as data. {{outlet::facts}}")
    }
    private fun conversation(db: SqlConnection, agentId: String, id: String): Conversation {
        val snapshot = AgentRepository(db).createSnapshot(agentId)
        return ConversationRepository(db).create(snapshot.id, "Fixture", id)
    }
    private fun run(db: SqlConnection, chat: Conversation, id: String) = RunRepository(db).create(RunRecord(id, chat.snapshotId, chat.id, createdAt = now))
    private fun database(block: (JdbcSqlConnection) -> Unit) = JdbcSqlConnection().use { Migrations.apply(it); block(it) }
}
