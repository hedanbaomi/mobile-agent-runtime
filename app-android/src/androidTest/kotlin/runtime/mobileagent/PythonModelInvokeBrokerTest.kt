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
import kotlinx.coroutines.launch
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
    /**
     * Each case starts its own isolated CPython service.  Rapid back-to-back
     * cases can race the previous service's teardown, which shows up as an
     * execution failure unrelated to what the case asserts, so the harness
     * waits for that boundary instead of retrying any assertion.
     */
    @org.junit.After
    fun waitForTheIsolatedServiceToExit() {
        Thread.sleep(700)
    }

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
        /** The frozen Agent parameter-override layer for this snapshot. */
        val agentOverridesJson: String = "{}",
        /** The prompt the Skill sends; its byte length is part of the reservation. */
        val prompt: String = "hello",
        /** When true the transport never answers, so a test can cancel in flight. */
        val holdTransport: Boolean = false,
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

        private fun engine(response: String, contentType: String) = MockEngine { request ->
            bodies += (request.body as io.ktor.http.content.TextContent).text
            if (holdTransport) kotlinx.coroutines.delay(600_000)
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
                    answer = mobileagent_sdk.model_invoke("$providerId", {${capArgument}"prompt": "$prompt"})
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
                parameterOverridesJson = agentOverridesJson,
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

        fun invoke(callId: String): ToolResult = runBlocking { invokeDirect(callId) }
        suspend fun invokeDirect(callId: String): ToolResult = executor.invoke(ToolCall(callId, specName, "{}"))
        suspend fun approveDirect(callId: String): ToolResult = executor.approve(callId)
        fun approve(callId: String): ToolResult = runBlocking { executor.approve(callId) }
        fun run(callId: String): ToolResult {
            val pending = invoke(callId)
            assertTrue("expected approval, got $pending; audit=${auditTrail()}", pending == ToolResult.NeedsApproval)
            return approve(callId)
        }
        fun persistedRun(): RunRecord = container.runs.get(runId)!!

        /** One sanitized row per audit event for precise assertions. */
        fun auditRows(): List<String> = container.audits.list(runId)
            .map { "${it.component}/${it.action}/${it.result}/${it.errorCode ?: "-"}" }

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

    /**
     * Discriminating settlement vector: prompt 10 bytes + MANUAL cap 2048 +
     * 256 overhead = reservation 2314.
     *
     * With the Run ceiling at 6000 the first call is admitted; after the
     * provider reports 7100 the durable ledger can no longer afford a second
     * reservation (7100 + 2314 > 6000).  If settlement were skipped the ledger
     * would still hold the first reservation and 2314 + 2314 <= 6000 would admit
     * the second call, so this vector genuinely distinguishes "replaced the
     * reservation with measured usage" from "kept the reservation".
     */
    @Test(timeout = 120_000)
    fun measuredUsageReplacesTheReservationBeforeTheNextAdmission() {
        val fixture = Harness(
            ApiFormat.OPENAI_COMPATIBLE, OutputLimitMode.MANUAL, 2048, 2, 6000, 6000,
            prompt = "0123456789",
        )
        fixture.start(chatSuccess(usageInput = 4000, usageOutput = 3100), "text/event-stream")

        val first = fixture.run("call-overrun-1")
        assertTrue("the paid result must be preserved: $first; audit=${fixture.auditTrail()}", first is ToolResult.Value)
        assertEquals(fixture.bodies.toString(), 1, fixture.bodies.size)

        // The second call must reach the real admission (invoke *and* approve) and
        // be refused by the budget before any dispatch, with an explicit reason.
        val second = fixture.run("call-overrun-2")
        assertTrue(
            "measured usage must block the next dispatch: $second; audit=${fixture.auditRows()}",
            second !is ToolResult.Value,
        )
        assertTrue(
            "the refusal must come from the shared budget: ${fixture.auditRows()}",
            fixture.auditRows().contains("python-broker/broker/DENIED/RESOURCE_LIMIT"),
        )
        assertEquals("no HTTP request may leave for the refused call", 1, fixture.bodies.size)

        // A duplicate terminal may disclose the cached paid result, but it must not
        // dispatch again, and the ledger must stay spent (checked by the third call).
        val duplicate = fixture.approve("call-overrun-1")
        assertTrue(
            "a duplicate terminal must not dispatch again: $duplicate",
            duplicate == first || duplicate !is ToolResult.Value,
        )
        assertEquals(fixture.bodies.toString(), 1, fixture.bodies.size)
        val third = fixture.run("call-overrun-3")
        assertTrue("the ledger must stay spent after the duplicate: $third", third !is ToolResult.Value)
        assertEquals(fixture.bodies.toString(), 1, fixture.bodies.size)
    }

    /**
     * The positive control for the same reservation: with a 9000 ceiling the
     * measured 5000 leaves room (5000 + 2314 <= 9000), so the second call must
     * succeed.  It also proves the settlement runs at most once: a second
     * settlement would raise the ledger to 7686 and 7686 + 2314 > 9000 would
     * refuse the second call.
     */
    @Test(timeout = 120_000)
    fun sufficientBalanceAdmitsTheSecondCallAndSettlesAtMostOnce() {
        val fixture = Harness(
            ApiFormat.OPENAI_COMPATIBLE, OutputLimitMode.MANUAL, 2048, 2, 9000, 9000,
            prompt = "0123456789",
        )
        fixture.start(chatSuccess(usageInput = 2500, usageOutput = 2500), "text/event-stream")

        val first = fixture.run("call-balance-1")
        assertTrue("expected a paid result: $first; audit=${fixture.auditTrail()}", first is ToolResult.Value)

        // A duplicate terminal must not settle again: a second settlement would raise
        // the ledger to 7686 and the 9000 ceiling would refuse the call below.
        val duplicate = fixture.approve("call-balance-1")
        assertTrue(
            "a duplicate terminal must disclose the cached result without a new dispatch: $duplicate",
            duplicate == first || duplicate !is ToolResult.Value,
        )
        assertEquals("the duplicate must not dispatch", 1, fixture.bodies.size)

        val second = fixture.run("call-balance-2")
        assertTrue(
            "an affordable second call must succeed: $second; audit=${fixture.auditRows()}",
            second is ToolResult.Value,
        )
        assertEquals("exactly two dispatches", 2, fixture.bodies.size)
    }

    /**
     * A failed terminal that already carried authoritative usage must settle that
     * usage, persist the unknown outcome and never replay the paid request.
     */
    @Test(timeout = 120_000)
    fun aFailedTerminalWithAuthoritativeUsageSettlesOnceAndNeverReplays() {
        val failed = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n")
            append("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":4000,\"completion_tokens\":3100}}\n\n")
            append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n")
        }
        val fixture = Harness(
            ApiFormat.OPENAI_COMPATIBLE, OutputLimitMode.MANUAL, 2048, 2, 6000, 6000,
            prompt = "0123456789",
        )
        fixture.start(failed, "text/event-stream")

        val result = fixture.run("call-failed-1")
        assertTrue("a truncated paid response is an unknown outcome: $result", result is ToolResult.UnknownOutcome)
        assertEquals(1, fixture.bodies.size)
        assertEquals(
            "exactly one unknown audit for the settled failure: ${fixture.auditRows()}",
            1,
            fixture.auditRows().count { it.startsWith("python-broker/invoke/UNKNOWN_OUTCOME") },
        )
        // The paid request is never replayed: the Run is unknown and the next call
        // returns immediately without touching the transport.
        assertEquals("the Run must be stopped as unknown", "UNKNOWN_OUTCOME", fixture.persistedRun().state.name)
        // The Run is already unknown, so the next call is refused before approval.
        val retry = fixture.invoke("call-failed-2")
        assertTrue("an unknown outcome must not be replayed: $retry", retry is ToolResult.UnknownOutcome)
        assertEquals("no replay dispatch", 1, fixture.bodies.size)
    }

    /**
     * Cancelling in flight keeps the reservation (the provider never answered),
     * stops the Run as unknown and never replays the request.
     */
    @Test(timeout = 120_000)
    fun cancellationInFlightKeepsTheReservationAndNeverReplays() = runBlocking {
        val fixture = Harness(
            ApiFormat.OPENAI_COMPATIBLE, OutputLimitMode.MANUAL, 2048, 2, 6000, 6000,
            prompt = "0123456789",
            holdTransport = true,
        )
        fixture.start("", "text/event-stream")
        val pending = fixture.invokeDirect("call-cancel")
        assertTrue("expected approval, got $pending", pending == ToolResult.NeedsApproval)

        var thrown: Throwable? = null
        val job = launch {
            thrown = runCatching { fixture.approveDirect("call-cancel") }.exceptionOrNull()
        }
        repeat(400) {
            if (fixture.bodies.isEmpty()) kotlinx.coroutines.delay(25)
        }
        assertTrue("the request must have been dispatched", fixture.bodies.isNotEmpty())
        job.cancel()
        job.join()

        assertTrue(
            "cancellation must propagate: $thrown",
            thrown is kotlinx.coroutines.CancellationException,
        )
        val rows = fixture.auditRows()
        assertEquals(
            "exactly one unknown audit after cancellation: $rows",
            1,
            rows.count { it.startsWith("python-broker/invoke/UNKNOWN_OUTCOME") },
        )
        assertEquals("the Run must stay unknown", "UNKNOWN_OUTCOME", fixture.persistedRun().state.name)
        val retry = fixture.invoke("call-cancel-2")
        assertTrue("a cancelled unknown outcome must not be replayed: $retry", retry is ToolResult.UnknownOutcome)
        assertEquals("no replay dispatch", 1, fixture.bodies.size)
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

    private val allOutputAliases = listOf("max_tokens", "max_completion_tokens", "max_output_tokens")

    @Test(timeout = 120_000)
    fun autoProfileSendsNoOutputFieldThroughTheBroker() {
        val fixture = Harness(ApiFormat.OPENAI_COMPATIBLE, OutputLimitMode.AUTO, 0, 1, 4_096, 4_096)
        fixture.start(chatSuccess(), "text/event-stream")

        val result = fixture.run("call-auto")
        assertTrue("expected a paid result, got $result; audit=${fixture.auditTrail()}", result is ToolResult.Value)
        assertEquals(fixture.bodies.toString(), 1, fixture.bodies.size)
        val body = fixture.bodies.single()
        assertTrue(
            "AUTO must not invent an output cap: $body",
            allOutputAliases.none { body.contains("\"$it\"") },
        )
    }

    @Test(timeout = 120_000)
    fun frozenAgentOverrideBeatsTheManualProfileCap() {
        // The Run ceiling must cover the reservation of the stronger agent cap
        // (prompt bytes + 4096 + overhead), otherwise the broker correctly
        // refuses before dispatch and this case would prove nothing.
        val fixture = Harness(
            ApiFormat.OPENAI_COMPATIBLE, OutputLimitMode.MANUAL, 512, 1, 8_192, 8_192,
            agentOverridesJson = "{\"max_completion_tokens\":4096}",
        )
        fixture.start(chatSuccess(), "text/event-stream")

        val result = fixture.run("call-agent")
        assertTrue("expected a paid result, got $result; audit=${fixture.auditTrail()}", result is ToolResult.Value)
        assertEquals(1, fixture.bodies.size)
        val body = fixture.bodies.single()
        assertEquals(4096, aliasOf(body, "max_completion_tokens"))
        assertTrue(body, !body.contains("\"max_tokens\""))
    }

    @Test(timeout = 120_000)
    fun theToolArgumentBeatsTheFrozenAgentOverride() {
        val fixture = Harness(
            ApiFormat.OPENAI_COMPATIBLE, OutputLimitMode.AUTO, 0, 1, 4_096, 4_096,
            requestedToolCap = 300,
            agentOverridesJson = "{\"max_completion_tokens\":4096}",
        )
        fixture.start(chatSuccess(), "text/event-stream")

        val result = fixture.run("call-tool")
        assertTrue("expected a paid result, got $result; audit=${fixture.auditTrail()}", result is ToolResult.Value)
        assertEquals(1, fixture.bodies.size)
        val body = fixture.bodies.single()
        assertEquals(300, aliasOf(body, "max_tokens"))
        assertTrue(body, !body.contains("\"max_completion_tokens\""))
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
