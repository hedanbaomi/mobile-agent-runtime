// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import runtime.mobileagent.domain.AgentMode
import runtime.mobileagent.domain.AgentSnapshot

object AgentModeValidation {
    /** Missing mode is legacy NORMAL; any explicit value must agree with the row. */
    fun validate(snapshot: AgentSnapshot) {
        val root = Json.parseToJsonElement(snapshot.bindingManifestJson) as? JsonObject
            ?: throw IllegalArgumentException("Snapshot binding manifest must be an object")
        val element = root["agentMode"]
        val manifestMode = if (element == null) AgentMode.NORMAL else {
            require(element is JsonPrimitive && element.isString) { "Invalid snapshot Agent mode" }
            AgentMode.valueOf(element.content)
        }
        require(manifestMode == snapshot.mode) { "Snapshot Agent mode differs from its immutable manifest" }
    }
}
