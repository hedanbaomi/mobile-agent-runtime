// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.domain.trpg.TrpgState
import runtime.mobileagent.domain.trpg.TrpgStateValidation

/**
 * Shared bounds, identity checks and failure shape for checkpoint persistence.
 *
 * Everything here is internal to the data module: checkpoints expose only the repository's public
 * read/capture/branch API, never these helpers.
 */
internal object TrpgCheckpointGuard {
    const val PAYLOAD_VERSION = 1
    const val MAX_CHECKPOINTS = 32
    const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024
    const val MAX_TOTAL_PAYLOAD_BYTES = 8 * 1024 * 1024
    const val MAX_NAME_CHARS = 128
    const val MAX_TITLE_CHARS = 256
    const val MAX_CHECKPOINT_ID_CHARS = 200
    const val STREAMING_STATUS = "STREAMING"
    const val COMPLETE_STATUS = "COMPLETE"
    const val HISTORICAL_BRANCH_STOP_REASON = "historical-branch"
    val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
    val HEX64 = Regex("[0-9a-f]{64}")
    val REVISION_PREFIX = Regex("""^\{"version":\d+,"revision":(\d+),""")

    fun fail(message: String): Nothing = throw AppError(
        code = ErrorCode.INVALID_CONFIG,
        userMessage = message,
        retryClass = RetryClass.USER_ACTION,
        stage = "trpg-checkpoint",
        operationId = "trpg-checkpoint",
        sanitizedDetails = message,
    ).asException()

    fun requireCheckpointName(name: String) {
        if (name.isBlank() || name.length > MAX_NAME_CHARS) fail("TRPG checkpoint name is empty or too long")
    }

    fun requireCheckpointId(checkpointId: String) {
        requireSafeId(checkpointId, "checkpointId")
        // The journal action id is "checkpoint:<id>" and must stay within its 256 char bound.
        if (checkpointId.length > MAX_CHECKPOINT_ID_CHARS) fail("TRPG checkpoint id is too long")
    }

    fun requireSafeId(value: String, field: String) {
        if (value.length > 256 || !SAFE_ID.matches(value)) fail("$field contains unsafe characters")
    }

    fun validateState(state: TrpgState) {
        try {
            TrpgStateValidation.validate(state)
        } catch (error: IllegalArgumentException) {
            fail(error.message ?: "Invalid TRPG checkpoint state")
        }
    }

    fun conversationRow(db: SqlConnection, id: String): SqlRow? =
        db.query("SELECT * FROM conversations WHERE id=?", listOf(id)).singleOrNull()

    fun requireTrpgSnapshot(db: SqlConnection, snapshotId: String, agentSnapshotId: String) {
        if (agentSnapshotId.ifBlank { snapshotId } != snapshotId) fail("TRPG conversation snapshot binding is inconsistent")
        val snapshot = db.query("SELECT mode FROM agent_snapshots WHERE id=?", listOf(snapshotId)).singleOrNull()
            ?: fail("TRPG conversation snapshot is missing")
        if (snapshot.string("mode") != "TRPG") fail("Conversation is not in TRPG mode")
    }

    /** Payload text is bounded per checkpoint and in total so a full conversation entry stays archivable. */
    fun requirePayloadBudget(db: SqlConnection, conversationId: String, additionalBytes: Long) {
        val used = db.query(
            "SELECT COALESCE(SUM(length(CAST(payload_json AS BLOB))),0) AS bytes FROM trpg_checkpoints WHERE conversation_id=?",
            listOf(conversationId),
        ).single().long("bytes")
        if (used + additionalBytes > MAX_TOTAL_PAYLOAD_BYTES) {
            fail("TRPG checkpoint payloads exceed the $MAX_TOTAL_PAYLOAD_BYTES byte budget for this conversation")
        }
    }
}
