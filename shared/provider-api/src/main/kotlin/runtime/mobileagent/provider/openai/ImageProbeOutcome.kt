// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import kotlinx.serialization.json.*

/** Accepted but truncated output cannot disprove image input, and must not trigger a paid replay. */
internal fun imageProbeBudgetExhausted(raw: String, responses: Boolean): Boolean = runCatching {
    val root = Json.parseToJsonElement(raw).jsonObject
    if (root["error"] is JsonObject) return@runCatching false
    if (responses) {
        root["status"]?.jsonPrimitive?.contentOrNull == "incomplete" &&
            root["incomplete_details"]?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull == "max_output_tokens" &&
            root["output"] is JsonArray
    } else {
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@runCatching false
        choice["finish_reason"]?.jsonPrimitive?.contentOrNull == "length" && choice["message"] is JsonObject
    }
}.getOrDefault(false)

internal const val IMAGE_PROBE_MAX_OUTPUT_TOKENS = 1024
