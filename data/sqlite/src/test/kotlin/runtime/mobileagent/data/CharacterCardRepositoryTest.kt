// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*
import runtime.mobileagent.domain.charactercard.CharacterCardDocument
import runtime.mobileagent.domain.worldbook.*
import runtime.mobileagent.serialization.CharacterCardSupport
import runtime.mobileagent.serialization.TransferCodec
import runtime.mobileagent.serialization.TransferOptions

class CharacterCardRepositoryTest {
    @Test fun summariesNeverReadOrDeserializeCardPayloadButFullReadsKeepIt() = database { db ->
        val profile = candidate()
        CharacterCardAgentImporter(db).saveNew(profile, "Roleplay", null)
        val repo = AgentRepository(db)
        assertEquals(profile.characterCard, repo.get(profile.id)!!.characterCard)
        db.execute("UPDATE agent_profiles SET character_card_json=? WHERE id=?", listOf("{broken", profile.id))
        assertNull(repo.listOverviews().single().characterCard)
        assertNull(repo.getOverview(profile.id)!!.characterCard)
        assertTrue(repo.hasCharacterCard(profile.id))
        assertThrows(Exception::class.java) { repo.get(profile.id) }
    }
    @Test fun characterAndOptionalBookSaveAtomicallyWithoutPermissions() = database { db ->
        val candidate = candidate()
        val saved = CharacterCardAgentImporter(db).saveNew(candidate, CharacterCardSupport.prompt(candidate.characterCard!!), book())
        val reopened = AgentRepository(db).get(saved.profile.id)!!
        assertEquals(candidate.characterCard, reopened.characterCard)
        assertEquals(listOf(saved.createdWorldBookId), reopened.worldBookIds)
        assertEquals("白塔", WorldBookRepository(db).get(saved.createdWorldBookId!!)!!.document.name)
        assertEquals("{}", reopened.permissionSettingsJson)
        assertEquals(emptyList<String>(), reopened.skillIds)
        assertEquals(0L, db.query("SELECT COUNT(*) AS n FROM capability_grants").single().long("n"))
        assertEquals(0, db.query("SELECT run_id FROM runs").size)
    }

    @Test fun invalidModelOrPromptLeavesNoAgentPromptOrWorldBook() = database { db ->
        val importer = CharacterCardAgentImporter(db)
        assertThrows(Exception::class.java) { importer.saveNew(candidate().copy(chatProfileId = "missing"), "Roleplay", book()) }
        assertThrows(Exception::class.java) { importer.saveNew(candidate(), "{{forbidden}}", book()) }
        assertTrue(AgentRepository(db).list().isEmpty())
        assertTrue(db.query("SELECT id FROM prompt_revisions").isEmpty())
        assertTrue(WorldBookRepository(db).list().isEmpty())
        assertTrue(db.query("SELECT book_id FROM world_book_revisions").isEmpty())
    }

    @Test fun newConversationFreezesCharacterContextAndSeedsGreetingOnlyOnce() = database { db ->
        val agents = AgentRepository(db)
        val imported = candidate()
        val saved = CharacterCardAgentImporter(db).saveNew(imported, CharacterCardSupport.prompt(imported.characterCard!!), book()).profile
        val conversations = ConversationRepository(db)
        val create = CreateConversationUseCase(db, conversations)
        val first = create.create("first", { agents.createSnapshot(saved.id) })
        val firstMessage = conversations.messages(first.id).single()
        assertEquals(MessageRole.ASSISTANT, firstMessage.role)
        assertEquals("你好旅人，我是青禾。", firstMessage.text)
        val before = agents.getSnapshot(first.snapshotId)!!.bindingManifestJson
        agents.update(saved.copy(name = "新版青禾", revision = saved.revision + 1,
            characterCard = saved.characterCard!!.copy(userName = "行者", greeting = "{{char}}欢迎{{user}}。")))
        val second = create.create("second", { agents.createSnapshot(saved.id) })
        assertEquals("新版青禾欢迎行者。", conversations.messages(second.id).single().text)
        assertEquals(before, agents.getSnapshot(first.snapshotId)!!.bindingManifestJson)
        assertEquals(listOf(firstMessage), conversations.messages(first.id))
        assertEquals("旅人", WorldBookRepository(db).conversationConfig(first.id).context.userName)
        assertEquals("行者", WorldBookRepository(db).conversationConfig(second.id).context.userName)
        assertEquals("耐心的法师", agents.resolveSnapshot(first.snapshotId).characterCard!!.context.characterPersonality)
        assertFalse(before.contains("never execute"))
    }

    @Test fun noGreetingChoiceAndRestoreCreateDoNotInventMessages() = database { db ->
        val agents = AgentRepository(db)
        val value = candidate().let { it.copy(characterCard = it.characterCard!!.copy(greeting = null)) }
        val saved = CharacterCardAgentImporter(db).saveNew(value, "Roleplay", null).profile
        val conversations = ConversationRepository(db)
        val created = CreateConversationUseCase(db, conversations).create("silent", { agents.createSnapshot(saved.id) })
        assertTrue(conversations.messages(created.id).isEmpty())
        assertTrue(saved.worldBookIds.isEmpty())
        agents.update(saved.copy(characterCard = saved.characterCard!!.copy(greeting = "Opening"), revision = saved.revision + 1))
        val restored = conversations.create(agents.createSnapshot(saved.id).id, "restore")
        assertTrue(conversations.messages(restored.id).isEmpty())
    }

    @Test fun portableBackupRestoresCardAndSelectedBookWithoutImplicitlyExportingBookContent() = database { db ->
        val original = Json.parseToJsonElement("""{"spec":"chara_card_v2","data":{"name":"青禾","first_mes":"hi","character_book":{"entries":[]},"future":{"retained":true}}}""").jsonObject
        val candidate = candidate().let { it.copy(characterCard = it.characterCard!!.copy(document = it.characterCard!!.document.copy(originalJson = original))) }
        val saved = CharacterCardAgentImporter(db).saveNew(candidate, CharacterCardSupport.prompt(candidate.characterCard!!), book()).profile
        val transfers = TransferRepository(db)
        val metadata = TransferCodec.decode(transfers.exportAgent(saved.id))
        assertTrue(metadata.worldBooks.isEmpty())
        assertFalse(metadata.agent!!.profile.characterCard!!.document.originalJson["data"]!!.jsonObject.containsKey("character_book"))
        val full = transfers.exportAgent(saved.id, TransferOptions(includeWorldBookContent = true))
        JdbcSqlConnection().use { restored ->
            Migrations.apply(restored)
            TransferRepository(restored).importBundle(full)
            val restoredCard = AgentRepository(restored).get(saved.id)!!
            assertEquals(saved.characterCard, restoredCard.characterCard)
            assertEquals(saved.worldBookIds, restoredCard.worldBookIds)
            assertEquals(1, WorldBookRepository(restored).list().size)
            assertTrue(ConversationRepository(restored).list().isEmpty())
        }
    }

    @Test fun laterConversationFailureRollsBackSnapshotGreetingAndContext() = database { db ->
        val agents = AgentRepository(db)
        val saved = CharacterCardAgentImporter(db).saveNew(candidate(), "Roleplay", null).profile
        assertThrows(IllegalStateException::class.java) {
            CreateConversationUseCase(db, ConversationRepository(db)).create("failed", { agents.createSnapshot(saved.id) },
                bindWorkspace = { error("fixture failure after greeting") })
        }
        assertTrue(db.query("SELECT id FROM conversations").isEmpty())
        assertTrue(db.query("SELECT id FROM agent_snapshots").isEmpty())
        assertTrue(db.query("SELECT id FROM messages").isEmpty())
        assertTrue(db.query("SELECT conversation_id FROM world_book_conversations").isEmpty())
    }

    @Test fun migrationFrom35PreservesOldAgentAndMessagesAndAddsNullMetadata() = database { db ->
        val agents = AgentRepository(db)
        val old = agents.saveWithPrompt(candidate().copy(characterCard = null), "Old prompt")
        val conversations = ConversationRepository(db)
        val chat = conversations.create(agents.createSnapshot(old.id).id, "old")
        val message = conversations.append(chat.id, MessageRole.USER, "Preserve me")
        db.execute("ALTER TABLE agent_profiles DROP COLUMN character_card_json")
        db.execute("UPDATE schema_version SET version=35")
        Migrations.apply(db)
        assertEquals(36L, db.query("SELECT version FROM schema_version").single().long("version"))
        assertNull(agents.get(old.id)!!.characterCard)
        assertEquals(listOf(message), conversations.messages(chat.id))
        assertEquals("Old prompt", agents.resolveSnapshot(chat.snapshotId).prompt.template)
    }

    @Test fun failedCharacterColumnMigrationRetainsVersionAndOriginalRows() = database { db ->
        AgentRepository(db).saveWithPrompt(candidate().copy(characterCard = null), "Old prompt")
        db.execute("ALTER TABLE agent_profiles DROP COLUMN character_card_json")
        db.execute("UPDATE schema_version SET version=35")
        val failing = object : SqlConnection by db {
            override fun execute(sql: String, args: List<Any?>) {
                if (sql.contains("ADD COLUMN character_card_json")) error("fixture DDL failure")
                db.execute(sql, args)
            }
        }
        assertThrows(IllegalStateException::class.java) { Migrations.apply(failing) }
        assertEquals(35L, db.query("SELECT version FROM schema_version").single().long("version"))
        assertEquals("青禾", db.query("SELECT name FROM agent_profiles").single().string("name"))
        assertTrue(db.query("PRAGMA table_info(agent_profiles)").none { it.string("name") == "character_card_json" })
    }

    private fun candidate(): AgentProfile {
        val original = Json.parseToJsonElement("""{"spec":"chara_card_v2","data":{"name":"青禾","extensions":{"script":"never execute"}}}""").jsonObject
        val doc = CharacterCardDocument("青禾", description = "管理白塔的{{char}}", personality = "耐心的法师", scenario = "白塔",
            firstMessage = "你好{{user}}，我是{{char}}。", tags = listOf("法师"), originalJson = original)
        return AgentProfile("character", "青禾", "pending", "model", revision = 0,
            characterCard = ImportedCharacterCard(doc, "旅人", doc.firstMessage))
    }
    private fun book() = WorldBookDocument("白塔", listOf(WorldBookEntry("1", listOf("白塔"), "白塔黄昏关闭")))
    private fun database(block: (JdbcSqlConnection) -> Unit) = JdbcSqlConnection().use { db ->
        Migrations.apply(db)
        val profiles = ProfileRepository(db)
        profiles.createProvider(ProviderProfile("provider", "Fixture", ApiFormat.OPENAI_COMPATIBLE,
            "https://example.invalid/v1", secretRef = "fixture-ref", revision = 1))
        profiles.createModel(ModelProfile("model", "provider", ModelRole.CHAT, "fixture",
            capabilities = setOf("stream"), contextLimit = 32768, outputLimit = 1024, revision = 1))
        block(db)
    }
}
