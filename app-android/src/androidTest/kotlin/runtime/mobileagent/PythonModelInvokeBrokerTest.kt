// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import runtime.mobileagent.agent.AgentRun
import runtime.mobileagent.agent.RunBudget
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.OutputLimitMode
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.modelInvokeRunTokens
import runtime.mobileagent.domain.runBudgetJson
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult

/**
 * The authorized `model.invoke` path end to end on a real device: the imported
 * Skill reaches the isolated interpreter, the isolated interpreter calls the
 * broker over the real IPC protocol, the broker reserves the Run's own fee
 * authorization, and the request leaves through the production RunTools
 * assembly and the real adapter onto a MockEngine transport.
 *
 * Only the provider transport is a mock.  The Run budget JSON is produced by the
 * same production helper the Chat Run creation uses, the executor is the same
 * `RunTools` the runtime builds, the install grant is written by the real
 * repository, and every refusal below is a real fail-closed decision.
 */
@RunWith(AndroidJUnit4::class)
class PythonModelInvokeBrokerTest {
    private inner class Harness(
        val format: ApiFormat,
        val mode: OutputLimitMode,
        val profileOutputLimit: Int,
        val grantMaxCalls: Int,
        val grantMaxTokens: Int,
        /** The user's per-run fee ceiling written into the Agent policy. */
        val policyTokens: Int?,
        /** Skip the derived Run ceiling to model "this Run carries no authorization". */
        val omitRunAuthorization: Boolean = false,
        val requestedToolCap: Int? = null,
    ) {
        lateinit var hostApp: MobileAgentApp
        lateinit var container: AppContainer
        lateinit var providerId: String
        lateinit var modelId: String
        lateinit var agentId: String
        lateinit var installId: String
        lateinit var runId: String
        lateinit var snapshot: runtime.mobileagent.domain.AgentSnapshot
        lateinit var runRecord: RunRecord
        lateinit var specName: String
        lateinit var executor: runtime.mobileagent.skills.ToolExecutor
        val bodies = mutableListOf<String>()

        fun engine(response: String, contentType: String) = MockEngine { request ->
            bodies += (request.body as io.ktor.http.content.TextContent).text
            respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, contentType))
        }

        fun start(response: String, contentType: String) {
            hostApp = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
            hostApp.ensureHostInitialized()
            container = hostApp.container
            val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
            providerId = "provider.pybroker.$suffix"
            modelId = "model.pybroker.$suffix"
            agentId = "agent.pybroker.$suffix"

            container.profiles.createProvider(ProviderProfile(
                id = providerId,
                name = "Python broker fixture",
                apiFormat = format,
                baseUrl = "https://example.invalid/v1",
                secretRef = "fixture-secret-$suffix",
                revision = 1,
            ))
            container.secrets.put("fixture-secret-$suffix", "synthetic-broker-token".toCharArray())
            container.profiles.createModel(ModelProfile(
                id = modelId,
                providerId = providerId,
                role = ModelRole.CHAT,
                modelId = "fixture-model",
                capabilities = setOf("stream"),
                contextLimit = 8_192,
                outputLimit = profileOutputLimit,
                revision = 1,
                outputLimitMode = mode,
            ))

            val manifest = """
                {
                  "schemaVersion": 1,
                  "id": "pybroker-$suffix",
                  "name": "Python broker fixture",
                  "version": "1.0.0",
                  "license": "AGPL-3.0-only",
                  "runtime": {"kind": "python", "mode": "pure-python", "entrypoint": "main:run"},
                  "inputSchema": {"type": "object", "properties": {}, "additionalProperties": false},
                  "outputSchema": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"], "additionalProperties": false},
                  "permissions": {
                    "model.invoke": {
                      "modelProfileIds": ["$modelId"],
                      "maxModelCalls": $grantMaxCalls,
                      "maxModelTokens": $grantMaxTokens
                    }
                  }
                }
            """.trimIndent()
            val capArgument = requestedToolCap?.let { "\"maxOutputTokens\": $it, " } ?: ""
            val source = """
                import mobileagent_sdk


                def run(payload):
                    # `model_invoke` returns the broker's own value; a denied or
                    # unavailable capability raises from the host bridge, so the
                    # failure is never silently turned into a successful answer.
                    answer = mobileagent_sdk.model_invoke("$providerId", {${capArgument}"prompt": "hello"})
                    return {"text": answer["text"]}
            """.trimIndent()

            val imported = container.skills.importPackage(skillZip(manifest, source))
            assertTrue(imported.inspection.reasons.toString(), imported.accepted)
            val install = container.skills.list().single { it.packageHash == imported.inspection.packageHash }
            installId = install.installId
            // The user approves the install scope once; the Run authorization is separate.
            val grant = container.skills.approvePermissions(
                installId,
                setOf("model.invoke"),
                modelProfileIds = setOf(modelId),
                maxModelCalls = grantMaxCalls,
                maxModelTokens = grantMaxTokens,
            )
            assertEquals(setOf(modelId), grant.modelProfileIds)
            container.skills.setEnabled(installId, true)

            container.agents.saveWithPrompt(AgentProfile(
                id = agentId,
                name = "Python broker agent",
                promptRevisionId = "pending",
                chatProfileId = modelId,
                skillIds = listOf(installId),
                contextPolicyJson = policyTokens?.let { "{\"pythonModelRunTokens\":$it}" } ?: "{}",
                revision = 0,
            ), "Use the explicitly bound local Skill.")
            snapshot = container.agents.createSnapshot(agentId)
            val conversation = container.conversations.create(snapshot.id, "Python broker conversation")
            runId = "run.pybroker.$suffix"
            val now = Utc.nowIso()
            // The production resolver: user number clamped to the approved scope.
            val approvedCeilings = if (omitRunAuthorization) emptyList() else listOf(grantMaxTokens)
            val runTokens = modelInvokeRunTokens(policyTokens, approvedCeilings)
            runRecord = RunRecord(
                runId = runId,
                snapshotId = snapshot.id,
                conversationId = conversation.id,
                budgetJson = runBudgetJson(
                    maxModelRounds = 8,
                    maxModelRoundsPerSegment = 4,
                    maxCompactionsPerRun = 2,
                    modelInvokeTokens = runTokens,
                ),
                startedAt = now,
                createdAt = now,
            )
            container.runs.save(runRecord)

            val agentRun = AgentRun(runId, snapshot.id, conversation.id, budget = RunBudget(maxModelRounds = 8))
            agentRun.startedAtMs = System.currentTimeMillis()
            val tools = RunTools(
                container,
                hostApp,
                snapshot,
                agentRun,
                supportsImages = false,
                textDegradation = false,
                providerHttp = HttpClient(engine(response, contentType)),
            )
            executor = tools.executor
            // RunTools exposes every route; this test drives the Python Skill route.
            specName = executor.specs.single { it.name.startsWith("py_") }.name
        }

        fun invoke(callId: String): ToolResult = runBlocking { executor.invoke(ToolCall(callId, specName, "{}")) }
        fun approve(callId: String): ToolResult = runBlocking { executor.approve(callId) }
        fun run(callId: String): ToolResult {
            val pending = invoke(callId)
            assertTrue("expected approval, got $pending; audit=${auditTrail()}", pending == ToolResult.NeedsApproval)
            return approve(callId)
        }
        fun persistedRun(): RunRecord = container.runs.get(runId)!!

        /** Sanitized audit trail for the failing assertion messages. */
        fun auditTrail(): String {
            val all = container.audits.list()
            val forRun = all.filter { it.runId == runId }
            return "rows=${all.size} forRun=${forRun.size} " +
                forRun.joinToString(" | ") { "${it.component}/${it.action}/${it.result}/${it.errorCode ?: "-"}" }
        }
    }

    private fun chatSuccess(usageInput: Int = 7, usageOutput: Int = 3): String = buildString {
        append("data: {\"choices\":[{\"delta\":{\"content\":\"canned answer\"}}]}\n\n")
        append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
        append("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":$usageInput,\"completion_tokens\":$usageOutput}}\n\n")
        append("data: [DONE]\n\n")
    }

    private fun responsesSuccess(bodyText: String = "canned answer"): String =
        "{\"status\":\"completed\",\"output_text\":\"$bodyText\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"$bodyText\"}]}],\"usage\":{\"input_tokens\":7,\"output_tokens\":3}}"

    private fun aliasOf(body: String, field: String): Int? =
        Json.parseToJsonElement(body).jsonObject[field]?.jsonPrimitive?.content?.toIntOrNull()

    @Test(timeout = 120_000)
    fun manualProfileCapUsesTheRunAuthorizationAndSettlesOnce() {
        val fixture = Harness(ApiFormat.OPENAI_COMPATIBLE, OutputLimitMode.MANUAL, 512, 1, 4_096, 4_096)
        fixture.start(chatSuccess(), "text/event-stream")

        val result = fixture.run("call-manual")
        assertTrue("expected a paid result, got $result; audit=${fixture.auditTrail()}", result is ToolResult.Value)
        val value = Json.parseToJsonElement((result as ToolResult.Value).json).jsonObject
        assertEquals("canned answer", value["text"]!!.jsonPrimitive.content)

        // Exactly one real HTTP dispatch with the profile MANUAL cap and one alias.
        assertEquals(fixture.bodies.toString(), 1, fixture.bodies.size)
        assertEquals("max_tokens", listOf("max_tokens", "max_completion_tokens").single { fixture.bodies.single().contains("\"$it\"") })
        assertEquals(512, aliasOf(fixture.bodies.single(), "max_tokens"))
        assertTrue(fixture.bodies.single(), fixture.bodies.single().contains("hello"))

        // A duplicate terminal for the same call must not settle or dispatch again.
        val replay = fixture.approve("call-manual")
        assertTrue("duplicate terminal must be refused or cached: $replay", replay !is ToolResult.Value || replay == result)
        assertEquals(fixture.bodies.toString(), 1, fixture.bodies.size)
    }

    @Test(timeout = 120_000)
    fun responsesProtocolKeepsItsNativeFieldOnTheSameRunAuthorization() {
        val fixture = Harness(ApiFormat.OPENAI_RESPONSES, OutputLimitMode.MANUAL, 512, 1, 4_096, 4_096)
        fixture.start(responsesSuccess(), "application/json")

        val result = fixture.run("call-responses")
        assertTrue("expected a paid result, got $result", result is ToolResult.Value)
        assertEquals(fixture.bodies.toString(), 1, fixture.bodies.size)
        val body = fixture.bodies.single()
        assertEquals(512, aliasOf(body, "max_output_tokens"))
        assertTrue(body, !body.contains("\"max_tokens\""))
    }

    @Test(timeout = 120_000)
    fun aRunWithoutTheFeeAuthorizationDispatchesNothing() {
        val fixture = Harness(ApiFormat.OPENAI_COMPATIBLE, OutputLimitMode.MANUAL, 512, 1, 4_096, 4_096, omitRunAuthorization = true)
        fixture.start(chatSuccess(), "text/event-stream")

        assertTrue("the Run must not carry a fee ceiling", !fixture.persistedRun().budgetJson.contains("maxModelTokens"))
        val result = fixture.run("call-no-quota")
        assertTrue("a Run without authorization must fail closed: $result", result !is ToolResult.Value)
        assertEquals("no HTTP request may leave", 0, fixture.bodies.size)
    }

    @Test(timeout = 120_000)
    fun aToolCapAboveTheRunCeilingIsRefusedBeforeDispatch() {
        val fixture = Harness(ApiFormat.OPENAI_COMPATIBLE, OutputLimitMode.MANUAL, 512, 1, 4_096, 4_096, requestedToolCap = 100_000)
        fixture.start(chatSuccess(), "text/event-stream")

        val result = fixture.run("call-too-large")
        assertTrue("an unaffordable cap must be refused: $result", result !is ToolResult.Value)
        assertEquals("no HTTP request may leave", 0, fixture.bodies.size)
    }

    @Test(timeout = 120_000)
    fun aMeasuredOverrunBlocksTheNextDispatch() {
        // Run ceiling 1_000: one call fits, its measured usage blows past it.
        val fixture = Harness(ApiFormat.OPENAI_COMPATIBLE, OutputLimitMode.MANUAL, 512, 2, 1_000, 1_000)
        fixture.start(chatSuccess(usageInput = 600, usageOutput = 700), "text/event-stream")

        val first = fixture.run("call-overrun-1")
        assertTrue("the paid result must be preserved: $first; audit=${fixture.auditTrail()}", first is ToolResult.Value)
        assertEquals(fixture.bodies.toString(), 1, fixture.bodies.size)

        // The same call cannot be replayed, and a new call cannot afford the reservation.
        val second = fixture.invoke("call-overrun-2")
        assertTrue("the ledger must block the next dispatch: $second", second !is ToolResult.Value)
        assertEquals(fixture.bodies.toString(), 1, fixture.bodies.size)
    }

    @Test(timeout = 120_000)
    fun revocationBlocksTheNextDispatch() {
        val fixture = Harness(ApiFormat.OPENAI_COMPATIBLE, OutputLimitMode.MANUAL, 512, 2, 4_096, 4_096)
        fixture.start(chatSuccess(), "text/event-stream")

        assertTrue("first call failed; audit=${fixture.auditTrail()}", fixture.run("call-revoke-1") is ToolResult.Value)
        assertEquals(1, fixture.bodies.size)
        fixture.container.skills.approvePermissions(fixture.installId, emptySet())

        val second = fixture.invoke("call-revoke-2")
        assertTrue("a revoked grant must fail closed: $second; audit=${fixture.auditTrail()}", second !is ToolResult.Value)
        assertEquals(fixture.bodies.toString(), 1, fixture.bodies.size)
    }

    @Test(timeout = 120_000)
    fun anUnknownOutcomeIsNeverReplayed() {
        // No terminal frame: the broker classifies the paid response as UNKNOWN.
        val unknown = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
        val fixture = Harness(ApiFormat.OPENAI_COMPATIBLE, OutputLimitMode.MANUAL, 512, 2, 4_096, 4_096)
        fixture.start(unknown, "text/event-stream")

        val result = fixture.run("call-unknown")
        assertTrue("expected an unknown outcome, got $result", result is ToolResult.UnknownOutcome)
        assertEquals(1, fixture.bodies.size)
        val retry = fixture.approve("call-unknown")
        assertTrue("an unknown outcome must not be replayed: $retry", retry !is ToolResult.Value)
        assertEquals(fixture.bodies.toString(), 1, fixture.bodies.size)
    }

    private fun skillZip(manifest: String, source: String): ByteArray {
        val entries = linkedMapOf("mobile-skill.json" to manifest, "main.py" to source)
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
