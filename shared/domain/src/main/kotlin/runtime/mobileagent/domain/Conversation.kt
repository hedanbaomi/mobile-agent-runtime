// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AgentSnapshot(
    val id: String,
    val schemaVersion: Int,
    val agentId: String,
    val promptRevisionId: String,
    val chatModelId: String,
    val providerRevision: Int,
    val knowledgeBaseIds: List<String>,
    val skillIds: List<String>,
    val createdAt: String,
    /** Provider identity is captured beside the model so a later reordering cannot retarget a run. */
    val providerId: String = "",
    val chatModelRevision: Int = 0,
    val visionModelId: String? = null,
    val visionModelRevision: Int? = null,
    val embeddingModelId: String? = null,
    val embeddingModelRevision: Int? = null,
    val rerankerModelId: String? = null,
    val rerankerModelRevision: Int? = null,
    val parameterOverridesJson: String = "{}",
    val contextPolicyJson: String = "{}",
    val permissionSettingsJson: String = "{}",
    /** Canonical, non-secret expansion retained for inspection and transfer validation. */
    val bindingManifestJson: String = "{}",
)

@Serializable
data class Conversation(
    val id: String,
    val snapshotId: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
enum class MessageRole { SYSTEM, USER, ASSISTANT, TOOL }

/**
 * Durable message content is typed instead of relying on a role-specific text convention.
 * Image parts contain references only; bytes stay in the knowledge CAS and are never copied
 * into the conversation database by default.
 */
@Serializable
sealed interface MessagePart

@Serializable
@SerialName("text")
data class TextPart(val value: String) : MessagePart

@Serializable
@SerialName("image")
data class ImagePart(
    val assetId: String,
    val mediaType: String = "application/octet-stream",
    val blobHash: String? = null,
) : MessagePart

@Serializable
@SerialName("tool_call")
data class ToolCallPart(
    val callId: String,
    val name: String,
    val argumentsJson: String,
) : MessagePart

@Serializable
@SerialName("tool_result")
data class ToolResultPart(
    val callId: String,
    val resultJson: String,
    val status: String = "SUCCEEDED",
) : MessagePart

@Serializable
@SerialName("citation")
data class CitationPart(val citationId: String) : MessagePart

@Serializable
data class Message(
    val id: String,
    val conversationId: String,
    val parentMessageId: String? = null,
    val role: MessageRole,
    val text: String = "",
    val status: String,
    val createdAt: String,
    val parts: List<MessagePart> = emptyList(),
    val metadataJson: String = "{}",
)
