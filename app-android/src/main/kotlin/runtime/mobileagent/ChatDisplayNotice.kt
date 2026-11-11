// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * R3 QA P3: a run that COMPLETED without any visible answer text used to read as a silent
 * non-reply (the only surface was the runtime-authored retrieval-coverage notice). This
 * returns the additive UI notice, or null when the answer is present.
 *
 * [notice] is already-localized host copy. It deliberately stays UI copy instead of assistant
 * text: fabricated text would be persisted as durable history and replayed to the model.
 */
internal fun emptyCompletedAnswerNotice(answer: String, notice: String): String? =
    if (answer.isBlank()) notice else null

/**
 * Keep the actual runtime disclosure visible instead of flattening every cause to one label.
 *
 * [degradedTemplate] and [defaultWarning] are the already-localized host copy; the runtime
 * warning text inside the payload is data and is never translated or altered.
 */
internal fun toolVisualEvidenceSummary(
    raw: String,
    degradedTemplate: String,
    defaultWarning: String,
): String? {
    val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    if ((root["textDegradation"] as? JsonPrimitive)?.booleanOrNull != true) return null
    val warning = (root["warning"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isNotBlank() }?.take(800)
    return fillSinglePlaceholder(degradedTemplate, warning ?: defaultWarning)
}

/**
 * Substitute the single positional placeholder without [String.format]: a runtime warning is
 * data and may legitimately contain `%`, which format would reject at runtime.
 */
internal fun fillSinglePlaceholder(template: String, value: String): String =
    template.replace("%1\$s", value)