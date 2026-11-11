// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.integration

import runtime.mobileagent.data.FullDeviceFilesGrantRepository
import runtime.mobileagent.resident.ResidentAdbAuthorityBridge
import runtime.mobileagent.shizuku.ShizukuAuthorityBridge
import runtime.mobileagent.shizuku.ShizukuBackendFactory
import runtime.mobileagent.shizuku.ShizukuPrivilegedWorkspaceFactory
import runtime.mobileagent.skills.tooling.PrivilegedWorkspaceProvider
import runtime.mobileagent.skills.tooling.ShellExecutor
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.wired.WiredAdbAuthorityPort
import runtime.mobileagent.wired.WiredAdbPrivilegedWorkspaceFactory

/** An authority contributes its own three adapters. Selection and grants remain with the facade. */
internal data class AuthorityBackendAssembly(
    val shell: ShellExecutor,
    val workspace: WorkspaceBackend,
    val browser: PrivilegedWorkspaceProvider,
) {
    companion object {
        fun shizuku(bridge: ShizukuAuthorityBridge, grants: FullDeviceFilesGrantRepository): AuthorityBackendAssembly {
            val workspace = ShizukuBackendFactory.createWorkspaceBackend(bridge)
            return AuthorityBackendAssembly(ShizukuBackendFactory.createShellExecutor(bridge), workspace,
                ShizukuPrivilegedWorkspaceFactory.createDeviceRoot(bridge, workspace.descriptor.id,
                    workspace.descriptor.displayName, grants))
        }

        fun wired(bridge: WiredAdbAuthorityPort, grants: FullDeviceFilesGrantRepository): AuthorityBackendAssembly {
            val resident = bridge as? ResidentAdbAuthorityBridge
            val workspace = resident?.createWorkspaceBackend() ?: WiredWorkspaceBackend(bridge)
            val shell = resident?.createShellExecutor() ?: WiredShellExecutor(bridge)
            val browser = resident?.createWorkspaceProvider(workspace.descriptor.id, workspace.descriptor.displayName, grants)
                ?: WiredAdbPrivilegedWorkspaceFactory.create(bridge, workspace.descriptor.id, workspace.descriptor.displayName,
                    fullDeviceGrantStore = grants)
            return AuthorityBackendAssembly(shell, workspace, browser)
        }
    }
}
