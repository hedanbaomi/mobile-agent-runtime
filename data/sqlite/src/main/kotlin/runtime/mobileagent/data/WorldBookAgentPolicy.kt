// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import runtime.mobileagent.domain.AgentSnapshot

internal object WorldBookAgentPolicy {
    fun permitsVariable(variable: String, allowed: Set<String>): Boolean = variable in allowed ||
        runtime.mobileagent.domain.worldbook.WorldBookTemplateRules.isOutletVariable(variable)
    fun validate(ids: List<String>, invalid: (String) -> Nothing) {
        if (ids.size > WorldBookRepository.MAX_BINDINGS || ids.distinct().size != ids.size) invalid("Too many or duplicate world book bindings")
    }

    fun requireBindings(db: SqlConnection, ids: List<String>, invalid: (String) -> Nothing) {
        ids.forEach { id ->
            if (db.query("SELECT id FROM world_books WHERE id=?", listOf(id)).isEmpty()) invalid("World book is missing or removed")
        }
    }

    fun requireManifest(snapshot: AgentSnapshot, root: JsonObject, invalid: (String) -> Nothing) {
        val ids = root["worldBookIds"]?.let { Json.decodeFromJsonElement<List<String>>(it) }.orEmpty()
        if (ids != snapshot.worldBookIds) invalid("Snapshot world book binding does not match its immutable manifest")
    }
}
