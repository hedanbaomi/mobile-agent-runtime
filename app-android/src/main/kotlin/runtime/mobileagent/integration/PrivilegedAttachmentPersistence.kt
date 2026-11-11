// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.integration

import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.skills.tooling.WorkspaceAttachment

/** Owns the provider-returned attachment from its first repository read through persistence. */
internal fun persistPrivilegedAttachment(
    attachment: WorkspaceAttachment,
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    persist: (Workspace?) -> WorkspaceAccessResult,
): WorkspaceAccessResult {
    try {
        if (attachment.descriptor.id != workspaceId) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
        }
        return persist(workspaceRepository.get(workspaceId))
    } finally {
        attachment.recoveryLocator?.clear()
    }
}
