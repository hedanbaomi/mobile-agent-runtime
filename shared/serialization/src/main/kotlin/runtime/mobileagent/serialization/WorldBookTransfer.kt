// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization

import kotlinx.serialization.Serializable
import runtime.mobileagent.domain.worldbook.WorldBookConversationConfig
import runtime.mobileagent.serialization.worldbook.WorldBookCodec

/** Included only when the user explicitly includes world book content in a backup. */
@Serializable
data class WorldBookTransfer(val id: String, val documentJson: String, val enabled: Boolean = true)

/** Conversation content backup preserves settings; activation state is recomputed on restore. */
@Serializable
data class WorldBookConversationTransfer(val config: WorldBookConversationConfig)

internal object WorldBookTransferValidation {
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
    fun hasPayload(bundle: TransferBundle): Boolean = bundle.agent != null || bundle.knowledgeBases.isNotEmpty() ||
        bundle.skills.isNotEmpty() || bundle.conversations.isNotEmpty() || bundle.worldBooks.isNotEmpty()

    fun bundle(books: List<WorldBookTransfer>, operationId: String) = guard(operationId) { validate(books) }
    fun binding(binding: WorldBookConversationTransfer?, conversationId: String, operationId: String) =
        guard(operationId) { validate(binding, conversationId) }
    fun ids(ids: List<String>, operationId: String) = guard(operationId) {
        require(ids.size <= 64 && ids.distinct().size == ids.size && ids.all(idPattern::matches)) { "Invalid world book bindings" }
    }
    private fun guard(operationId: String, action: () -> Unit) {
        try { action() } catch (_: IllegalArgumentException) {
            throw runtime.mobileagent.domain.AppError(runtime.mobileagent.domain.ErrorCode.TRANSFER_INVALID,
                "Invalid world book backup", runtime.mobileagent.domain.RetryClass.USER_ACTION, "transfer", operationId).asException()
        }
    }
    fun validate(books: List<WorldBookTransfer>) {
        require(books.size <= 512 && books.map { it.id }.distinct().size == books.size) { "Invalid world book transfer count or duplicate identity" }
        books.forEach {
            require(idPattern.matches(it.id)) { "Invalid world book transfer identity" }
            WorldBookCodec.decode(it.documentJson)
        }
    }

    fun validate(binding: WorldBookConversationTransfer?, conversationId: String) {
        if (binding == null) return
        require(binding.config.conversationId == conversationId) { "World book binding belongs to another conversation" }
        val ids = binding.config.bookIds
        require(ids.size <= 64 && ids.distinct().size == ids.size && ids.all(idPattern::matches)) { "Invalid conversation world book bindings" }
    }
}
