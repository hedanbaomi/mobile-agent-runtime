// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.agent.AgentRun
import runtime.mobileagent.agent.RunState
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.skills.BuiltinTools
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult

/**
 * EMU-019/033 device regression: the production `enrich()` path must
 *  - rebuild each hit with a citationId reference only — no per-hit
 *    "citations" object array (that field has no reader and bloated output),
 *  - keep the producer's warning and sibling fields through the rebuild.
 * Driven through the real `RunTools.enrich` against a seeded knowledge base so
 * the hit shape cannot regress behind a hand-built expectation.
 */
@RunWith(AndroidJUnit4::class)
class RunToolsEnrichDeviceTest {

    @Test
    fun knowledgeSearchEnrichKeepsCitationIdOnlyAndProducerWarning() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container
        val suffix = UUID.randomUUID().toString().replace("-", "")

        val kb = container.knowledge.createKnowledgeBase("enrich fixture $suffix")
        val seed = buildString {
            append("Enrich fixture document about citation boundaries.\n")
            repeat(8) { append("Paragraph $it carries ordinary filler prose for chunking.\n") }
        }
        val imported = container.knowledge.importBytes(
            "enrich-seed.txt", "text/plain", seed.toByteArray(Charsets.UTF_8), false, kb,
        )
        assertEquals("seed import must be READY: ${imported.error}", ImportStage.READY, imported.stage)
        val versionId = container.db.query(
            "SELECT active_version_id FROM documents WHERE kb_id = ?", listOf(kb),
        ).single().string("active_version_id")
        val chunk = container.db.query(
            "SELECT id, text FROM chunks WHERE document_version_id = ? ORDER BY ordinal LIMIT 1",
            listOf(versionId),
        ).single()
        val chunkId = chunk.string("id")
        val chunkText = chunk.string("text")
        val documentId = imported.documentId

        container.profiles.createProvider(
            ProviderProfile(
                id = "provider.enrich.$suffix",
                name = "Enrich fixture",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "fixture-enrich-$suffix",
                revision = 1,
            ),
        )
        container.profiles.createModel(
            ModelProfile(
                id = "model.enrich.$suffix",
                providerId = "provider.enrich.$suffix",
                role = ModelRole.CHAT,
                modelId = "fixture-enrich",
                capabilities = setOf("stream", "tools"),
                contextLimit = 4096,
                outputLimit = 512,
                revision = 1,
            ),
        )
        val agentId = "agent.enrich.$suffix"
        container.agents.saveWithPrompt(
            AgentProfile(
                id = agentId,
                name = "Enrich fixture",
                promptRevisionId = "pending",
                chatProfileId = "model.enrich.$suffix",
                knowledgeBaseIds = listOf(kb),
                revision = 0,
            ),
            "Enrich fixture agent.",
        )
        val snapshot = container.agents.createSnapshot(agentId)
        val run = AgentRun(
            runId = "run.enrich.$suffix",
            snapshotId = snapshot.id,
            conversationId = "conversation.enrich.$suffix",
            state = RunState.MODEL_STREAMING,
            startedAtMs = System.currentTimeMillis(),
        )
        val runTools = RunTools(container, app, snapshot, run, false, false)

        // Exercise the production broker, local embedding/index lookup and
        // evidence enrichment before checking producer-field preservation.
        val searched = runTools.executor.invoke(ToolCall("model-call-search", BuiltinTools.knowledgeSearch.name,
            """{"query":"citation boundaries","knowledgeBaseIds":["$kb"],"topK":1}"""))
        assertTrue("Real scoped knowledge_search must return usable evidence: $searched", searched is ToolResult.Value)
        val searchedHit = Json.parseToJsonElement((searched as ToolResult.Value).json).jsonObject
            .getValue("hits").jsonArray.single().jsonObject
        assertEquals(kb, searchedHit.getValue("knowledgeBaseId").jsonPrimitive.content)
        assertEquals(documentId, searchedHit.getValue("documentId").jsonPrimitive.content)
        assertTrue("Production search must register citation evidence", runTools.evidence().isNotEmpty())
        assertTrue("Production search must expose its citation reference", "citationId" in searchedHit)

        val raw = ToolResult.Value(
            buildJsonObject {
                put("hits", buildJsonArray {
                    add(buildJsonObject {
                        put("knowledgeBaseId", kb)
                        put("documentId", documentId)
                        put("documentVersionId", versionId)
                        put("chunkId", chunkId)
                        put("text", chunkText)
                    })
                })
                put("warning", JsonPrimitive("producer warning must survive"))
                put("count", JsonPrimitive(1))
            }.toString(),
        )
        val processed = runTools.enrich(
            ToolCall("model-call-enrich", BuiltinTools.knowledgeSearch.name, "{}"),
            raw,
        )
        val root = Json.parseToJsonElement(processed.result.json).jsonObject
        val hit = root.getValue("hits").jsonArray.single().jsonObject
        assertEquals(chunkId, hit["chunkId"]!!.jsonPrimitive.content)
        assertTrue("enriched hit must carry a citationId reference", "citationId" in hit)
        assertFalse("enriched hit must not embed a per-hit citations array", "citations" in hit)
        assertEquals("producer warning must survive the rebuild", "producer warning must survive",
            root["warning"]!!.jsonPrimitive.content)
        assertEquals(1, root["count"]!!.jsonPrimitive.content.toInt())
    }
}
