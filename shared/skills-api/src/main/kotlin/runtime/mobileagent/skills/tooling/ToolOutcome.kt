// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills.tooling

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Unified durable/model-facing tool outcome contract.
 *
 * Every terminal [runtime.mobileagent.skills.ToolResult] must project to a
 * JSON **object** envelope so model transport, durable [ToolResultPart]
 * persistence, reload, and UI can share one semantic:
 *
 * ```json
 * {"ok":false,"status":"DENIED","error":{"code":"PERMISSION_DENIED","message":"...","retryable":false}}
 * ```
 *
 * Plain free-text reasons must never cross this boundary: the conversation
 * store rejects non-object payloads, which previously turned a legitimate
 * `Denied`/`Invalid` into a persistence failure reported as an internal run
 * error. Backend exception text, paths, and secrets must already be redacted
 * by the caller; this object only shapes the envelope.
 */
enum class ToolOutcomeStatus {
    VALUE,
    DENIED,
    INVALID,
    FAILED,
    UNKNOWN_OUTCOME,
    NEEDS_APPROVAL,
}

object ToolOutcome {
    const val STATUS_VALUE = "VALUE"
    const val STATUS_DENIED = "DENIED"
    const val STATUS_INVALID = "INVALID"
    const val STATUS_FAILED = "FAILED"
    const val STATUS_UNKNOWN_OUTCOME = "UNKNOWN_OUTCOME"
    const val STATUS_NEEDS_APPROVAL = "NEEDS_APPROVAL"

    /** Denied outcomes are never retryable and never escalate to INTERNAL. */
    fun denied(
        code: ToolErrorCode = ToolErrorCode.PERMISSION_DENIED,
        message: String,
        retryable: Boolean = false,
    ): String = envelope(STATUS_DENIED, code, message, retryable)

    /** Invalid outcomes are never retryable and never escalate to INTERNAL. */
    fun invalid(
        code: ToolErrorCode = ToolErrorCode.INVALID_REQUEST,
        message: String,
        retryable: Boolean = false,
    ): String = envelope(STATUS_INVALID, code, message, retryable)

    fun failed(error: ToolError): String = envelope(STATUS_FAILED, error.code, error.message, error.retryable)

    fun unknown(message: String, automaticReplayAllowed: Boolean = false): String {
        require(!automaticReplayAllowed) { "Unknown outcomes must never allow automatic replay" }
        val root = Json.parseToJsonElement(envelope(STATUS_UNKNOWN_OUTCOME, ToolErrorCode.UNKNOWN_OUTCOME, message, false))
            .jsonObject.toMutableMap()
        root["automaticReplayAllowed"] = JsonPrimitive(false)
        return JsonObject(root).toString()
    }

    fun needsApproval(message: String = ToolErrorCode.APPROVAL_REQUIRED.name): String =
        envelope(STATUS_NEEDS_APPROVAL, ToolErrorCode.APPROVAL_REQUIRED, message, false)

    fun envelope(status: String, code: ToolErrorCode, message: String, retryable: Boolean): String {
        val safeMessage = message.ifBlank { code.name }.take(2000)
        return JsonObject(
            mapOf(
                "ok" to JsonPrimitive(false),
                "status" to JsonPrimitive(status),
                "error" to JsonObject(
                    mapOf(
                        "code" to JsonPrimitive(code.name),
                        "message" to JsonPrimitive(safeMessage),
                        "retryable" to JsonPrimitive(retryable),
                    ),
                ),
            ),
        ).toString()
    }

    /** True when the payload parses as a JSON object (the durable requirement). */
    fun isDurableObject(resultJson: String): Boolean =
        runCatching { Json.parseToJsonElement(resultJson) is JsonObject }.getOrDefault(false)

    /** Typed status read for UI/reload; null when the payload is not an outcome envelope. */
    fun statusOf(resultJson: String): ToolOutcomeStatus? {
        val root = runCatching { Json.parseToJsonElement(resultJson) as? JsonObject }.getOrNull() ?: return null
        // Legacy failure envelopes carry {"ok":false,"error":{...}} without a status.
        val rawStatus = root["status"]?.jsonPrimitive?.contentOrNull
            ?: if (root["error"] is JsonObject) STATUS_FAILED else return null
        return runCatching { ToolOutcomeStatus.valueOf(rawStatus) }.getOrNull()
    }

    /** Typed error code read for UI; null when absent or not an envelope. */
    fun errorCodeOf(resultJson: String): ToolErrorCode? {
        val root = runCatching { Json.parseToJsonElement(resultJson) as? JsonObject }.getOrNull() ?: return null
        val code = root["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
            ?: root["code"]?.jsonPrimitive?.contentOrNull
            ?: return null
        return runCatching { ToolErrorCode.valueOf(code) }.getOrNull()
    }

    fun retryableOf(resultJson: String): Boolean? {
        val root = runCatching { Json.parseToJsonElement(resultJson) as? JsonObject }.getOrNull() ?: return null
        return root["error"]?.jsonObject?.get("retryable")?.jsonPrimitive?.booleanOrNull
    }
}
