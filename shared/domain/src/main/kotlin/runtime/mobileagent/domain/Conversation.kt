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

/**
 * Limits applied to durable conversation parts.  These limits are deliberately part of the
 * domain contract so the database, transfer codec, and UI cannot silently choose different
 * bounds.  Reasoning and diff previews are display data, not an unbounded transcript channel.
 */
object MessagePartLimits {
    const val MAX_REASONING_CHARS = 64 * 1024
    const val MAX_DIFF_SUMMARY_CHARS = 8 * 1024
    const val MAX_DIFF_PREVIEW_CHARS = 64 * 1024
    const val MAX_ERROR_MESSAGE_CHARS = 4 * 1024
    const val MAX_PART_COUNT = 512
    const val MAX_TOTAL_ENCODED_BYTES = 4 * 1024 * 1024
}

/** A closed set of safe, model-facing message failure categories. */
@Serializable
enum class MessageErrorCode {
    CONFIG_INVALID,
    SECRET_UNAVAILABLE,
    PROVIDER_UNAUTHORIZED,
    NETWORK_UNAVAILABLE,
    RATE_LIMITED,
    TIMEOUT,
    CONTEXT_OVERFLOW,
    PERMISSION_DENIED,
    WORKSPACE_UNAVAILABLE,
    RESOURCE_LIMIT,
    BUDGET_EXHAUSTED,
    INVALID_RESPONSE,
    TOOL_FAILED,
    UNKNOWN_OUTCOME,
    CANCELLED,
    INTERNAL,
}

@Serializable
@SerialName("text")
data class TextPart(val value: String) : MessagePart

/**
 * Provider-returned reasoning/thinking.  This part must only be created from actual provider
 * content; the runtime must not infer or synthesize hidden chain-of-thought.  [streaming] is a
 * transport hint for the presentation layer and is not mutated by UI expansion state.
 */
@Serializable
@SerialName("reasoning")
data class ReasoningPart(
    val text: String,
    val streaming: Boolean = false,
) : MessagePart {
    init {
        require(text.isNotBlank()) { "Reasoning content must not be blank" }
        require(text.length <= MessagePartLimits.MAX_REASONING_CHARS) {
            "Reasoning content exceeds the durable limit"
        }
    }

    /** Alias for callers that use the same value naming as [TextPart]. */
    val value: String
        get() = text

    /** Presentation-friendly alias retained without duplicating serialized state. */
    val isStreaming: Boolean
        get() = streaming
}

/**
 * A provider-returned refusal.  This is readable assistant output, not a
 * transport failure and not reasoning: it renders and persists like answer
 * text and proves the endpoint, auth, and protocol round-trip succeeded.
 */
@Serializable
@SerialName("refusal")
data class RefusalPart(
    val text: String,
) : MessagePart {
    init {
        require(text.isNotBlank()) { "Refusal content must not be blank" }
        require(text.length <= MessagePartLimits.MAX_REASONING_CHARS) {
            "Refusal content exceeds the durable limit"
        }
    }

    /** Alias for callers that use the same value naming as [TextPart]. */
    val value: String
        get() = text
}

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

/**
 * A bounded, user-safe diff event.  Only a summary and an optional patch preview are persisted;
 * the preview must not carry absolute device paths.  Workspace-relative paths in a conventional
 * diff are allowed and remain subject to the workspace tool's own confinement checks.
 */
@Serializable
@SerialName("diff")
data class DiffPart(
    val summary: String,
    val patchPreview: String = "",
    val changedFiles: Int = 0,
) : MessagePart {
    init {
        require(summary.isNotBlank()) { "Diff summary must not be blank" }
        require(summary.length <= MessagePartLimits.MAX_DIFF_SUMMARY_CHARS) {
            "Diff summary exceeds the durable limit"
        }
        require(patchPreview.length <= MessagePartLimits.MAX_DIFF_PREVIEW_CHARS) {
            "Diff preview exceeds the durable limit"
        }
        require(changedFiles >= 0) { "Diff changedFiles must not be negative" }
        require(!containsAbsolutePath(summary) && !containsAbsolutePath(patchPreview)) {
            "Diff preview must not contain an absolute path"
        }
    }

    /** Compatibility alias for integrations that call the preview a patch. */
    val patch: String
        get() = patchPreview

    private companion object {
        /**
         * Keep this deliberately conservative.  A diff can contain relative POSIX/Windows
         * paths, but it must never disclose a drive-qualified, rooted, or UNC device path.
         */
        val ABSOLUTE_PATH = Regex("""(^|[\s\"'`(){}\[\]])(?:[A-Za-z]:[\\/]|/|\\)""")

        fun containsAbsolutePath(value: String): Boolean = ABSOLUTE_PATH.containsMatchIn(value)
    }
}

/** A typed, bounded error suitable for rendering and durable audit/history. */
@Serializable
@SerialName("error")
data class ErrorPart(
    val code: MessageErrorCode,
    val message: String,
    val retryable: Boolean = false,
) : MessagePart {
    init {
        require(message.isNotBlank()) { "Message error must not be blank" }
        require(message.length <= MessagePartLimits.MAX_ERROR_MESSAGE_CHARS) {
            "Message error exceeds the durable limit"
        }
    }
}

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
