// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.serialization.json.*
import runtime.mobileagent.domain.*
import runtime.mobileagent.feature.chat.ChatMessageUi
import runtime.mobileagent.agent.toolResultUserMessage

internal fun projectChatMessage(rawMessage: Message, streaming: Boolean): ChatMessageUi {
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
        ?.let { coverageNoticeOf(it.metadataJson) }
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
            visualReceipt -> "已向模型传输并分析 " + message.parts.filterIsInstance<ImagePart>().size + " 张原图"
            toolEvidence -> "工具附带 " + message.parts.filterIsInstance<ImagePart>().size + " 张原图"
            errorPart != null -> errorPart.message
            diffPart != null -> diffPart.summary
            else -> listOfNotNull(toolFailureSummary, coverageNotice,
                if (message.role == MessageRole.ASSISTANT && message.status != "COMPLETE" &&
                    (message.status != "STREAMING" || !streaming))
                    "本轮未完成（${message.status}）；已保留输出，不会自动重发。" else null,
                toolResultPart?.resultJson?.let(::toolVisualEvidenceSummary),
                trpgCard?.let(::trpgToolEventSummary),
            ).joinToString(" ")
        },
    )
}

private fun coverageNoticeOf(metadataJson: String): String? {
    val root = runCatching { Json.parseToJsonElement(metadataJson).jsonObject }.getOrNull() ?: return null
    val coverage = root["retrievalCoverage"]?.jsonObject ?: return null
    if (coverage["partial"]?.jsonPrimitive?.booleanOrNull != true) return null
    return coverage["noticeUi"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: "本次检索有知识库未参与；不要声称已检索这些未参与的来源。"
}
