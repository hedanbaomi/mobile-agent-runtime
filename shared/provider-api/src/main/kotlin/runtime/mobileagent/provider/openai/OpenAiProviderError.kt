// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import runtime.mobileagent.provider.ProviderConnectionErrorCode

/** Classifies only client errors; callers retain auth, timeout, rate-limit and billing policy. */
internal fun classifyOpenAiClientError(status: Int, raw: String): ProviderConnectionErrorCode {
    val fallback = if (status == 404) ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED
        else ProviderConnectionErrorCode.PROVIDER_REJECTED
    val root = try {
        Json.parseToJsonElement(raw)
    } catch (_: IllegalArgumentException) {
        null
    }
    val errorValue = (root as? JsonObject)?.get("error")
    val error = errorValue as? JsonObject
    fun field(name: String): String? = (error?.get(name) as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    val code = field("code")?.lowercase()
    val type = field("type")?.lowercase()
    val modelCodes = setOf("model_not_found", "unsupported_model")
    val featureCodes = setOf("unsupported_feature", "feature_not_supported")
    if (code in modelCodes) return ProviderConnectionErrorCode.MODEL_NOT_FOUND
    if (code in featureCodes) return ProviderConnectionErrorCode.FEATURE_UNSUPPORTED
    // An explicit unknown code is authoritative, even if its message mentions a feature.
    if (code != null) return fallback
    if (type in modelCodes) return ProviderConnectionErrorCode.MODEL_NOT_FOUND
    if (type in featureCodes) return ProviderConnectionErrorCode.FEATURE_UNSUPPORTED
    // Valid JSON outside the error envelope is not provider error evidence.
    // Json also accepts bare identifiers as primitives; preserve legacy plain-text errors.
    val plainText = root == null || (root is JsonPrimitive && !raw.trimStart().startsWith('"'))
    val errorText = (errorValue as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    val message = (if (plainText) raw else field("message") ?: errorText).lowercase()
    val modelRoute = field("param") != "model" &&
        Regex("""\bmodel\s+(?:list|catalog|endpoint|route|path)\b""").containsMatchIn(message)
    val modelMissing = !modelRoute && (
        listOf("model_not_found", "unknown model", "no such model").any(message::contains) ||
            Regex("""\bmodel\b[^\r\n]{0,256}\b(?:not found|does not exist|not available)\b""").containsMatchIn(message)
        )
    if (status == 404) return if (modelMissing) ProviderConnectionErrorCode.MODEL_NOT_FOUND else fallback
    // Generic structured request errors must not be mistaken for missing capabilities.
    if (type != null || field("param") != null) return fallback
    if (modelMissing) return ProviderConnectionErrorCode.MODEL_NOT_FOUND
    val feature = "(?:stream(?:ing)?|tools?|tool_choice|images?|vision|response_format|reasoning_effort|structured output)"
    val unsupported = "(?:unsupported|not supported|not support(?:ed)?|does not support)"
    val featureMissing = Regex("\\b$feature(?: is| are)? $unsupported\\b").containsMatchIn(message) ||
        Regex("\\b$unsupported $feature\\b").containsMatchIn(message)
    return if (featureMissing) ProviderConnectionErrorCode.FEATURE_UNSUPPORTED else fallback
}
