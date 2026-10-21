// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain.trpg

import kotlinx.serialization.Serializable

/**
 * Lightweight identity of one durable conversation checkpoint.
 *
 * A checkpoint is a read-only historical boundary, never an instruction to replay a turn. The
 * payload that backs it is data (state, message-prefix fingerprints, world-book configuration,
 * summary chain and journal), so this metadata type deliberately carries no executable field.
 */
@Serializable
data class TrpgCheckpoint(
    val conversationId: String,
    val checkpointId: String,
    val name: String,
    val createdAt: String,
    /** TRPG revision after the CHECKPOINT journal event that was committed with this checkpoint. */
    val revision: Long,
)

/**
 * Portable checkpoint wrapper for the streamed conversation transfer entry.
 *
 * The payload text is copied verbatim across devices so a restored checkpoint can still branch.
 * It is untrusted historical data: the repository re-verifies its identity, message-prefix
 * digests, image references and summary chain before accepting it.
 */
@Serializable
data class TrpgCheckpointTransfer(
    val conversationId: String,
    val checkpointId: String,
    val name: String,
    val createdAt: String,
    val revision: Long,
    val payloadJson: String,
)

/**
 * Reserved branch provenance: which checkpoint of which conversation a branch was cut from.
 *
 * No branch keeps a live execution binding to its source. A later integration may persist this
 * value for audit; this change only reserves the domain shape so callers can already depend on it.
 */
@Serializable
data class TrpgBranchOrigin(
    val sourceConversationId: String,
    val checkpointId: String,
    val sourceRevision: Long,
)
