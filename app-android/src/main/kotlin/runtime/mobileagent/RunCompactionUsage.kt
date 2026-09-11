// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import runtime.mobileagent.domain.ContextCompactionRecord
import runtime.mobileagent.domain.RunRecord

/** One collector's credited snapshots; durable attempts also settle events lost to cancellation. */
internal class RunCompactionUsage {
    private val credited = mutableMapOf<String, Pair<Int, Int>>()

    fun reconcile(run: RunRecord, attempts: Iterable<ContextCompactionRecord>): RunRecord {
        var input = run.inputTokens
        var output = run.outputTokens
        for (attempt in attempts) {
            if (attempt.runId != run.runId || attempt.conversationId != run.conversationId ||
                attempt.snapshotId != run.snapshotId) continue
            val previous = credited[attempt.id] ?: (0 to 0)
            input += attempt.inputTokens - previous.first
            output += attempt.outputTokens - previous.second
            credited[attempt.id] = attempt.inputTokens to attempt.outputTokens
        }
        return run.copy(inputTokens = input, outputTokens = output)
    }
}
