// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.SecretRedactor

object OpenAiSse {
    private val json = Json { ignoreUnknownKeys = true }

    fun eventsFromLine(
        line: String,
        toolBuf: LinkedHashMap<String, Pair<String, StringBuilder>>,
        extraSecrets: List<String> = emptyList(),
        indexToId: MutableMap<Int, String> = mutableMapOf(),
    ): List<ModelEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return emptyList()
        if (!trimmed.startsWith("data:")) return emptyList()
        val data = trimmed.removePrefix("data:").trim()
        if (data == "[DONE]") {
            val toolEvents = flushToolCalls(toolBuf, extraSecrets)
                ?: return listOf(ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name))
            toolBuf.clear()
            indexToId.clear()
            return toolEvents + ModelEvent.Completed
        }
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList()
        val events = mutableListOf<ModelEvent>()
        // Some OpenAI-compatible providers put cumulative usage in a final
        // usage-only frame with `choices: []`. Parse it before looking for a
        // choice or error so the accounting event is not silently discarded.
        val usage = obj["usage"]?.let { element ->
            runCatching { element.jsonObject }.getOrNull()
        }
        if (usage != null) {
            val input = usage["prompt_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val output = usage["completion_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            events += ModelEvent.Usage(input, output)
        }
        (obj["error"] as? kotlinx.serialization.json.JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull?.let { msg ->
            events += ModelEvent.Failed(SecretRedactor.redact(msg, extraSecrets))
            return events
        }
        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return events
        val delta = choice["delta"]?.jsonObject
        reasoningText(delta)?.let { events += ModelEvent.ReasoningDelta(it) }
        delta?.get("content")?.jsonPrimitive?.contentOrNull?.let { events += ModelEvent.TextDelta(it) }
        delta?.get("refusal")?.jsonPrimitive?.contentOrNull?.let { events += ModelEvent.RefusalDelta(it) }
        val toolCalls = delta?.get("tool_calls")?.jsonArray
        toolCalls?.forEach { call ->
            val c = call.jsonObject
            val index = c["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val id = c["id"]?.jsonPrimitive?.contentOrNull
                ?: index?.let { indexToId[it] }
            if (id == null) return@forEach
            if (index != null) indexToId[index] = id
            val name = c["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
            val args = c["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull.orEmpty()
            val acc = toolBuf.getOrPut(id) { name to StringBuilder() }
            if (name.isNotBlank() && acc.first.isBlank()) {
                toolBuf[id] = name to acc.second
            }
            acc.second.append(args)
        }
        choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it == "length" }
            ?.let { events += ModelEvent.Failed(ErrorCode.CONTEXT_OVERFLOW.name) }
        return events
    }

    /** Structural terminal metadata only; never returns provider text. */
    internal fun finishReasonFromLine(line: String): String? {
        val data = line.trim().takeIf { it.startsWith("data:") }?.removePrefix("data:")?.trim()
            ?: return null
        if (data == "[DONE]") return "done"
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        return obj["choices"]?.let { runCatching { it.jsonArray.firstOrNull()?.jsonObject }.getOrNull() }
            ?.get("finish_reason")?.jsonPrimitive?.contentOrNull
    }

    /**
     * OpenAI-compatible providers use both spellings in the wild. Prefer the
     * canonical `reasoning_content` field when both are present, and never
     * infer reasoning from `content`.
     */
    private fun reasoningText(delta: kotlinx.serialization.json.JsonObject?): String? =
        listOf("reasoning_content", "reasoning")
            .firstNotNullOfOrNull { key ->
                (delta?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
            }

    /**
     * Tool arguments are withheld until the provider's terminal marker.  A
     * partial argument cannot be sent to the runtime, and a call containing a
     * credential is rejected without exposing any earlier argument prefix.
     */
    private fun flushToolCalls(
        toolBuf: LinkedHashMap<String, Pair<String, StringBuilder>>,
        secrets: List<String>,
    ): List<ModelEvent>? {
        if (toolBuf.isEmpty()) return emptyList()
        val events = mutableListOf<ModelEvent>()
        for ((callId, call) in toolBuf) {
            val arguments = call.second.toString()
            val parsed = runCatching { json.parseToJsonElement(arguments).jsonObject }.getOrNull()
                ?: return null
            if (containsCredentialText(callId, secrets) ||
                containsCredentialText(call.first, secrets) ||
                containsCredentialText(arguments, secrets) ||
                containsCredentialJson(parsed, secrets)
            ) return null
            events += ModelEvent.ToolCallDelta(callId, call.first, arguments)
        }
        return events
    }
}
