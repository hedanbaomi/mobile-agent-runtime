// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

class ChatProjectionPersistenceTest {
    @Test fun messagePagesMatchFullHistoryWithEqualTimestampsAndOpaqueIds() {
        JdbcSqlConnection().use { db ->
            val repo = fixture(db)
            repeat(137) { index -> repo.append("conversation", MessageRole.USER, "$index", messageId = "message.${137-index}", createdAt = "2026-10-13T00:00:00Z") }
            var page = repo.messagePage("conversation", limit = 17)
            var collected = page.messages
            assertEquals(17, collected.size)
            while (page.nextBeforeMessageId != null) {
                page = repo.messagePage("conversation", page.nextBeforeMessageId, 17)
                collected = page.messages + collected
            }
            assertEquals(repo.messages("conversation"), collected)
            assertEquals(137, collected.map { it.id }.distinct().size)
            assertThrows(AppException::class.java) { repo.messagePage("other", "message.1") }
            assertThrows(IllegalArgumentException::class.java) { repo.messagePage("conversation", limit = 101) }
        }
    }

    @Test fun conversationPagesKeepArchivedViewsSeparateAndCoverTimestampTies() {
        JdbcSqlConnection().use { db ->
            val repo = fixture(db)
            val snapshot = repo.get("conversation")!!.snapshotId
            repeat(65) { i -> repo.create(snapshot, "session $i", "conversation.$i", "2026-10-13T00:00:00Z").also { if (i % 3 == 0) repo.setArchived(it.id, true) } }
            for (archived in listOf(false, true)) {
                var page = repo.page(archived, limit = 7)
                val all = page.conversations.toMutableList()
                while (page.nextCursor != null) { page = repo.page(archived, page.nextCursor, 7); all += page.conversations }
                assertEquals(repo.list().filter { it.archived == archived }, all)
            }
        }
    }

    @Test fun largeDraftsStayDurableAndIsolatedWithoutBecomingMessages() {
        JdbcSqlConnection().use { db ->
            val conversations = fixture(db)
            val repo = ChatDraftRepository(db)
            val owner = ChatDraftOwner("conversation", "conversation")
            val agent = ChatDraftOwner("agent", "agent")
            val body = "中a\n".repeat(200_000)
            repo.save(owner, body)
            repo.save(agent, "different")
            assertEquals(body, ChatDraftRepository(db).load(owner))
            assertEquals("different", repo.load(agent))
            assertTrue(conversations.messages("conversation").isEmpty())
            assertThrows(IllegalArgumentException::class.java) { repo.save(owner, "x".repeat(1_000_001)) }
            assertEquals(body, repo.load(owner))
            assertTrue(conversations.delete("conversation"))
            assertEquals("", repo.load(owner))
            assertThrows(IllegalStateException::class.java) { repo.save(owner, "cannot resurrect") }
            repo.save(agent, "")
            assertEquals("", repo.load(agent))
        }
    }

    private fun fixture(db: SqlConnection): ConversationRepository {
        Migrations.apply(db)
        val profiles = ProfileRepository(db)
        profiles.createProvider(ProviderProfile("provider", "Fixture", ApiFormat.OPENAI_COMPATIBLE,
            "https://example.invalid/v1", secretRef = "synthetic", revision = 1))
        profiles.createModel(ModelProfile("model", "provider", ModelRole.CHAT, "synthetic", setOf("stream"),
            contextLimit = 4096, outputLimit = 512, revision = 1))
        val agents = AgentRepository(db)
        agents.saveWithPrompt(AgentProfile("agent", "Fixture", "pending", "model", revision = 0), "Fixture")
        val snapshot = agents.createSnapshot("agent")
        return ConversationRepository(db).also { it.create(snapshot.id, "Fixture", "conversation") }
    }
}
