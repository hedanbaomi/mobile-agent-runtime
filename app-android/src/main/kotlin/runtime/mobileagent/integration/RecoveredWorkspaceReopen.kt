// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import kotlinx.coroutines.CancellationException
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.skills.tooling.PrivilegedWorkspaceProvider
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.WorkspaceAttachment
import runtime.mobileagent.skills.tooling.WorkspaceReattachRequest
import runtime.mobileagent.skills.tooling.WorkspaceRecoveryLocator
import runtime.mobileagent.skills.tooling.WorkspaceResult

/** Owns the decrypted locator until dispatch finishes, including a lost grant or failed lookup.
 * A null result means that full-device consent was lost; the caller persists GRANT_LOST.
 * The already selected provider is the only possible dispatch target, and is never retried.
 */
internal suspend fun reopenRecoveredWorkspace(
    workspaceId: String,
    displayName: String,
    scope: WorkspaceScope,
    locator: WorkspaceRecoveryLocator,
    provider: PrivilegedWorkspaceProvider,
    fullDeviceGrantRevision: () -> Long?,
): WorkspaceResult<WorkspaceAttachment>? {
    return try {
        val revision = if (scope == WorkspaceScope.FULL_DEVICE_FILES) {
            fullDeviceGrantRevision() ?: return null
        } else {
            null
        }
        try {
            provider.reopenDirectory(
                WorkspaceReattachRequest(workspaceId, displayName, locator, scope, revision),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            WorkspaceResult.Failure(ToolError(ToolErrorCode.UNKNOWN_OUTCOME))
        }
    } finally {
        locator.clear()
    }
}
