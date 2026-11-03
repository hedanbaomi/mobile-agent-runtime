// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.diagnostics.DiagnosticAuthority

class AgentToolConfirmationTest {
    @Test fun backgroundShellPreservesCommandDisclosureAndCannotSkipApproval() {
        val arguments = Json.parseToJsonElement("""{"tool":"shell_exec","arguments":{"command":"rm -rf /sdcard/example","cwd":"/sdcard"}}""").jsonObject
        assertEquals(arguments["arguments"], AgentToolConfirmation.shellArguments("job_start", arguments))
        assertTrue(AgentToolConfirmation.requiresExplicitApproval("job_start"))
        assertFalse(AgentToolConfirmation.requiresExplicitApproval("device_observe"))
        assertFalse(AgentToolConfirmation.requiresExplicitApproval("device_wait_for"))
        assertNull(AgentToolConfirmation.shellArguments("read_batch", arguments))
        assertNull(AgentToolConfirmation.shellArguments("job_start", Json.parseToJsonElement("""{"tool":"calculator","arguments":{}}""").jsonObject))
    }

    @Test fun rootOperationsAlwaysRequireExplicitApprovalWithoutForcingUnrelatedTools() {
        // Actual Root operations always require interactive approval regardless of skipToolConfirmations.
        assertTrue(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "file_write_text",
                selectedAuthority = "ROOT_DIRECT",
                workspaceAuthority = DiagnosticAuthority.ROOT_DIRECT,
                rootRun = true,
            ),
        )
        assertTrue(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "read_batch",
                selectedAuthority = "SHIZUKU_ROOT",
                workspaceAuthority = DiagnosticAuthority.NONE,
                rootRun = true,
            ),
        )
        assertTrue(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "shell_exec",
                selectedAuthority = "ROOT_DIRECT",
                workspaceAuthority = DiagnosticAuthority.ROOT_DIRECT,
                rootRun = true,
            ),
        )
        assertTrue(AgentToolConfirmation.requiresExplicitApproval("file_get_metadata"))
        assertTrue(AgentToolConfirmation.requiresExplicitApproval("file_set_metadata"))
        assertTrue(AgentToolConfirmation.requiresExplicitApproval("file_apply_patch"))

        // Non-Root conversations/workspaces and unrelated tools are NOT forced into approval merely because Root is selected globally.
        assertFalse(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "file_write_text",
                selectedAuthority = "ROOT_DIRECT",
                workspaceAuthority = DiagnosticAuthority.NONE,
                rootRun = false,
            ),
        )
        assertFalse(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "file_read_text",
                selectedAuthority = "SHIZUKU_ROOT",
                workspaceAuthority = DiagnosticAuthority.SHIZUKU,
                rootRun = false,
            ),
        )
        assertFalse(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "mcp__demo__ping",
                selectedAuthority = "ROOT_DIRECT",
                workspaceAuthority = DiagnosticAuthority.NONE,
                rootRun = false,
            ),
        )
        assertFalse(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "mcp__demo__ping",
                selectedAuthority = "ROOT_DIRECT",
                workspaceAuthority = DiagnosticAuthority.NONE,
                rootRun = true,
            ),
        )
    }

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
