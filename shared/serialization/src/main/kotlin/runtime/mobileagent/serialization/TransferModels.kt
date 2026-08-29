// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization

import kotlinx.serialization.Serializable
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.AuditEvent
import runtime.mobileagent.domain.Conversation
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.PromptRevision
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.ToolInvocation

/** A portable provider description. Secret values and secret references are deliberately absent. */
@Serializable
data class ProviderTransfer(
    val id: String,
    val name: String,
    val apiFormat: String,
    val baseUrl: String,
    val nonSecretHeaders: Map<String, String> = emptyMap(),
    val revision: Int,
)

@Serializable
data class ModelTransfer(
    val profile: ModelProfile,
)

@Serializable
data class AgentTransfer(
    val profile: AgentProfile,
    val promptRevisions: List<PromptRevision> = emptyList(),
    val snapshots: List<AgentSnapshot> = emptyList(),
    val providers: List<ProviderTransfer> = emptyList(),
    val models: List<ModelTransfer> = emptyList(),
)

@Serializable
data class BlobTransfer(
    val hash: String,
    val byteLength: Long,
    val mediaType: String,
    /** A user-safe relative name only; absolute device paths are never exported. */
    val relativePath: String = "",
    /** Rights metadata is optional because the legacy SQLite schema has no license column. */
    val licenseId: String? = null,
)

@Serializable
data class DocumentTransfer(
    val id: String,
    val knowledgeBaseId: String,
    val blobHash: String,
    val displayName: String,
    val format: String,
    val activeVersionId: String? = null,
    val contentHash: String? = null,
    val relativePath: String = "",
)

@Serializable
data class DocumentVersionTransfer(
    val id: String,
    val documentId: String,
    val parserFingerprint: String,
    val contentHash: String,
    val status: String,
    val createdAt: String,
)

@Serializable
data class ChunkTransfer(
    val id: String,
    val documentVersionId: String,
    val ordinal: Int,
    val text: String,
    val contentHash: String,
    val sourceSpan: String? = null,
    val assetIds: List<String> = emptyList(),
    val page: Int? = null,
)

@Serializable
data class AssetTransfer(
    val id: String,
    val documentId: String,
    val documentVersionId: String? = null,
    val blobHash: String,
    val page: Int? = null,
    val section: String? = null,
    val kind: String,
    val surroundingTextHash: String,
)

@Serializable
data class KnowledgeTransfer(
    val id: String,
    val name: String,
    val embeddingSpaceId: String? = null,
    val blobs: List<BlobTransfer> = emptyList(),
    val documents: List<DocumentTransfer> = emptyList(),
    /** True only for an explicit source-content export. */
    val contentIncluded: Boolean = false,
    val documentVersions: List<DocumentVersionTransfer> = emptyList(),
    val chunks: List<ChunkTransfer> = emptyList(),
    val assets: List<AssetTransfer> = emptyList(),
)

@Serializable
data class SkillTransfer(
    val packageHash: String,
    val id: String,
    val name: String,
    val version: String,
    val licenseId: String,
    val classification: String,
    val manifestJson: String? = null,
    val skillMarkdown: String? = null,
    val sourceHash: String? = null,
    /** Base64 is optional so metadata-only export remains small and never reads package bytes. */
    val packageBase64: String? = null,
    /** Full archive exports carry package bytes in a ZIP entry, never in this JSON field. */
    val packageIncluded: Boolean = false,
)

@Serializable
data class ConversationTransfer(
    val conversation: Conversation,
    /**
     * A complete immutable snapshot expansion with provider secret fields removed.  Its
     * snapshot id is retained so historical messages/runs keep their original boundary.
     */
    val snapshot: AgentSnapshot,
    /**
     * Continuing a restored conversation requires a new local snapshot after credentials are
     * configured; the imported history itself remains read-only and auditable.
     */
    val snapshotRebindPolicy: String = "LOCAL_CREDENTIALS_REQUIRED",
    val messages: List<Message> = emptyList(),
    val runs: List<RunRecord> = emptyList(),
    val toolInvocations: List<ToolInvocation> = emptyList(),
    val auditEvents: List<AuditEvent> = emptyList(),
    /** Full archive exports carry conversation rows in a bounded, separate ZIP entry. */
    val contentIncluded: Boolean = false,
    val contentEntry: String? = null,
)

@Serializable
data class TransferBundle(
    val schemaVersion: Int,
    val exportedAt: String,
    val agent: AgentTransfer? = null,
    val knowledgeBases: List<KnowledgeTransfer> = emptyList(),
    val skills: List<SkillTransfer> = emptyList(),
    val conversations: List<ConversationTransfer> = emptyList(),
)

/** Runtime options are deliberately not serialized into the transfer document. */
data class TransferOptions(
    val includeSkillPackageBytes: Boolean = false,
    val includeKnowledgeContent: Boolean = false,
    val includeConversations: Boolean = false,
)

enum class TransferConflictPolicy {
    REJECT,
    KEEP_EXISTING,
}

data class TransferValidationResult(
    val bundle: TransferBundle,
    val warnings: List<String> = emptyList(),
)
