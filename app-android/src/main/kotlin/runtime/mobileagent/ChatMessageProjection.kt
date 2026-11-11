// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.serialization.json.*
import runtime.mobileagent.agent.toolResultUserMessage
import runtime.mobileagent.domain.*
import runtime.mobileagent.feature.chat.ChatMessageUi

internal class ChatMessageProjectionCopy(
    val visualBatchAnalyzed: (Int) -> String = { "Sent and analyzed $it original image(s) with the model." },
    val toolAttachedImages: (Int) -> String = { "Tool attached $it original image(s)." },
    val turnIncomplete: (String) -> String = { "This turn did not finish ($it); output was kept and will not be resent automatically." },
    val partialRetrievalCoverage: String = "Some knowledge bases did not participate in this retrieval; do not claim those unsearched sources were checked.",
    val visualDegraded: (String) -> String? = { null },
)

internal fun projectChatMessage(
    rawMessage: Message,
    streaming: Boolean,
    copy: ChatMessageProjectionCopy = ChatMessageProjectionCopy(),
): ChatMessageUi {
    val message = trpgMessageForDisplay(rawMessage)
    val reasoningParts = message.parts.filterIsInstance<ReasoningPart>()
    val errorPart = message.parts.filterIsInstance<ErrorPart>().lastOrNull()
    val diffPart = message.parts.filterIsInstance<DiffPart>().lastOrNull()
    val toolResultPart = message.parts.filterIsInstance<ToolResultPart>().lastOrNull()
    val toolFailureSummary = toolResultPart
        ?.takeIf { it.status != "SUCCEEDED" }
        ?.let { toolResultUserMessage(it.resultJson) }
    // Reload-visible partial-coverage note, persisted in the assistant
    // message metadata next to the citations.
    val coverageNotice = message.takeIf { it.role == MessageRole.ASSISTANT }
        ?.let { coverageNoticeOf(it.metadataJson, copy.partialRetrievalCoverage) }
    val metadata = runCatching { Json.parseToJsonElement(message.metadataJson).jsonObject }.getOrNull()
    val visualReceipt = (metadata?.get("visualBatchAnalysis") as? JsonPrimitive)?.booleanOrNull == true
    val toolEvidence = message.role == MessageRole.USER &&
        (metadata?.get("toolEvidence") as? JsonPrimitive)?.booleanOrNull == true
    val trpgCard = parseTrpgToolCard(message)
    return ChatMessageUi(
        id = message.id,
        role = if (visualReceipt || toolEvidence) "tool" else message.role.name.lowercase(),
        text = message.text,
        timeLabel = message.createdAt.take(16),
        attachedImages = ChatImagePolicy.attachedCount(message, visualReceipt, toolEvidence),
        trpgToolCard = trpgCard,
        citationIds = message.parts.filterIsInstance<CitationPart>().map { it.citationId },
        streaming = message.status == "STREAMING" && streaming,
        reasoning = reasoningParts.joinToString("") { it.text },
        reasoningStreaming = reasoningParts.lastOrNull()?.streaming == true && streaming,
        eventSummary = when {
            visualReceipt -> copy.visualBatchAnalyzed(message.parts.filterIsInstance<ImagePart>().size)
            toolEvidence -> copy.toolAttachedImages(message.parts.filterIsInstance<ImagePart>().size)
            errorPart != null -> errorPart.message
            diffPart != null -> diffPart.summary
            else -> listOfNotNull(toolFailureSummary, coverageNotice,
                if (message.role == MessageRole.ASSISTANT && message.status != "COMPLETE" &&
                    (message.status != "STREAMING" || !streaming))
                    copy.turnIncomplete(message.status) else null,
                toolResultPart?.resultJson?.let { copy.visualDegraded(it) },
                trpgCard?.let(::trpgToolEventSummary),
            ).joinToString(" ")
        },
    )
}

private fun coverageNoticeOf(metadataJson: String, fallbackNotice: String): String? {
    val root = runCatching { Json.parseToJsonElement(metadataJson).jsonObject }.getOrNull() ?: return null
    val coverage = root["retrievalCoverage"]?.jsonObject ?: return null
    if (coverage["partial"]?.jsonPrimitive?.booleanOrNull != true) return null
    return coverage["noticeUi"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: fallbackNotice
}
