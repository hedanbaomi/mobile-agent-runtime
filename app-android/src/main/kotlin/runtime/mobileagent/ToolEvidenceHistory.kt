// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.serialization.json.*
import runtime.mobileagent.domain.*
import runtime.mobileagent.provider.openai.StreamingSecretRedactor

/** Repairs only complete legacy exchanges in the request projection. Durable records keep
 * their original identities/order; genuine user input and incomplete exchanges never move. */
internal fun orderedToolEvidenceHistory(messages: List<Message>): List<Message> = buildList {
    var cursor = 0
    while (cursor < messages.size) {
        val assistant = messages[cursor]
        val calls = assistant.parts.filterIsInstance<ToolCallPart>()
        if (assistant.role != MessageRole.ASSISTANT || calls.isEmpty()) {
            add(assistant); cursor++; continue
        }
        val pending = calls.map { it.callId }.toMutableSet()
        val results = mutableListOf<Message>()
        val evidence = mutableListOf<Message>()
        var end = cursor + 1
        var valid = pending.size == calls.size
        while (valid && pending.isNotEmpty() && end < messages.size) {
            val next = messages[end]
            val result = next.parts.filterIsInstance<ToolResultPart>().singleOrNull()
            when {
                next.role == MessageRole.TOOL && result != null && pending.remove(result.callId) -> results += next
                next.isToolImageEvidence() -> evidence += next
                else -> valid = false
            }
            if (valid) end++
        }
        if (valid && pending.isEmpty()) {
            add(assistant); addAll(results); addAll(evidence); cursor = end
        } else {
            add(assistant); cursor++
        }
    }
}

private fun Message.isToolImageEvidence(): Boolean =
    role == MessageRole.USER && status == "COMPLETE" && parts.isNotEmpty() && parts.all { it is ImagePart } &&
        runCatching { Json.parseToJsonElement(metadataJson).jsonObject["toolEvidence"]?.jsonPrimitive?.booleanOrNull }.getOrNull() == true

/** Only provider-declared reasoning parts may populate the replay field; never answer text. */
internal fun replayableReasoning(message: Message, enabled: Boolean = true): String? {
    if (!enabled || message.role != MessageRole.ASSISTANT) return null
    val text = message.parts.filterIsInstance<ReasoningPart>().joinToString("") { it.text }
    require(text.length < MessagePartLimits.MAX_REASONING_CHARS) {
        "CONTEXT_OVERFLOW: 历史推理达到保存上限，无法保证完整重放；请开启新会话。"
    }
    return text.takeIf { it.isNotEmpty() }
}

internal fun appendBoundedReasoning(target: StringBuilder, clean: CharSequence) {
    if (clean.isEmpty()) return
    val remaining = MessagePartLimits.MAX_REASONING_CHARS - target.length
    if (remaining > 0) {
        target.append(clean, 0, minOf(clean.length, remaining))
    }
}

internal fun appendDeclaredReasoning(
    target: StringBuilder,
    redactor: StreamingSecretRedactor,
    delta: String,
    secrets: List<String> = emptyList(),
) {
    if (delta.isEmpty()) return
    redactor.registerSecrets(secrets)
    val clean = redactor.accept(delta, StreamingSecretRedactor.Channel.REASONING)
    appendBoundedReasoning(target, clean)
}

internal fun finishDeclaredReasoning(
    target: StringBuilder,
    redactor: StreamingSecretRedactor,
): String {
    for ((channel, tail) in redactor.finish()) {
        if (channel == StreamingSecretRedactor.Channel.REASONING) {
            appendBoundedReasoning(target, tail)
        }
    }
    return target.toString()
}

internal fun discardDeclaredReasoning(
    target: StringBuilder,
    redactor: StreamingSecretRedactor,
): String {
    redactor.discard()
    return target.toString()
}
