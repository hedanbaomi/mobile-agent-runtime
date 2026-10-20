// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import runtime.mobileagent.data.CharacterCardAgentImporter
import runtime.mobileagent.data.CreateConversationUseCase
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.storage.BundledSqliteConnection
import runtime.mobileagent.domain.*
import runtime.mobileagent.serialization.CharacterCardSupport
import runtime.mobileagent.serialization.charactercard.CharacterCardCodec

/** Captures real wire requests for both adapters; no external provider traffic. */
class CharacterCardRuntimeDeviceTest {
    @Test fun physicalSchema35UpgradeAndFailedDdlAreAtomicOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "character-migration-${UUID.randomUUID()}.db")
        try { BundledSqliteConnection(file.absolutePath).use { db ->
            Migrations.apply(db)
            db.execute("ALTER TABLE agent_profiles DROP COLUMN character_card_json")
            db.execute("UPDATE schema_version SET version=35")
            db.execute("CREATE TABLE character_migration_sentinel(value TEXT)")
            db.execute("INSERT INTO character_migration_sentinel VALUES('保留旧资料')")
            val failing = object : SqlConnection by db {
                override fun execute(sql: String, args: List<Any?>) {
                    if (sql.contains("ADD COLUMN character_card_json")) error("fixture DDL failure")
                    db.execute(sql, args)
                }
            }
            try { Migrations.apply(failing); fail("DDL failure must surface") } catch (_: IllegalStateException) { }
            assertEquals(35L, db.query("SELECT version FROM schema_version").single().long("version"))
            assertFalse(db.query("PRAGMA table_info(agent_profiles)").any { it.string("name") == "character_card_json" })
            Migrations.apply(db)
            assertEquals(36L, db.query("SELECT version FROM schema_version").single().long("version"))
            assertTrue(db.query("PRAGMA table_info(agent_profiles)").any { it.string("name") == "character_card_json" })
            assertEquals("保留旧资料", db.query("SELECT value FROM character_migration_sentinel").single().string("value"))
        } } finally { file.delete(); java.io.File(file.path + "-wal").delete(); java.io.File(file.path + "-shm").delete() }
    }

    @Test fun importedPromptGreetingAndChineseBookReachBothProtocols() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val c = app.container
        for (format in ApiFormat.entries) WorldBookLoopback(format, false).use { server ->
            val id = UUID.randomUUID().toString()
            val ref = "character-fixture-$id"
            c.secrets.put(ref, "synthetic-character-fixture".toCharArray())
            try {
                val provider = ProviderProfile("p-$id", "Card fixture", format, "http://127.0.0.1:${server.port}/v1", secretRef = ref, revision = 1)
                c.profiles.createProvider(provider)
                val model = ModelProfile("m-$id", provider.id, ModelRole.CHAT, "card-fixture", setOf("stream"),
                    contextLimit = 64000, outputLimit = 1024, revision = 1)
                c.profiles.createModel(model)
                val imported = CharacterCardCodec.decode(CARD.toByteArray(), "fixture.json")
                val card = ImportedCharacterCard(imported.document, "旅人", imported.document.firstMessage)
                val saved = CharacterCardAgentImporter(c.db).saveNew(
                    AgentProfile("a-$id", "青禾", "pending", model.id, revision = 0, characterCard = card),
                    CharacterCardSupport.prompt(card), imported.worldBook).profile
                val chat = CreateConversationUseCase(c.db, c.conversations).create("Card test", { c.agents.createSnapshot(saved.id) })
                assertEquals("你好旅人，我是青禾。", c.conversations.messages(chat.id).single().text)
                lateinit var vm: ChatViewModel
                instrumentation.runOnMainSync {
                    vm = ChatViewModel(app, SavedStateHandle())
                    vm.selectSession(chat.id); vm.input("请介绍白塔"); vm.send()
                }
                val deadline = System.currentTimeMillis() + 30000
                while (vm.state.value.streaming && System.currentTimeMillis() < deadline) Thread.sleep(30)
                assertFalse("Run did not finish", vm.state.value.streaming)
                assertEquals(vm.state.value.status, RunStatus.COMPLETED, c.runs.list(chat.id).last().state)
                val request = server.requests.single().toString()
                assertTrue(request, request.contains("青禾陪伴旅人探索"))
                assertTrue(request, request.contains("你好旅人，我是青禾。"))
                assertTrue(request, request.contains("CHARACTER_BOOK_SENTINEL"))
                assertFalse(request, request.contains("{{character_user}}"))
                server.assertHealthy()
            } finally { c.db.execute("DELETE FROM secrets WHERE ref=?", listOf(ref)) }
        }
    }
    companion object {
        private const val CARD = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"青禾","description":"{{char}}陪伴{{user}}探索","first_mes":"你好{{user}}，我是{{char}}。","mes_example":"","character_book":{"name":"白塔","entries":[{"id":1,"keys":["白塔"],"content":"CHARACTER_BOOK_SENTINEL","enabled":true,"insertion_order":100}]}}}"""
    }
}
