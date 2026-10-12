// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.graphics.Bitmap
import androidx.compose.runtime.MutableState
import runtime.mobileagent.domain.ChatImageUpload
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.feature.chat.ChatPendingImageUi
import runtime.mobileagent.feature.chat.ChatUiState

/** In-memory picker bytes and their truthful, conversation-scoped composer projection. */
internal class ChatImageDraft(private val state: MutableState<ChatUiState>) {
    class Pending(val upload: ChatImageUpload, val ui: ChatPendingImageUi)
    private var draft: Pair<String?, List<Pending>>? = null
        set(value) {
            field = value?.takeIf { it.second.isNotEmpty() }
            state.value = state.value.copy(pendingImages = selected().map { it.ui })
        }

    private fun selected() = draft?.takeIf { it.first == state.value.selectedSessionId }?.second.orEmpty()
    fun uploads() = selected().map { it.upload }
    fun hasSendableInput(text: String, uploads: List<ChatImageUpload>) =
        (text.isNotBlank() || uploads.isNotEmpty()) && !state.value.streaming
    fun pending() = draft?.second.orEmpty()
    fun sync() { if (draft?.first != state.value.selectedSessionId) draft = null }
    fun clear() { if (!state.value.streaming) draft = null }
    fun detach() { draft = null }
    fun persisted(conversationId: String) { if (draft?.first == conversationId) draft = null }

    fun adopt(conversationId: String, uploads: List<ChatImageUpload>, previous: List<Pending>) {
        draft = conversationId to uploads.map { upload ->
            previous.firstOrNull { it.upload === upload }
                ?: Pending(upload, ChatPendingImageUi(EntityId.random().value, upload.mediaType, upload.byteLength, null))
        }
    }

    fun attach(uploads: List<Pair<ChatImageUpload, Bitmap?>>, rejected: List<String>, forSession: String?, forAgent: String?) {
        if (uploads.isEmpty() && rejected.isEmpty()) return
        if (state.value.streaming || state.value.selectedSessionArchived) {
            state.value = state.value.copy(status = "当前正在运行或会话已归档，所选图片未添加。", statusKind = "error")
            return
        }
        if (forSession != state.value.selectedSessionId || forAgent != state.value.selectedAgentId) {
            state.value = state.value.copy(status = "已切换会话，所选图片未添加。", statusKind = "error")
            return
        }
        val current = selected()
        val room = ChatImageUpload.MAX_IMAGES_PER_MESSAGE - current.size
        val accepted = uploads.take(room.coerceAtLeast(0)).map { (upload, thumbnail) ->
            Pending(upload, ChatPendingImageUi(EntityId.random().value, upload.mediaType, upload.byteLength, thumbnail))
        }
        val messages = rejected.toMutableList()
        if (uploads.size > accepted.size) {
            messages += "每条消息最多 ${ChatImageUpload.MAX_IMAGES_PER_MESSAGE} 张图片，已忽略 ${uploads.size - accepted.size} 张。"
        }
        if (accepted.isNotEmpty()) draft = state.value.selectedSessionId to (current + accepted)
        if (messages.isNotEmpty()) state.value = state.value.copy(status = messages.joinToString(" "), statusKind = "error")
    }

    fun remove(id: String) {
        if (state.value.streaming) return
        val current = draft ?: return
        draft = current.first to current.second.filterNot { it.ui.id == id }
    }
}
