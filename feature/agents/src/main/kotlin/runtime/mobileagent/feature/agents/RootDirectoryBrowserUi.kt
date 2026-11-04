// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.agents

/**
 * One directory row. Paths are display-only and never enter a model-facing schema.
 *
 * [targetPath] is set only for a directory alias (for example `sdcard` -> `/storage/emulated/0`).
 * It is the target observed by the service and is shown so the user knows what alias they are
 * selecting; typed operations still resolve the final directory without following an arbitrary
 * symlink path segment.
 */
data class RootDirectoryBrowseEntryUi(
    val name: String,
    val path: String,
    val directory: Boolean,
    val targetPath: String? = null,
)

/**
 * Foreground browser state for the Root attach dialog.
 *
 * The host owns the request generation: a late response for an older request must never overwrite a
 * newer path, so the host drops it instead of letting this state leap backwards.
 */
data class RootDirectoryBrowserUiState(
    val path: String = "",
    val entries: List<RootDirectoryBrowseEntryUi> = emptyList(),
    val nextCursor: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** False while the host has no live browse port (manual absolute-path flow only). */
    val browsable: Boolean = false,
) {
    val hasMore: Boolean get() = nextCursor != null
}

/**
 * Current durable permission intent of an already attached Root workspace.
 *
 * The host must not let a failed or still-running read look like "every permission is off": while
 * [loading] or after [loadFailed] the dialog keeps the save action disabled instead of overwriting
 * the real intent with defaults.
 */
data class RootWorkspacePermissionsUiState(
    val workspaceId: String,
    val displayName: String,
    val writable: Boolean,
    val metadata: Boolean,
    val shell: Boolean,
    val busy: Boolean = false,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val error: String? = null,
) {
    /** True only when the live intent is known and no mutation is in flight. */
    val canApply: Boolean get() = !busy && !loading && !loadFailed
}

object RootWorkspaceTestTags {
    const val BROWSE = "agents.root.browse"
    const val BROWSE_ENTRY = "agents.root.browse.entry"
    const val BROWSE_PARENT = "agents.root.browse.parent"
    const val BROWSE_MORE = "agents.root.browse.more"
    const val BROWSE_ERROR = "agents.root.browse.error"
    const val BROWSE_LIST = "agents.root.browse.list"
    const val ATTACH_CONFIRM = "agents.root.attach.confirm"
    const val SHELL_SETTINGS_HINT = "agents.root.shell.settings_hint"
    const val PERMISSIONS_ENTRY = "agents.workspace.root_permissions"
    const val PERMISSIONS_DIALOG = "agents.root.permissions"
    const val PERMISSIONS_CONFIRM = "agents.root.permissions.confirm"
}
