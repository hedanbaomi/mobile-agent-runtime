// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
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

    @Test fun rootMutationsAndRootReadsBothRequireApprovalUntilTheirOwnOptIn() {
        assertTrue(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "file_write_text",
                selectedAuthority = "ROOT_DIRECT",
                workspaceAuthority = DiagnosticAuthority.ROOT_DIRECT,
                rootRun = true,
            ),
        )
        // A Root read is a Root capability: the global switch never authorizes it, so it requires
        // the dedicated `skipRootReadConfirmations` opt-in even though it is read-only.
        assertTrue(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "read_batch",
                selectedAuthority = "SHIZUKU_ROOT",
                workspaceAuthority = DiagnosticAuthority.NONE,
                rootRun = true,
            ),
        )
        assertFalse(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "read_batch",
                selectedAuthority = "SHIZUKU_ROOT",
                workspaceAuthority = DiagnosticAuthority.NONE,
                rootRun = true,
                rootReadConfirmed = true,
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

    @Test fun rootReadOptInIsSeparateFromTheGlobalConfirmationSwitch() {
        val global = """{"skipToolConfirmations":true}"""
        val rootRead = """{"skipRootReadConfirmations":true}"""
        val both = """{"skipToolConfirmations":true,"skipRootReadConfirmations":true}"""

        // The global switch alone must never authorize a Root read.
        assertTrue(AgentToolConfirmation.allows(global, global))
        assertFalse(AgentToolConfirmation.skipRootReadConfirmations(global))
        assertFalse(AgentToolConfirmation.allowsRootRead(global, global))

        // The Root opt-in alone must not relax ordinary tool confirmations.
        assertFalse(AgentToolConfirmation.allows(rootRead, rootRead))
        assertTrue(AgentToolConfirmation.skipRootReadConfirmations(rootRead))
        assertTrue(AgentToolConfirmation.allowsRootRead(rootRead, rootRead))
        assertTrue(AgentToolConfirmation.allowsRootRead(both, both))

        // Snapshot + live must both opt in, and a live off switch revokes it.
        assertFalse(AgentToolConfirmation.allowsRootRead(null, rootRead))
        assertFalse(AgentToolConfirmation.allowsRootRead(rootRead, null))
        assertFalse(AgentToolConfirmation.allowsRootRead(rootRead, """{"skipRootReadConfirmations":false}"""))

        // Malformed or non-boolean values fail closed.
        assertFalse(AgentToolConfirmation.skipRootReadConfirmations("not-json"))
        assertFalse(AgentToolConfirmation.skipRootReadConfirmations("""{"skipRootReadConfirmations":"true"}"""))

        // The Root read opt-in is written independently and never disturbs the global switch.
        val enabled = AgentToolConfirmation.updateRootReadConfirmations(global, true)
        assertTrue(AgentToolConfirmation.skipRootReadConfirmations(enabled))
        assertTrue(AgentToolConfirmation.skip(enabled))
        val revoked = AgentToolConfirmation.updateRootReadConfirmations(enabled, false)
        assertFalse(AgentToolConfirmation.skipRootReadConfirmations(revoked))
        assertTrue(AgentToolConfirmation.skip(revoked))
    }

    @Test fun mixedRootRunKeepsOrdinaryWorkspaceSkipAndRootWritesExplicit() {
        val ordinaryArgs = """{"workspace_id":"saf-workspace","relative_path":"notes.txt","text":"hello"}"""
        val ordinaryAuthority = AgentToolConfirmation.invocationWorkspaceAuthority(
            "file_write_text", ordinaryArgs, "root-thread-workspace",
        ) { id -> if (id == "root-thread-workspace") DiagnosticAuthority.ROOT_DIRECT else DiagnosticAuthority.NONE }
        assertEquals(DiagnosticAuthority.NONE, ordinaryAuthority)
        assertFalse(AgentToolConfirmation.isRunScopedRootTool("file_write_text", ordinaryArgs))
        // The Run still carries a Root stamp, but this invocation routes to an ordinary workspace.
        assertFalse(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "file_write_text",
                selectedAuthority = "ROOT_DIRECT",
                workspaceAuthority = ordinaryAuthority,
                rootRun = false,
            ),
        )
        assertTrue(AgentToolConfirmation.allows("""{"skipToolConfirmations":true}""", """{"skipToolConfirmations":true}"""))

        val rootArgs = """{"workspace_id":"root-workspace","relative_path":"notes.txt","text":"hello"}"""
        val rootAuthority = AgentToolConfirmation.invocationWorkspaceAuthority(
            "file_write_text", rootArgs, "saf-thread-workspace",
        ) { id -> if (id == "root-workspace") DiagnosticAuthority.ROOT_DIRECT else DiagnosticAuthority.NONE }
        assertEquals(DiagnosticAuthority.ROOT_DIRECT, rootAuthority)
        assertTrue(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "file_write_text",
                selectedAuthority = "ROOT_DIRECT",
                workspaceAuthority = rootAuthority,
                rootRun = false,
            ),
        )
        // The dedicated Root read opt-in cannot turn a Root write into an auto-approved call.
        assertTrue(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "file_write_text",
                selectedAuthority = "ROOT_DIRECT",
                workspaceAuthority = rootAuthority,
                rootRun = false,
                rootReadConfirmed = true,
            ),
        )
    }

    @Test fun batchAndWorkspaceListFollowTheActualRouteEvidence() {
        val ordinaryBatch = """
            {"calls":[
              {"tool":"file_write_text","arguments":{"workspace_id":"saf-workspace","relative_path":"a.txt","text":"a"}},
              {"tool":"file_read_text","arguments":{"workspace_id":"saf-workspace","relative_path":"a.txt"}}
            ]}
        """.trimIndent()
        assertEquals(listOf("saf-workspace", "saf-workspace"), AgentToolConfirmation.invocationWorkspaceIds("read_batch", ordinaryBatch))
        assertFalse(AgentToolConfirmation.isRunScopedRootTool("read_batch", ordinaryBatch))
        assertFalse(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "read_batch",
                selectedAuthority = "SHIZUKU_ROOT",
                workspaceAuthority = DiagnosticAuthority.NONE,
                rootRun = false,
            ),
        )

        val rootBatch = """
            {"calls":[
              {"tool":"file_read_text","arguments":{"workspace_id":"root-workspace","relative_path":"a.txt"}}
            ]}
        """.trimIndent()
        val rootAuthority = AgentToolConfirmation.invocationWorkspaceAuthority(
            "read_batch", rootBatch, null,
        ) { id -> if (id == "root-workspace") DiagnosticAuthority.SHIZUKU_ROOT else DiagnosticAuthority.NONE }
        assertEquals(DiagnosticAuthority.SHIZUKU_ROOT, rootAuthority)
        assertFalse(AgentToolConfirmation.isRunScopedRootTool("read_batch", rootBatch))
        assertTrue(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "read_batch",
                selectedAuthority = "SHIZUKU_ROOT",
                workspaceAuthority = rootAuthority,
                rootRun = false,
            ),
        )
        assertFalse(
            AgentToolConfirmation.autoApproves(
                toolName = "read_batch",
                selectedAuthority = "SHIZUKU_ROOT",
                workspaceAuthority = rootAuthority,
                rootRun = false,
                rootReadConfirmed = false,
                ordinarySkip = true,
            ),
        )
        assertTrue(
            AgentToolConfirmation.autoApproves(
                toolName = "read_batch",
                selectedAuthority = "SHIZUKU_ROOT",
                workspaceAuthority = rootAuthority,
                rootRun = false,
                rootReadConfirmed = true,
                ordinarySkip = false,
            ),
        )
        assertFalse(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "read_batch",
                selectedAuthority = "SHIZUKU_ROOT",
                workspaceAuthority = rootAuthority,
                rootRun = false,
                rootReadConfirmed = true,
            ),
        )

        // workspace_list has no single workspace argument: a Root-stamped Run is the only
        // evidence that this aggregate invocation may probe the Root side.
        assertTrue(AgentToolConfirmation.isRunScopedRootTool("workspace_list", "{}"))
        assertTrue(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "workspace_list",
                selectedAuthority = "SHIZUKU_ROOT",
                workspaceAuthority = DiagnosticAuthority.NONE,
                rootRun = true,
            ),
        )
        // A batch made only of non-workspace read tools is still an ordinary tool even in a
        // Root-stamped Run; a workspace child without an id fails closed.
        assertFalse(
            AgentToolConfirmation.isRunScopedRootTool(
                "read_batch",
                """{"calls":[{"tool":"device_observe","arguments":{}}]}""",
            ),
        )
        assertTrue(
            AgentToolConfirmation.isRunScopedRootTool(
                "read_batch",
                """{"calls":[{"tool":"file_read_text","arguments":{"relative_path":"a.txt"}}]}""",
            ),
        )
    }

    @Test fun autoApprovalCombinesTheRouteSpecificSwitchExactlyOnce() {
        // Ordinary workspace inside a Root-stamped Run: the ordinary switch remains effective.
        assertTrue(
            AgentToolConfirmation.autoApproves(
                toolName = "file_write_text",
                selectedAuthority = "ROOT_DIRECT",
                workspaceAuthority = DiagnosticAuthority.NONE,
                rootRun = false,
                rootReadConfirmed = false,
                ordinarySkip = true,
            ),
        )
        // Root read: only the dedicated opt-in works, even when the ordinary switch is on.
        assertFalse(
            AgentToolConfirmation.autoApproves(
                toolName = "file_read_text",
                selectedAuthority = "ROOT_DIRECT",
                workspaceAuthority = DiagnosticAuthority.ROOT_DIRECT,
                rootRun = false,
                rootReadConfirmed = false,
                ordinarySkip = true,
            ),
        )
        assertTrue(
            AgentToolConfirmation.autoApproves(
                toolName = "file_read_text",
                selectedAuthority = "ROOT_DIRECT",
                workspaceAuthority = DiagnosticAuthority.ROOT_DIRECT,
                rootRun = false,
                rootReadConfirmed = true,
                ordinarySkip = false,
            ),
        )
        // Root write: neither switch can auto-approve it.
        assertFalse(
            AgentToolConfirmation.autoApproves(
                toolName = "file_write_text",
                selectedAuthority = "ROOT_DIRECT",
                workspaceAuthority = DiagnosticAuthority.ROOT_DIRECT,
                rootRun = false,
                rootReadConfirmed = true,
                ordinarySkip = true,
            ),
        )
    }

    @Test fun unreadableWorkspaceAuthorityCannotReachEitherAutomaticApprovalSwitch() {
        for ((tool, arguments, fallback) in listOf(
            Triple("file_read_text", """{"workspace_id":"root-workspace"}""", null),
            Triple("file_read_text", "{}", "root-workspace"),
            Triple("read_batch", """{"calls":[{"tool":"file_read_text","arguments":{"workspace_id":"root-workspace"}}]}""", null),
        )) {
            var automaticDecisionReached = false
            assertThrows(IllegalStateException::class.java) {
                val authority = AgentToolConfirmation.invocationWorkspaceAuthority(tool, arguments, fallback) {
                    error("workspace storage unavailable")
                }
                automaticDecisionReached = true
                AgentToolConfirmation.autoApproves(tool, "ROOT_DIRECT", authority, false, true, true)
            }
            assertFalse(automaticDecisionReached)
        }
    }

    @Test fun invocationWrapperKeepsSingleToolRouteDecision() {
        val authorityLookups = mutableListOf<String>()
        assertTrue(
            AgentToolConfirmation.autoApprovesInvocation(
                toolName = "file_write_text",
                argumentsJson = "{}",
                selectedAuthority = "ROOT_DIRECT",
                workspaceAuthority = DiagnosticAuthority.NONE,
                fallbackWorkspaceId = null,
                rootRun = true,
                runScopedRoot = false,
                rootReadConfirmed = false,
                ordinarySkip = true,
                authorityForWorkspace = { id -> authorityLookups += id; error("must not be used") },
            ),
        )
        assertTrue(authorityLookups.isEmpty())
        assertTrue(
            AgentToolConfirmation.autoApprovesInvocation(
                toolName = "file_read_text",
                argumentsJson = "{}",
                selectedAuthority = "ROOT_DIRECT",
                workspaceAuthority = DiagnosticAuthority.ROOT_DIRECT,
                fallbackWorkspaceId = null,
                rootRun = false,
                runScopedRoot = false,
                rootReadConfirmed = true,
                ordinarySkip = false,
                authorityForWorkspace = { id -> authorityLookups += id; error("must not be used") },
            ),
        )
        assertTrue(authorityLookups.isEmpty())
    }

    @Test fun mixedRootAndOrdinaryBatchNeedsBothRouteSwitches() {
        val batch = """
            {"calls":[
              {"call_id":"root-read","tool":"file_read_text","arguments":{"workspace_id":"root-workspace","relative_path":"a.txt"}},
              {"call_id":"ordinary-read","tool":"file_read_text","arguments":{"workspace_id":"ordinary-workspace","relative_path":"b.txt"}}
            ]}
        """.trimIndent()
        // Root-only opt-in must not approve the ordinary child; ordinary-only must not approve Root.
        assertFalse(batchAutoApproval(batch, rootRun = false, rootReadConfirmed = true, ordinarySkip = false))
        assertFalse(batchAutoApproval(batch, rootRun = false, rootReadConfirmed = false, ordinarySkip = true))
        assertFalse(batchAutoApproval(batch, rootRun = false, rootReadConfirmed = false, ordinarySkip = false))
        assertTrue(batchAutoApproval(batch, rootRun = false, rootReadConfirmed = true, ordinarySkip = true))

        assertTrue(
            batchAutoApproval(
                """{"calls":[{"call_id":"root","tool":"file_read_text","arguments":{"workspace_id":"root-workspace"}}]}""",
                rootRun = false,
                rootReadConfirmed = true,
                ordinarySkip = false,
            ),
        )
        assertTrue(
            batchAutoApproval(
                """{"calls":[{"call_id":"ordinary","tool":"file_read_text","arguments":{"workspace_id":"ordinary-workspace"}}]}""",
                rootRun = false,
                rootReadConfirmed = false,
                ordinarySkip = true,
            ),
        )
    }

    @Test fun ordinaryAndWorkspaceListBatchRequiresRootDiscoveryOnlyForTheRootSide() {
        val batch = """
            {"calls":[
              {"call_id":"ordinary-read","tool":"file_read_text","arguments":{"workspace_id":"ordinary-workspace","relative_path":"a.txt"}},
              {"call_id":"discover","tool":"workspace_list","arguments":{}}
            ]}
        """.trimIndent()
        // The ordinary switch must not auto-approve run-scoped Root discovery.
        assertFalse(batchAutoApproval(batch, rootRun = true, rootReadConfirmed = false, ordinarySkip = true))
        // Root discovery alone must not auto-approve the ordinary child.
        assertFalse(batchAutoApproval(batch, rootRun = true, rootReadConfirmed = true, ordinarySkip = false))
        assertTrue(batchAutoApproval(batch, rootRun = true, rootReadConfirmed = true, ordinarySkip = true))
        // Outside a Root-stamped Run, workspace_list and the ordinary child both use ordinarySkip.
        assertTrue(batchAutoApproval(batch, rootRun = false, rootReadConfirmed = false, ordinarySkip = true))
        assertFalse(batchAutoApproval(batch, rootRun = false, rootReadConfirmed = true, ordinarySkip = false))

        val workspaceListOnly =
            """{"calls":[{"call_id":"discover","tool":"workspace_list","arguments":{}}]}"""
        assertTrue(
            batchAutoApproval(
                workspaceListOnly,
                rootRun = false,
                rootReadConfirmed = true,
                ordinarySkip = false,
                authorityForWorkspace = { DiagnosticAuthority.ROOT_DIRECT },
            ),
        )
        assertFalse(
            batchAutoApproval(
                workspaceListOnly,
                rootRun = false,
                rootReadConfirmed = false,
                ordinarySkip = true,
                authorityForWorkspace = { DiagnosticAuthority.ROOT_DIRECT },
            ),
        )
    }

    @Test fun readBatchFailsClosedForMalformedUnknownAndUnreadableChildren() {
        val ordinaryFile = """{"call_id":"c1","tool":"file_read_text","arguments":{"workspace_id":"ordinary-workspace"}}"""
        val workspaceList = """{"call_id":"c2","tool":"workspace_list","arguments":{}}"""
        for (batch in listOf(
            """{"calls":[]}""",
            """{"calls":[{"tool":"file_read_text","arguments":{"workspace_id":"ordinary-workspace"}}]}""",
            """{"calls":[{"call_id":"c1","tool":"file_read_text","arguments":{"workspace_id":"ordinary-workspace"},"extra":1}]}""",
            """{"calls":[{"call_id":"c1","tool":"file_write_text","arguments":{"workspace_id":"ordinary-workspace"}}]}""",
            """{"calls":[{"call_id":"c1","tool":"file_read_text","arguments":{}}]}""",
            """{"calls":[{"call_id":"c1","tool":"workspace_list","arguments":{"extra":1}}]}""",
            """{"calls":[{"call_id":"dup","tool":"file_read_text","arguments":{"workspace_id":"ordinary-workspace"}},{"call_id":"dup","tool":"file_read_text","arguments":{"workspace_id":"ordinary-workspace"}}]}""",
            """{"calls":[{"call_id":"c1","tool":"calculator","arguments":{}}]}""",
        )) {
            assertFalse(
                batchAutoApproval(batch, rootRun = true, rootReadConfirmed = true, ordinarySkip = true),
                "Malformed, empty, unknown or mixed-root batch must not auto-approve: $batch",
            )
        }
        assertFalse(
            batchAutoApproval(
                """{"calls":[$ordinaryFile]}""",
                rootRun = false,
                rootReadConfirmed = false,
                ordinarySkip = true,
                authorityForWorkspace = { error("workspace authority unavailable") },
            ),
        )
        assertFalse(
            batchAutoApproval(
                """{"calls":[$workspaceList]}""",
                rootRun = false,
                rootReadConfirmed = false,
                ordinarySkip = true,
                authorityForWorkspace = { error("workspace authority unavailable") },
            ),
        )
    }

    private fun batchAutoApproval(
        batch: String,
        rootRun: Boolean,
        rootReadConfirmed: Boolean,
        ordinarySkip: Boolean,
        authorityForWorkspace: (String) -> DiagnosticAuthority = { id -> classifyBatchWorkspace(id) },
    ): Boolean = AgentToolConfirmation.autoApprovesInvocation(
        toolName = "read_batch",
        argumentsJson = batch,
        selectedAuthority = "ROOT_DIRECT",
        workspaceAuthority = DiagnosticAuthority.NONE,
        fallbackWorkspaceId = "ordinary-workspace",
        rootRun = rootRun,
        runScopedRoot = false,
        rootReadConfirmed = rootReadConfirmed,
        ordinarySkip = ordinarySkip,
        authorityForWorkspace = authorityForWorkspace,
    )

    private fun classifyBatchWorkspace(workspaceId: String): DiagnosticAuthority = when (workspaceId) {
        "root-workspace" -> DiagnosticAuthority.ROOT_DIRECT
        "ordinary-workspace" -> DiagnosticAuthority.NONE
        else -> error("unexpected workspace $workspaceId")
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
