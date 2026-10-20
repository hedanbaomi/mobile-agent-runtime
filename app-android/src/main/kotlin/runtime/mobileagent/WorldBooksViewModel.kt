// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import runtime.mobileagent.agent.worldbook.WorldBookEngine
import runtime.mobileagent.domain.worldbook.*
import runtime.mobileagent.feature.worldbooks.*
import runtime.mobileagent.provider.SecretRedactor
import runtime.mobileagent.serialization.worldbook.WorldBookCodec

class WorldBooksViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MobileAgentApp
    private val repository get() = app.container.worldBooks
    val state = mutableStateOf(WorldBooksUiState())
    private var draft: WorldBookDocument? = null
    private var exportText: String? = null
    private val chinese get() = state.value.language == "zh-CN"

    /** Staged batch import awaiting confirmation; owns its private copies of the selected files. */
    private var importSession: WorldBookImportSession? = null
    private var busyJob: Job? = null
    @Volatile private var confirming = false
    @Volatile private var stopRequested = false

    init {
        reload()
        val cache = app.cacheDir
        viewModelScope.launch(Dispatchers.IO) { runCatching { WorldBookBatchStager.sweep(cache) } }
    }

    fun language(language: String) {
        if (state.value.language == language) return
        state.value = state.value.copy(language = language)
        rebuildEditor(preserveValues = true)
    }

    fun reload() = work { refreshBooks() }
    fun query(value: String) { state.value = state.value.copy(query = value) }
    fun dismissError() { state.value = state.value.copy(error = null) }
    fun previewInput(value: String) { state.value = state.value.copy(previewInput = value) }

    fun create() {
        if (state.value.loading) return
        stage(WorldBookDocument(name = if (chinese) "新世界书" else "New world book", entries = emptyList()), null, 0, true)
    }

    fun select(id: String) = work {
        val stored = withContext(Dispatchers.IO) { checkNotNull(repository.get(id)) { "World book was removed" } }
        stage(stored.document, id, stored.summary.revision, stored.summary.enabled)
    }

    fun bookName(value: String) = edit { it.copy(name = value) }
    fun bookDescription(value: String) = edit { it.copy(description = value) }
    fun bookEnabled(value: Boolean) = edit { it.copy(enabled = value) }
    fun entryField(key: String, value: String) = edit { editor ->
        editor.copy(entryFields = editor.entryFields.map { if (it.key == key) it.copy(value = value) else it },
            entries = editor.entries.map { entry -> if (entry.uid != editor.selectedEntryUid) entry else when (key) {
                "enabled" -> entry.copy(enabled = value.toBooleanStrictOrNull() ?: entry.enabled)
                "constant" -> entry.copy(constant = value.toBooleanStrictOrNull() ?: entry.constant)
                else -> entry
            } })
    }
    fun settingField(key: String, value: String) = edit { editor ->
        editor.copy(settingsFields = editor.settingsFields.map { if (it.key == key) it.copy(value = value) else it })
    }

    fun selectEntry(uid: String) = localEdit {
        draft = flushed()
        state.value = state.value.copy(editor = state.value.editor?.copy(selectedEntryUid = uid))
        rebuildEditor()
    }

    fun addEntry() = localEdit {
        val document = flushed()
        val uid = nextUid(document)
        draft = document.copy(entries = document.entries + WorldBookEntry(uid = uid, title = if (chinese) "新条目" else "New entry"))
        state.value = state.value.copy(editor = state.value.editor?.copy(selectedEntryUid = uid, dirty = true))
        rebuildEditor()
    }

    fun duplicateEntry() = localEdit {
        val document = flushed()
        val selected = document.entries.first { it.uid == state.value.editor?.selectedEntryUid }
        val copy = selected.copy(uid = nextUid(document), title = selected.title + if (chinese) " 副本" else " copy", originalKey = null)
        draft = document.copy(entries = document.entries + copy)
        state.value = state.value.copy(editor = state.value.editor?.copy(selectedEntryUid = copy.uid, dirty = true))
        rebuildEditor()
    }

    fun deleteEntry() = localEdit {
        val document = flushed()
        val entries = document.entries.filterNot { it.uid == state.value.editor?.selectedEntryUid }
        draft = document.copy(entries = entries)
        state.value = state.value.copy(editor = state.value.editor?.copy(selectedEntryUid = entries.firstOrNull()?.uid, dirty = true))
        rebuildEditor()
    }

    fun save() = work {
        val editor = checkNotNull(state.value.editor)
        val document = flushed()
        val id = editor.id
        val stored = withContext(Dispatchers.IO) {
            if (id == null) repository.save(document, enabled = editor.enabled)
            else repository.save(document, id, editor.revision, editor.enabled)
        }
        stage(stored.document, stored.summary.id, stored.summary.revision, stored.summary.enabled)
        refreshBooks()
        status("已保存；新内容从下一轮发送生效。", "Saved. The new content takes effect on the next send.")
    }

    fun toggleBook(id: String, enabled: Boolean) = work {
        val book = state.value.books.first { it.id == id }
        withContext(Dispatchers.IO) { repository.setEnabled(id, enabled, book.revision) }
        refreshBooks()
    }

    fun duplicateBook() = work {
        val document = flushed()
        val copy = document.copy(name = document.name.take(240) + if (chinese) " 副本" else " copy")
        val stored = withContext(Dispatchers.IO) { repository.save(copy, enabled = state.value.editor?.enabled ?: true) }
        stage(stored.document, stored.summary.id, stored.summary.revision, stored.summary.enabled)
        refreshBooks()
    }

    fun deleteBook() = work {
        val editor = checkNotNull(state.value.editor)
        editor.id?.let { id -> withContext(Dispatchers.IO) { repository.delete(id, editor.revision) } }
        closeDraft()
        refreshBooks()
        status("世界书已删除；已有聊天内容保留。", "World book removed. Existing chat content is retained.")
    }

    fun closeEditor() { if (!state.value.loading) closeDraft() }
    private fun closeDraft() { draft = null; state.value = state.value.copy(editor = null, preview = null) }

    fun canImport(): Boolean {
        if (state.value.editor?.dirty == true) {
            state.value = state.value.copy(error = if (chinese) "请先保存或关闭当前编辑，再导入世界书。" else "Save or close the current draft before importing.")
            return false
        }
        return !state.value.loading
    }

    /** One picked file; kept for callers that hand over a single document. */
    fun importFrom(uri: Uri?) { if (uri != null) importFiles(listOf(uri)) }

    fun importFiles(uris: List<Uri>) {
        if (uris.isNotEmpty() && canImport()) beginImport { stager -> stager.stageFiles(uris.map { it.toString() }) }
    }

    fun importFolder(uri: Uri?) {
        if (uri != null && canImport()) beginImport { stager -> stager.stageFolder(folderRootNode(app.contentResolver, uri)) }
    }

    /** Copies, classifies and parses the selection off the main thread; nothing is written. */
    private fun beginImport(select: (WorldBookBatchStager) -> WorldBookStaging) = work(cancellable = true) {
        discardImport()
        val zh = chinese
        var made: WorldBookImportSession? = null
        val prepared = try {
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                val checkpoint = { context.ensureActive() }
                val stager = WorldBookBatchStager.forResolver(app.contentResolver, app.cacheDir, zh, checkpoint, ::progress)
                WorldBookImportSession.prepare(select(stager), repository, zh, checkpoint, ::progress).also { made = it }
            }
        } catch (cancelled: CancellationException) {
            // Cancelled just as preparation finished: the result is dropped, so drop its staged copies too.
            made?.let { session -> Thread { session.discard() }.start() }
            throw cancelled
        }
        WorldBookImportSession.identicalMessage(prepared, zh)?.let { message ->
            Thread { prepared.discard() }.start()
            state.value = state.value.copy(status = message)
            return@work
        }
        importSession = prepared
        publishImport()
        state.value = state.value.copy(status = "")
    }

    fun importPolicy(policy: WorldBookConflictPolicy) {
        val session = importSession ?: return
        if (!state.value.loading) { session.replan(policy); publishImport() }
    }

    fun toggleImportItem(key: String) {
        val session = importSession ?: return
        if (!state.value.loading && session.toggle(key)) publishImport()
    }

    private fun publishImport() {
        val session = importSession ?: return
        state.value = state.value.copy(pendingImport = worldBookImportUi(session.plan, session.staging, chinese), error = null)
    }

    /** Each selected book commits on its own; "Stop" lets the book being written finish. */
    fun confirmImport() {
        val session = importSession ?: return
        if (!session.plan.canConfirm || state.value.loading) return
        state.value = state.value.copy(pendingImport = null)
        work(cancellable = true) {
            confirming = true
            stopRequested = false
            val zh = chinese
            // Everything after the first write must finish: a second "Stop" or leaving the screen may
            // not drop the summary or report committed books as not written.
            val outcome = try {
                withContext(Dispatchers.IO + NonCancellable) { session.writeAndDiscard(repository, zh, { stopRequested }, ::progress) }
                    .also { withContext(NonCancellable) { discardImport(); refreshBooks() } }
            } finally {
                confirming = false
            }
            val single = outcome.single
            if (single != null) {
                stage(single.document, single.summary.id, single.summary.revision, single.summary.enabled)
                if (outcome.replaced == 1) status("已覆盖；新内容从下一轮发送生效。", "Replaced. The new content takes effect on the next send.")
                else status("已导入；在 Agent 或对话中选择后才会发送。", "Imported. Select it in an Agent or chat before it is sent.")
            } else {
                state.value = state.value.copy(status = outcome.summary(zh), error = outcome.failures())
            }
        }
    }

    fun cancelImport() { if (!state.value.loading) discardImport() }

    /** Stops scanning immediately; while writing, the current book finishes first. */
    fun cancelBusy() {
        if (!state.value.cancellable) return
        if (confirming) {
            stopRequested = true
            status("将在当前这本写入完成后停止…", "Stopping after the current book is written…")
        } else {
            busyJob?.cancel()
        }
    }

    private fun discardImport() {
        val previous = importSession ?: return
        importSession = null
        state.value = state.value.copy(pendingImport = null)
        Thread { previous.discard() }.start()
    }

    /** Progress from IO threads is applied in order on the main thread, never after the work ends. */
    private fun progress(message: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (state.value.loading) state.value = state.value.copy(status = message)
        }
    }

    override fun onCleared() {
        stopRequested = true
        // A confirm that is still writing owns the session and discards it when it finishes.
        if (!confirming) importSession?.let { session -> Thread { session.discard() }.start() }
        super.onCleared()
    }

    fun prepareExport(kind: WorldBookExportKind, ready: (String) -> Unit) = work {
        val document = flushed()
        exportText = withContext(Dispatchers.IO) { WorldBookCodec.encode(document, when (kind) {
            WorldBookExportKind.NATIVE -> WorldBookFormat.NATIVE
            WorldBookExportKind.SILLY_TAVERN -> WorldBookFormat.SILLY_TAVERN
            WorldBookExportKind.ORIGINAL -> document.format
        }) }
        ready("worldbook.json")
    }

    fun exportTo(uri: Uri?) {
        val text = exportText
        exportText = null
        if (uri == null || text == null) return
        work {
            withContext(Dispatchers.IO) { WorldBookFileIO.write(app.contentResolver, uri, text) }
            status("已导出世界书。", "World book exported.")
        }
    }

    fun preview() = work {
        val document = flushed()
        val input = state.value.previewInput
        val result = withContext(Dispatchers.Default) {
            WorldBookCodec.decode(WorldBookCodec.encodeNative(document))
            val budget = WorldBookRunPreparation.budget(document.settings, PREVIEW_WINDOW)
            val scan = WorldBookScanRequest(listOf(WorldBookSource("preview", 1, document)), listOf(WorldBookScanMessage(input)), settings = document.settings)
            WorldBookEngine.evaluate(scan, budget, PREVIEW_WINDOW) { it.toByteArray(Charsets.UTF_8).size + 96 }
        }
        state.value = state.value.copy(preview = worldBookPreviewUi(result, chinese).copy(warnings = result.warnings +
            if (chinese) "离线预览使用固定的估算窗口；实际发送按所选模型预算检查。" else "Offline preview uses a fixed estimated window. Actual requests use the selected model budget."))
    }

    private fun stage(document: WorldBookDocument, id: String?, revision: Int, enabled: Boolean) {
        draft = document
        state.value = state.value.copy(editor = WorldBookEditorUi(id, revision, document.name, document.description, enabled,
            selectedEntryUid = document.entries.firstOrNull()?.uid), error = null, preview = null)
        rebuildEditor()
    }

    private fun flushed(): WorldBookDocument {
        val document = checkNotNull(draft) { "Choose a world book" }
        val editor = checkNotNull(state.value.editor)
        return document.copy(name = editor.name, description = editor.description,
            settings = WorldBookForms.applySettings(document.settings, editor.settingsFields.associate { it.key to it.value }),
            entries = document.entries.map { if (it.uid == editor.selectedEntryUid)
                WorldBookForms.applyEntry(it, editor.entryFields.associate { field -> field.key to field.value }) else it })
    }

    private fun rebuildEditor(preserveValues: Boolean = false) {
        val document = draft ?: return
        val editor = state.value.editor ?: return
        val selected = document.entries.firstOrNull { it.uid == editor.selectedEntryUid }
        val fields = selected?.let { WorldBookForms.entryFields(it, chinese) }.orEmpty()
        val settings = WorldBookForms.settingsFields(document.settings, chinese)
        state.value = state.value.copy(editor = editor.copy(
            entries = document.entries.map { WorldBookEntryUi(it.uid, it.title.ifBlank { it.uid }, it.keys.joinToString(", "), it.enabled, it.constant) },
            entryFields = preserve(fields, editor.entryFields, preserveValues), settingsFields = preserve(settings, editor.settingsFields, preserveValues)))
    }

    private fun preserve(fresh: List<WorldBookFieldUi>, old: List<WorldBookFieldUi>, keep: Boolean) =
        fresh.map { field -> if (keep) field.copy(value = old.firstOrNull { it.key == field.key }?.value ?: field.value) else field }

    private fun edit(change: (WorldBookEditorUi) -> WorldBookEditorUi) {
        if (state.value.loading) return
        state.value.editor?.let { state.value = state.value.copy(editor = change(it).copy(dirty = true), error = null) }
    }
    private fun localEdit(block: () -> Unit) {
        if (state.value.loading) return
        try { block() } catch (error: Exception) { fail(error) }
    }
    private fun nextUid(document: WorldBookDocument): String {
        val taken = document.entries.map { it.uid }.toSet()
        return generateSequence(0) { it + 1 }.map(Int::toString).first { it !in taken }
    }
    private suspend fun refreshBooks() {
        val books = withContext(Dispatchers.IO) { repository.list().map(::worldBookCardUi) }
        state.value = state.value.copy(books = books)
    }
    private fun status(zh: String, en: String) { state.value = state.value.copy(status = if (chinese) zh else en, error = null) }
    private fun fail(error: Exception) { state.value = state.value.copy(error = SecretRedactor.redact(error.message ?: "World book operation failed").take(600)) }
    private fun work(cancellable: Boolean = false, block: suspend () -> Unit) {
        if (state.value.loading) return
        state.value = state.value.copy(loading = true, cancellable = cancellable, error = null)
        busyJob = viewModelScope.launch {
            try { block() } catch (cancelled: CancellationException) {
                // Only the preview scan is cancelled by the user; writing stops between books instead.
                if (viewModelScope.isActive) status("已取消导入，未写入任何内容。", "Import cancelled. Nothing was written.")
                throw cancelled
            }
            catch (error: Exception) { fail(error) }
            finally { state.value = state.value.copy(loading = false, cancellable = false) }
        }
    }
    companion object { private const val PREVIEW_WINDOW = 16_384 }
}
