// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain.worldbook

import kotlinx.serialization.Serializable

@Serializable
data class WorldBookSummary(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val revision: Int,
    val entryCount: Int,
    val createdAt: String,
    val updatedAt: String,
)

data class StoredWorldBook(
    val summary: WorldBookSummary,
    val document: WorldBookDocument,
    val contentHash: String,
    val revocationEpoch: Long,
)

@Serializable
data class WorldBookRunPin(val bookId: String, val revision: Int, val revocationEpoch: Long)

@Serializable
data class WorldBookConversationConfig(
    val conversationId: String,
    val bookIds: List<String> = emptyList(),
    val revision: Int = 0,
    /** Null inherits the first enabled bound book's global scan/budget settings for each new run. */
    val settings: WorldBookSettings? = null,
    val context: WorldBookContext = WorldBookContext(),
)

data class WorldBookAvailability(val bookId: String, val name: String, val reason: String)

data class PreparedWorldBooks(
    val conversationId: String,
    val agentId: String,
    val snapshotId: String,
    val bindingRevision: Int,
    val sources: List<WorldBookSource>,
    val pins: List<WorldBookRunPin>,
    val settings: WorldBookSettings,
    val context: WorldBookContext,
    val unavailable: List<WorldBookAvailability> = emptyList(),
)

data class WorldBookRunReceipt(
    val runId: String,
    val conversationId: String,
    val evaluationJson: String,
    val budgetUnits: Int,
    val createdAt: String,
)

/** The only new Agent prompt macro is a bounded literal outlet name. No expression evaluation. */
object WorldBookTemplateRules {
    fun isOutletVariable(variable: String): Boolean = variable.startsWith("outlet::") &&
        variable.removePrefix("outlet::").let { name ->
            name.isNotBlank() && name.length <= 128 && name.none { it == '{' || it == '}' || it.isISOControl() }
        }
}
