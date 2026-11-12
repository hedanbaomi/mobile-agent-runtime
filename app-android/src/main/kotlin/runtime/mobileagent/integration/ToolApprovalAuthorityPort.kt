// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import runtime.mobileagent.diagnostics.DiagnosticAuthority
import runtime.mobileagent.observation.ScreenActionAuthority

/**
 * Narrow authority query port for tool approvals and screen action admission.
 */
interface ToolApprovalAuthorityPort {
    fun approvalWorkspaceAuthority(workspaceId: String): DiagnosticAuthority
    fun diagnosticWorkspaceAuthority(workspaceId: String?): DiagnosticAuthority
    fun screenActionAuthority(): ScreenActionAuthority?
}

fun interface ToolApprovalAuthorityPortProvider {
    fun toolApprovalAuthorityPort(): ToolApprovalAuthorityPort
}
