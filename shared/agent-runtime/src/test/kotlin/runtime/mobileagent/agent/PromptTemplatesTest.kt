// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PromptTemplatesTest {
    @Test
    fun allowedVariablesRender() {
        val text = PromptTemplates.render("Hello {{agent_name}}", mapOf("agent_name" to "Ada"))
        assertEquals("Hello Ada", text)
    }

    @Test
    fun expressionVariablesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PromptTemplates.render("{{lookup}}", emptyMap())
        }
    }
}
