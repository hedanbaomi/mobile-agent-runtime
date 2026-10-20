// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import runtime.mobileagent.domain.ImportedCharacterCard
import runtime.mobileagent.domain.worldbook.WorldBookDocument
import runtime.mobileagent.feature.agents.CharacterCardImportUi
import runtime.mobileagent.serialization.CharacterCardSupport
import runtime.mobileagent.serialization.charactercard.CharacterCardCodec
import runtime.mobileagent.serialization.charactercard.CharacterCardImportException
import runtime.mobileagent.serialization.charactercard.CharacterCardImportResult

internal data class CharacterCardImportState(
    val preview: CharacterCardImportUi? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** UI-owned drafts only. Late picker/IO results cannot replace another editor; no database writes. */
internal class CharacterCardImportController(
    private val app: MobileAgentApp,
    private val scope: CoroutineScope,
    private val owner: () -> Long,
    private val eligible: () -> Boolean,
    private val applyDraft: (String, String, ImportedCharacterCard, WorldBookDocument?) -> Unit,
) {
    val state = mutableStateOf(CharacterCardImportState())
    private var generation = 0L
    private var requestedOwner = 0L
    private var pending: CharacterCardImportResult? = null
    private var job: Job? = null
    private var chinese = true

    fun beginPicker(zh: Boolean): Long? {
        if (!eligible()) return null
        cancel()
        chinese = zh
        requestedOwner = owner()
        state.value = CharacterCardImportState(busy = true)
        return generation
    }

    fun picked(uri: Uri?, token: Long?) {
        if (token == null || !owns(token)) return
        if (uri == null) { cancel(); return }
        job = scope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    val (bytes, name) = CharacterCardFileIO.read(app.contentResolver, uri)
                    CharacterCardCodec.decode(bytes, name)
                }
                if (!owns(token)) return@launch
                pending = parsed
                state.value = CharacterCardImportState(preview = preview(parsed))
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { if (owns(token)) state.value = CharacterCardImportState(error = error(failure)) }
        }
    }

    fun change(value: CharacterCardImportUi) {
        if (state.value.busy || pending == null) return
        state.value = state.value.copy(preview = value, error = null)
    }

    fun confirm() {
        if (state.value.busy || !eligible()) return
        val parsed = pending ?: return
        val selected = state.value.preview ?: return
        val token = generation
        if (!owns(token)) { cancel(); return }
        state.value = state.value.copy(busy = true, error = null)
        job = scope.launch {
            try {
                val draft = withContext(Dispatchers.IO) { prepareDraft(parsed, selected) }
                if (owns(token)) applyDraft(selected.name.trim(), draft.prompt, draft.card, draft.book)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (owns(token)) state.value = state.value.copy(busy = false, error = error(failure))
            }
        }
    }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        pending = null
        state.value = CharacterCardImportState()
    }

    private fun owns(token: Long): Boolean = token == generation && owner() == requestedOwner && eligible()

    private fun preview(result: CharacterCardImportResult): CharacterCardImportUi {
        val doc = result.document
        val greetings = (listOf(doc.firstMessage) + doc.alternateGreetings).filter { it.isNotBlank() }.distinct()
        return CharacterCardImportUi(doc.name, "${doc.format} · ${result.container}", doc.creator,
            doc.description.take(8000), result.notices.map { it.message }, greetings = greetings,
            useGreeting = greetings.isNotEmpty(), hasWorldBook = result.worldBook != null,
            worldBookName = result.worldBook?.name.orEmpty().ifBlank { doc.name + " · World book" }.take(256),
            worldBookEntries = result.worldBook?.entries?.size ?: 0)
    }

    private data class Draft(val prompt: String, val card: ImportedCharacterCard, val book: WorldBookDocument?)

    private fun prepareDraft(parsed: CharacterCardImportResult, selected: CharacterCardImportUi): Draft {
        require(selected.name.isNotBlank() && selected.name.trim().length <= 512) { "character-card.name-limit" }
        val greetings = (listOf(parsed.document.firstMessage) + parsed.document.alternateGreetings).filter { it.isNotBlank() }.distinct()
        val greeting = if (selected.useGreeting && greetings.isNotEmpty()) {
            requireNotNull(greetings.getOrNull(selected.greetingIndex)) { "character-card.greeting-selection" }
        } else null
        val card = ImportedCharacterCard(parsed.document, selected.userName.trim(), greeting)
        CharacterCardSupport.validate(card)
        val book = parsed.worldBook?.takeIf { selected.importWorldBook }?.let {
            require(selected.worldBookName.isNotBlank() && selected.worldBookName.trim().length <= 256) { "character-card.world-book-name" }
            it.copy(name = selected.worldBookName.trim())
        }
        return Draft(CharacterCardSupport.prompt(card), card, book)
    }

    private fun error(failure: Exception): String {
        val code = (failure as? CharacterCardImportException)?.code ?: "invalid-or-unreadable"
        return if (chinese) "无法导入角色卡（$code）。请检查文件格式、大小和填写内容；未保存任何资源。"
        else "Cannot import character card ($code). Check the file, size and entered values; no resources were saved."
    }
}
