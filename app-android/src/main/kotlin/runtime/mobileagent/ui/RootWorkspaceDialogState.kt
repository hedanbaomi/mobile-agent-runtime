// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.feature.agents.AgentWorkspaceUi
import runtime.mobileagent.feature.agents.RootDirectoryBrowseEntryUi
import runtime.mobileagent.feature.agents.RootDirectoryBrowserUiState
import runtime.mobileagent.feature.agents.RootWorkspacePermissionsUiState
import runtime.mobileagent.integration.RootWorkspaceUiPort
import runtime.mobileagent.workspace.WorkspaceSelectionOutcome

/**
 * Single state owner for the two Root dialogs: the foreground directory browser and the in-place
 * permission editor of an already attached Root directory.
 *
 * The screen renders [browse] and [permissions] and forwards intents; it keeps no second copy of
 * either. Everything that decides whether a response still belongs to the dialog that asked for it
 * lives here, so a late page or a late permission read cannot overwrite a newer dialog:
 *
 * - [browseGeneration] invalidates every in-flight page on close, cancel, and editor/channel change.
 * - [permissionsGeneration] plus [editorToken] invalidate a permission read or write that answered
 *   an older editor session or an older dialog.
 *
 * [permissions] stays non-null but not applicable (`loading`, `loadFailed`) until the live intent is
 * known: a failed read must never look like "everything is off", and Apply submits exactly the three
 * checkbox values the user sees.
 *
 * Composition-scoped inputs — the selected authority, the editor Agent id, and the localized failure
 * copy — are explicit call parameters instead of constructor captures: this holder survives many
 * compositions, so capturing them would freeze a stale channel or a stale locale.
 */
internal class RootWorkspaceDialogState(
    private val scope: CoroutineScope,
    private val port: RootWorkspaceUiPort?,
    private val editorToken: () -> Long,
) {
    private val browseState = mutableStateOf(RootDirectoryBrowserUiState(browsable = port != null))

    /** Current foreground page of the attach dialog. */
    val browse: RootDirectoryBrowserUiState by browseState

    private val browseOpenState = mutableStateOf(false)
    val browseOpen: Boolean by browseOpenState

    private val permissionsState = mutableStateOf<RootWorkspacePermissionsUiState?>(null)

    /** Current permission intent shown by the edit dialog; null when it is closed. */
    val permissions: RootWorkspacePermissionsUiState? by permissionsState

    private val permissionsOpenState = mutableStateOf(false)
    val permissionsOpen: Boolean by permissionsOpenState

    private var browseGeneration = 0
    private var permissionsGeneration = 0
    private var permissionsOwnerToken: Long? = null

    /**
     * The edit entry for [workspaceId] when it is an attached Root directory, else null. Also the
     * one lookup used by [editPermissions], so the entry and the dialog can never disagree about
     * which workspace they describe.
     */
    fun entry(workspaceId: String?, workspaces: List<AgentWorkspaceUi>): RootWorkspacePermissionsUiState? =
        rootWorkspace(workspaceId, workspaces)?.let { workspace ->
            RootWorkspacePermissionsUiState(
                workspaceId = workspace.id,
                displayName = workspace.displayName,
                writable = workspace.writable,
                metadata = false,
                shell = false,
            )
        }

    /** Opens the attach dialog on a fresh foreground listing of the device root. */
    fun open(authority: Authority, failureMessage: String) {
        browseOpenState.value = true
        load(authority, "/", cursor = null, append = false, failureMessage = failureMessage)
    }

    fun openPath(authority: Authority, path: String, failureMessage: String) =
        load(authority, path, cursor = null, append = false, failureMessage = failureMessage)

    fun browseParent(authority: Authority, failureMessage: String) {
        if (browse.loading) return
        val current = browse.path
        if (current.length > 1) {
            load(
                authority,
                current.substringBeforeLast('/').ifBlank { "/" },
                cursor = null,
                append = false,
                failureMessage = failureMessage,
            )
        }
    }

    fun browseMore(authority: Authority, failureMessage: String) {
        if (browse.loading) return
        browse.nextCursor?.let { cursor ->
            load(authority, browse.path, cursor = cursor, append = true, failureMessage = failureMessage)
        }
    }

    /** Closes the attach dialog and drops every in-flight page of it. */
    fun closeBrowse() {
        browseGeneration++
        browseOpenState.value = false
    }

    /**
     * An attach that was committed or staged closes the dialog; a failure keeps it — and therefore
     * the typed path — on screen with the reason in the status line.
     */
    fun attachFinished(outcome: WorkspaceSelectionOutcome) {
        if (outcome !is WorkspaceSelectionOutcome.Failed) closeBrowse()
    }

    /**
     * Opens the permission editor for this Root workspace and reads the live intent for [agentId].
     *
     * The fallback values from the attached workspace are display-only: [RootWorkspacePermissionsUiState.canApply]
     * stays false while the live read is running or after it failed.
     */
    fun editPermissions(
        authority: Authority,
        workspaceId: String?,
        workspaces: List<AgentWorkspaceUi>,
        agentId: String?,
        failureMessage: String,
    ) {
        val workspace = rootWorkspace(workspaceId, workspaces) ?: return
        val generation = ++permissionsGeneration
        val owner = editorToken()
        permissionsOwnerToken = owner
        permissionsState.value = RootWorkspacePermissionsUiState(
            workspaceId = workspace.id,
            displayName = workspace.displayName,
            writable = workspace.writable,
            metadata = false,
            shell = false,
            loading = port != null,
            loadFailed = port == null || agentId == null,
        )
        permissionsOpenState.value = true
        readPermissions(workspace, agentId, generation, owner, failureMessage)
    }

    /** Single source of truth for the three checkboxes: always derived from the current intent. */
    fun toggle(writable: Boolean, metadata: Boolean, shell: Boolean) {
        permissionsState.value = permissions?.copy(
            writable = writable,
            metadata = metadata,
            shell = shell,
        )
    }

    /**
     * Applies exactly the values currently rendered. Refused while the live intent is unknown, and
     * dropped when the editor session or the dialog that opened it has already changed. [onUpdated]
     * receives the success copy so the caller keeps owning the localized text and the status line.
     */
    fun apply(
        authority: Authority,
        agentId: String?,
        failureMessage: String,
        successMessage: String,
        onUpdated: (String) -> Unit,
    ) {
        val current = permissions ?: return
        val owner = permissionsOwnerToken
        val generation = permissionsGeneration
        if (owner != editorToken()) {
            permissionsOpenState.value = false
            return
        }
        if (!current.canApply) return
        val livePort = port
        if (livePort == null || agentId == null) {
            permissionsState.value = current.copy(error = failureMessage)
            return
        }
        permissionsState.value = current.copy(busy = true, error = null)
        scope.launch {
            val updated = withContext(Dispatchers.IO) {
                runCatching {
                    livePort.updateRootWorkspacePermissions(
                        authority = authority,
                        workspaceId = current.workspaceId,
                        agentId = agentId,
                        writable = current.writable,
                        metadata = current.metadata,
                        shell = current.shell,
                    )
                }.getOrDefault(false)
            }
            if (editorToken() != owner || permissionsGeneration != generation) return@launch
            if (updated) {
                permissionsGeneration++
                permissionsOpenState.value = false
                permissionsState.value = null
                onUpdated(successMessage)
            } else {
                permissionsState.value = permissions?.copy(busy = false, error = failureMessage)
            }
        }
    }

    /** Closes the permission editor and drops an in-flight read or write of it. */
    fun closePermissions() {
        permissionsGeneration++
        permissionsOpenState.value = false
    }

    /**
     * The editor Agent or the selected channel changed: both dialogs close and every in-flight
     * response for the previous one is stale, so it is dropped instead of reviving a closed dialog.
     */
    fun invalidate() {
        browseGeneration++
        permissionsGeneration++
        browseOpenState.value = false
        permissionsOpenState.value = false
        browseState.value = RootDirectoryBrowserUiState(browsable = port != null)
        permissionsState.value = null
        permissionsOwnerToken = null
    }

    private fun rootWorkspace(workspaceId: String?, workspaces: List<AgentWorkspaceUi>): AgentWorkspaceUi? =
        workspaces.firstOrNull { workspace -> workspace.id == workspaceId && workspace.isRoot }

    private fun load(
        authority: Authority,
        path: String,
        cursor: String?,
        append: Boolean,
        failureMessage: String,
    ) {
        val livePort = port ?: return
        val generation = ++browseGeneration
        // A new path must not keep the previous listing: rows of the old directory would render
        // under the new path title and stay clickable while the read runs and after it fails. A
        // paging request appends to the same path, so it keeps what is already shown.
        browseState.value = if (append) {
            browse.copy(path = path, loading = true, error = null)
        } else {
            browse.copy(path = path, entries = emptyList(), nextCursor = null, loading = true, error = null)
        }
        scope.launch {
            val page = withContext(Dispatchers.IO) {
                runCatching { livePort.browseRootDirectory(authority, path, cursor) }.getOrNull()
            }
            if (generation != browseGeneration) return@launch
            browseState.value = page?.let { loaded ->
                val incoming = loaded.entries.mapNotNull { entry ->
                    entry.name.takeIf { it.isNotBlank() }?.let { name ->
                        RootDirectoryBrowseEntryUi(
                            name = name,
                            path = entry.path,
                            directory = entry.directory,
                            targetPath = entry.targetPath,
                        )
                    }
                }
                // Paging is copy-on-append: keep every page already shown, then append only rows
                // this page has not shown yet. The cursor is retained as-is, so a page is never
                // silently cut to a fixed total and "load more" stays available.
                val merged = LinkedHashMap<String, RootDirectoryBrowseEntryUi>()
                if (append) browse.entries.forEach { merged[it.name + '\u0000' + it.path] = it }
                incoming.forEach { merged[it.name + '\u0000' + it.path] = it }
                browse.copy(
                    path = loaded.path,
                    entries = merged.values.toList(),
                    nextCursor = loaded.nextCursor,
                    loading = false,
                    error = null,
                )
            } ?: browse.copy(loading = false, error = failureMessage)
        }
    }

    private fun readPermissions(
        workspace: AgentWorkspaceUi,
        agentId: String?,
        generation: Int,
        owner: Long,
        failureMessage: String,
    ) {
        val livePort = port ?: return
        if (agentId == null) return
        scope.launch {
            val live = withContext(Dispatchers.IO) {
                runCatching { livePort.rootWorkspacePermissions(workspace.id, agentId) }.getOrNull()
            }
            if (editorToken() != owner || permissionsGeneration != generation ||
                permissions?.workspaceId != workspace.id
            ) {
                return@launch
            }
            permissionsState.value = live?.let { intent ->
                // `live` is the integration DTO; the dialog renders the UI state, so the values are
                // carried over explicitly instead of copy()ing across types.
                RootWorkspacePermissionsUiState(
                    workspaceId = intent.workspaceId,
                    displayName = workspace.displayName,
                    writable = intent.writable,
                    metadata = intent.metadata,
                    shell = intent.shell,
                    loading = false,
                    loadFailed = false,
                )
            } ?: permissions?.copy(
                loading = false,
                loadFailed = true,
                error = failureMessage,
            )
        }
    }
}
