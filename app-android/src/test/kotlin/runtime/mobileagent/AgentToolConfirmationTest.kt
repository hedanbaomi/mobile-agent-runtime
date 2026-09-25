// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentToolConfirmationTest {
    @Test fun missingOrMalformedSettingsRequireConfirmation() {
        assertFalse(AgentToolConfirmation.skip(null))
        assertFalse(AgentToolConfirmation.skip("not-json"))
        assertFalse(AgentToolConfirmation.skip("{\"skipToolConfirmations\":\"true\"}"))
    }

    @Test fun explicitAgentSettingCanBeEnabledAndRevoked() {
        val enabled = AgentToolConfirmation.update("{\"other\":7}", true)
        assertTrue(AgentToolConfirmation.skip(enabled))
        val disabled = AgentToolConfirmation.update(enabled, false)
        assertFalse(AgentToolConfirmation.skip(disabled))
        assertTrue(disabled.contains("\"other\":7"))
    }

    @Test fun oldConversationCannotGainSkipAndLiveOffRevokesIt() {
        val enabled = "{\"skipToolConfirmations\":true}"
        val disabled = "{\"skipToolConfirmations\":false}"
        assertFalse(AgentToolConfirmation.allows(disabled, enabled))
        assertFalse(AgentToolConfirmation.allows(enabled, disabled))
        assertTrue(AgentToolConfirmation.allows(enabled, enabled))
    }
}
