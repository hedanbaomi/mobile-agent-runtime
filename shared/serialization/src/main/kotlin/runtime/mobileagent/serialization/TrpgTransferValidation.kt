// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization

import runtime.mobileagent.domain.AgentMode
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.trpg.TrpgStateValidation
import runtime.mobileagent.domain.trpg.TrpgJournalValidation

/** Additive mode and state validation, independent of the transfer transport. */
internal object TrpgTransferValidation {
    fun bundle(bundle: TransferBundle, invalid: (String) -> Nothing) {
        bundle.agent?.let { agent ->
            if (bundle.conversations.any { it.snapshot.agentId == agent.profile.id && it.snapshot.mode != agent.profile.mode }) {
                invalid("Conversation snapshot mode differs from its Agent")
            }
        }
    }

    fun agentSnapshot(snapshot: AgentSnapshot, mode: AgentMode, invalid: (String) -> Nothing) {
        this.snapshot(snapshot, invalid)
        if (snapshot.mode != mode) invalid("Agent snapshot mode differs from its Agent")
    }

    fun conversation(transfer: ConversationTransfer, invalid: (String) -> Nothing) {
        if (transfer.trpgCheckpointIds.size > 32 || transfer.trpgCheckpointIds.distinct().size != transfer.trpgCheckpointIds.size ||
            transfer.trpgCheckpointIds.any { it.isBlank() || it.length > 200 }) invalid("Invalid checkpoint identities")
        if (transfer.trpgCheckpointIds.isNotEmpty() && (transfer.snapshot.mode != AgentMode.TRPG || transfer.trpgSession == null))
            invalid("Checkpoints require a TRPG session")
        if (transfer.trpgCheckpoints.size > 32 || transfer.trpgCheckpoints.map { it.checkpointId }.distinct().size != transfer.trpgCheckpoints.size)
            invalid("Invalid checkpoint count or duplicate identity")
        if (transfer.trpgCheckpoints.isNotEmpty() && transfer.trpgCheckpoints.map { it.checkpointId } != transfer.trpgCheckpointIds)
            invalid("Checkpoint payloads do not match the listed identities")
        var checkpointBytes = 0L
        transfer.trpgCheckpoints.forEach { checkpoint ->
            if (checkpoint.conversationId != transfer.conversation.id || checkpoint.name.isBlank() || checkpoint.name.length > 128 ||
                checkpoint.createdAt.isBlank() || checkpoint.createdAt.length > 64 ||
                checkpoint.revision < 0 || checkpoint.revision > (transfer.trpgSession?.revision ?: -1))
                invalid("Checkpoint metadata does not match the conversation")
            val bytes = checkpoint.payloadJson.toByteArray(Charsets.UTF_8).size
            checkpointBytes += bytes
            if (bytes > 2 * 1024 * 1024 || checkpointBytes > 8 * 1024 * 1024) invalid("Checkpoint payload exceeds its transfer limit")
        }

        transfer.trpgJournalRevision?.let { revision ->
            if (revision < 0 || transfer.trpgSession?.revision != revision) invalid("TRPG journal revision differs from its state")
        }
        transfer.trpgJournal?.let { journal ->
            val session = transfer.trpgSession ?: invalid("TRPG journal is missing its state")
            if (transfer.trpgJournalRevision != session.revision) invalid("TRPG journal is missing its revision marker")
            try { TrpgJournalValidation.validate(journal, session.revision) } catch (error: IllegalArgumentException) {
                invalid(error.message ?: "Invalid TRPG journal")
            }
        }
        transfer.trpgSession?.let { session ->
            if (transfer.snapshot.mode != AgentMode.TRPG || session.conversationId != transfer.conversation.id) {
                invalid("TRPG state does not match its conversation mode or identity")
            }
            try { TrpgStateValidation.validate(session) } catch (error: IllegalArgumentException) {
                invalid(error.message ?: "Invalid TRPG state")
            }
        }
    }

    fun snapshot(snapshot: AgentSnapshot, invalid: (String) -> Nothing) {
        try { AgentModeValidation.validate(snapshot) } catch (error: IllegalArgumentException) {
            invalid(error.message ?: "Invalid snapshot Agent mode")
        }
    }
}
