// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.serialization.json.*
import runtime.mobileagent.domain.*
import runtime.mobileagent.knowledge.*
import runtime.mobileagent.agent.*
import runtime.mobileagent.provider.*

internal data class ChatHistory(val messages: List<ChatMessage>, val sources: List<ContextSource>)

internal fun projectBoundedHistory(container: AppContainer, visualReferencePlan: (List<String>) -> VisualReferencePlan, messages: List<Message>, max: Int, allowedKbs: Set<String>, source: Map<String, Pair<Citation, String>>, replayReasoning: Boolean = false, allowOriginals: Boolean = true): ChatHistory {
    val groups = mutableListOf<MutableList<Message>>()
    orderedToolEvidenceHistory(messages).forEach { message ->
        val toolEvidence = runCatching { Json.parseToJsonElement(message.metadataJson).jsonObject["toolEvidence"]?.jsonPrimitive?.booleanOrNull }.getOrNull() == true
        if ((message.role == MessageRole.USER && !toolEvidence) || groups.isEmpty()) groups.add(mutableListOf())
        groups.last().add(message)
    }
    val selected = mutableListOf<Message>()
    val turnIds = mutableMapOf<String, String>()
    for (group in groups.asReversed()) {
        val pending = linkedSetOf<String>()
        var valid = true
        var completeEnd = 0
        for ((index, message) in group.withIndex()) {
            if (message.status != "COMPLETE" && !canIncludePartialAssistant(
                    message.role.name.lowercase(), message.status, message.text,
                    message.parts.any { it is ToolCallPart || it is ToolResultPart ||
                        (it is ErrorPart && it.code !in setOf(MessageErrorCode.UNKNOWN_OUTCOME, MessageErrorCode.CANCELLED)) },
                )) break
            message.parts.filterIsInstance<ToolCallPart>().forEach { if (!pending.add(it.callId)) valid = false }
            message.parts.filterIsInstance<ToolResultPart>().forEach { if (!pending.remove(it.callId)) valid = false }
            if (!valid) break
            if (pending.isEmpty()) completeEnd = index + 1
        }
        // A later cancelled/error response must not erase complete exchanges already
        // summarized earlier in this same run. Exclude only the incomplete suffix.
        val complete = group.take(completeEnd)
        if (complete.isEmpty()) continue
        if (selected.size + complete.size > max) break
        selected.addAll(0, complete)
        complete.forEach { turnIds[it.id] = group.first().id }
    }
    val analyzedHashes = selected.flatMap { message ->
        val metadata = runCatching { Json.parseToJsonElement(message.metadataJson).jsonObject }.getOrNull()
        if ((metadata?.get("visualBatchAnalysis") as? JsonPrimitive)?.booleanOrNull != true || message.text.isBlank()) emptyList()
        else (metadata["sourceHashes"] as? JsonObject).orEmpty().map { (asset, hash) -> asset to hash.jsonPrimitive.content }
    }.toMap()
    val projected = selected.map { message ->
        val assets = message.parts.filterIsInstance<ImagePart>()
        val images = if (assets.isEmpty()) emptyList() else {
            val uploads = assets.filter { it.assetId.startsWith(ChatImageUpload.ASSET_PREFIX) }
            val knowledgeAssets = assets - uploads.toSet()
            val uploadedReferences = container.conversationImages.referencesForHistory(
                message.conversationId, uploads, analyzedHashes, allowOriginals,
            )
            require(knowledgeAssets.all { asset -> source.values.any { (citation, _) -> citation.assetId == asset.assetId &&
                citation.knowledgeBaseId in allowedKbs && !container.knowledge.locateCitation(citation).removed } }) { "历史图片来源已撤销；请开启新会话。" }
            val knowledgeImages = if (!allowOriginals) emptyList() else when (val plan = visualReferencePlan(knowledgeAssets.map { it.assetId })) {
                is VisualReferencePlan.Incomplete -> error(plan.reason)
                is VisualReferencePlan.Complete -> plan.images.filter { analyzedHashes[it.assetId] != it.sha256 }
                    .map { InlineImage(it.mediaType, "", it.assetId, it.byteLength, it.sha256) }
            }
            uploadedReferences + knowledgeImages
        }
        val historyText = if (assets.isNotEmpty() && !allowOriginals) message.text +
            "\n历史原图本轮未发送；不得声称本轮查看了这些原图。" else message.text
        ChatMessage(message.role.name.lowercase(), historyText, if (allowOriginals) images else emptyList(), message.parts.filterIsInstance<ToolResultPart>().singleOrNull()?.callId,
            message.parts.filterIsInstance<ToolCallPart>().map { AssistantToolCall(it.callId, it.name, it.argumentsJson) },
            reasoningContent = replayableReasoning(message, replayReasoning))
    }
    return ChatHistory(projected, selected.map { ContextSource(it.id, turnIds.getValue(it.id), complete = it.status == "COMPLETE") })
}
