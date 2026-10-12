// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.data.*
import runtime.mobileagent.domain.*

class ChatRunPreflightTest {
    @Test fun uploadedOriginalReachesBothProviderProtocolsAndMainAnswerUsesAnalysis() = runBlocking {
        withConversation { db, conversations, _ ->
            val sink = runtime.mobileagent.knowledge.MemoryBlobSink()
            val images = ConversationImageRepository(db, conversations, sink)
            val bytes = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jGd0AAAAASUVORK5CYII=")
            val user = images.appendUser("conversation", "", listOf(ChatImageUpload(bytes, "image/png")))
            val references = images.references("conversation", user.parts.filterIsInstance<ImagePart>())
            listOf(ApiFormat.OPENAI_COMPATIBLE, ApiFormat.OPENAI_RESPONSES).forEach { format ->
                val bodies = mutableListOf<String>()
                val client = HttpClient(MockEngine { request ->
                    bodies += (request.body as TextContent).text
                    val text = if (bodies.size == 1) "A small image" else "The answer"
                    val stream = if (format == ApiFormat.OPENAI_COMPATIBLE)
                        "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"}}]}\n\ndata: [DONE]\n\n"
                    else "data: {\"type\":\"response.output_text.delta\",\"delta\":\"$text\"}\n\n" +
                        "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
                    respond(stream, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
                })
                try {
                    val adapter = runtime.mobileagent.provider.openai.OpenAiAdapterFactory.create(format, client, "https://example.invalid/v1")
                    val events = runtime.mobileagent.agent.AgentRuntime(adapter).run(runtime.mobileagent.agent.AgentRuntimeRequest(
                        runtime.mobileagent.agent.AgentRun("run-${format.name}", "snapshot", "conversation"),
                        runtime.mobileagent.agent.EffectivePrompt("Runtime", "System", emptyList(), emptyList(), emptyList(), "", references),
                        "synthetic", "test-token".toCharArray(), false, batchAllImages = true, maxInputBudgetUnits = 16384,
                        imageLoader = { images.load("conversation", it) },
                        beforeImageRequest = { group -> group.forEach { images.verify("conversation", it) } },
                    )).toList()
                    assertEquals(2, bodies.size, events.toString())
                    val url = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
                    assertTrue(bodies[0].contains(url), bodies[0])
                    assertTrue(bodies[0].contains(if (format == ApiFormat.OPENAI_COMPATIBLE) "image_url" else "input_image"))
                    assertFalse(bodies[1].contains("data:image/"))
                    assertTrue(bodies[1].contains("A small image"))
                    assertTrue(events.filterIsInstance<runtime.mobileagent.agent.RuntimeEvent.RunFinished>().single().state == runtime.mobileagent.agent.RunState.COMPLETED)
                } finally { client.close() }
            }
        }
    }

    @Test fun unknownGateDoesNotConsumeOrStoreUploadedImages() = runBlocking {
        withConversation { db, conversations, runs ->
            val sink = runtime.mobileagent.knowledge.MemoryBlobSink()
            val images = ConversationImageRepository(db, conversations, sink)
            runs.create(RunRecord("run", "snapshot", "conversation", state = RunStatus.UNKNOWN_OUTCOME, createdAt = AT))
            val result = ChatRunPreflight(runs, conversations, TransferRepository(db), null, null, images)
                .prepare("conversation", "", listOf(runtime.mobileagent.domain.ChatImageUpload("GIF89a".toByteArray(), "image/gif"))) {
                    fail<Unit>("Unknown run must not persist")
                }
            assertEquals(ChatPreflightResult.Unknown("run"), result)
            assertTrue(conversations.messages("conversation").isEmpty())
            assertTrue(sink.storedHashes().isEmpty())
        }
    }

    @Test fun imageOnlyTurnSurvivesBindingFailure() = runBlocking {
        withConversation { db, conversations, runs ->
            val sink = runtime.mobileagent.knowledge.MemoryBlobSink()
            val images = ConversationImageRepository(db, conversations, sink)
            var projected: Message? = null
            val result = ChatRunPreflight(runs, conversations, TransferRepository(db), null, null, images)
                .prepare("conversation", "", listOf(runtime.mobileagent.domain.ChatImageUpload("GIF89a".toByteArray(), "image/gif"))) { projected = it }
            assertTrue(result is ChatPreflightResult.Failed)
            assertTrue((result as ChatPreflightResult.Failed).userMessagePersisted)
            assertEquals(projected, conversations.messages("conversation").single())
            assertEquals(1, projected!!.parts.filterIsInstance<ImagePart>().size)
            assertEquals(1, sink.storedHashes().size)
        }
    }

    @Test fun imageTurnForTextOnlyModelOrDegradationIsRejectedBeforePersistence() = runBlocking {
        withConversation { db, conversations, runs ->
            val profiles = ProfileRepository(db)
            profiles.createProvider(ProviderProfile("provider", "Fixture", ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1", revision = 1))
            profiles.createModel(ModelProfile("text", "provider", ModelRole.CHAT, "synthetic-text", emptySet(), contextLimit = 16384, outputLimit = 512, revision = 1))
            profiles.createModel(ModelProfile("vision", "provider", ModelRole.CHAT, "synthetic-vision", setOf("image"), contextLimit = 16384, outputLimit = 512, revision = 1))
            val agents = AgentRepository(db)
            agents.saveWithPrompt(AgentProfile("text-agent", "Text", "pending", "text", revision = 0), "Synthetic prompt")
            agents.saveWithPrompt(AgentProfile("vision-agent", "Vision", "pending", "vision", revision = 0), "Synthetic prompt")
            agents.createSnapshot("text-agent", "text-snapshot")
            agents.createSnapshot("vision-agent", "vision-snapshot")
            conversations.create("text-snapshot", "Text", "text-conversation")
            conversations.create("vision-snapshot", "Vision", "vision-conversation")
            val sink = runtime.mobileagent.knowledge.MemoryBlobSink()
            val preflight = ChatRunPreflight(runs, conversations, TransferRepository(db), null, null,
                ConversationImageRepository(db, conversations, sink))
            val gif = listOf(ChatImageUpload("GIF89a".toByteArray(), "image/gif"))
            listOf("text-conversation" to false, "vision-conversation" to true).forEach { (id, degrade) ->
                val result = preflight.prepare(id, "describe", gif, textOnly = degrade) { fail<Unit>("must not persist") }
                assertFalse((result as ChatPreflightResult.Failed).userMessagePersisted)
                assertTrue(conversations.messages(id).isEmpty())
            }
            assertTrue(sink.storedHashes().isEmpty())
            val accepted = preflight.prepare("vision-conversation", "describe", gif) {}
            assertTrue(accepted is ChatPreflightResult.Prepared, accepted.toString())
        }
    }

    @Test fun unacknowledgedUnknownStopsBeforeNewUserMessage() = runBlocking {
        withConversation { db, conversations, runs ->
            runs.create(RunRecord("run", "snapshot", "conversation",
                state = RunStatus.UNKNOWN_OUTCOME, createdAt = AT))
            var projected = false
            val result = ChatRunPreflight(runs, conversations, TransferRepository(db), null, null)
                .prepare("conversation", "New request") { projected = true }
            assertEquals(ChatPreflightResult.Unknown("run"), result)
            assertFalse(projected)
            assertTrue(conversations.messages("conversation").isEmpty())
        }
    }

    @Test fun bindingFailureKeepsUserMessageAndItsImmediateProjection() = runBlocking {
        withConversation { db, conversations, runs ->
            val projected = mutableListOf<Message>()
            // Deliberately lacks a resolvable model/prompt binding. User persistence must
            // precede that configuration failure and must not be retried by the preparer.
            val result = ChatRunPreflight(runs, conversations, TransferRepository(db), null, null)
                .prepare("conversation", "Retain this first turn") { projected += it }
            assertTrue(result is ChatPreflightResult.Failed)
            assertTrue((result as ChatPreflightResult.Failed).userMessagePersisted)
            assertEquals(1, projected.size)
            assertEquals(projected, conversations.messages("conversation"))
            assertEquals("Retain this first turn", projected.single().text)
        }
    }

    @Test fun missingConversationDoesNotAppendOrProject() = runBlocking {
        withConversation { db, conversations, runs ->
            val result = ChatRunPreflight(runs, conversations, TransferRepository(db), null, null)
                .prepare("missing", "Request") { fail<Unit>("Missing conversation must not project") }
            assertEquals(ChatPreflightResult.MissingConversation, result)
            assertTrue(conversations.messages("conversation").isEmpty())
        }
    }

    private suspend fun withConversation(block: suspend (SqlConnection, ConversationRepository, RunRepository) -> Unit) {
        JdbcConnection().use { db ->
            Migrations.apply(db)
            db.execute("INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                listOf("snapshot", 1, "agent", "prompt", "model", 1, "[]", "[]", AT))
            val conversations = ConversationRepository(db) { AT }
            conversations.create("snapshot", "Preflight fixture", "conversation")
            block(db, conversations, RunRepository(db) { AT })
        }
    }

    private class JdbcConnection : SqlConnection, AutoCloseable {
        private val connection = DriverManager.getConnection("jdbc:sqlite::memory:").apply {
            createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
        }
        override fun execute(sql: String, args: List<Any?>) {
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }; statement.execute()
            }
        }
        override fun query(sql: String, args: List<Any?>): List<SqlRow> = connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { result -> buildList {
                while (result.next()) add(SqlRow((1..result.metaData.columnCount).associate {
                    result.metaData.getColumnLabel(it) to result.getObject(it)
                }))
            } }
        }
        override fun <T> transaction(block: () -> T): T {
            if (!connection.autoCommit) return block()
            connection.autoCommit = false
            return try { block().also { connection.commit() } }
            catch (failure: Throwable) { connection.rollback(); throw failure }
            finally { connection.autoCommit = true }
        }
        override fun close() = connection.close()
    }


    companion object { private const val AT = "2026-10-10T00:00:00Z" }
}
