// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import runtime.mobileagent.domain.*
import runtime.mobileagent.skills.ToolCall

/** Real Chat VM, RunTools and both HTTP wire adapters; the provider is a loopback fixture. */
class RandomNumberDeviceTest {
    @Test fun modelChoosesDiceBoundsAndReceivesTheActualDrawOnBothProtocols() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val c = app.container
        for (format in listOf(ApiFormat.OPENAI_COMPATIBLE, ApiFormat.OPENAI_RESPONSES)) {
            val call = ToolCall("roll-d20", "random_number", """{"min":1,"max":20}""")
            WorldBookLoopback(format, true, call).use { server ->
                val id = UUID.randomUUID().toString()
                val ref = "random-fixture-$id"
                c.secrets.put(ref, "synthetic-random-fixture".toCharArray())
                try {
                    val provider = ProviderProfile("p-$id", "Dice fixture", format, "http://127.0.0.1:${server.port}/v1",
                        secretRef = ref, revision = 1)
                    c.profiles.createProvider(provider)
                    val model = ModelProfile("m-$id", provider.id, ModelRole.CHAT, "dice-fixture",
                        setOf("stream", "tools"), contextLimit = 64000, outputLimit = 1024, revision = 1)
                    c.profiles.createModel(model)
                    val agent = c.agents.saveWithPrompt(
                        AgentProfile("a-$id", "Dice fixture", "pending", model.id, revision = 0), "Use tools to roll dice.")
                    val chat = c.conversations.create(c.agents.createSnapshot(agent.id).id, "Dice fixture")
                    lateinit var vm: ChatViewModel
                    instrumentation.runOnMainSync {
                        vm = ChatViewModel(app, SavedStateHandle()); vm.selectSession(chat.id)
                        vm.input("请掷一次二十面骰，使用随机数工具。"); vm.send()
                    }
                    val deadline = System.currentTimeMillis() + 30000
                    while (vm.state.value.streaming && System.currentTimeMillis() < deadline) Thread.sleep(30)
                    assertFalse("Run did not finish: ${vm.state.value.status}", vm.state.value.streaming)
                    assertEquals(vm.state.value.status, RunStatus.COMPLETED, c.runs.list(chat.id).last().state)
                    assertEquals(2, server.requests.size)
                    val definitions = server.requests.first().getValue("tools").jsonArray.map { it.jsonObject }
                    val spec = if (format == ApiFormat.OPENAI_COMPATIBLE)
                        definitions.map { it.getValue("function").jsonObject }.single { it["name"]?.jsonPrimitive?.content == call.name }
                    else definitions.single { it["name"]?.jsonPrimitive?.content == call.name }
                    val parameters = spec.getValue("parameters").jsonObject
                    assertEquals(setOf("min", "max"), parameters.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
                    val next = server.requests.last()
                    val output = if (format == ApiFormat.OPENAI_COMPATIBLE) {
                        val message = next.getValue("messages").jsonArray.map { it.jsonObject }
                            .single { it["role"]?.jsonPrimitive?.content == "tool" }
                        assertEquals(call.callId, message.getValue("tool_call_id").jsonPrimitive.content)
                        message.getValue("content").jsonPrimitive.content
                    } else {
                        val message = next.getValue("input").jsonArray.map { it.jsonObject }
                            .single { it["type"]?.jsonPrimitive?.content == "function_call_output" }
                        assertEquals(call.callId, message.getValue("call_id").jsonPrimitive.content)
                        message.getValue("output").jsonPrimitive.content
                    }
                    // Production wraps untrusted tool data before giving it to either adapter.
                    val prefix = "<untrusted-tool-result call_id=\"${call.callId}\">\n"
                    val suffix = "\n</untrusted-tool-result>"
                    assertTrue(output, output.startsWith(prefix) && output.endsWith(suffix))
                    val result = Json.parseToJsonElement(output.removePrefix(prefix).removeSuffix(suffix)).jsonObject
                    assertEquals(1, result.getValue("min").jsonPrimitive.int)
                    assertEquals(20, result.getValue("max").jsonPrimitive.int)
                    assertTrue(result.getValue("value").jsonPrimitive.int in 1..20)
                    server.assertHealthy()
                } finally { c.db.execute("DELETE FROM secrets WHERE ref=?", listOf(ref)) }
            }
        }
    }
}
