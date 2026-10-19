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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import runtime.mobileagent.agent.worldbook.WorldBookEngine
import runtime.mobileagent.domain.worldbook.*
import runtime.mobileagent.feature.worldbooks.*
import runtime.mobileagent.provider.SecretRedactor
import runtime.mobileagent.serialization.worldbook.WorldBookCodec
import runtime.mobileagent.serialization.worldbook.WorldBookImportResult

class WorldBooksViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MobileAgentApp
    private val repository get() = app.container.worldBooks
    val state = mutableStateOf(WorldBooksUiState())
    private var draft: WorldBookDocument? = null
    private var pending: WorldBookImportResult? = null
    private var exportText: String? = null
    private val chinese get() = state.value.language == "zh-CN"

    init { reload() }

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

    fun importFrom(uri: Uri?) {
        if (uri == null || !canImport()) return
        work {
            pending = withContext(Dispatchers.IO) {
                val (raw, name) = WorldBookFileIO.read(app.contentResolver, uri)
                WorldBookCodec.decode(raw, name)
            }
            val parsed = checkNotNull(pending)
            state.value = state.value.copy(pendingImport = WorldBookImportUi(parsed.document.name, parsed.document.entries.size,
                parsed.notices.map { it.message }), error = null)
        }
    }

    fun confirmImport() = work {
        val parsed = checkNotNull(pending) { "Import preview expired" }
        val stored = withContext(Dispatchers.IO) { repository.save(parsed.document) }
        pending = null
        state.value = state.value.copy(pendingImport = null)
        stage(stored.document, stored.summary.id, stored.summary.revision, stored.summary.enabled)
        refreshBooks()
        status("已导入；在 Agent 或对话中选择后才会发送。", "Imported. Select it in an Agent or chat before it is sent.")
    }

    fun cancelImport() { pending = null; state.value = state.value.copy(pendingImport = null) }

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
    private fun work(block: suspend () -> Unit) {
        if (state.value.loading) return
        state.value = state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(error) }
            finally { state.value = state.value.copy(loading = false) }
        }
    }
    companion object { private const val PREVIEW_WINDOW = 16_384 }
}
