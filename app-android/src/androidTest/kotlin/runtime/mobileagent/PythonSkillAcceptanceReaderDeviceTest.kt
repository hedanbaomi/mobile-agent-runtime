// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
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
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.ToolErrorCode

/**
 * Drives the production Python skill path on device: executor entrypoint →
 * approval → InvocationBroker → isolated CPython → result.  Covers the
 * non-legacy manifest form (`module:function`, custom inputSchema) used by the
 * acceptance "Acceptance Reader" package, which is also reproduced for the
 * broker-call cap regression.
 */
@RunWith(AndroidJUnit4::class)
class PythonSkillAcceptanceReaderDeviceTest {
    private companion object {
        /** acc-long.txt in the AcceptBak base on the acceptance device (~1000 lines). */
        const val ACCEPTANCE_LONG_DOC_ID = "327a05d9-30ea-4dbf-a5be-256bc6012406"
    }

    /** Pure isolated execution: no broker calls, result must reach the caller. */
    @Test(timeout = 90_000)
    fun manifestSkillExecutesIsolatedAndReturnsValue() = runBlocking {
        val fixture = installFixture(probeZip(withKnowledge = false), emptySet())
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
    @Test(timeout = 90_000)
    fun manifestSkillBrokerSearchRoundTrips() = runBlocking {
        val fixture = installFixture(probeZip(withKnowledge = true), setOf("knowledge.search"))
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
     * The acceptance "Acceptance Reader" package paginates a long document and
     * exceeds the per-invocation broker cap (native or host side).  The worker
     * result is FAILED and must surface as a typed Failure — never as
     * INVALID_REQUEST, which falsely claims nothing was executed.
     */
    @Test(timeout = 90_000)
    fun acceptanceReaderPaginationCapIsTypedFailure() = runBlocking {
        val packageBytes = File("/data/local/tmp/acceptance-reader.zip").readBytes()
        val fixture = installFixture(packageBytes, setOf("knowledge.search", "knowledge.read"))
        val call = ToolCall("acc-reader-paged", fixture.specName,
            """{"query":"pipe","documentId":"$ACCEPTANCE_LONG_DOC_ID"}""")
        assertEquals(ToolResult.NeedsApproval, fixture.executor.invoke(call))
        val result = fixture.executor.approve(call.callId)
        assertTrue(
            "expected typed Failure, got $result; audits=\n${fixture.audits()}",
            result is ToolResult.Failure && result.error.code == ToolErrorCode.PYTHON_EXECUTION_FAILED,
        )
        assertTrue("expected invoke:FAILED audit", fixture.audits().contains("invoke:FAILED"))
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

    private fun installFixture(packageBytes: ByteArray, capabilities: Set<String>): Fixture {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container
        val suffix = UUID.randomUUID().toString().replace("-", "")

        val imported = container.skills.importPackage(packageBytes)
        assertTrue("skill import rejected: ${imported.inspection}", imported.accepted)
        val install = container.skills.list().last { it.packageHash == imported.inspection.packageHash }
        val kbIds = if (capabilities.any { it.startsWith("knowledge.") }) {
            val kbId = container.knowledge.listKnowledgeBases().firstOrNull()?.first
                ?: error("No knowledge base installed on this device")
            setOf(kbId)
        } else {
            emptySet()
        }
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

    private fun probeZip(withKnowledge: Boolean): ByteArray {
        val permissions = if (withKnowledge) {
            """"permissions":{"knowledge.search":{"scope":"selected-by-user"}},"""
        } else {
            ""
        }
        val manifest = """{"schemaVersion":1,"id":"dev.mobileagent.probe","name":"Probe","version":"1.0.0","license":"AGPL-3.0-only","runtime":{"kind":"python","python":"3.14","mode":"pure-python","entrypoint":"probe:run"},${permissions}"inputSchema":{"type":"object","properties":{"echo":{"type":"string"},"search":{"type":"string"}},"additionalProperties":false},"outputSchema":{"type":"object","properties":{},"additionalProperties":true}}"""
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
        return ByteArrayOutputStream().also { output ->
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
    }
}
