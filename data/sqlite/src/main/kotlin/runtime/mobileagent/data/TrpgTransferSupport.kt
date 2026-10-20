// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.AgentMode
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.trpg.TrpgSession
import runtime.mobileagent.serialization.AgentModeValidation
import runtime.mobileagent.serialization.ConversationTransfer

/** TRPG transfer checks remain inside the caller's existing export/import transactions. */
internal class TrpgTransferSupport(
    private val db: SqlConnection,
    private val clock: () -> String,
    private val invalid: (String) -> AppException,
    private val conflict: (String) -> AppException,
) {
    fun export(snapshot: AgentSnapshot, conversationId: String): TrpgSession? =
        if (snapshot.mode == AgentMode.TRPG) TrpgRepository(db, clock).get(conversationId) else null

    fun requireUnchanged(snapshot: AgentSnapshot, transfer: ConversationTransfer) {
        if (snapshot.mode == AgentMode.TRPG && TrpgRepository(db, clock).get(transfer.conversation.id) != transfer.trpgSession) {
            throw invalid("Conversation TRPG state changed during export; retry the backup")
        }
    }

    fun requireAgentMode(existing: SqlRow?, mode: AgentMode) {
        if (existing != null && existing.string("mode") != mode.name) throw conflict("Agent mode is immutable during restore")
    }

    fun matchesEnvelope(full: ConversationTransfer, manifest: ConversationTransfer): Boolean =
        full.snapshotRebindPolicy == manifest.snapshotRebindPolicy && full.trpgSession == manifest.trpgSession

    fun requireSnapshotMode(snapshot: AgentSnapshot, importedMode: AgentMode? = null) {
        try { AgentModeValidation.validate(snapshot) } catch (error: IllegalArgumentException) {
            throw invalid(error.message ?: "Invalid snapshot Agent mode")
        }
        if (importedMode != null && snapshot.mode != importedMode) throw invalid("Snapshot mode differs from its Agent")
        val localAgent = db.query("SELECT mode FROM agent_profiles WHERE id=?", listOf(snapshot.agentId)).singleOrNull()
        if (localAgent != null && localAgent.string("mode") != snapshot.mode.name) throw conflict("Snapshot mode differs from existing Agent mode")
        val localSnapshot = db.query("SELECT mode FROM agent_snapshots WHERE id=?", listOf(snapshot.id)).singleOrNull()
        if (localSnapshot != null && localSnapshot.string("mode") != snapshot.mode.name) throw conflict("Snapshot mode is immutable during restore")
    }
}
