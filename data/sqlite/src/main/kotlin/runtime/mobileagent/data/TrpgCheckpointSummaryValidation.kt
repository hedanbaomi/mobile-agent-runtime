// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.ContextCompactionLimits
import runtime.mobileagent.domain.ContextCompactionRecord

/** Imported history must satisfy the same provenance and parent-chain constraints as live rows. */
internal object TrpgCheckpointSummaryValidation {
    fun requireChain(records: List<ContextCompactionRecord>) {
        var previous: String? = null
        records.forEach { record ->
            listOf(record.id, record.conversationId, record.snapshotId, record.runId).forEach { value ->
                TrpgCheckpointGuard.requireSafeId(value, "summary identity")
            }
            if (record.parentId != previous || record.parentId == record.id) {
                TrpgCheckpointGuard.fail("TRPG checkpoint summaries must form one ordered parent chain")
            }
            listOf(record.inputHash, record.modelId, record.modelFingerprint, record.authorizationFingerprint,
                record.reason, record.createdAt, record.updatedAt).forEach { value ->
                if (value.isBlank() || value.length > ContextCompactionLimits.MAX_FIELD_CHARS) {
                    TrpgCheckpointGuard.fail("TRPG checkpoint summary provenance is empty or too long")
                }
            }
            if (!TrpgCheckpointGuard.HEX64.matches(record.sourceContentHash) || record.version < 1 ||
                record.beforeUnits < 0 || record.afterUnits < 0 || record.inputTokens < 0 || record.outputTokens < 0) {
                TrpgCheckpointGuard.fail("TRPG checkpoint summary digest or counters are invalid")
            }
            previous = record.id
        }
    }
}
