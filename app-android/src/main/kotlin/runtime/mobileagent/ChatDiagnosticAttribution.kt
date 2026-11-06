// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import runtime.mobileagent.diagnostics.DiagnosticAuthority
import runtime.mobileagent.diagnostics.DiagnosticToolCapability
import runtime.mobileagent.domain.CapabilityId

/**
 * Chat attribution uses exact declared contracts, including legacy workspace aliases.
 * Search attribution requires the qualified network or knowledge contract.
 */
internal fun chatDiagnosticToolCapability(capability: String?): DiagnosticToolCapability = when (capability) {
    "python.execute" -> DiagnosticToolCapability.PYTHON_EXECUTE
    CapabilityId.SHELL_EXECUTE -> DiagnosticToolCapability.SHELL_EXECUTE
    CapabilityId.WORKSPACE_ENUMERATE, CapabilityId.FILE_LIST, CapabilityId.FILE_STAT, CapabilityId.FILE_READ_TEXT,
    CapabilityId.FILE_METADATA_READ, "workspace.read", "shizuku.workspace.read" ->
        DiagnosticToolCapability.WORKSPACE_READ
    CapabilityId.FILE_WRITE_TEXT, CapabilityId.FILE_CREATE_DIRECTORY, CapabilityId.FILE_MOVE, CapabilityId.FILE_DELETE,
    CapabilityId.FILE_METADATA_WRITE, "file.apply_patch", "workspace.write", "shizuku.workspace.write" -> DiagnosticToolCapability.WORKSPACE_WRITE
    CapabilityId.MEMORY_READ, CapabilityId.MEMORY_SEARCH -> DiagnosticToolCapability.MEMORY_READ
    CapabilityId.MEMORY_APPEND, CapabilityId.MEMORY_REPLACE -> DiagnosticToolCapability.MEMORY_WRITE
    "network.search", "knowledge.search" -> DiagnosticToolCapability.SEARCH
    else -> DiagnosticToolCapability.UNKNOWN
}

internal fun chatDiagnosticAuthority(
    capability: DiagnosticToolCapability,
    selectedAuthority: String,
    workspaceAuthority: DiagnosticAuthority = DiagnosticAuthority.NONE,
): DiagnosticAuthority = when (capability) {
    DiagnosticToolCapability.SHELL_EXECUTE -> when (selectedAuthority) {
        "SHIZUKU" -> DiagnosticAuthority.SHIZUKU
        "WIRED_ADB" -> DiagnosticAuthority.WIRED_ADB
        "ROOT_DIRECT" -> DiagnosticAuthority.ROOT_DIRECT
        "SHIZUKU_ROOT" -> DiagnosticAuthority.SHIZUKU_ROOT
        else -> DiagnosticAuthority.NONE
    }
    DiagnosticToolCapability.WORKSPACE_READ, DiagnosticToolCapability.WORKSPACE_WRITE -> workspaceAuthority
    else -> DiagnosticAuthority.NONE
}
