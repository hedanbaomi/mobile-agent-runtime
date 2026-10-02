// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import runtime.mobileagent.diagnostics.DiagnosticAuthority
import runtime.mobileagent.diagnostics.DiagnosticToolCapability

class ChatDiagnosticAttributionTest {
    @Test
    fun isolatedPythonIsDistinctFromShellUnderEverySelectedAuthority() {
        val capability = chatDiagnosticToolCapability("python.execute")
        assertEquals(DiagnosticToolCapability.PYTHON_EXECUTE, capability)
        listOf("NONE", "SHIZUKU", "WIRED_ADB").forEach {
            assertEquals(DiagnosticAuthority.NONE, chatDiagnosticAuthority(capability, it))
        }
    }

    @Test
    fun shellRequiresExactContractAndKeepsSelectedAuthority() {
        assertEquals(DiagnosticToolCapability.SHELL_EXECUTE, chatDiagnosticToolCapability("shell.execute"))
        assertEquals(DiagnosticAuthority.SHIZUKU, chatDiagnosticAuthority(DiagnosticToolCapability.SHELL_EXECUTE, "SHIZUKU"))
        assertEquals(DiagnosticAuthority.WIRED_ADB, chatDiagnosticAuthority(DiagnosticToolCapability.SHELL_EXECUTE, "WIRED_ADB"))
        listOf(null, "execute", "custom.execute", "python.execute.extra", "custom.shell.execute").forEach {
            val capability = chatDiagnosticToolCapability(it)
            assertEquals(DiagnosticToolCapability.UNKNOWN, capability)
            assertEquals(DiagnosticAuthority.NONE, chatDiagnosticAuthority(capability, "SHIZUKU"))
        }
    }

    @Test
    fun workspaceContractsUseBoundBackendAuthorityInsteadOfGlobalSelection() {
        mapOf("workspace.read" to DiagnosticToolCapability.WORKSPACE_READ,
            "workspace.write" to DiagnosticToolCapability.WORKSPACE_WRITE,
            "shizuku.workspace.read" to DiagnosticToolCapability.WORKSPACE_READ,
            "shizuku.workspace.write" to DiagnosticToolCapability.WORKSPACE_WRITE).forEach { (contract, expected) ->
            val capability = chatDiagnosticToolCapability(contract)
            assertEquals(expected, capability)
            // Internal/SAF remains local even when another Authority is selected.
            assertEquals(DiagnosticAuthority.NONE, chatDiagnosticAuthority(capability, "SHIZUKU", DiagnosticAuthority.NONE))
            assertEquals(DiagnosticAuthority.SHIZUKU, chatDiagnosticAuthority(capability, "WIRED_ADB", DiagnosticAuthority.SHIZUKU))
            assertEquals(DiagnosticAuthority.WIRED_ADB, chatDiagnosticAuthority(capability, "SHIZUKU", DiagnosticAuthority.WIRED_ADB))
        }
    }

    @Test
    fun localMemoryAndSearchNeverInheritGlobalAuthority() {
        mapOf("memory.append" to DiagnosticToolCapability.MEMORY_WRITE,
            "memory.read" to DiagnosticToolCapability.MEMORY_READ,
            "network.search" to DiagnosticToolCapability.SEARCH,
            "file.read_text" to DiagnosticToolCapability.WORKSPACE_READ,
            "file.move" to DiagnosticToolCapability.WORKSPACE_WRITE).forEach { (contract, expected) ->
            assertEquals(expected, chatDiagnosticToolCapability(contract))
            assertEquals(DiagnosticAuthority.NONE, chatDiagnosticAuthority(expected, "SHIZUKU"))
        }
    }
}
