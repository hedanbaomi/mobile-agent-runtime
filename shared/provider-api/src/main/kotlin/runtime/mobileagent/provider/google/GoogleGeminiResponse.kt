// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.google

import kotlinx.serialization.json.*
import runtime.mobileagent.provider.*
import runtime.mobileagent.provider.openai.StreamingSecretRedactor
import runtime.mobileagent.provider.openai.containsCredentialJson
import runtime.mobileagent.domain.classifyLengthStop

/** One response accumulator: usage values are cumulative snapshots, never additive deltas. */
internal class GoogleGeminiResponse(private val secrets: List<String>) {
    private val parts = mutableListOf<JsonElement>()
    private val calls = mutableListOf<ModelEvent.ToolCallDelta>()
    private val redactor = StreamingSecretRedactor(secrets)
    private var finish: String? = null
    private var visible = false
    private var reasoning = false
    private var refused = false
    private var thoughtTokens: Int? = null
    private var inputTokens: Int? = null
    private var candidateTokens: Int? = null
    private var privateChars = 0
    private val localCallScope = java.util.UUID.randomUUID().toString()
    val finishReason: String? get() = finish
    var latestUsage: ModelEvent.Usage? = null
        private set

    fun accept(raw: String): List<ModelEvent> {
        val parsed = Json.parseToJsonElement(raw)
        val roots = (parsed as? JsonArray)?.also { require(it.isNotEmpty()) }?.map { it.jsonObject } ?: listOf(parsed.jsonObject)
        val events = mutableListOf<ModelEvent>()
        roots.forEach { root -> events += acceptObject(root) }
        return events
    }

    private fun acceptObject(root: JsonObject): List<ModelEvent> {
        val events = mutableListOf<ModelEvent>()
        readUsage(root)
        require(root["error"] == null || root["error"] == JsonNull)
        root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.content?.let {
            if (it != "BLOCK_REASON_UNSPECIFIED") refused = true
        }
        val candidates = root["candidates"] as? JsonArray ?: JsonArray(emptyList())
        require(candidates.size <= 1)
        candidates.firstOrNull()?.jsonObject?.let { candidate ->
            candidate["index"]?.let { require(it.jsonPrimitive.intOrNull == 0) }
            candidate["content"]?.jsonObject?.let { content ->
                require(finish == null) { "Content after terminal candidate" }
                content["role"]?.let { require(it.jsonPrimitive.content == "model") }
                content["parts"]?.jsonArray?.forEach { value ->
                    val part = value.jsonObject
                    require(!containsCredentialJson(part, secrets)) { "Credential in response" }
                    privateChars += part.toString().length
                    require(privateChars <= 8_388_608)
                    parts += part
                    part["text"]?.jsonPrimitive?.content?.let { text ->
                        if (part["thought"]?.jsonPrimitive?.booleanOrNull == true) {
                            if (text.isNotBlank()) reasoning = true
                            val clean = redactor.accept(text, StreamingSecretRedactor.Channel.REASONING)
                            if (clean.isNotEmpty()) events += ModelEvent.ReasoningDelta(clean)
                        } else {
                            if (text.isNotBlank()) visible = true
                            val clean = redactor.accept(text)
                            if (clean.isNotEmpty()) events += ModelEvent.TextDelta(clean)
                        }
                    }
                    part["functionCall"]?.jsonObject?.let { call ->
                        val name = call["name"]?.jsonPrimitive?.content.orEmpty()
                        require(name.isNotBlank() && name.length <= 128)
                        val args = call["args"] ?: JsonObject(emptyMap())
                        require(args is JsonObject)
                        val nativeId = call["id"]?.jsonPrimitive?.content
                        require(nativeId == null || (SAFE_CALL_ID.matches(nativeId) && !nativeId.startsWith(LOCAL_CALL_PREFIX)))
                        val id = nativeId ?: "$LOCAL_CALL_PREFIX$localCallScope:${calls.size}"
                        require(calls.none { it.callId == id })
                        calls += ModelEvent.ToolCallDelta(id, name, args.toString())
                    }
                    require(part.keys.any { it in setOf("text", "functionCall", "thoughtSignature") })
                }
            }
            candidate["finishReason"]?.jsonPrimitive?.content?.let { reason ->
                require(finish == null || finish == reason)
                finish = reason
                if (reason in REFUSAL_REASONS) refused = true
            }
        }
        return events
    }

    fun readUsage(element: JsonElement) {
        val roots = (element as? JsonArray)?.mapNotNull { it as? JsonObject } ?: listOfNotNull(element as? JsonObject)
        roots.forEach(::readUsageObject)
    }

    private fun readUsageObject(root: JsonObject) {
        root["usageMetadata"]?.jsonObject?.let { usage ->
            fun count(key: String): Int? = usage[key]?.let { value ->
                val p = value as? JsonPrimitive ?: error("Invalid usage")
                require(!p.isString)
                p.intOrNull?.also { require(it >= 0) } ?: error("Invalid usage")
            }
            count("promptTokenCount")?.let { inputTokens = maxOf(inputTokens ?: 0, it) }
            count("candidatesTokenCount")?.let { candidateTokens = maxOf(candidateTokens ?: 0, it) }
            count("thoughtsTokenCount")?.let { thoughtTokens = maxOf(thoughtTokens ?: 0, it) }
            val output = if (candidateTokens != null || thoughtTokens != null)
                ((candidateTokens ?: 0).toLong() + (thoughtTokens ?: 0)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null
            latestUsage = ModelEvent.Usage(inputTokens ?: 0, output ?: 0, thoughtTokens, inputTokens, output)
        }
    }

    fun complete(): List<ModelEvent> {
        val events = mutableListOf<ModelEvent>()
        if (refused) {
            redactor.discard()
            return listOf(ModelEvent.RefusalDelta("The provider declined this request."), ModelEvent.Completed)
        }
        if (finish != "STOP") {
            redactor.discard()
            return listOf(ModelEvent.Failed(if (finish == "MAX_TOKENS") {
                classifyLengthStop(visible || calls.isNotEmpty(), thoughtTokens, latestUsage?.reportedOutputTokens).name
            } else if (finish == null) "INVALID_RESPONSE" else "UNKNOWN_OUTCOME"))
        }
        if (!visible && calls.isEmpty()) {
            redactor.discard()
            return listOf(ModelEvent.Failed(if (reasoning || (thoughtTokens ?: 0) > 0) "REASONING_ONLY" else "INVALID_RESPONSE"))
        }
        redactor.finish().forEach { (channel, text) ->
            events += if (channel == StreamingSecretRedactor.Channel.REASONING) ModelEvent.ReasoningDelta(text) else ModelEvent.TextDelta(text)
        }
        // The complete model content is retained exactly once, including signed text and empty signature parts.
        if (calls.isNotEmpty() || parts.any { it.jsonObject["thoughtSignature"] != null }) {
            val content = obj("role" to JsonPrimitive("model"), "parts" to JsonArray(parts))
            require(CONTINUATION_PREFIX.length + content.toString().length <= ProviderContinuationItem.MAX_CONTINUATION_CHARS)
            events += ModelEvent.ProviderContinuation(ProviderContinuationItem(
                encryptedContent = CONTINUATION_PREFIX + content.toString(), protocol = GOOGLE_PROTOCOL))
        }
        events += calls
        events += ModelEvent.Completed
        return events
    }

    companion object {
        private val SAFE_CALL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val REFUSAL_REASONS = setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT")
    }
}
