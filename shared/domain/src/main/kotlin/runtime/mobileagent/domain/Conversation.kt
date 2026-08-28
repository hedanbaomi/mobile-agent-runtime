// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

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

@Serializable
data class Message(
    val id: String,
    val conversationId: String,
    val parentMessageId: String? = null,
    val role: MessageRole,
    val text: String,
    val status: String,
    val createdAt: String,
)
