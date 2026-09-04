// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.feature.agents.WorkspacePickerAttachedUi
import runtime.mobileagent.feature.agents.WorkspacePickerAttachPhaseUi
import runtime.mobileagent.feature.agents.WorkspacePickerNewThreadUi
import runtime.mobileagent.feature.agents.WorkspacePickerAuthorityUi
import runtime.mobileagent.feature.agents.WorkspacePickerBreadcrumbUi
import runtime.mobileagent.feature.agents.WorkspacePickerEntryUi
import runtime.mobileagent.feature.agents.WorkspacePickerErrorCodeUi
import runtime.mobileagent.feature.agents.WorkspacePickerLoadPhaseUi
import runtime.mobileagent.feature.agents.WorkspacePickerLocationUi
import runtime.mobileagent.feature.agents.WorkspacePickerModeUi
import runtime.mobileagent.feature.agents.WorkspacePickerRecentUi
import runtime.mobileagent.feature.agents.WorkspacePickerUiState
import runtime.mobileagent.integration.WorkspaceAccessErrorCode
import runtime.mobileagent.integration.WorkspaceAccessItem
import runtime.mobileagent.integration.WorkspaceAccessResult
import runtime.mobileagent.integration.WorkspaceAccessStatus
import runtime.mobileagent.skills.tooling.WorkspaceAttachRequest
import runtime.mobileagent.skills.tooling.WorkspaceBrowseRequest
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryEntry
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryHandle
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryPage
import runtime.mobileagent.skills.tooling.WorkspaceEntryType
import runtime.mobileagent.skills.tooling.WorkspaceResult
import runtime.mobileagent.skills.tooling.ToolErrorCode

/**
 * Stable foreground state for the privileged workspace picker.
 *
 * Opaque directory handles are retained only in this process and never enter
 * [WorkspacePickerUiState].  A browse operation is single-flight, and a
 * successful attach consumes the committed result directly rather than
 * re-reading repositories after the transaction.
 */
class WorkspacePickerViewModel(
    application: Application,
    private val port: WorkspacePickerPort = UnavailableWorkspacePickerPort,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(WorkspacePickerUiState())
    val state: StateFlow<WorkspacePickerUiState> = _state.asStateFlow()

    private var browseJob: Job? = null
    private var operationGeneration = 0L
    private var currentPage: WorkspaceDirectoryPage? = null
    private var currentContinuation: String? = null
    private var accumulatedEntries = ArrayList<WorkspaceDirectoryEntry>()
    private var currentDirectoryReadable = false
    private var currentDirectoryWritable = false
    private val directoryStack = ArrayList<DirectoryLevel>()
    private val entryHandles = LinkedHashMap<String, WorkspaceDirectoryEntry>()
    private var target = WorkspacePickerTarget()
    private var targetLabel = "当前目标"
    private var selectedAuthority = Authority.NONE
    private var authorityReady = false

    init {
        refresh()
    }

    fun setTarget(target: WorkspacePickerTarget, label: String = "当前目标") {
        this.target = target
        targetLabel = label.trim().ifBlank { "当前目标" }.take(128)
        _state.value = _state.value.copy(targetLabel = targetLabel)
    }

    fun refresh() {
        val generation = nextGeneration()
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val authority = port.authoritySnapshot()
                    val recent = port.recentWorkspaces(target.agentId)
                    authority to recent
                }.getOrElse { WorkspacePickerAuthoritySnapshot() to emptyList() }
            }
            if (!isCurrent(generation)) return@launch
            val authority = loaded.first
            val recent = loaded.second
            val mode = modeFor(authority)
            selectedAuthority = authority.selectedAuthority
            authorityReady = authority.ready
            clearDirectoryState()
            _state.value = _state.value.copy(
                mode = mode,
                authority = authority.toUi(),
                targetLabel = targetLabel,
                recentWorkspaces = recent.map { it.toRecentUi() },
                locations = emptyList(),
                breadcrumbs = emptyList(),
                currentLabel = "根目录",
                entries = emptyList(),
                loadPhase = WorkspacePickerLoadPhaseUi.IDLE,
                loading = false,
                loadingMore = false,
                listTruncated = false,
                canLoadMore = false,
                currentDirectoryReadable = false,
                currentDirectoryWritable = false,
                canGoParent = false,
                canUseCurrentDirectory = false,
                canUseSafFallback = mode != WorkspacePickerModeUi.SAF_FALLBACK,
                attachPhase = WorkspacePickerAttachPhaseUi.IDLE,
                attached = null,
                errorCode = null,
                errorMessage = null,
                statusMessage = null,
            )
            if (mode == WorkspacePickerModeUi.PRIVILEGED) {
                browseRoot(generation, authority.selectedAuthority)
            } else if (mode == WorkspacePickerModeUi.AUTHORITY_UNAVAILABLE) {
                _state.value = _state.value.copy(
                    statusMessage = "${authority.displayLabel()}当前不可用；不会自动切换通道。",
                )
            }
        }
    }

    /** Explicitly choose the ordinary SAF fallback. */
    fun chooseSafFallback() {
        browseJob?.cancel()
        nextGeneration()
        clearDirectoryState()
        _state.value = _state.value.copy(
            mode = WorkspacePickerModeUi.SAF_FALLBACK,
            loadPhase = WorkspacePickerLoadPhaseUi.IDLE,
            loading = false,
            canUseSafFallback = false,
            attachPhase = WorkspacePickerAttachPhaseUi.IDLE,
            attached = null,
            errorCode = null,
            errorMessage = null,
            statusMessage = "请通过系统文件选择器选择工作区。",
        )
    }

    fun retry() = refresh()

    fun openLocation(id: String) = openEntry(id)

    fun openEntry(id: String) {
        val entry = entryHandles[id] ?: return
        val handle = entry.handle
        if (entry.type != WorkspaceEntryType.DIRECTORY || handle == null) {
            showError(WorkspacePickerErrorCodeUi.PERMISSION_DENIED, "该文件夹不可访问。")
            return
        }
        if (entryHandles[id]?.let { it.handle == null || !isReadable(it) } == true) {
            showError(WorkspacePickerErrorCodeUi.PERMISSION_DENIED, "该文件夹不可访问。")
            return
        }
        val authority = selectedAuthority
        if (!isElevated(authority) || !authorityReady) {
            showError(WorkspacePickerErrorCodeUi.AUTHORITY_UNAVAILABLE, "当前增强访问不可用。")
            return
        }
        val generation = nextGeneration()
        browseJob?.cancel()
        pendingChildLabel = entry.name
        browseJob = browseJobFor(generation) {
            port.browsePrivileged(
                authority,
                WorkspaceBrowseRequest(handle, WorkspacePickerPort.DEFAULT_PAGE_SIZE),
            )
        }
    }

    fun openBreadcrumb(id: String) {
        val index = id.removePrefix("depth:").toIntOrNull() ?: return
        if (index !in directoryStack.indices || index == directoryStack.lastIndex) return
        val authority = selectedAuthority
        if (!isElevated(authority) || !authorityReady) {
            showError(WorkspacePickerErrorCodeUi.AUTHORITY_UNAVAILABLE, "当前增强访问不可用。")
            return
        }
        val generation = nextGeneration()
        browseJob?.cancel()
        val level = directoryStack[index]
        pendingStackIndex = index
        browseJob = browseJobFor(generation) {
            port.browsePrivileged(
                authority,
                WorkspaceBrowseRequest(level.handle, WorkspacePickerPort.DEFAULT_PAGE_SIZE),
            )
        }
    }

    fun goParent() {
        if (directoryStack.size <= 1) return
        openBreadcrumb("depth:${directoryStack.lastIndex - 1}")
    }

    /**
     * Loads the next picker page for the currently displayed directory and
     * appends it.  Directories stay sorted before files across the
     * accumulated set, so a later page can only add reachability, never move
     * an already visible entry.  A stale continuation fails closed with a
     * typed refresh prompt and keeps the entries already shown.
     */
    fun loadMore() {
        val continuation = currentContinuation ?: return
        if (_state.value.loading || _state.value.loadingMore) return
        if (currentPage == null) return
        val level = directoryStack.lastOrNull() ?: return
        val authority = selectedAuthority
        if (!isElevated(authority) || !authorityReady) {
            showError(WorkspacePickerErrorCodeUi.AUTHORITY_UNAVAILABLE, "当前增强访问不可用。")
            return
        }
        val generation = operationGeneration
        val expectedHandle = level.handle
        val expectedDepth = directoryStack.size
        browseJob = viewModelScope.launch {
            _state.value = _state.value.copy(loadingMore = true, errorCode = null, errorMessage = null)
            val result = try {
                withContext(Dispatchers.IO) {
                    port.browsePrivileged(
                        authority,
                        WorkspaceBrowseRequest(expectedHandle, WorkspacePickerPort.DEFAULT_PAGE_SIZE, continuation),
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(loadingMore = false)
                throw cancelled
            } catch (_: RuntimeException) {
                WorkspaceResult.Failure(runtime.mobileagent.skills.tooling.ToolError(ToolErrorCode.UNKNOWN_OUTCOME))
            }
            if (!isCurrent(generation)) return@launch
            // The user may have navigated while the page was in flight; never
            // append a page to a different level.
            if (directoryStack.size != expectedDepth || directoryStack.lastOrNull()?.handle !== expectedHandle) {
                _state.value = _state.value.copy(loadingMore = false)
                return@launch
            }
            when (result) {
                is WorkspaceResult.Success -> applyPage(result.value, generation, append = true)
                is WorkspaceResult.Failure -> {
                    val code = result.error.toPickerErrorCode()
                    currentContinuation = null
                    _state.value = _state.value.copy(
                        loadingMore = false,
                        canLoadMore = false,
                        errorCode = code,
                        errorMessage = code.toUiMessage(),
                    )
                }
            }
        }
    }

    /**
     * Attaches the currently displayed directory.  The model cannot invoke
     * this method; it is wired only to foreground UI actions.
     */
    fun useCurrentDirectory() {
        if (_state.value.attachPhase == WorkspacePickerAttachPhaseUi.ATTACHING) return
        if (_state.value.mode != WorkspacePickerModeUi.PRIVILEGED) {
            showError(WorkspacePickerErrorCodeUi.AUTHORITY_UNAVAILABLE, "请先连接并选择增强访问。")
            return
        }
        val authority = selectedAuthority
        if (!isElevated(authority) || !authorityReady) {
            showError(WorkspacePickerErrorCodeUi.AUTHORITY_UNAVAILABLE, "当前增强访问不可用。")
            return
        }
        val page = currentPage
        val level = directoryStack.lastOrNull()
        if (page == null || level == null || !currentDirectoryReadable) {
            showError(WorkspacePickerErrorCodeUi.PERMISSION_DENIED, "当前位置不可访问。")
            return
        }
        attachPrivileged(authority, page.current, pathHintForAttach(level.label))
    }

    /** Called by the host after the user explicitly selected a SAF tree. */
    fun onSafUriSelected(uri: Uri, resultFlags: Int = WorkspacePickerPort.DEFAULT_SAF_FLAGS) {
        if (_state.value.mode != WorkspacePickerModeUi.SAF_FALLBACK) return
        if (_state.value.attachPhase == WorkspacePickerAttachPhaseUi.ATTACHING) return
        val name = "文件夹授权"
        attachSaf(uri, resultFlags, name)
    }

    fun openRecent(workspaceId: String) {
        if (workspaceId.isBlank()) return
        if (_state.value.attachPhase == WorkspacePickerAttachPhaseUi.ATTACHING) return
        val generation = nextGeneration()
        browseJob?.cancel()
        _state.value = _state.value.copy(
            attachPhase = WorkspacePickerAttachPhaseUi.ATTACHING,
            errorCode = null,
            errorMessage = null,
            statusMessage = "正在打开最近工作区…",
        )
        browseJob = viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { port.useRecentWorkspace(workspaceId, target) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                WorkspaceAccessResult.Failure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
            }
            if (!isCurrent(generation)) return@launch
            when (result) {
                is WorkspaceAccessResult.Success -> {
                    _state.value = _state.value.copy(
                        attachPhase = WorkspacePickerAttachPhaseUi.SUCCESS,
                        attached = WorkspacePickerAttachedUi(
                            workspaceId = result.workspace.workspaceId,
                            displayName = result.workspace.displayName,
                            statusLabel = result.workspace.status.toUiLabel(),
                        ),
                        pendingNewThread = null,
                        statusMessage = "已打开最近工作区。",
                    )
                }
                is WorkspaceAccessResult.NewThreadRequired -> applyNewThreadRequired(result)
                is WorkspaceAccessResult.Failure -> showError(
                    result.code.toUiCode(),
                    result.code.toUiMessage(),
                    attachFailure = true,
                )
            }
        }
    }

    fun clearResult() {
        _state.value = _state.value.copy(
            attachPhase = WorkspacePickerAttachPhaseUi.IDLE,
            attached = null,
            pendingNewThread = null,
            statusMessage = null,
        )
    }

    private fun attachPrivileged(authority: Authority, handle: WorkspaceDirectoryHandle, displayName: String) {
        val generation = nextGeneration()
        val workspaceId = newWorkspaceId()
        val request = WorkspaceAttachRequest(workspaceId, displayName, handle)
        _state.value = _state.value.copy(
            attachPhase = WorkspacePickerAttachPhaseUi.ATTACHING,
            errorCode = null,
            errorMessage = null,
            statusMessage = null,
            attached = null,
        )
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    port.attachPrivilegedDirectory(authority, request, target)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                WorkspaceAccessResult.Failure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
            }
            if (!isCurrent(generation)) return@launch
            applyAttachResult(result)
        }
    }

    private fun attachSaf(uri: Uri, resultFlags: Int, displayName: String) {
        val generation = nextGeneration()
        _state.value = _state.value.copy(
            attachPhase = WorkspacePickerAttachPhaseUi.ATTACHING,
            errorCode = null,
            errorMessage = null,
            statusMessage = null,
            attached = null,
        )
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    port.attachSaf(uri, resultFlags, target)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                WorkspaceAccessResult.Failure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
            }
            if (!isCurrent(generation)) return@launch
            applyAttachResult(result)
        }
    }

    private fun applyAttachResult(result: WorkspaceAccessResult) {
        when (result) {
            is WorkspaceAccessResult.Success -> {
                _state.value = _state.value.copy(
                    attachPhase = WorkspacePickerAttachPhaseUi.SUCCESS,
                    attached = WorkspacePickerAttachedUi(
                        workspaceId = result.workspace.workspaceId,
                        displayName = result.workspace.displayName,
                        statusLabel = result.workspace.status.toUiLabel(),
                    ),
                    pendingNewThread = null,
                    errorCode = null,
                    errorMessage = null,
                    statusMessage = "工作区已添加。",
                )
            }
            is WorkspaceAccessResult.NewThreadRequired -> applyNewThreadRequired(result)
            is WorkspaceAccessResult.Failure -> showError(
                result.code.toUiCode(),
                result.code.toUiMessage(),
                attachFailure = true,
            )
        }
    }

    private fun applyNewThreadRequired(result: WorkspaceAccessResult.NewThreadRequired) {
        _state.value = _state.value.copy(
            attachPhase = WorkspacePickerAttachPhaseUi.NEEDS_NEW_THREAD,
            attached = WorkspacePickerAttachedUi(
                workspaceId = result.requestedWorkspaceId,
                displayName = result.workspace.displayName,
                statusLabel = result.workspace.status.toUiLabel(),
            ),
            pendingNewThread = WorkspacePickerNewThreadUi(
                agentId = result.agentId,
                currentThreadId = result.currentThreadId,
                currentWorkspaceId = result.currentWorkspaceId,
                requestedWorkspaceId = result.requestedWorkspaceId,
                requiresGrantCommit = result.requiresGrantCommit,
            ),
            errorCode = null,
            errorMessage = null,
            statusMessage = "工作区属于当前会话上下文，切换将创建新会话。",
        )
    }

    fun confirmNewThread(
        pending: WorkspacePickerNewThreadUi,
        onConfirmed: (workspaceId: String) -> Unit,
    ) {
        if (_state.value.attachPhase == WorkspacePickerAttachPhaseUi.ATTACHING) return
        val generation = nextGeneration()
        browseJob?.cancel()
        _state.value = _state.value.copy(
            attachPhase = WorkspacePickerAttachPhaseUi.ATTACHING,
            errorCode = null,
            errorMessage = null,
            statusMessage = "正在确认工作区切换…",
        )
        browseJob = viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    port.confirmNewThreadWorkspace(
                        agentId = pending.agentId,
                        currentThreadId = pending.currentThreadId,
                        currentWorkspaceId = pending.currentWorkspaceId,
                        requestedWorkspaceId = pending.requestedWorkspaceId,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                WorkspaceAccessResult.Failure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
            }
            if (!isCurrent(generation)) return@launch
            when (result) {
                is WorkspaceAccessResult.Success -> {
                    clearResult()
                    onConfirmed(pending.requestedWorkspaceId)
                }
                is WorkspaceAccessResult.Failure -> {
                    showError(
                        result.code.toUiCode(),
                        result.code.toUiMessage(),
                        attachFailure = true,
                    )
                }
                is WorkspaceAccessResult.NewThreadRequired -> {
                    showError(
                        WorkspacePickerErrorCodeUi.CONFLICT,
                        WorkspaceAccessErrorCode.CONFLICT.toUiMessage(),
                        attachFailure = true,
                    )
                }
            }
        }
    }

    private fun browseRoot(generation: Long, authority: Authority) {
        browseJob = browseJobFor(generation) {
            port.browsePrivilegedRoot(authority, WorkspacePickerPort.DEFAULT_PAGE_SIZE)
        }
    }

    private fun browseJobFor(
        generation: Long,
        operation: suspend () -> WorkspaceResult<WorkspaceDirectoryPage>,
    ): Job = viewModelScope.launch {
        _state.value = _state.value.copy(
            loadPhase = WorkspacePickerLoadPhaseUi.LOADING,
            loading = true,
            loadingMore = false,
            errorCode = null,
            errorMessage = null,
        )
        val result = try {
            withContext(Dispatchers.IO) { operation() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            WorkspaceResult.Failure(runtime.mobileagent.skills.tooling.ToolError(ToolErrorCode.UNKNOWN_OUTCOME))
        }
        if (!isCurrent(generation)) return@launch
        when (result) {
            is WorkspaceResult.Success -> applyPage(result.value, generation)
            is WorkspaceResult.Failure -> {
                if (currentPage == null) clearDirectoryState()
                val code = result.error.toPickerErrorCode()
                _state.value = _state.value.copy(
                    loadPhase = WorkspacePickerLoadPhaseUi.ERROR,
                    loading = false,
                    canUseCurrentDirectory = false,
                    errorCode = code,
                    errorMessage = code.toUiMessage(),
                )
            }
        }
    }

    private fun applyPage(page: WorkspaceDirectoryPage, generation: Long, append: Boolean = false) {
        currentPage = page
        currentContinuation = page.continuation
        val access = runCatching { port.directoryAccess(page) }
            .getOrDefault(WorkspacePickerDirectoryAccess(readable = true, writable = false))
        currentDirectoryReadable = access.readable
        currentDirectoryWritable = access.writable

        if (!append) {
            accumulatedEntries = ArrayList(page.entries)
            if (pendingStackIndex != null) {
                val index = pendingStackIndex!!
                while (directoryStack.size > index + 1) directoryStack.removeAt(directoryStack.lastIndex)
                pendingStackIndex = null
            } else if (pendingChildLabel != null) {
                directoryStack += DirectoryLevel(pendingChildLabel!!, page.current)
                pendingChildLabel = null
            } else if (directoryStack.isEmpty()) {
                directoryStack += DirectoryLevel("根目录", page.current)
            } else {
                directoryStack[directoryStack.lastIndex] = directoryStack.last().copy(handle = page.current)
            }
        } else {
            // Append only genuinely new names; a retried page must not
            // duplicate entries already shown.
            val known = accumulatedEntries.map { it.name to it.type }.toSet()
            page.entries.forEach { entry ->
                if (!known.contains(entry.name to entry.type)) accumulatedEntries += entry
            }
        }

        entryHandles.clear()
        // Keep the navigation surface predictable even when a provider does
        // not return a directory-first listing.  The opaque provider handle
        // remains attached to the VM-only entry map, never to UI state.
        val entryUi = accumulatedEntries
            .sortedWith(
                compareByDescending<WorkspaceDirectoryEntry> {
                    it.type == WorkspaceEntryType.DIRECTORY
                }.thenBy { it.name.lowercase() },
            )
            .mapIndexed { index, entry ->
                val id = "entry-$index"
                entryHandles[id] = entry
                WorkspacePickerEntryUi(
                    id = id,
                    name = entry.name,
                    directory = entry.type == WorkspaceEntryType.DIRECTORY,
                    sizeBytes = entry.sizeBytes,
                    readable = entry.readable && entry.handle != null,
                    writable = entry.writable,
                )
            }
        val rootLanding = directoryStack.size == 1
        val locations = if (rootLanding) {
            entryUi.asSequence()
                .filter { it.directory && isRecommendedPrivilegedRootLocation(it.name) }
                .sortedBy { privilegedRootLocationPriority(it.name) }
                .distinctBy { friendlyLocationLabel(it.name) }
                .map { entry ->
                    WorkspacePickerLocationUi(entry.id, friendlyLocationLabel(entry.name), entry.readable)
                }
                .toList()
        } else {
            emptyList()
        }
        val breadcrumbs = directoryStack.mapIndexed { index, level ->
            WorkspacePickerBreadcrumbUi("depth:$index", level.label, enabled = index != directoryStack.lastIndex)
        }
        val currentLabel = directoryStack.lastOrNull()?.label ?: "根目录"
        _state.value = _state.value.copy(
            loadPhase = WorkspacePickerLoadPhaseUi.CONTENT,
            loading = false,
            loadingMore = false,
            breadcrumbs = breadcrumbs,
            currentLabel = currentLabel,
            // The provider root can contain sensitive/system-only namespaces
            // such as /data, /proc and /apex.  Keep their opaque handles in
            // the VM for the explicit advanced flow, but do not expose a raw
            // root listing in the default picker.  Normal browsing begins at
            // a small set of friendly storage locations.
            entries = if (rootLanding) emptyList() else entryUi,
            locations = locations,
            listTruncated = page.truncated,
            canLoadMore = page.continuation != null && !rootLanding,
            currentDirectoryReadable = currentDirectoryReadable,
            currentDirectoryWritable = currentDirectoryWritable,
            canGoParent = directoryStack.size > 1 && page.parent != null,
            canUseCurrentDirectory = currentDirectoryReadable && !rootLanding,
            errorCode = null,
            errorMessage = null,
        )
    }

    private fun clearDirectoryState() {
        currentPage = null
        currentContinuation = null
        accumulatedEntries = ArrayList()
        currentDirectoryReadable = false
        currentDirectoryWritable = false
        directoryStack.clear()
        entryHandles.clear()
        pendingChildLabel = null
        pendingStackIndex = null
    }

    private fun showError(
        code: WorkspacePickerErrorCodeUi,
        message: String = code.toUiMessage(),
        attachFailure: Boolean = false,
    ) {
        _state.value = _state.value.copy(
            loadPhase = if (attachFailure) _state.value.loadPhase else WorkspacePickerLoadPhaseUi.ERROR,
            loading = false,
            attachPhase = if (attachFailure) WorkspacePickerAttachPhaseUi.ERROR else _state.value.attachPhase,
            errorCode = code,
            errorMessage = message,
        )
    }

    private fun nextGeneration(): Long {
        operationGeneration += 1
        return operationGeneration
    }

    private fun isCurrent(generation: Long): Boolean = generation == operationGeneration

    private fun modeFor(authority: WorkspacePickerAuthoritySnapshot): WorkspacePickerModeUi = when {
        authority.selectedAuthority == Authority.NONE -> WorkspacePickerModeUi.SAF_FALLBACK
        isElevated(authority.selectedAuthority) && authority.ready -> WorkspacePickerModeUi.PRIVILEGED
        isElevated(authority.selectedAuthority) -> WorkspacePickerModeUi.AUTHORITY_UNAVAILABLE
        else -> WorkspacePickerModeUi.AUTHORITY_UNAVAILABLE
    }

    private fun isElevated(authority: Authority): Boolean =
        authority == Authority.SHIZUKU || authority == Authority.WIRED_ADB

    private fun newWorkspaceId(): String =
        "picker-${UUID.randomUUID().toString().replace("-", "").take(32)}"

    private fun WorkspacePickerAuthoritySnapshot.toUi(): WorkspacePickerAuthorityUi =
        WorkspacePickerAuthorityUi(
            label = displayLabel(),
            statusLabel = status.toUiLabel(),
            selected = selectedAuthority != Authority.NONE,
            ready = ready,
        )

    private fun WorkspacePickerAuthoritySnapshot.displayLabel(): String = when (selectedAuthority) {
        Authority.SHIZUKU -> "Shizuku"
        Authority.WIRED_ADB -> "Wired ADB"
        Authority.NONE -> "未选择增强访问"
    }

    private fun WorkspacePickerAuthorityStatus.toUiLabel(): String = when (this) {
        WorkspacePickerAuthorityStatus.READY -> "已连接"
        WorkspacePickerAuthorityStatus.CONNECTING -> "正在连接"
        WorkspacePickerAuthorityStatus.OFFLINE -> "授权保留，当前未连接"
        WorkspacePickerAuthorityStatus.NOT_SELECTED -> "未选择"
        WorkspacePickerAuthorityStatus.UNSUPPORTED -> "不可用"
    }

    private fun WorkspaceAccessItem.toRecentUi(): WorkspacePickerRecentUi = WorkspacePickerRecentUi(
        id = workspaceId,
        displayName = displayName,
        authorityLabel = authority?.let { if (it == Authority.SHIZUKU) "Shizuku" else "Wired ADB" } ?: "普通文件夹授权",
        statusLabel = status.toUiLabel(),
        durablyAuthorized = durablyAuthorized,
        enabled = status != WorkspaceAccessStatus.REVOKED,
    )

    private fun WorkspaceAccessStatus.toUiLabel(): String = when (this) {
        WorkspaceAccessStatus.ACTIVE -> "可用"
        WorkspaceAccessStatus.GRANT_LOST -> "授权已失效"
        WorkspaceAccessStatus.REVOKED -> "已撤销"
        WorkspaceAccessStatus.DISABLED -> "已停用"
        WorkspaceAccessStatus.UNAVAILABLE -> "授权保留，当前未连接"
    }

    private fun WorkspaceAccessErrorCode.toUiCode(): WorkspacePickerErrorCodeUi = when (this) {
        WorkspaceAccessErrorCode.AUTHORITY_NOT_SELECTED -> WorkspacePickerErrorCodeUi.AUTHORITY_NOT_SELECTED
        WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE -> WorkspacePickerErrorCodeUi.AUTHORITY_UNAVAILABLE
        WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND -> WorkspacePickerErrorCodeUi.WORKSPACE_NOT_FOUND
        WorkspaceAccessErrorCode.CAPABILITY_DENIED -> WorkspacePickerErrorCodeUi.PERMISSION_DENIED
        WorkspaceAccessErrorCode.URI_PERMISSION_REQUIRED -> WorkspacePickerErrorCodeUi.URI_PERMISSION_REQUIRED
        WorkspaceAccessErrorCode.CONFLICT -> WorkspacePickerErrorCodeUi.CONFLICT
        WorkspaceAccessErrorCode.UNSUPPORTED -> WorkspacePickerErrorCodeUi.UNSUPPORTED
        WorkspaceAccessErrorCode.PERSISTENCE_FAILED -> WorkspacePickerErrorCodeUi.PERSISTENCE_FAILED
        WorkspaceAccessErrorCode.INVALID_REQUEST,
        WorkspaceAccessErrorCode.UNKNOWN_OUTCOME,
        -> WorkspacePickerErrorCodeUi.UNKNOWN_OUTCOME
    }

    private fun WorkspaceAccessErrorCode.toUiMessage(): String = toUiCode().toUiMessage()

    private fun runtime.mobileagent.skills.tooling.ToolError.toPickerErrorCode(): WorkspacePickerErrorCodeUi = when (code) {
        ToolErrorCode.AUTHORITY_NOT_GRANTED,
        ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE,
        -> WorkspacePickerErrorCodeUi.AUTHORITY_UNAVAILABLE
        ToolErrorCode.CAPABILITY_DENIED -> WorkspacePickerErrorCodeUi.PERMISSION_DENIED
        ToolErrorCode.WORKSPACE_NOT_FOUND -> WorkspacePickerErrorCodeUi.WORKSPACE_NOT_FOUND
        ToolErrorCode.CONFLICT,
        ToolErrorCode.INVALID_CURSOR,
        -> WorkspacePickerErrorCodeUi.CONFLICT
        else -> WorkspacePickerErrorCodeUi.UNKNOWN_OUTCOME
    }

    private fun WorkspacePickerErrorCodeUi.toUiMessage(): String = when (this) {
        WorkspacePickerErrorCodeUi.AUTHORITY_UNAVAILABLE -> "当前增强访问不可用。"
        WorkspacePickerErrorCodeUi.AUTHORITY_NOT_SELECTED -> "尚未选择增强访问。"
        WorkspacePickerErrorCodeUi.WORKSPACE_NOT_FOUND -> "工作区不存在或已移除。"
        WorkspacePickerErrorCodeUi.PERMISSION_DENIED -> "当前目录不可访问。"
        WorkspacePickerErrorCodeUi.URI_PERMISSION_REQUIRED -> "需要先完成文件夹授权。"
        WorkspacePickerErrorCodeUi.CONFLICT -> "工作区状态已变化，请刷新后重试。"
        WorkspacePickerErrorCodeUi.UNSUPPORTED -> "当前权限通道不支持此操作。"
        WorkspacePickerErrorCodeUi.PERSISTENCE_FAILED -> "工作区保存失败，请稍后重试。"
        WorkspacePickerErrorCodeUi.UNKNOWN_OUTCOME -> "工作区操作结果未知，请检查状态后再试。"
    }

    private fun friendlyLocationLabel(name: String): String = when (name.lowercase()) {
        "storage", "sdcard" -> "内部存储"
        "download", "downloads" -> "下载"
        "documents", "document" -> "文档"
        else -> name
    }

    private fun isRecommendedPrivilegedRootLocation(name: String): Boolean =
        name.equals("storage", ignoreCase = true) || name.equals("sdcard", ignoreCase = true)

    private fun privilegedRootLocationPriority(name: String): Int = when {
        name.equals("storage", ignoreCase = true) -> 0
        else -> 1
    }

    private fun pathHintForAttach(currentLabel: String): String {
        val trail = directoryStack.map { it.label }.filter {
            it.isNotBlank() && it != "根目录" && !it.equals("root", ignoreCase = true)
        }
        val joined = trail.joinToString("/")
        return when {
            joined.startsWith("/") -> joined
            joined.isNotBlank() -> "/$joined"
            else -> currentLabel
        }
    }

    private fun isReadable(entry: WorkspaceDirectoryEntry): Boolean = entry.readable && entry.handle != null

    private data class DirectoryLevel(
        val label: String,
        val handle: WorkspaceDirectoryHandle,
    )

    private var pendingChildLabel: String? = null
    private var pendingStackIndex: Int? = null
}
