// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * R3 QA P3: a run that COMPLETED without any visible answer text used to read as a silent
 * non-reply (the only surface was the runtime-authored retrieval-coverage notice).  This
 * returns the additive UI notice, or null when the answer is present.
 *
 * It deliberately returns UI copy instead of assistant text: fabricated text would be
 * persisted as durable history and replayed to the model on the next turn.
 */
internal fun emptyCompletedAnswerNotice(answer: String): String? =
    if (answer.isBlank()) {
        "模型本轮没有给出可见答复。可重试或换一种问法；若上方提示有知识库未参与本次检索，请先确认该知识库已启用检索。"
    } else {
        null
    }

/** Keep the actual runtime disclosure visible instead of flattening every cause to one label. */
internal fun toolVisualEvidenceSummary(raw: String): String? {
    val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    if ((root["textDegradation"] as? JsonPrimitive)?.booleanOrNull != true) return null
    val warning = (root["warning"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isNotBlank() }?.take(800)
    return "视觉证据已降级：" + (warning ?: "未向模型发送原始图片，视觉证据可能不完整。")
}
