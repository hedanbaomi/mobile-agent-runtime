// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import runtime.mobileagent.workspace.WorkspaceUiPresentation

/**
 * Narrow presentation-facing projection for workspace titles and breadcrumbs.
 * Decouples chat UI and history projection from monolithic runtime facade.
 */
interface WorkspacePresentationPort {
    fun workspaceUiPresentation(workspaceId: String, chinese: Boolean = true): WorkspaceUiPresentation?
}

fun interface WorkspacePresentationPortProvider {
    fun workspacePresentationPort(): WorkspacePresentationPort
}
