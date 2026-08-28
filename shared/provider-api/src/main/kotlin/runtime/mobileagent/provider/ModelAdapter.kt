// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider

import kotlinx.coroutines.flow.Flow
import runtime.mobileagent.domain.ModelProfile

data class CapabilityReport(
    val modelId: String,
    val supportsStream: Boolean,
    val supportsTools: Boolean,
    val supportsImages: Boolean,
    val source: String,
    val probedAt: String,
)

data class InlineImage(
    val mediaType: String,
    val base64: String,
    val assetId: String? = null,
)

data class AssistantToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class ChatMessage(
    val role: String,
    val text: String = "",
    val images: List<InlineImage> = emptyList(),
    val toolCallId: String? = null,
    val toolCalls: List<AssistantToolCall> = emptyList(),
)

data class ModelRequest(
    val modelId: String,
    val messages: List<ChatMessage>,
    val tools: List<Map<String, String>> = emptyList(),
    val stream: Boolean = true,
    val extra: Map<String, Any?> = emptyMap(),
)

sealed interface ModelEvent {
    data class TextDelta(val text: String) : ModelEvent
    data class ToolCallDelta(val callId: String, val name: String, val argumentsJson: String) : ModelEvent
    data class Usage(val inputTokens: Int, val outputTokens: Int) : ModelEvent
    data class ToolApprovalRequired(val callId: String, val name: String, val argumentsJson: String) : ModelEvent
    data object Completed : ModelEvent
    data class Failed(val sanitizedMessage: String) : ModelEvent
}

data class EmbeddingRequest(val modelId: String, val inputs: List<String>)
data class EmbeddingBatch(val vectors: List<FloatArray>, val dimension: Int)

fun interface SecretStore {
    suspend fun resolveForHost(ref: String): CharArray
}

interface ModelAdapter {
    suspend fun probe(profile: ModelProfile): CapabilityReport
    fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent>
    suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch
}
