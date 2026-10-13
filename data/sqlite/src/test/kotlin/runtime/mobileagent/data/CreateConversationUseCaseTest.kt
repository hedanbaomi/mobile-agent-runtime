// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

class CreateConversationUseCaseTest {
    @Test fun everyCreationBoundaryRollsBackWithoutPublishingPartialConversation() {
        for (boundary in 0..2) JdbcSqlConnection().use { db ->
            val agents = fixture(db)
            val conversations = ConversationRepository(db)
            assertThrows(IllegalStateException::class.java) {
                CreateConversationUseCase(db, conversations).create("Atomic session", {
                    agents.createSnapshot("agent").also { if (boundary == 0) error("injected snapshot failure") }
                }, { frozen ->
                    db.execute("INSERT INTO app_prefs(key,value) VALUES(?,?)", listOf("mcp.snapshot.v1:" + frozen.id, "pin"))
                    if (boundary == 1) error("injected pin failure")
                }, { conversation ->
                    db.execute("INSERT INTO app_prefs(key,value) VALUES(?,?)", listOf("binding:" + conversation.id, "workspace"))
                    error("injected binding failure")
                })
            }
            assertTrue(conversations.list().isEmpty(), "A partial conversation escaped at boundary $boundary")
            assertTrue(db.query("SELECT id FROM agent_snapshots").isEmpty(), "A failed creation leaked its snapshot")
            assertTrue(db.query("SELECT key FROM app_prefs WHERE key LIKE 'mcp.snapshot.v1:%' OR key LIKE 'binding:%'").isEmpty())
            assertNotNull(agents.get("agent"), "Existing configuration must survive rollback")
        }
    }

    @Test fun successPublishesSnapshotPinsAndConversationAsOneUnit() {
        JdbcSqlConnection().use { db ->
            val agents = fixture(db)
            val conversations = ConversationRepository(db)
            val created = CreateConversationUseCase(db, conversations).create("Atomic session", {
                agents.createSnapshot("agent")
            }, { frozen -> db.execute("INSERT INTO app_prefs(key,value) VALUES(?,?)", listOf("mcp.snapshot.v1:" + frozen.id, "pin")) },
                { conversation -> db.execute("INSERT INTO app_prefs(key,value) VALUES(?,?)", listOf("binding:" + conversation.id, "workspace")) })
            assertEquals(created, conversations.get(created.id))
            assertEquals("agent", agents.getSnapshot(created.snapshotId)?.agentId)
            assertEquals(2, db.query("SELECT key FROM app_prefs WHERE key LIKE 'mcp.snapshot.v1:%' OR key LIKE 'binding:%'").size)
        }
    }

    private fun fixture(db: SqlConnection): AgentRepository {
        Migrations.apply(db)
        val profiles = ProfileRepository(db)
        profiles.createProvider(ProviderProfile("provider", "Fixture", ApiFormat.OPENAI_COMPATIBLE,
            "https://example.invalid/v1", secretRef = "synthetic", revision = 1))
        profiles.createModel(ModelProfile("model", "provider", ModelRole.CHAT, "synthetic", setOf("stream"),
            contextLimit = 4096, outputLimit = 512, revision = 1))
        return AgentRepository(db).also { it.saveWithPrompt(AgentProfile("agent", "Fixture", "pending", "model", revision = 0), "Fixture") }
    }
}
