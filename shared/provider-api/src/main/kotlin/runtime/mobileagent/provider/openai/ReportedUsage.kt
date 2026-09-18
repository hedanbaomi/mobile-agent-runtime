// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import runtime.mobileagent.provider.ModelEvent

/** Extract only numeric billing facts, including from a decided HTTP error response. */
internal fun reportedUsage(raw: String): ModelEvent.Usage? {
    val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    val usage = root["usage"] as? JsonObject ?: return null
    fun count(obj: JsonObject, vararg keys: String): Int? = keys.firstNotNullOfOrNull {
        (obj[it] as? JsonPrimitive)?.intOrNull?.takeIf { value -> value >= 0 }
    }
    val input = count(usage, "input_tokens", "prompt_tokens")
    val output = count(usage, "output_tokens", "completion_tokens")
    val details = (usage["output_tokens_details"] ?: usage["completion_tokens_details"]) as? JsonObject
    val reasoning = details?.let { count(it, "reasoning_tokens", "reasoningTokens") }
        ?.takeIf { output == null || it <= output }
    return ModelEvent.Usage(input ?: 0, output ?: 0, reasoning, input, output)
}
