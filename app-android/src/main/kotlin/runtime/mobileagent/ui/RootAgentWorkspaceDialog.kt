// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.isRootAuthority
import runtime.mobileagent.feature.agents.RootDirectorySelectionCard

internal fun isFullDeviceFilesEligibleForEditor(
    selectedAuthority: Authority,
    authorityReady: Boolean,
    dangerousModeBuildAllowed: Boolean,
    dangerousMode: DangerousMode,
    workspaceBusy: Boolean,
): Boolean = !selectedAuthority.isRootAuthority &&
    authorityReady &&
    dangerousModeBuildAllowed &&
    dangerousMode != DangerousMode.DISABLED &&
    !workspaceBusy

internal fun offlinePrivilegedAuthoritySummary(
    selectedAuthority: Authority,
    chinese: Boolean,
): String = if (selectedAuthority.isRootAuthority) {
    if (chinese) {
        "Root 通道授权记录已保留；当前会话未连接，需重新连接后生效。"
    } else {
        "Root channel consent is retained, but no verified Root session is currently connected."
    }
} else {
    if (chinese) {
        "ADB 级授权保持不变，但当前连接不可用。"
    } else {
        "ADB-level authorization is retained, but the current connection is unavailable."
    }
}

@Composable
internal fun RootAgentWorkspaceDialog(
    enabled: Boolean,
    chinese: Boolean,
    onConfirm: (path: String, writable: Boolean, metadata: Boolean, shell: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("agents.workspace.root_path"),
        title = { Text(if (chinese) "绑定 Root 设备目录" else "Attach Root directory") },
        text = {
            RootDirectorySelectionCard(
                enabled = enabled,
                chinese = chinese,
                onConfirm = onConfirm,
            )
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onClose) {
                Text(if (chinese) "取消" else "Cancel")
            }
        },
    )
}
