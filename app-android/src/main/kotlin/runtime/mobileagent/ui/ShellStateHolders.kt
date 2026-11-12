// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import runtime.mobileagent.R

internal class ShellDraftState(
    private val unsavedDialogState: MutableState<Boolean>,
    private val pendingRouteState: MutableState<String?>,
    private val editorOwnerState: MutableState<String?>,
    private val editorDirtyState: MutableState<Boolean>,
    private val editorDiscardState: MutableState<(() -> Unit)?>,
) {
    var unsavedDialog: Boolean
        get() = unsavedDialogState.value
        set(value) { unsavedDialogState.value = value }

    var pendingRoute: String?
        get() = pendingRouteState.value
        set(value) { pendingRouteState.value = value }

    var editorOwner: String?
        get() = editorOwnerState.value
        set(value) { editorOwnerState.value = value }

    var editorDirty: Boolean
        get() = editorDirtyState.value
        set(value) { editorDirtyState.value = value }

    var editorDiscard: (() -> Unit)?
        get() = editorDiscardState.value
        set(value) { editorDiscardState.value = value }

    fun isEditorActiveFor(route: String): Boolean =
        editorOwner == route && editorDirty

    fun register(owner: String, dirty: Boolean, discard: (() -> Unit)?, currentRoute: String) {
        if (owner != currentRoute) return
        editorOwner = owner
        editorDirty = dirty
        editorDiscard = discard
    }

    fun requestRoute(target: String, currentRoute: String, onNavigate: (String) -> Unit) {
        if (target !in allAppRoutes || target == currentRoute) return
        if (isEditorActiveFor(currentRoute)) {
            pendingRoute = target
            unsavedDialog = true
        } else {
            clear()
            onNavigate(target)
        }
    }

    fun requestClose(currentRoute: String) {
        if (isEditorActiveFor(currentRoute)) {
            pendingRoute = null
            unsavedDialog = true
        } else {
            editorDiscard?.invoke()
        }
    }

    fun discard(onNavigate: (String) -> Unit) {
        val target = pendingRoute
        unsavedDialog = false
        pendingRoute = null
        editorDiscard?.invoke()
        clear()
        target?.let(onNavigate)
    }

    fun clear() {
        editorOwner = null
        editorDirty = false
        editorDiscard = null
    }
}

@Composable
internal fun rememberShellDraftState(): ShellDraftState {
    val unsaved = rememberSaveable { mutableStateOf(false) }
    val pending = rememberSaveable { mutableStateOf<String?>(null) }
    val owner = rememberSaveable { mutableStateOf<String?>(null) }
    val dirty = rememberSaveable { mutableStateOf(false) }
    val discard = remember { mutableStateOf<(() -> Unit)?>(null) }
    return remember(unsaved, pending, owner, dirty, discard) {
        ShellDraftState(unsaved, pending, owner, dirty, discard)
    }
}

internal class ShellDetailState(
    private val detailOwnerState: MutableState<String?>,
    private val detailTitleState: MutableState<String?>,
    private val detailBackState: MutableState<(() -> Unit)?>,
) {
    var owner: String?
        get() = detailOwnerState.value
        set(value) { detailOwnerState.value = value }

    var title: String?
        get() = detailTitleState.value
        set(value) { detailTitleState.value = value }

    var onBack: (() -> Unit)?
        get() = detailBackState.value
        set(value) { detailBackState.value = value }

    fun register(newOwner: String, newTitle: String?, back: (() -> Unit)?, currentRoute: String) {
        if (newOwner != currentRoute) return
        owner = newOwner.takeIf { newTitle != null && back != null }
        title = newTitle
        onBack = back
    }

    fun clear() {
        owner = null
        title = null
        onBack = null
    }
}

@Composable
internal fun rememberShellDetailState(): ShellDetailState {
    val owner = rememberSaveable { mutableStateOf<String?>(null) }
    val title = rememberSaveable { mutableStateOf<String?>(null) }
    val back = remember { mutableStateOf<(() -> Unit)?>(null) }
    return remember(owner, title, back) { ShellDetailState(owner, title, back) }
}

internal class ShellWorkspacePickerState(
    private val openState: MutableState<Boolean>,
    private val agentIdState: MutableState<String?>,
    private val threadIdState: MutableState<String?>,
    private val labelState: MutableState<String>,
) {
    var isOpen: Boolean
        get() = openState.value
        set(value) { openState.value = value }

    var agentId: String?
        get() = agentIdState.value
        set(value) { agentIdState.value = value }

    var threadId: String?
        get() = threadIdState.value
        set(value) { threadIdState.value = value }

    var label: String
        get() = labelState.value
        set(value) { labelState.value = value }

    fun open(newAgentId: String?, newThreadId: String?, newLabel: String) {
        agentId = newAgentId
        threadId = newThreadId
        label = newLabel
        isOpen = true
    }

    fun close() {
        isOpen = false
    }
}

@Composable
internal fun rememberShellWorkspacePickerState(): ShellWorkspacePickerState {
    val defaultLabel = stringResource(R.string.shell_current_agent)
    val open = rememberSaveable { mutableStateOf(false) }
    val agentId = rememberSaveable { mutableStateOf<String?>(null) }
    val threadId = rememberSaveable { mutableStateOf<String?>(null) }
    val label = rememberSaveable { mutableStateOf(defaultLabel) }
    return remember(open, agentId, threadId, label) {
        ShellWorkspacePickerState(open, agentId, threadId, label)
    }
}
