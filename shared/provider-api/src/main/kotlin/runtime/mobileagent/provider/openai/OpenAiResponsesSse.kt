// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.SecretRedactor

/**
 * Parser for the OpenAI Responses SSE protocol.
 *
 * Responses deliberately has its own event vocabulary and output-item model;
 * it must not be treated as a Chat Completions stream. Unknown event types are
 * ignored so newer provider lifecycle/annotation events remain forward
 * compatible. Function arguments are withheld until the provider marks them
 * done, matching the runtime's all-or-nothing tool dispatch boundary.
 */
object OpenAiResponsesSse {
    private val json = Json { ignoreUnknownKeys = true }
    /** Matches the transport bound on provider continuation payloads; oversized blobs are dropped. */
    private const val ProviderContinuationLimit = 32 * 1024

    class State {
        internal val functionCalls = linkedMapOf<String, FunctionCallBuffer>()
        internal val emittedCalls = mutableSetOf<String>()
        internal val text = linkedMapOf<String, StringBuilder>()
        internal val reasoning = linkedMapOf<String, StringBuilder>()
        internal val refusal = linkedMapOf<String, StringBuilder>()
        internal val finalizedText = mutableSetOf<String>()
        internal val finalizedReasoning = mutableSetOf<String>()
        internal val finalizedRefusal = mutableSetOf<String>()
        internal val emittedContinuations = mutableSetOf<String>()
    }

    internal data class FunctionCallBuffer(
        var callId: String,
        var name: String,
        val arguments: StringBuilder = StringBuilder(),
    )

    fun eventsFromLine(
        line: String,
        state: State = State(),
        extraSecrets: List<String> = emptyList(),
    ): List<ModelEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return emptyList()
        if (!trimmed.startsWith("data:")) return emptyList()
        val data = trimmed.removePrefix("data:").trim()
        if (data == "[DONE]") return listOf(ModelEvent.Completed)
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList()
        val type = string(obj, "type") ?: return emptyList()
        return when (type) {
            "response.output_text.delta" -> appendTextDelta(obj, state, Channel.TEXT)
            "response.output_text.done" -> finishText(obj, state, Channel.TEXT)
            "response.reasoning_summary_text.delta",
            "response.reasoning_summary.delta",
            "response.reasoning_text.delta",
            -> appendTextDelta(obj, state, Channel.REASONING)
            "response.reasoning_summary_text.done",
            "response.reasoning_summary.done",
            "response.reasoning_text.done",
            -> finishText(obj, state, Channel.REASONING)
            "response.refusal.delta" -> appendTextDelta(obj, state, Channel.REFUSAL)
            "response.refusal.done" -> finishText(obj, state, Channel.REFUSAL)
            "response.output_item.added",
            "response.output_item.done",
            -> parseOutputItem(obj, state, extraSecrets, terminal = type.endsWith(".done"))
            "response.function_call_arguments.delta" -> {
                val key = string(obj, "item_id") ?: string(obj, "call_id") ?: return emptyList()
                val buffer = state.functionCalls.getOrPut(key) { FunctionCallBuffer("", "") }
                string(obj, "delta")?.let(buffer.arguments::append)
                emptyList()
            }
            "response.function_call_arguments.done" -> {
                val key = string(obj, "item_id") ?: string(obj, "call_id") ?: return emptyList()
                val buffer = state.functionCalls.getOrPut(key) {
                    FunctionCallBuffer(string(obj, "call_id").orEmpty(), string(obj, "name").orEmpty())
                }
                string(obj, "call_id")?.let { buffer.callId = it }
                string(obj, "name")?.takeIf { it.isNotBlank() }?.let { buffer.name = it }
                string(obj, "arguments")?.let {
                    buffer.arguments.setLength(0)
                    buffer.arguments.append(it)
                }
                finishFunctionCall(key, buffer, state, extraSecrets)
            }
            "response.completed" -> buildList {
                val response = obj["response"]?.let { runCatching { it.jsonObject }.getOrNull() }
                usage(response ?: obj)?.let(::add)
                add(ModelEvent.Completed)
            }
            "response.failed",
            "response.incomplete",
            "error",
            -> listOf(ModelEvent.Failed(SecretRedactor.redact(errorMessage(obj), extraSecrets)))
            // created/in_progress/queued/output annotation and future event
            // types are lifecycle metadata, not model output.
            else -> emptyList()
        }
    }

    private enum class Channel { TEXT, REASONING, REFUSAL }

    private fun channelBuffers(state: State, channel: Channel): LinkedHashMap<String, StringBuilder> = when (channel) {
        Channel.TEXT -> state.text
        Channel.REASONING -> state.reasoning
        Channel.REFUSAL -> state.refusal
    }

    private fun channelFinalized(state: State, channel: Channel): MutableSet<String> = when (channel) {
        Channel.TEXT -> state.finalizedText
        Channel.REASONING -> state.finalizedReasoning
        Channel.REFUSAL -> state.finalizedRefusal
    }

    private fun channelEvent(channel: Channel, text: String): ModelEvent = when (channel) {
        Channel.TEXT -> ModelEvent.TextDelta(text)
        Channel.REASONING -> ModelEvent.ReasoningDelta(text)
        Channel.REFUSAL -> ModelEvent.RefusalDelta(text)
    }

    private fun appendTextDelta(event: JsonObject, state: State, channel: Channel): List<ModelEvent> {
        val delta = string(event, "delta")?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val key = contentKey(event)
        if (key in channelFinalized(state, channel)) return listOf(ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name))
        channelBuffers(state, channel).getOrPut(key, ::StringBuilder).append(delta)
        return listOf(channelEvent(channel, delta))
    }

    private fun finishText(event: JsonObject, state: State, channel: Channel): List<ModelEvent> {
        val complete = string(event, "text") ?: string(event, "summary_text")
            ?: string(event, "refusal") ?: return emptyList()
        val key = contentKey(event)
        val finalized = channelFinalized(state, channel)
        if (!finalized.add(key)) return emptyList()
        val partial = channelBuffers(state, channel)[key]?.toString().orEmpty()
        val missing = when {
            partial.isEmpty() -> complete
            complete.startsWith(partial) -> complete.removePrefix(partial)
            else -> return listOf(ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name))
        }
        return missing.takeIf { it.isNotEmpty() }
            ?.let { listOf(channelEvent(channel, it)) }
            .orEmpty()
    }

    private fun contentKey(event: JsonObject): String = buildString {
        append(string(event, "item_id") ?: "item")
        append(':')
        append(number(event, "output_index") ?: -1)
        append(':')
        append(number(event, "content_index") ?: -1)
    }

    private fun parseOutputItem(
        event: JsonObject,
        state: State,
        extraSecrets: List<String>,
        terminal: Boolean,
    ): List<ModelEvent> {
        val item = event["item"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return emptyList()
        // A completed reasoning output item carries the provider-private
        // payload needed for stateless continuation.  It is captured as
        // transport data only: never rendered, never logged, and never
        // mistaken for visible chain-of-thought.
        if (string(item, "type") == "reasoning") {
            if (!terminal) return emptyList()
            return captureContinuation(item, state)
        }
        if (string(item, "type") != "function_call") return emptyList()
        val key = string(item, "id") ?: string(item, "call_id") ?: return emptyList()
        val buffer = state.functionCalls.getOrPut(key) {
            FunctionCallBuffer(string(item, "call_id").orEmpty(), string(item, "name").orEmpty())
        }
        string(item, "call_id")?.let { buffer.callId = it }
        string(item, "name")?.takeIf { it.isNotBlank() }?.let { buffer.name = it }
        string(item, "arguments")?.takeIf { it.isNotEmpty() }?.let {
            // Added events often carry an empty argument string. A non-empty
            // item snapshot is authoritative when a provider sends one.
            if (terminal || buffer.arguments.isEmpty()) {
                buffer.arguments.setLength(0)
                buffer.arguments.append(it)
            }
        }
        return if (terminal) finishFunctionCall(key, buffer, state, extraSecrets) else emptyList()
    }

    private fun captureContinuation(item: JsonObject, state: State): List<ModelEvent> =
        captureContinuation(item, state.emittedContinuations)

    internal fun captureContinuation(item: JsonObject, emitted: MutableSet<String>): List<ModelEvent> {
        val encrypted = string(item, "encrypted_content")?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (encrypted.length > ProviderContinuationLimit) return emptyList()
        val id = string(item, "id")
        val key = id ?: encrypted
        if (!emitted.add(key)) return emptyList()
        return listOf(
            ModelEvent.ProviderContinuation(
                runtime.mobileagent.provider.ProviderContinuationItem(itemId = id, encryptedContent = encrypted),
            ),
        )
    }

    private fun finishFunctionCall(
        key: String,
        buffer: FunctionCallBuffer,
        state: State,
        extraSecrets: List<String>,
    ): List<ModelEvent> {
        if (key in state.emittedCalls || (buffer.callId.isNotBlank() && buffer.callId in state.emittedCalls)) return emptyList()
        state.emittedCalls += key
        if (buffer.callId.isNotBlank()) state.emittedCalls += buffer.callId
        val arguments = buffer.arguments.toString()
        val parsed = runCatching { json.parseToJsonElement(arguments).jsonObject }.getOrNull()
        if (buffer.callId.isBlank() || buffer.name.isBlank() || parsed == null ||
            credentialText(buffer.callId, extraSecrets) ||
            credentialText(buffer.name, extraSecrets) ||
            credentialText(arguments, extraSecrets) ||
            credentialJson(parsed, extraSecrets)
        ) {
            return listOf(ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name))
        }
        return listOf(ModelEvent.ToolCallDelta(buffer.callId, buffer.name, arguments))
    }

    private fun usage(root: JsonObject): ModelEvent.Usage? {
        val usage = root["usage"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return null
        val input = number(usage, "input_tokens") ?: number(usage, "prompt_tokens") ?: 0
        val output = number(usage, "output_tokens") ?: number(usage, "completion_tokens") ?: 0
        return ModelEvent.Usage(input, output)
    }

    private fun number(obj: JsonObject, key: String): Int? =
        obj[key]?.let { runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull() }

    private fun string(obj: JsonObject, key: String): String? =
        obj[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun errorMessage(obj: JsonObject): String =
        obj["message"]?.let { stringValue(it) }
            ?: obj["error"]?.let { element ->
                runCatching { element.jsonObject["message"]?.let(::stringValue) }.getOrNull()
            }
            ?: obj["response"]?.let { element ->
                runCatching {
                    element.jsonObject["error"]?.jsonObject?.get("message")?.let(::stringValue)
                }.getOrNull()
            }
            ?: ErrorCode.UNKNOWN_OUTCOME.name

    private fun stringValue(element: kotlinx.serialization.json.JsonElement): String =
        runCatching { element.jsonPrimitive.content }.getOrDefault(ErrorCode.UNKNOWN_OUTCOME.name)

    private fun credentialText(value: String, secrets: List<String>): Boolean =
        SecretRedactor.redact(value, secrets) != value

    private fun credentialJson(value: kotlinx.serialization.json.JsonElement, secrets: List<String>): Boolean = when (value) {
        is JsonPrimitive -> credentialText(value.content, secrets)
        is kotlinx.serialization.json.JsonObject -> value.values.any { credentialJson(it, secrets) }
        is kotlinx.serialization.json.JsonArray -> value.any { credentialJson(it, secrets) }
        else -> false
    }
}
