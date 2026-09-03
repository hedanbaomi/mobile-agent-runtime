// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass

object ParameterMerger {
    private val json = Json { ignoreUnknownKeys = false }
    val reserved = setOf(
        "model", "messages", "input", "tools", "tool_choice", "stream",
        "authorization", "api_key", "instructions",
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
        rejectUserLayer(modelParams, operationId, "model parameters")
        rejectUserLayer(agentOverrides, operationId, "agent overrides")
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

    /** Merge a request's typed layers and its source-compatible legacy extras. */
    fun merge(
        layers: ParameterLayers,
        legacyExtras: Map<String, Any?>,
        runtimeFields: Map<String, JsonElement>,
        operationId: String,
    ): JsonObject {
        val custom = linkedMapOf<String, JsonElement>()
        layers.customJson?.takeIf { it.isNotBlank() }?.let { raw ->
            val parsed = runCatching { json.parseToJsonElement(raw) }.getOrElse {
                throw invalid("Custom JSON is not valid", operationId)
            }
            val obj = parsed as? JsonObject ?: throw invalid("Custom JSON root must be an object", operationId)
            rejectReserved(obj, operationId)
            rejectNonFinite(obj, operationId)
            custom.putAll(obj)
        }
        val extras = objectFromAnyMap(legacyExtras, operationId, "request extras")
        rejectReserved(extras, operationId)
        rejectNonFinite(extras, operationId)
        custom.putAll(extras)
        return merge(
            adapterDefaults = layers.adapterDefaults,
            modelParams = layers.modelParameters,
            agentOverrides = layers.agentOverrides,
            customJson = if (custom.isEmpty()) null else JsonObject(custom).toString(),
            runtimeFields = runtimeFields,
            operationId = operationId,
        )
    }

    fun rejectReserved(element: JsonElement, operationId: String, path: String = "", alreadyRuntime: Boolean = false) {
        when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    val name = key.lowercase()
                    if (!alreadyRuntime && name in reserved) {
                        throw invalid("Reserved field '$key' cannot be set via user parameters", operationId)
                    }
                    rejectReserved(value, operationId, "$path/$key", alreadyRuntime)
                }
            }
            is JsonArray -> element.forEachIndexed { i, v -> rejectReserved(v, operationId, "$path/$i", alreadyRuntime) }
            else -> Unit
        }
    }

    private fun rejectUserLayer(values: Map<String, JsonElement>, operationId: String, layer: String) {
        val objectValue = JsonObject(values)
        runCatching { rejectReserved(objectValue, operationId) }.getOrElse { error ->
            if (error is AppException) {
                throw invalid("Reserved field in $layer", operationId)
            }
            throw error
        }
        rejectNonFinite(objectValue, operationId)
    }

    /** Convert only JSON-shaped values; arbitrary objects are never stringified into a request. */
    fun objectFromAnyMap(values: Map<String, Any?>, operationId: String = "parameter-merge", source: String = "parameters"): JsonObject =
        buildJsonObject {
            values.forEach { (key, value) ->
                if (key.isBlank()) throw invalid("Blank key in $source", operationId)
                put(key, anyToJson(value, operationId, source))
            }
        }

    private fun anyToJson(value: Any?, operationId: String, source: String): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Char -> JsonPrimitive(value.toString())
        is Boolean -> JsonPrimitive(value)
        is Byte -> JsonPrimitive(value)
        is Short -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Float -> {
            if (!value.isFinite()) throw invalid("Non-finite number in $source", operationId)
            JsonPrimitive(value)
        }
        is Double -> {
            if (!value.isFinite()) throw invalid("Non-finite number in $source", operationId)
            JsonPrimitive(value)
        }
        is Number -> {
            val number = value.toDouble()
            if (!number.isFinite()) throw invalid("Non-finite number in $source", operationId)
            JsonPrimitive(value.toString())
        }
        is Map<*, *> -> {
            val nested = linkedMapOf<String, JsonElement>()
            value.forEach { (key, item) ->
                val stringKey = key as? String ?: throw invalid("Non-string key in $source", operationId)
                if (stringKey.isBlank()) throw invalid("Blank key in $source", operationId)
                nested[stringKey] = anyToJson(item, operationId, source)
            }
            JsonObject(nested)
        }
        is Iterable<*> -> JsonArray(value.map { anyToJson(it, operationId, source) })
        is Array<*> -> JsonArray(value.map { anyToJson(it, operationId, source) })
        else -> throw invalid("Unsupported value in $source", operationId)
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
