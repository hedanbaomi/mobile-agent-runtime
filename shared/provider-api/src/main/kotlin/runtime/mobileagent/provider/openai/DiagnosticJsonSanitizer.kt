// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import runtime.mobileagent.provider.SecretRedactor

/** Fail-closed JSON content sanitizer used only by explicitly enabled local diagnostics. */
internal object DiagnosticJsonSanitizer {
    private val privateKeys = setOf(
        "encrypted_content",
        "encryptedcontent",
        "provider_continuation",
        "providercontinuation",
        "provider_continuation_items",
        "providercontinuationitems",
    )

    fun sanitize(raw: String, secrets: List<String>): String? {
        val parsed = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return null
        return sanitizeElement(parsed, secrets).toString()
    }

    fun sanitizeSseLine(line: String, secrets: List<String>): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return null
        val data = trimmed.removePrefix("data:").trim()
        if (data == "[DONE]") return data
        val parsed = runCatching { Json.parseToJsonElement(data) }.getOrNull() ?: return null
        return sanitizeSseElement(parsed, secrets).toString()
    }

    fun sanitizeModelEvent(event: runtime.mobileagent.provider.ModelEvent, secrets: List<String>): String? {
        val fields = when (event) {
            is runtime.mobileagent.provider.ModelEvent.TextDelta -> mapOf("type" to JsonPrimitive("text_delta"), "text" to JsonPrimitive(event.text))
            is runtime.mobileagent.provider.ModelEvent.ReasoningDelta -> mapOf("type" to JsonPrimitive("reasoning_delta"), "text" to JsonPrimitive(event.text))
            is runtime.mobileagent.provider.ModelEvent.RefusalDelta -> mapOf("type" to JsonPrimitive("refusal_delta"), "text" to JsonPrimitive(event.text))
            is runtime.mobileagent.provider.ModelEvent.ToolCallDelta -> mapOf(
                "type" to JsonPrimitive("tool_call"), "call_id" to JsonPrimitive(event.callId),
                "name" to JsonPrimitive(event.name), "arguments" to JsonPrimitive(event.argumentsJson),
            )
            is runtime.mobileagent.provider.ModelEvent.Failed -> mapOf("type" to JsonPrimitive("failed"), "code" to JsonPrimitive(event.sanitizedMessage))
            else -> return null
        }
        return sanitizeElement(JsonObject(fields), secrets).toString()
    }

    private fun sanitizeSseElement(value: JsonElement, secrets: List<String>, key: String? = null): JsonElement = when (value) {
        is JsonObject -> JsonObject(
            value.entries
                .filterNot { (childKey, _) -> childKey.lowercase() in privateKeys }
                .associate { (childKey, child) -> childKey to sanitizeSseElement(child, secrets, childKey) },
        )
        is JsonArray -> JsonArray(value.map { sanitizeSseElement(it, secrets, key) })
        is JsonPrimitive -> if (value.isString) {
            if (key in structuralSseKeys) JsonPrimitive(SecretRedactor.redact(value.content, secrets))
            else JsonPrimitive("<captured-after-stream-redaction>")
        } else value
    }

    private fun sanitizeElement(value: JsonElement, secrets: List<String>): JsonElement = when (value) {
        is JsonObject -> JsonObject(
            value.entries
                .filterNot { (key, _) -> key.lowercase() in privateKeys }
                .associate { (key, child) -> key to sanitizeElement(child, secrets) },
        )
        is JsonArray -> JsonArray(value.map { sanitizeElement(it, secrets) })
        is JsonPrimitive -> if (value.isString) JsonPrimitive(SecretRedactor.redact(value.content, secrets)) else value
    }

    private val structuralSseKeys = setOf(
        "type", "role", "finish_reason", "status", "id", "item_id", "call_id",
    )
}
