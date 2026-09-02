// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import runtime.mobileagent.skills.tooling.FullDeviceFilesGrantStore
import runtime.mobileagent.skills.tooling.PrivilegedWorkspaceProvider
import runtime.mobileagent.skills.tooling.TypedAuthorityWorkspaceProvider
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.domain.Authority

/**
 * Public app-container seam for the Shizuku directory browser. The existing
 * typed UserService file store is used; no shell command is synthesized.
 */
object ShizukuPrivilegedWorkspaceFactory {
    @JvmStatic
    fun create(
        bridge: ShizukuAuthorityBridge,
        workspaceId: String = ShizukuWorkspaceBackendAdapter.DEFAULT_WORKSPACE_ID,
        displayName: String = ShizukuWorkspaceBackendAdapter.DEFAULT_DISPLAY_NAME,
        fullDeviceBackend: WorkspaceBackend? = null,
        fullDeviceGrantStore: FullDeviceFilesGrantStore? = null,
    ): PrivilegedWorkspaceProvider = TypedAuthorityWorkspaceProvider(
        authority = Authority.SHIZUKU,
        rootBackend = ShizukuWorkspaceBackendAdapter(bridge, workspaceId, displayName),
        fullDeviceBackend = fullDeviceBackend,
        fullDeviceGrantStore = fullDeviceGrantStore,
    )

    /**
     * Creates the device-root typed browser. The returned provider still
     * exposes only opaque directory/workspace handles; it does not turn shell
     * into file access and it does not imply that every `/data` node is
     * readable under the shell UID/SELinux policy.
     */
    @JvmStatic
    fun createDeviceRoot(
        bridge: ShizukuAuthorityBridge,
        workspaceId: String = ShizukuWorkspaceBackendAdapter.DEFAULT_WORKSPACE_ID,
        displayName: String = ShizukuWorkspaceBackendAdapter.DEFAULT_DISPLAY_NAME,
        fullDeviceGrantStore: FullDeviceFilesGrantStore? = null,
    ): PrivilegedWorkspaceProvider = ShizukuDeviceWorkspaceProvider(
        bridge = bridge,
        defaultWorkspaceId = workspaceId,
        defaultDisplayName = displayName,
        fullDeviceGrantStore = fullDeviceGrantStore,
    )
}
