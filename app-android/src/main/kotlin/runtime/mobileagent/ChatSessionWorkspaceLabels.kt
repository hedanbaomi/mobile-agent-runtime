// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

/**
 * Resolve a workspace's display title once per refresh. The cache never survives this call:
 * rename, deletion and connection changes remain visible on the next reload.
 * Thread bindings are still read independently; execution performs its own full validation.
 */
internal fun chatSessionWorkspaceLabels(
    sessionIds: Collection<String>,
    bindingWorkspaceId: (String) -> String?,
    title: (String) -> String?,
): Map<String, String> {
    val workspaceLabels = mutableMapOf<String, String>()
    return sessionIds.associateWith { sessionId ->
        runCatching {
            val workspaceId = bindingWorkspaceId(sessionId)
            if (workspaceId == null) "无工作区"
            else workspaceLabels.getOrPut(workspaceId) {
                runCatching { title(workspaceId) ?: "已绑定工作区" }
                    .getOrDefault("工作区状态不可用")
            }
        }.getOrDefault("工作区状态不可用")
    }
}
