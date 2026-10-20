// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import runtime.mobileagent.domain.AgentMode
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.feature.agents.AgentEditorUi

internal fun resolveAgentEditorMode(agent: AgentProfile?): AgentMode = agent?.mode ?: AgentMode.NORMAL

internal fun resolvePersistedAgentMode(previous: AgentProfile?, editor: AgentEditorUi): AgentMode {
    if (previous != null) {
        require(editor.mode == previous.mode) { "Agent mode cannot be changed after creation" }
        return previous.mode
    }
    return editor.mode
}
