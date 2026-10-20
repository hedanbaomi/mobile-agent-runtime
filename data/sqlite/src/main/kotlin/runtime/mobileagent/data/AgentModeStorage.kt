// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import runtime.mobileagent.domain.AgentMode
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.serialization.AgentModeValidation

/** Creation-time mode checks shared by profile writes and immutable expansion. */
internal object AgentModeStorage {
    fun requireUnchanged(current: AgentProfile?, profile: AgentProfile, invalid: (String) -> AppException) {
        if (current != null && current.mode != profile.mode) throw invalid("Agent mode is immutable")
    }

    fun addToManifest(builder: JsonObjectBuilder, profile: AgentProfile) {
        if (profile.mode != AgentMode.NORMAL) builder.put("agentMode", profile.mode.name)
    }

    fun requireManifest(snapshot: AgentSnapshot, invalid: (String) -> AppException) {
        try { AgentModeValidation.validate(snapshot) } catch (error: IllegalArgumentException) {
            throw invalid(error.message ?: "Invalid snapshot Agent mode")
        }
    }
}
