// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization

import runtime.mobileagent.domain.AgentMode
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.trpg.TrpgStateValidation

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
