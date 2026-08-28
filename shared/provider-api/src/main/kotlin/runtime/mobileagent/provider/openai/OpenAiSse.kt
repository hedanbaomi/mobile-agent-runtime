// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import runtime.mobileagent.provider.ModelEvent

object OpenAiSse {
    private val json = Json { ignoreUnknownKeys = true }

    fun eventsFromLine(
        line: String,
        toolBuf: LinkedHashMap<String, Pair<String, StringBuilder>>,
    ): List<ModelEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return emptyList()
        if (!trimmed.startsWith("data:")) return emptyList()
        val data = trimmed.removePrefix("data:").trim()
        if (data == "[DONE]") return listOf(ModelEvent.Completed)
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList()
        obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull?.let { msg ->
            return listOf(ModelEvent.Failed(msg))
        }
        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return emptyList()
        val events = mutableListOf<ModelEvent>()
        val delta = choice["delta"]?.jsonObject
        delta?.get("content")?.jsonPrimitive?.contentOrNull?.let { events += ModelEvent.TextDelta(it) }
        val toolCalls = delta?.get("tool_calls")?.jsonArray
        toolCalls?.forEach { call ->
            val c = call.jsonObject
            val id = c["id"]?.jsonPrimitive?.contentOrNull
                ?: toolBuf.keys.lastOrNull()
                ?: return@forEach
            val name = c["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
            val args = c["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull.orEmpty()
            val acc = toolBuf.getOrPut(id) { name to StringBuilder() }
            if (name.isNotBlank() && acc.first.isBlank()) {
                toolBuf[id] = name to acc.second
            }
            acc.second.append(args)
            val resolved = toolBuf.getValue(id)
            events += ModelEvent.ToolCallDelta(id, resolved.first.ifBlank { name }, resolved.second.toString())
        }
        val usage = obj["usage"]?.jsonObject
        if (usage != null) {
            val input = usage["prompt_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val output = usage["completion_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            events += ModelEvent.Usage(input, output)
        }
        return events
    }
}
