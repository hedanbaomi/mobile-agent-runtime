// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import runtime.mobileagent.domain.*
import runtime.mobileagent.feature.chat.*
import runtime.mobileagent.knowledge.*
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.InlineImage
import java.util.Base64

/** Rechecks frozen context and original image provenance immediately before disclosure/dispatch. */
internal class ChatRunDispatchAuthorization(
    private val container: AppContainer,
    private val runId: String,
    private val conversationId: String,
    private val snapshot: AgentSnapshot,
    private val kbIds: List<String>,
    private val bound: List<Citation>,
    private val runCitations: Map<String, Pair<Citation, String>>,
    private val typedHistory: List<ChatMessage>,
    private val historicalCitations: List<String>,
    private val runTools: RunTools,
    private val authorizationFingerprint: String,
    private val contextAuthorizationFingerprint: () -> String,
) {
    suspend fun loadImage(source: InlineImage): InlineImage = withContext(Dispatchers.IO) {
                        require(contextAuthorizationFingerprint() == authorizationFingerprint) {
                            "PERMISSION_DENIED: visual source authorization changed"
                        }
                        if (source.assetId?.startsWith(ChatImageUpload.ASSET_PREFIX) == true) {
                            return@withContext container.conversationImages.load(conversationId, source)
                        }
                        runTools.loadImage(source) ?: run {
                            val citation = (bound + runCitations.values.map { it.first }).firstOrNull { it.assetId == source.assetId }
                                ?: error("PERMISSION_DENIED: visual source lacks authorized provenance")
                            val bytes = container.knowledge.evidenceBytes(citation) ?: error("Visual source removed")
                            require(bytes.first == source.mediaType && bytes.second.size.toLong() == source.byteLength &&
                                RunCoordinator.sha256Hex(bytes.second) == source.sha256) { "Visual original changed" }
                            source.copy(base64 = Base64.getEncoder().encodeToString(bytes.second))
                        }
    }
    fun beforeImageRequest(images: List<InlineImage>) {
                        val live = container.agents.get(snapshot.agentId) ?: error("Agent authorization was removed")
                        val allowed = kbIds.intersect(live.knowledgeBaseIds.toSet())
                        require(images.all { image ->
                            if (image.assetId?.startsWith(ChatImageUpload.ASSET_PREFIX) == true) {
                                container.conversationImages.verify(conversationId, image)
                                true
                            } else runCitations.values.any { (citation, _) ->
                            if (citation.assetId != image.assetId || citation.knowledgeBaseId !in allowed) false
                            else container.knowledge.locateCitation(citation).let { !it.removed && it.blobHash == image.sha256 }
                        } }) { "PERMISSION_DENIED: visual source removed or changed before dispatch" }
    }
    fun beforeModelRequest() {
                        container.worldBooks.validateDispatch(runId)
                        require(contextAuthorizationFingerprint() == authorizationFingerprint) {
                            "PERMISSION_DENIED: authorization changed before model request; context summary cannot restore access"
                        }
                        val live = container.agents.get(snapshot.agentId) ?: error("Agent authorization was removed")
                        val allowed = kbIds.intersect(live.knowledgeBaseIds.toSet())
                        require(bound.all { it.knowledgeBaseId in allowed && !container.knowledge.locateCitation(it).removed }) {
                            "Knowledge authorization or source changed before request"
                        }
                        require(historicalCitations.all { id -> runCitations[id]?.first?.let { citation ->
                            citation.knowledgeBaseId in allowed && !container.knowledge.locateCitation(citation).removed
                        } == true }) { "PERMISSION_DENIED: historical knowledge was removed or revoked; start a new conversation" }
                        val historyAssetIds = typedHistory.flatMap { it.images }.mapNotNull { it.assetId }
                            .filterNot { it.startsWith(ChatImageUpload.ASSET_PREFIX) }.toSet()
                        require(historyAssetIds.all { assetId -> runCitations.values.any { (citation, _) ->
                            citation.assetId == assetId && citation.knowledgeBaseId in allowed && !container.knowledge.locateCitation(citation).removed
                        } }) { "PERMISSION_DENIED: historical visual evidence was removed or revoked" }
    }
}
