// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.google

import kotlinx.serialization.json.*
import runtime.mobileagent.provider.*

internal const val GOOGLE_PROTOCOL = "google-generate-content"
internal const val CONTINUATION_PREFIX = "google-content-v1:"

/** Native wire encoding. The private model content replaces, rather than duplicates, public parts. */
internal object GoogleGeminiPayload {
    fun build(request: ModelRequest, preview: Boolean = false): JsonObject {
        require(RequestInputBudget.imageBytesWithinLimit(request)) { "Image budget exceeded" }
        val calls = linkedMapOf<String, AssistantToolCall>()
        val system = mutableListOf<JsonElement>()
        val contents = mutableListOf<JsonElement>()
        val answered = mutableSetOf<String>()
        var lastWasPrivate = false
        var responseOrder = emptyList<String>()
        val responseParts = mutableMapOf<String, JsonElement>()
        for (message in request.messages) {
            if (message.role == "system" || message.role == "developer") {
                require(message.images.isEmpty() && message.toolCalls.isEmpty())
                system += obj("text" to JsonPrimitive(message.text))
                continue
            }
            require(message.toolCalls.isEmpty() || message.role == "assistant")
            message.toolCalls.forEach { call -> require(calls.put(call.id, call) == null) }
            if (message.role != "tool") {
                require(answered.containsAll(responseOrder))
                responseParts.clear()
                responseOrder = message.toolCalls.let { list ->
                    if (list.all { it.id.startsWith(LOCAL_CALL_PREFIX) }) list.sortedBy { it.id.substringAfterLast(':').toIntOrNull() ?: Int.MAX_VALUE }
                    else list
                }.map { it.id }
            }
            val privateItems = message.providerContinuationItems.filter { it.protocol == GOOGLE_PROTOCOL }
            if (privateItems.isNotEmpty() && !preview) {
                require(message.role == "assistant" && privateItems.size == 1)
                val raw = privateItems.single().encryptedContent
                require(raw.startsWith(CONTINUATION_PREFIX))
                val content = Json.parseToJsonElement(raw.removePrefix(CONTINUATION_PREFIX)).jsonObject
                require(content["role"]?.jsonPrimitive?.content == "model")
                require(content["parts"] is JsonArray)
                // Bind opaque replay to the exact public calls; never accept an unrelated content envelope.
                val nativeCalls = content["parts"]!!.jsonArray.mapNotNull { it.jsonObject["functionCall"]?.jsonObject }
                require(nativeCalls.size == message.toolCalls.size)
                val unmatched = message.toolCalls.toMutableList()
                val orderedIds = mutableListOf<String>()
                nativeCalls.forEachIndexed { nativeIndex, native ->
                    val match = unmatched.indexOfFirst { call ->
                        native["name"]?.jsonPrimitive?.content == call.name &&
                            (native["args"] ?: JsonObject(emptyMap())) == Json.parseToJsonElement(call.argumentsJson) &&
                            (native["id"]?.jsonPrimitive?.content?.let { it == call.id } ?:
                                (call.id.startsWith(LOCAL_CALL_PREFIX) && call.id.substringAfterLast(':').toIntOrNull() == nativeIndex))
                    }
                    require(match >= 0)
                    orderedIds += unmatched.removeAt(match).id
                }
                responseOrder = orderedIds
                contents += content
                lastWasPrivate = true
                continue
            }
            val parts = mutableListOf<JsonElement>()
            if (message.role == "tool") {
                val call = calls[message.toolCallId] ?: error("Unmatched tool response")
                require(call.id in responseOrder)
                require(answered.add(call.id))
                val response = runCatching { Json.parseToJsonElement(message.text) as? JsonObject }.getOrNull()
                    ?: obj("result" to JsonPrimitive(message.text))
                val function = linkedMapOf<String, JsonElement>("name" to JsonPrimitive(call.name), "response" to response)
                // Generated local ids for older models are correlation-only and must never be fabricated on wire.
                if (!call.id.startsWith(LOCAL_CALL_PREFIX)) function["id"] = JsonPrimitive(call.id)
                parts += obj("functionResponse" to JsonObject(function))
                responseParts[call.id] = parts.single()
            } else {
                require(message.role == "assistant" || message.role == "user")
                if (message.text.isNotEmpty()) parts += obj("text" to JsonPrimitive(message.text))
                message.images.forEach { image ->
                    require(image.mediaType in setOf("image/png", "image/jpeg", "image/webp", "image/heic", "image/heif"))
                    require(image.base64.isNotEmpty())
                    parts += obj("inlineData" to obj("mimeType" to JsonPrimitive(image.mediaType),
                        "data" to JsonPrimitive(if (preview) "<redacted-image>" else image.base64)))
                }
                responseOrder.mapNotNull { calls[it] }.takeIf { message.role == "assistant" }.orEmpty().forEach { call ->
                    val args = Json.parseToJsonElement(call.argumentsJson)
                    require(args is JsonObject)
                    val function = linkedMapOf<String, JsonElement>("name" to JsonPrimitive(call.name), "args" to args)
                    if (!call.id.startsWith(LOCAL_CALL_PREFIX)) function["id"] = JsonPrimitive(call.id)
                    parts += obj("functionCall" to JsonObject(function))
                }
            }
            if (parts.isNotEmpty()) {
                val role = if (message.role == "assistant") "model" else "user"
                val turnParts = if (message.role == "tool") responseOrder.mapNotNull { responseParts[it] } else parts
                val prev = contents.lastOrNull()?.jsonObject?.takeIf { !lastWasPrivate && it["role"]?.jsonPrimitive?.content == role }
                if (prev != null) contents.removeAt(contents.lastIndex)
                val merged = if (prev != null && message.role != "tool") prev["parts"]!!.jsonArray + turnParts else turnParts
                contents += obj("role" to JsonPrimitive(role), "parts" to JsonArray(merged))
                lastWasPrivate = false
            }
        }
        require(contents.isNotEmpty() && answered.containsAll(responseOrder))
        val root = linkedMapOf<String, JsonElement>("contents" to JsonArray(contents))
        if (system.isNotEmpty()) root["systemInstruction"] = obj("parts" to JsonArray(system))
        if (request.tools.isNotEmpty()) root["tools"] = JsonArray(listOf(obj("functionDeclarations" to JsonArray(request.tools.map { spec ->
            require(spec["name"].orEmpty().matches(Regex("[A-Za-z_][A-Za-z0-9_.:-]{0,127}")))
            val schema = Json.parseToJsonElement(spec["parameters"] ?: "{}")
            require(schema is JsonObject)
            obj("name" to JsonPrimitive(spec["name"]!!), "description" to JsonPrimitive(spec["description"].orEmpty()), "parametersJsonSchema" to schema)
        }))))
        val config = generationConfig(request)
        if (config.isNotEmpty()) root["generationConfig"] = config
        return JsonObject(root)
    }

    private val aliases = mapOf("max_tokens" to "maxOutputTokens", "max_completion_tokens" to "maxOutputTokens",
        "max_output_tokens" to "maxOutputTokens", "temperature" to "temperature", "top_p" to "topP", "top_k" to "topK", "stop" to "stopSequences")
    private val allowed = setOf("maxOutputTokens", "temperature", "topP", "topK", "stopSequences", "candidateCount",
        "responseMimeType", "responseSchema", "responseJsonSchema", "presencePenalty", "frequencyPenalty", "seed",
        "responseLogprobs", "logprobs", "thinkingConfig", "mediaResolution", "responseModalities")
    private val reserved = setOf("contents", "systeminstruction", "system_instruction", "tools", "toolconfig", "tool_config",
        "cachedcontent", "cached_content", "safetysettings", "safety_settings", "model", "messages", "input", "stream",
        "authorization", "api_key", "apikey", "key", "x-goog-api-key", "thoughtsignature", "thought_signature", "functioncall", "functionresponse")

    internal fun generationConfig(request: ModelRequest): JsonObject {
        val layers = request.parameters
        val sources = listOf(JsonObject(layers.adapterDefaults), JsonObject(layers.modelParameters), JsonObject(layers.agentOverrides)) +
            listOfNotNull(layers.customJson?.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it) as? JsonObject ?: error("Invalid custom JSON") }) +
            ParameterMerger.objectFromAnyMap(request.extra)
        val result = linkedMapOf<String, JsonElement>()
        val spellings = mutableMapOf<String, String>()
        fun check(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) -> require(key.lowercase() !in reserved); check(value) }
                is JsonArray -> element.forEach(::check)
                is JsonPrimitive -> if (!element.isString) require(element.content.toDoubleOrNull()?.isFinite() != false)
                else -> Unit
            }
        }
        fun add(key: String, value: JsonElement, source: String) {
            val native = aliases[key] ?: key
            require(native in allowed)
            if (native == "maxOutputTokens" && request.outputTokenField != null) return
            val previous = spellings[native]
            require(previous == null || previous == source) { "Conflicting parameter aliases" }
            spellings[native] = source
            result[native] = if (key == "stop" && value is JsonPrimitive && value.isString) JsonArray(listOf(value)) else value
        }
        sources.forEach { source ->
            // Equivalent spellings may override an earlier layer; ambiguity is local to one layer.
            spellings.clear()
            check(source)
            source.forEach { (key, value) ->
                if (key == "generationConfig") {
                    require(value is JsonObject)
                    value.forEach { (nested, nestedValue) ->
                        require(nested !in aliases || aliases[nested] == nested)
                        add(nested, nestedValue, "generationConfig.$nested")
                    }
                } else add(key, value, key)
            }
        }
        request.outputTokenField?.let { require(it in setOf("max_tokens", "max_completion_tokens", "max_output_tokens", "maxOutputTokens") && request.outputTokenLimit != null) }
        request.outputTokenLimit?.let { budget ->
            require(budget > 0)
            val configured = result["maxOutputTokens"]?.jsonPrimitive?.intOrNull
            require(configured == null || configured <= budget || request.outputTokenField != null)
            result["maxOutputTokens"] = JsonPrimitive(if (request.outputTokenField != null) budget else minOf(configured ?: budget, budget))
        }
        result["maxOutputTokens"]?.let { require(it is JsonPrimitive && !it.isString && (it.intOrNull ?: 0) > 0) }
        result["candidateCount"]?.let { require(it is JsonPrimitive && !it.isString && it.intOrNull == 1) }
        // This adapter consumes text/tool outputs; reject billable media generation before dispatch.
        result["responseModalities"]?.let { require(it == JsonArray(listOf(JsonPrimitive("TEXT")))) }
        for (field in listOf("temperature", "topP", "topK")) result[field]?.let {
            require(it is JsonPrimitive && !it.isString && it.doubleOrNull != null && it.doubleOrNull!! >= 0)
            if (field == "topP") require(it.doubleOrNull!! <= 1)
            if (field == "temperature") require(it.doubleOrNull!! <= 2)
            if (field == "topK") require(it.intOrNull != null && it.intOrNull!! > 0)
        }
        result["stopSequences"]?.let { require(it is JsonArray && it.size <= 5 && it.all { s -> s is JsonPrimitive && s.isString }) }
        return JsonObject(result)
    }
}

internal const val LOCAL_CALL_PREFIX = "google-local-call:"
internal fun obj(vararg values: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*values))
