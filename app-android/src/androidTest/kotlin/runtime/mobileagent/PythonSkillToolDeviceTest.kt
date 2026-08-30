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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
import runtime.mobileagent.skills.ToolResult

/** App-level proof that an imported Claude Skill reaches the model-visible executor and real isolated CPython. */
@RunWith(AndroidJUnit4::class)
class PythonSkillToolDeviceTest {
    @Test(timeout = 60_000)
    fun importedBoundClaudeSkillIsDiscoveredApprovedAndExecuted() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val program = "lieflat/scripts/check-structure.py"
        val source = """
            import re
            import sys
            from pathlib import Path

            def main():
                files = list(Path(sys.argv[1]).rglob("*.md"))
                text = files[0].read_text(encoding="utf-8")
                print(f"{files[0].parent.name}|{files[0].stem}|{len(re.findall(r'[一-鿿]', text))}")

            if __name__ == "__main__":
                main()
        """.trimIndent()
        val packageBytes = legacyClaudeZip(program, source)
        val imported = container.skills.importPackage(packageBytes)
        assertTrue(imported.accepted)
        val install = container.skills.list().single { it.packageHash == imported.inspection.packageHash }
        container.skills.approvePermissions(install.installId, emptySet())
        container.skills.setEnabled(install.installId, true)

        val providerId = "provider.skilltool.$suffix"
        val modelId = "model.skilltool.$suffix"
        val agentId = "agent.skilltool.$suffix"
        container.profiles.createProvider(ProviderProfile(
            id = providerId,
            name = "Skill tool device fixture",
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
            name = "Skill tool device agent",
            promptRevisionId = "pending",
            chatProfileId = modelId,
            skillIds = listOf(install.installId),
            revision = 0,
        ), "Use the explicitly bound local Skill.")
        val snapshot = container.agents.createSnapshot(agentId)
        val conversation = container.conversations.create(snapshot.id, "Skill tool device conversation")
        val runId = "run.skilltool.$suffix"
        val now = Utc.nowIso()
        container.runs.save(RunRecord(
            runId = runId,
            snapshotId = snapshot.id,
            conversationId = conversation.id,
            budgetJson = "{\"maxRuntimeMs\":60000}",
            startedAt = now,
            createdAt = now,
        ))

        val executor = pythonSkillTools(container, app, snapshot, runId)
        val spec = executor.specs.single()
        assertTrue(spec.name.startsWith("py_"))
        assertTrue(spec.description.contains(program))
        val arguments = buildJsonObject {
            put("program", program)
            putJsonArray("arguments") { add(JsonPrimitive("corpus")) }
            putJsonArray("files") {
                add(buildJsonObject {
                    put("path", "corpus/example.md")
                    put("text", "测试文字")
                })
            }
        }.toString()
        val call = ToolCall("legacy-device-call", spec.name, arguments)

        assertEquals(ToolResult.NeedsApproval, executor.invoke(call))
        val result = executor.approve(call.callId)
        assertTrue(result is ToolResult.Value)
        val value = Json.parseToJsonElement((result as ToolResult.Value).json).jsonObject
        assertEquals(program, value.getValue("program").jsonPrimitive.content)
        assertEquals("corpus|example|4\n", value.getValue("stdout").jsonPrimitive.content)
    }

    private fun legacyClaudeZip(program: String, source: String): ByteArray {
        val entries = linkedMapOf(
            "lieflat/SKILL.md" to "---\nname: lieflat-less-ai-tone\n---\n# 去 AI 味\n",
            program to source,
        )
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
