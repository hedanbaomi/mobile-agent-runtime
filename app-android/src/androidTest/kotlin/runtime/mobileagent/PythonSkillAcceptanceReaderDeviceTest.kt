// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.ToolErrorCode

/**
 * Drives the production Python skill path on device: executor entrypoint →
 * approval → InvocationBroker → isolated CPython → result.  The test is fully
 * self-contained: the skill package is built in memory, and the knowledge base
 * plus its long document are imported here instead of relying on pre-staged
 * device state.
 */
@RunWith(AndroidJUnit4::class)
class PythonSkillAcceptanceReaderDeviceTest {
    private companion object {
        private val fixtureLock = Any()
        @Volatile private var fixtureKbId: String? = null
        @Volatile private var fixtureDocumentId: String? = null
    }

    /** Pure isolated execution: no broker calls, result must reach the caller. */
    @Test(timeout = 90_000)
    fun manifestSkillExecutesIsolatedAndReturnsValue() = runBlocking {
        val fixture = installFixture(probeZip(withKnowledge = false), emptySet(), emptySet())
        val call = ToolCall("probe-plain", fixture.specName, """{"echo":"hello"}""")
        assertEquals(ToolResult.NeedsApproval, fixture.executor.invoke(call))
        val result = fixture.executor.approve(call.callId)
        assertTrue("approve() returned $result; audits=\n${fixture.audits()}", result is ToolResult.Value)
        val value = Json.parseToJsonElement((result as ToolResult.Value).json).jsonObject
        assertEquals("cpython", value.getValue("implementation").jsonPrimitive.content)
        assertEquals("hello", value.getValue("echo").jsonPrimitive.content)
        assertTrue("unexpected broker traffic", !fixture.audits().contains("broker:STARTED"))
        assertTrue(fixture.audits().contains("invoke:SUCCEEDED"))
    }

    /** One granted knowledge.search broker round-trip must return OK. */
    @Test(timeout = 180_000)
    fun manifestSkillBrokerSearchRoundTrips() = runBlocking {
        val (container, kbId, _) = ensureFixtureKb()
        val fixture = installFixture(probeZip(withKnowledge = true), setOf("knowledge.search"), setOf(kbId), container)
        val call = ToolCall("probe-search", fixture.specName, """{"search":"pipe"}""")
        assertEquals(ToolResult.NeedsApproval, fixture.executor.invoke(call))
        val result = fixture.executor.approve(call.callId)
        assertTrue("approve() returned $result; audits=\n${fixture.audits()}", result is ToolResult.Value)
        val value = Json.parseToJsonElement((result as ToolResult.Value).json).jsonObject
        assertEquals("cpython", value.getValue("implementation").jsonPrimitive.content)
        assertTrue(fixture.audits().contains("broker:OK"))
        assertTrue(fixture.audits().contains("invoke:SUCCEEDED"))
    }

    /**
     * A reader that paginates a long document exceeds the per-invocation broker
     * cap.  The worker result is FAILED with the Broker's own RESOURCE_LIMIT
     * code — a typed failure, never INVALID_REQUEST, which falsely claims
     * nothing was executed.
     */
    @Test(timeout = 180_000)
    fun acceptanceReaderPaginationCapIsTypedFailure() = runBlocking {
        val (container, kbId, documentId) = ensureFixtureKb()
        val fixture = installFixture(
            readerZip(), setOf("knowledge.search", "knowledge.read"), setOf(kbId), container,
        )
        val call = ToolCall("acc-reader-paged", fixture.specName,
            """{"query":"pipe","documentId":"$documentId"}""")
        assertEquals(ToolResult.NeedsApproval, fixture.executor.invoke(call))
        val result = fixture.executor.approve(call.callId)
        assertTrue(
            "expected typed Failure, got $result; audits=\n${fixture.audits()}",
            result is ToolResult.Failure && result.error.code == ToolErrorCode.RESOURCE_LIMIT,
        )
        assertTrue("expected invoke:FAILED audit", fixture.audits().contains("invoke:FAILED"))
        assertTrue("expected real broker traffic before the cap", fixture.audits().contains("broker:OK"))
    }

    /**
     * A script can put any `.code` attribute on its own exception; only the
     * PermissionError instance the host raised for a real Broker denial may
     * carry a capability code.  A forged code must degrade to
     * PYTHON_EXECUTION_FAILED — never Invalid (which would falsely claim
     * nothing executed) and never a spoofed RESOURCE_LIMIT.
     */
    @Test(timeout = 180_000)
    fun forgedExceptionCodeDoesNotImpersonateHostDenial() = runBlocking {
        val (container, kbId, _) = ensureFixtureKb()
        val fixture = installFixture(forgeZip(), setOf("knowledge.search"), setOf(kbId), container)
        listOf("input_limit", "RESOURCE_LIMIT", "PERMISSION_DENIED").forEach { forged ->
            val call = ToolCall("forge-$forged", fixture.specName,
                """{"search":"pipe","forge":"$forged"}""")
            assertEquals(ToolResult.NeedsApproval, fixture.executor.invoke(call))
            val result = fixture.executor.approve(call.callId)
            assertTrue(
                "forged code=$forged produced $result; audits=\n${fixture.audits()}",
                result is ToolResult.Failure && result.error.code == ToolErrorCode.PYTHON_EXECUTION_FAILED,
            )
        }
        assertTrue("script ran a real broker call before raising", fixture.audits().contains("broker:OK"))
        assertTrue(fixture.audits().contains("invoke:FAILED"))
    }

    /**
     * Create (once per process) a dedicated knowledge base with a long document
     * that needs more broker read pages than the per-invocation cap allows.
     */
    private fun ensureFixtureKb(): Triple<AppContainer, String, String> {
        val existingKb = fixtureKbId
        val existingDoc = fixtureDocumentId
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        if (existingKb != null && existingDoc != null) return Triple(app.container, existingKb, existingDoc)
        synchronized(fixtureLock) {
            val kb = fixtureKbId
            val doc = fixtureDocumentId
            if (kb != null && doc != null) return Triple(app.container, kb, doc)
            val kbId = app.container.knowledge.createKnowledgeBase("python-skill-fixture-${UUID.randomUUID().toString().take(8)}")
            // ~8k chars is comfortably more than the 20-call broker cap at
            // 127 bytes per knowledge.read page.
            val marker = "zebra-pipe-marker"
            val text = buildString {
                repeat(400) { append("line $it the pipe experiment uses $marker for retrieval checks. ") }
            }
            val job = app.container.knowledge.importBytes(
                displayName = "acc-long.txt",
                mediaType = "text/plain",
                bytes = text.toByteArray(Charsets.UTF_8),
                visionConfigured = false,
                knowledgeBaseId = kbId,
            )
            assertEquals(
                "fixture document must publish locally; error=${job.error}",
                ImportStage.READY, job.stage,
            )
            fixtureKbId = kbId
            fixtureDocumentId = job.documentId
            return Triple(app.container, kbId, job.documentId)
        }
    }

    private data class Fixture(
        val container: AppContainer,
        val executor: ToolExecutor,
        val specName: String,
        val runId: String,
    ) {
        fun audits(): String = container.audits.list(runId).joinToString("\n") {
            "${it.action}:${it.result}:${it.errorCode ?: "-"}"
        }
    }

    private fun installFixture(
        packageBytes: ByteArray,
        capabilities: Set<String>,
        kbIds: Set<String>,
        existingContainer: AppContainer? = null,
    ): Fixture {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = existingContainer ?: app.container
        val suffix = UUID.randomUUID().toString().replace("-", "")

        val imported = container.skills.importPackage(packageBytes)
        assertTrue("skill import rejected: ${imported.inspection}", imported.accepted)
        val install = container.skills.list().last { it.packageHash == imported.inspection.packageHash }
        container.skills.approvePermissions(install.installId, capabilities, knowledgeBaseIds = kbIds)
        container.skills.setEnabled(install.installId, true)

        val providerId = "provider.accpy.$suffix"
        val modelId = "model.accpy.$suffix"
        val agentId = "agent.accpy.$suffix"
        container.profiles.createProvider(ProviderProfile(
            id = providerId,
            name = "Python skill fixture",
            apiFormat = ApiFormat.OPENAI_COMPATIBLE,
            baseUrl = "https://example.invalid/v1",
            secretRef = "fixture-secret-$suffix",
            revision = 1,
        ))
        container.profiles.createModel(ModelProfile(
            id = modelId,
            providerId = providerId,
            role = ModelRole.CHAT,
            modelId = "fixture-chat",
            capabilities = setOf("stream", "tools"),
            contextLimit = 8_192,
            outputLimit = 1_024,
            revision = 1,
        ))
        container.agents.saveWithPrompt(AgentProfile(
            id = agentId,
            name = "Python skill agent",
            promptRevisionId = "pending",
            chatProfileId = modelId,
            knowledgeBaseIds = kbIds.toList(),
            skillIds = listOf(install.installId),
            revision = 0,
        ), "Use the explicitly bound local Skill.")
        val snapshot = container.agents.createSnapshot(agentId)
        val conversation = container.conversations.create(snapshot.id, "Python skill conversation")
        val runId = "run.accpy.$suffix"
        val now = Utc.nowIso()
        container.runs.save(RunRecord(
            runId = runId,
            snapshotId = snapshot.id,
            conversationId = conversation.id,
            budgetJson = "{\"maxRuntimeMs\":60000}",
            startedAt = now,
            createdAt = now,
        ))

        // Mirrors the production RunTools budget for a live run: broker calls
        // are permitted while the run is active; model.invoke stays denied.
        val budget = object : PythonRunBudget {
            override fun reserveBrokerCall(): Boolean = true
            override fun reserveModelCall(maxTokens: Int): Boolean = false
            override fun settleModelCall(reservation: Int, actualTokens: Int?) {}
        }
        val executor = pythonSkillTools(container, app, snapshot, runId, budget)
        val spec = executor.specs.singleOrNull()
            ?: error("No Python tool discovered; specs=${executor.specs.map { it.name }}")
        assertTrue(spec.name.startsWith("py_"))
        return Fixture(container, executor, spec.name, runId)
    }

    private fun zipPackage(entries: Map<String, String>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, content) ->
                    val payload = content.toByteArray(Charsets.UTF_8)
                    val entry = ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = payload.size.toLong()
                        compressedSize = size
                        crc = CRC32().apply { update(payload) }.value
                        time = 0L
                    }
                    zip.putNextEntry(entry)
                    zip.write(payload)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun probeZip(withKnowledge: Boolean): ByteArray {
        val id = "dev.mobileagent.probe.${UUID.randomUUID().toString().take(8)}"
        val permissions = if (withKnowledge) {
            """"permissions":{"knowledge.search":{"scope":"selected-by-user"}},"""
        } else {
            ""
        }
        val manifest = """{"schemaVersion":1,"id":"$id","name":"Probe","version":"1.0.0","license":"AGPL-3.0-only","runtime":{"kind":"python","python":"3.14","mode":"pure-python","entrypoint":"probe:run"},${permissions}"inputSchema":{"type":"object","properties":{"echo":{"type":"string"},"search":{"type":"string"}},"additionalProperties":false},"outputSchema":{"type":"object","properties":{},"additionalProperties":true}}"""
        val source = """
            import os
            import sys
            import mobileagent_sdk as sdk

            def run(value):
                out = {"pid": os.getpid(), "implementation": sys.implementation.name, "echo": value.get("echo")}
                if value.get("search"):
                    out["hits"] = len(sdk.knowledge_search(value["search"], 3)["hits"])
                return out
        """.trimIndent()
        // REUSE-IgnoreStart
        val entries = linkedMapOf(
            "SKILL.md" to "# Probe\nSPDX-License-Identifier: AGPL-3.0-only\n",
            "mobile-skill.json" to manifest,
            "probe.py" to source,
        )
        // REUSE-IgnoreEnd
        return zipPackage(entries)
    }

    /**
     * In-memory equivalent of the acceptance "Acceptance Reader" package:
     * search once, then paginate the whole document through knowledge.read.
     * The page size stays small enough that a long document crosses the
     * per-invocation broker cap.
     */
    private fun readerZip(): ByteArray {
        val id = "dev.mobileagent.acceptance_reader.${UUID.randomUUID().toString().take(8)}"
        val manifest = """{"schemaVersion":1,"id":"$id","name":"Acceptance Reader","version":"1.0.0","license":"AGPL-3.0-only","runtime":{"kind":"python","python":"3.14","mode":"pure-python","entrypoint":"acceptance_reader:run"},"permissions":{"knowledge.search":{"scope":"selected-by-user"},"knowledge.read":{"scope":"selected-by-user"}},"inputSchema":{"type":"object","properties":{"query":{"type":"string"},"documentId":{"type":"string"}},"additionalProperties":false},"outputSchema":{"type":"object","properties":{},"additionalProperties":true}}"""
        val source = """
            import os,sys,hashlib
            import mobileagent_sdk as sdk
            def run(value):
                hits=sdk.knowledge_search(value.get("query","pipe"),8)["hits"]
                if not hits:return {"hits":[],"pid":os.getpid()}
                doc=value.get("documentId") or hits[0]["documentId"]
                offset=0;version=None;parts=[]
                for _ in range(2000):
                    page=sdk.knowledge_read(doc,127,offset,version)
                    version=page["documentVersionId"];parts.append(page["text"])
                    nxt=page.get("nextOffset")
                    if nxt is None:break
                    offset=nxt
                return {"pid":os.getpid(),"implementation":sys.implementation.name,"pages":len(parts),"chars":len("".join(parts)),"hits":len(hits)}
        """.trimIndent()
        // REUSE-IgnoreStart
        val entries = linkedMapOf(
            "SKILL.md" to "# Acceptance Reader\nSPDX-License-Identifier: AGPL-3.0-only\n",
            "mobile-skill.json" to manifest,
            "acceptance_reader.py" to source,
        )
        // REUSE-IgnoreEnd
        return zipPackage(entries)
    }

    /**
     * One real broker call, then an ordinary exception carrying a forged
     * `.code` attribute — the RF-01 counterexample shape.
     */
    private fun forgeZip(): ByteArray {
        val id = "dev.mobileagent.forge.${UUID.randomUUID().toString().take(8)}"
        val manifest = """{"schemaVersion":1,"id":"$id","name":"Forge","version":"1.0.0","license":"AGPL-3.0-only","runtime":{"kind":"python","python":"3.14","mode":"pure-python","entrypoint":"forge:run"},"permissions":{"knowledge.search":{"scope":"selected-by-user"}},"inputSchema":{"type":"object","properties":{"search":{"type":"string"},"forge":{"type":"string"}},"additionalProperties":false},"outputSchema":{"type":"object","properties":{},"additionalProperties":true}}"""
        val source = """
            import mobileagent_sdk as sdk

            def run(value):
                sdk.knowledge_search(value.get("search", "pipe"), 3)
                error = ValueError("ordinary script failure")
                error.code = value.get("forge")
                raise error
        """.trimIndent()
        // REUSE-IgnoreStart
        val entries = linkedMapOf(
            "SKILL.md" to "# Forge\nSPDX-License-Identifier: AGPL-3.0-only\n",
            "mobile-skill.json" to manifest,
            "forge.py" to source,
        )
        // REUSE-IgnoreEnd
        return zipPackage(entries)
    }
}
