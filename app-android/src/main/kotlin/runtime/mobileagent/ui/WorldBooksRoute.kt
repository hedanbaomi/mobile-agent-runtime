// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import runtime.mobileagent.WorldBooksViewModel
import runtime.mobileagent.feature.worldbooks.WorldBooksActions
import runtime.mobileagent.feature.worldbooks.WorldBooksScreen

@Composable
internal fun WorldBooksRoute(vm: WorldBooksViewModel, chinese: Boolean,
    onRequestEditorClose: () -> Unit,
    onEditorState: (String, Boolean, (() -> Unit)?) -> Unit,
    onShellDetail: (String, String?, (() -> Unit)?) -> Unit,
) {
    val state = vm.state.value
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), vm::importFiles)
    val folderImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), vm::importFolder)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), vm::exportTo)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reload() }
    LaunchedEffect(chinese) { vm.language(if (chinese) "zh-CN" else "en-US") }
    LaunchedEffect(state.editor?.dirty) { onEditorState(AppRoutes.WORLD_BOOKS, state.editor?.dirty == true, vm::closeEditor) }
    LaunchedEffect(state.editor != null, chinese) {
        onShellDetail(AppRoutes.WORLD_BOOKS, state.editor?.let { if (chinese) "编辑世界书" else "Edit world book" },
            state.editor?.let { onRequestEditorClose })
    }
    DisposableEffect(Unit) { onDispose { onShellDetail(AppRoutes.WORLD_BOOKS, null, null) } }
    WorldBooksScreen(state, WorldBooksActions(
        onQuery = vm::query, onCreate = vm::create, onSelect = vm::select, onToggleBook = vm::toggleBook,
        onBookName = vm::bookName, onBookDescription = vm::bookDescription, onBookEnabled = vm::bookEnabled,
        onAddEntry = vm::addEntry, onSelectEntry = vm::selectEntry, onEntryFieldChange = vm::entryField,
        onSettingFieldChange = vm::settingField, onDuplicateEntry = vm::duplicateEntry, onDeleteEntry = vm::deleteEntry,
        onSave = vm::save, onCloseEditor = vm::closeEditor, onDuplicateBook = vm::duplicateBook, onDeleteBook = vm::deleteBook,
        onImport = { if (vm.canImport()) importer.launch(IMPORT_TYPES) },
        onImportFolder = { if (vm.canImport()) folderImporter.launch(null) },
        onConfirmImport = vm::confirmImport, onCancelImport = vm::cancelImport,
        onImportPolicy = vm::importPolicy, onToggleImportItem = vm::toggleImportItem, onCancelBusy = vm::cancelBusy,
        onExport = { kind -> vm.prepareExport(kind) { exporter.launch(it) } },
        onPreviewInput = vm::previewInput, onPreview = vm::preview, onDismissError = vm::dismissError,
    ))
}

/** Providers label JSON and ZIP inconsistently; the stager sniffs content for unknown types. */
private val IMPORT_TYPES = arrayOf(
    "application/json", "text/*", "application/zip", "application/x-zip-compressed", "application/octet-stream",
)
