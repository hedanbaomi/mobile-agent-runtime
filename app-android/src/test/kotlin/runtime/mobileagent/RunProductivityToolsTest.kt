// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.skills.*

class RunProductivityToolsTest {
    @Test fun sharedReadNamesRequireBatchApprovalOnlyForExplicitRootWorkspaceIds(): Unit = runBlocking {
        val seen = java.util.Collections.synchronizedList(mutableListOf<ToolCall>())
        val spec = runtime.mobileagent.privileged.RootToolArguments.spec("file_read_text")!!
        val core = object : ToolExecutor {
            override val specs = listOf(ToolSpec(spec.name, spec.description, spec.inputSchema, spec.capability!!.value, false, spec.schemaVersion))
            override suspend fun invoke(call: ToolCall): ToolResult { seen += call; return ToolResult.Value("{}") }
            override suspend fun approve(callId: String): ToolResult = error("The fixture completes reads directly")
            override suspend fun authorizeReplay(call: ToolCall) = call in seen
            override suspend fun authorizeDeferredResult(call: ToolCall) = call in seen
        }
        val tools = RunProductivityTools(core, this, { true }, { it }, rootWorkspaceIds = setOf("root-id"))
        fun batch(id: String, workspace: String) = ToolCall(id, "read_batch",
            """{"calls":[{"call_id":"child-$id","tool":"file_read_text","arguments":{"workspace_id":"$workspace","relative_path":"a"}}]}""")
        try {
            assertTrue(tools.executor.invoke(batch("ordinary", "internal-id")) is ToolResult.Value)
            assertEquals(1, seen.size)
            assertEquals(ToolResult.NeedsApproval, tools.executor.invoke(batch("root", "root-id")))
            assertEquals(1, seen.size, "Root child must wait for the outer durable approval")
            assertTrue(tools.executor.approve("root") is ToolResult.Value)
            assertEquals(2, seen.size)
        } finally { tools.executor.close() }
    }
    @Test fun discoveryAndMetadataReadsAreEligibleForExplicitReadBatches(): Unit = runBlocking {
        val seen = java.util.Collections.synchronizedList(mutableListOf<ToolCall>())
        val core = object : ToolExecutor {
            override val specs = listOf("workspace_list", "file_get_metadata").map {
                ToolSpec(it, "fixture", "{}", "", false)
            }
            override suspend fun invoke(call: ToolCall): ToolResult {
                seen += call
                return ToolResult.Value("{}")
            }
            override suspend fun approve(callId: String): ToolResult = ToolResult.Invalid("unused")
            override suspend fun authorizeReplay(call: ToolCall) = call in seen
            override suspend fun authorizeDeferredResult(call: ToolCall) = call in seen
        }
        val tools = RunProductivityTools(core, this, { true }, { it })
        try {
            val call = ToolCall("read-metadata-and-discover", "read_batch",
                """{"calls":[{"call_id":"discover","tool":"workspace_list","arguments":{}},{"call_id":"metadata","tool":"file_get_metadata","arguments":{}}]}""")
            val result = tools.executor.invoke(call)
            assertTrue(result is ToolResult.Value, result.toString())
            assertEquals(setOf("workspace_list", "file_get_metadata"), seen.map { it.name }.toSet())
        } finally { tools.executor.close() }
    }

    @Test fun rootChildrenWithoutDurableApprovalAreRejectedBeforeJobCreation(): Unit = runBlocking {
        var calls = 0
        val core = object : ToolExecutor {
            override val specs = listOf("file_read_text", "shell_exec", "calculator").map {
                ToolSpec(it, "fixture", "{}", "", false)
            }
            override suspend fun invoke(call: ToolCall): ToolResult {
                calls++
                return ToolResult.Value("{}")
            }
            override suspend fun approve(callId: String): ToolResult = error("not required")
        }
        val tools = RunProductivityTools(core, this, { true }, { it },
            excludedChildTools = setOf("file_read_text", "shell_exec"))
        try {
            for (tool in listOf("file_read_text", "shell_exec")) {
                assertTrue(tools.executor.invoke(ToolCall("job-$tool", "job_start",
                    "{\"tool\":\"$tool\",\"arguments\":{}}")) is ToolResult.Invalid)
            }
            assertEquals(0, calls)
            assertTrue(tools.executor.invoke(ToolCall("direct", "file_read_text", "{}")) is ToolResult.Value)
            assertEquals(1, calls, "Only nesting is excluded, not the direct approved path")
            assertEquals(ToolResult.NeedsApproval, tools.executor.invoke(ToolCall("calculator-job", "job_start",
                "{\"tool\":\"calculator\",\"arguments\":{}}")))
        } finally { tools.executor.close() }
    }

    @Test fun aLaterLegacyCallWithTheSameModelIdKeepsItsOwnApproval(): Unit = runBlocking {
        var approved: String? = null
        val core = object : ToolExecutor {
            override val specs = listOf(
                ToolSpec("calculator", "fixture", "{}", "", false),
                ToolSpec("shell_exec", "fixture", "{}", "shell.execute", true),
            )
            override suspend fun invoke(call: ToolCall): ToolResult =
                if (call.name == "shell_exec") ToolResult.NeedsApproval else ToolResult.Value("{\"ok\":true}")
            override suspend fun approve(callId: String): ToolResult {
                approved = callId
                return ToolResult.Value("{\"shell\":true}")
            }
            override suspend fun authorizeReplay(call: ToolCall): Boolean = true
        }
        val tools = RunProductivityTools(core, this, { true }, { it })
        try {
            val start = ToolCall("reused", "job_start", "{\"tool\":\"calculator\",\"arguments\":{}}")
            assertEquals(ToolResult.NeedsApproval, tools.executor.invoke(start))
            tools.executor.approve("reused")
            assertEquals(ToolResult.NeedsApproval, tools.executor.invoke(ToolCall("reused", "shell_exec", "{}")))
            assertEquals(ToolResult.Value("{\"shell\":true}"), tools.executor.approve("reused"))
            assertEquals("reused", approved)
        } finally { tools.executor.close() }
    }

    private class Core : ToolExecutor {
        var calls = 0
        var authorized = true
        var block = false
        override val specs = listOf(
            ToolSpec("calculator", "fixture", "{\"type\":\"object\"}", "", false),
            ToolSpec("shell_exec", "fixture", "{\"type\":\"object\"}", "shell.execute", true),
            ToolSpec("device_observe", "fixture", "{\"type\":\"object\"}", "device.observe", false),
        )
        override suspend fun invoke(call: ToolCall): ToolResult {
            calls++
            if (block) awaitCancellation()
            return ToolResult.Value("{\"answer\":\"result-fixture\"}")
        }
        override suspend fun approve(callId: String): ToolResult = error("not required")
        override suspend fun authorizeReplay(call: ToolCall): Boolean = authorized
    }

    @Test fun jobsUseSharedBudgetAndCanDiscloseAuthorizedResults(): Unit = runBlocking {
        val core = Core(); var reserved = 0
        val tools = RunProductivityTools(core, this, { true }, { reserved += it; it })
        try {
            val start = ToolCall("start", "job_start", "{\"tool\":\"calculator\",\"arguments\":{}}")
            assertEquals(ToolResult.NeedsApproval, tools.executor.invoke(start)); assertEquals(0, core.calls)
            val invocationId = tools.runtimeInvocationId(start.callId)
            assertNotNull(invocationId)
            assertNotEquals(start.callId, invocationId)
            assertEquals(ToolResult.NeedsApproval, tools.executor.invoke(start))
            assertEquals(invocationId, tools.runtimeInvocationId(start.callId))
            assertTrue(tools.executor.invoke(start.copy(argumentsJson = "{\"tool\":\"shell_exec\",\"arguments\":{}}")) is ToolResult.Invalid)
            val started = tools.executor.approve(requireNotNull(invocationId)) as ToolResult.Value
            val id = Json.parseToJsonElement(started.json).jsonObject.getValue("job_id").jsonPrimitive.content
            val read = ToolCall("output", "job_output", "{\"job_id\":\"$id\",\"wait_ms\":1000}")
            val output = tools.executor.invoke(read) as ToolResult.Value
            assertTrue(output.json.contains("result-fixture"), output.json)
            assertEquals(1, reserved); assertEquals(1, core.calls)
            core.authorized = false
            val withheld = tools.executor.invoke(read)
            assertFalse(withheld is ToolResult.Value && withheld.json.contains("result-fixture"))
        } finally { tools.executor.close() }
    }

    @Test fun cancellingAReadJobDoesNotBlockLaterToolsInTheRun(): Unit = runBlocking {
        val core = Core(); val tools = RunProductivityTools(core, this, { true }, { it })
        try {
            core.block = true
            val start = ToolCall("read", "job_start", "{\"tool\":\"calculator\",\"arguments\":{}}")
            tools.executor.invoke(start)
            val started = tools.executor.approve(start.callId) as ToolResult.Value
            val id = Json.parseToJsonElement(started.json).jsonObject.getValue("job_id").jsonPrimitive.content
            withTimeout(1000) { while (core.calls == 0) yield() }
            tools.executor.invoke(ToolCall("cancel", "job_cancel", "{\"job_id\":\"$id\"}"))
            assertFalse(tools.hasUnknownOutcomes)

            core.block = false
            val next = tools.executor.invoke(ToolCall("next", "calculator", "{}"))
            assertTrue(next is ToolResult.Value, "a stopped read must not lock the Run: $next")
        } finally { tools.executor.close() }
    }

    @Test fun screenCannotBeNestedAndRunningSideEffectsRemainUnknownAtClose(): Unit = runBlocking {
        val core = Core(); val tools = RunProductivityTools(core, this, { true }, { it })
        val forbidden = ToolCall("screen", "job_start", "{\"tool\":\"device_observe\",\"arguments\":{}}")
        assertTrue(tools.executor.invoke(forbidden) is ToolResult.Invalid)
        core.block = true
        val start = ToolCall("shell", "job_start", "{\"tool\":\"shell_exec\",\"arguments\":{}}")
        tools.executor.invoke(start); tools.executor.approve(start.callId)
        withTimeout(1000) { while (core.calls == 0) yield() }
        tools.executor.close()
        assertTrue(tools.hasUnknownOutcomes)
    }

    @Test fun undispatchedInvalidControlCallMustNotResolveToCompletedPriorCall(): Unit = runBlocking {
        val core = object : ToolExecutor {
            override val specs = listOf(BuiltinTools.calculator)
            override suspend fun invoke(call: ToolCall) = ToolResult.Value("""{"value":2}""")
            override suspend fun approve(callId: String) = ToolResult.Invalid("unused")
            override suspend fun authorizeReplay(call: ToolCall) = true
            override suspend fun authorizeDeferredResult(call: ToolCall) = true
        }
        val tools = RunProductivityTools(core, this, liveContext = { true }, reserveCalls = { it })
        val validBatch = """{"calls":[{"call_id":"child-1","tool":"calculator","arguments":{"expression":"1+1"}}]}"""
        val validJob = """{"tool":"calculator","arguments":{"expression":"2+2"}}"""
        var round = 0
        val adapter = object : runtime.mobileagent.provider.ModelAdapter {
            override suspend fun probe(profile: runtime.mobileagent.domain.ModelProfile): runtime.mobileagent.provider.CapabilityReport = error("unused")
            override suspend fun embed(request: runtime.mobileagent.provider.EmbeddingRequest, secret: CharArray): runtime.mobileagent.provider.EmbeddingBatch = error("unused")
            override fun stream(request: runtime.mobileagent.provider.ModelRequest, secret: CharArray) = kotlinx.coroutines.flow.flow {
                when (round++) {
                    0 -> emit(runtime.mobileagent.provider.ModelEvent.ToolCallDelta("reused-batch-id", "read_batch", validBatch))
                    1 -> emit(runtime.mobileagent.provider.ModelEvent.ToolCallDelta("reused-batch-id", "read_batch", "{}"))
                    2 -> emit(runtime.mobileagent.provider.ModelEvent.ToolCallDelta("reused-job-id", "job_start", validJob))
                    3 -> emit(runtime.mobileagent.provider.ModelEvent.ToolCallDelta("reused-job-id", "job_start", "{}"))
                    else -> emit(runtime.mobileagent.provider.ModelEvent.TextDelta("done"))
                }
                emit(runtime.mobileagent.provider.ModelEvent.Completed)
            }
        }
        val statuses = mutableListOf<String>()
        val ids = mutableListOf<String?>()
        runtime.mobileagent.privileged.InMemoryRootSqlConnection().use { db ->
            runtime.mobileagent.data.Migrations.apply(db)
            val now = runtime.mobileagent.domain.Utc.nowIso()
            db.execute(
                "INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at,provider_id,chat_model_revision,parameter_overrides_json,context_policy_json,permission_settings_json,binding_manifest_json,expanded_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                listOf("review-snapshot", 1, "agent-1", "prompt-1", "model-1", 1, "[]", "[]", now, "provider-1", 1, "{}", "{}", "{}", "{}", "{}"),
            )
            db.execute(
                "INSERT INTO conversations(id,snapshot_id,agent_snapshot_id,title,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                listOf("review-conversation", "review-snapshot", "review-snapshot", "Review", now, now),
            )
            val runs = runtime.mobileagent.data.RunRepository(db)
            runs.create(runtime.mobileagent.domain.RunRecord("review-control-run", "review-snapshot", "review-conversation", createdAt = now))
            val f = runtime.mobileagent.agent.AgentRun("review-control-run", "review-snapshot", "review-conversation")
            val invocations = linkedMapOf<String, runtime.mobileagent.domain.ToolInvocation>()
            try {
                runtime.mobileagent.agent.AgentRuntime(
                    adapter = adapter,
                    executor = tools.executor,
                    onApprove = { true },
                ).run(
                    runtime.mobileagent.agent.AgentRuntimeRequest(
                        f,
                        runtime.mobileagent.agent.EffectivePrompt("fixture", "", emptyList(), emptyList(), emptyList(), "read"),
                        "model",
                        "synthetic-secret".toCharArray(),
                        true,
                    ),
                ).collect { event ->
                    if (event is runtime.mobileagent.agent.RuntimeEvent.ToolResultProduced) {
                        statuses += event.status
                        val resolved = tools.runtimeInvocationId(event.callId)
                        ids += resolved
                        val runtimeInvocationId = resolved ?: runtime.mobileagent.skills.tooling.InternalRequestIds.new()
                        val invocation = (invocations[runtimeInvocationId] ?: runtime.mobileagent.domain.ToolInvocation(
                            runtimeInvocationId,
                            f.runId,
                            event.callId,
                            event.name,
                            "{}",
                            createdAt = now,
                        )).copy(
                            state = if (event.status == "VALUE") "SUCCEEDED" else "FAILED",
                            resultJson = event.resultJson,
                            permissionDecision = "NOT_REQUESTED",
                            updatedAt = now,
                        )
                        runs.observeRuntimeInvocation(invocation)
                        invocations[runtimeInvocationId] = invocation
                    }
                }
            } finally {
                tools.executor.close()
            }
            assertEquals(listOf("VALUE", "INVALID", "VALUE", "INVALID"), statuses)
            assertNotNull(ids[0])
            assertNull(ids[1], "Undispatched invalid read_batch call must not inherit prior completed runtimeInvocationId")
            assertNotNull(ids[2])
            assertNull(ids[3], "Undispatched invalid job_start call must not inherit prior completed runtimeInvocationId")
            val persisted = runs.invocations(f.runId)
            assertEquals(4, persisted.size)
            assertEquals("SUCCEEDED", persisted.first { it.invocationId == ids[0] }.state)
            assertEquals("SUCCEEDED", persisted.first { it.invocationId == ids[2] }.state)
        }
    }
}
