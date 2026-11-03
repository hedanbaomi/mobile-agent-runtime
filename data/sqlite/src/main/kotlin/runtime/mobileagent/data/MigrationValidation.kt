// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.ContextCompactionState
import runtime.mobileagent.domain.ContextSummary

/**
 * Context-compaction durability validation, extracted from [Migrations] for the conditional-token
 * ratchet. Behaviour is unchanged: a malformed row aborts the migration instead of being adopted.
 */
internal object MigrationValidation {
    fun contextCompactions(connection: SqlConnection, invalid: (String) -> Nothing) {
        val json = Json { ignoreUnknownKeys = false; explicitNulls = false }
        connection.query("SELECT * FROM context_compactions").forEach { row ->
            val id = row.string("id")
            val state = runCatching { ContextCompactionState.valueOf(row.string("state")) }.getOrElse {
                invalid("context_compactions[$id] has an invalid state")
            }
            val summary = row.columns["summary_json"] as? String
            if (state == ContextCompactionState.SUCCEEDED) {
                val text = summary ?: invalid("context_compactions[$id] is SUCCEEDED without a summary")
                if (runCatching { ContextSummary.canonicalize(text) }.isFailure) {
                    invalid("context_compactions[$id] has an invalid summary")
                }
            } else if (summary != null) {
                invalid("context_compactions[$id] carries a summary while ${state.name}")
            }
            val ids = runCatching { json.decodeFromString<List<String>>(row.string("source_message_ids_json")) }
                .getOrElse { invalid("context_compactions[$id] has an invalid source id list") }
            if (ids.isEmpty() || ids.distinct().size != ids.size) {
                invalid("context_compactions[$id] source ids must be unique and non-empty")
            }
            if (row.string("source_content_hash").isBlank()) {
                invalid("context_compactions[$id] has no source content digest")
            }
            listOf("before_units", "after_units", "input_tokens", "output_tokens").forEach { field ->
                if (row.long(field) < 0) invalid("context_compactions[$id].$field is negative")
            }
            listOf(
                "conversation_id", "snapshot_id", "run_id", "input_hash", "model_id",
                "model_fingerprint", "authorization_fingerprint", "reason", "created_at", "updated_at",
            ).forEach { field ->
                if (row.string(field).isBlank()) invalid("context_compactions[$id].$field is blank")
            }
        }
    }
}
