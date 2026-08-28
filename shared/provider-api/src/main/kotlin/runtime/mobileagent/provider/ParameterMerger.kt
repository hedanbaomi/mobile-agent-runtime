// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass

object ParameterMerger {
    private val json = Json { ignoreUnknownKeys = false }
    val reserved = setOf(
        "model", "messages", "input", "tools", "tool_choice", "stream",
        "authorization", "api_key",
    )

    fun merge(
        adapterDefaults: Map<String, JsonElement>,
        modelParams: Map<String, JsonElement>,
        agentOverrides: Map<String, JsonElement>,
        customJson: String?,
        runtimeFields: Map<String, JsonElement>,
        operationId: String,
    ): JsonObject {
        val acc = linkedMapOf<String, JsonElement>()
        acc.putAll(adapterDefaults)
        acc.putAll(modelParams)
        acc.putAll(agentOverrides)
        if (!customJson.isNullOrBlank()) {
            val parsed = runCatching { json.parseToJsonElement(customJson) }.getOrElse {
                throw invalid("Custom JSON is not valid", operationId)
            }
            val obj = parsed as? JsonObject ?: throw invalid("Custom JSON root must be an object", operationId)
            rejectReserved(obj, operationId, path = "")
            rejectNonFinite(obj, operationId)
            acc.putAll(obj)
        }
        rejectReserved(JsonObject(runtimeFields.filterKeys { it in reserved }.mapValues { it.value }), operationId, alreadyRuntime = true)
        acc.putAll(runtimeFields)
        return JsonObject(acc)
    }

    fun rejectReserved(element: JsonElement, operationId: String, path: String = "", alreadyRuntime: Boolean = false) {
        when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    val name = key.lowercase()
                    if (!alreadyRuntime && name in reserved) {
                        throw invalid("Reserved field '$key' cannot be set via custom JSON", operationId)
                    }
                    rejectReserved(value, operationId, "$path/$key", alreadyRuntime)
                }
            }
            is JsonArray -> element.forEachIndexed { i, v -> rejectReserved(v, operationId, "$path/$i", alreadyRuntime) }
            else -> Unit
        }
    }

    private fun rejectNonFinite(element: JsonElement, operationId: String) {
        when (element) {
            is JsonObject -> element.values.forEach { rejectNonFinite(it, operationId) }
            is JsonArray -> element.forEach { rejectNonFinite(it, operationId) }
            is JsonPrimitive -> {
                if (element.isString.not()) {
                    val d = element.content.toDoubleOrNull()
                    if (d != null && !d.isFinite()) {
                        throw invalid("Non-finite numbers are not allowed", operationId)
                    }
                }
            }
        }
    }

    private fun invalid(message: String, operationId: String): AppException =
        AppError(
            code = ErrorCode.INVALID_CONFIG,
            userMessage = message,
            retryClass = RetryClass.USER_ACTION,
            stage = "parameter-merge",
            operationId = operationId,
        ).asException()
}

private typealias AppException = runtime.mobileagent.domain.AppException
