// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.runtime.mutableStateOf
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ChatImageUpload
import runtime.mobileagent.feature.chat.ChatUiState

class ChatImageDraftSynchronizationTest {
    private val upload = ChatImageUpload("GIF89a".toByteArray(), "image/gif")

    @Test fun sameOwnerHistoryProjectionCannotHideImagesThatWillStillBeSent() {
        val state = mutableStateOf(ChatUiState(selectedSessionId = "first", selectedAgentId = "agent", loading = true))
        val draft = ChatImageDraft(state)
        draft.attach(List(9) { upload to null }, emptyList(), "first", "agent")
        val ids = state.value.pendingImages.map { it.id }
        assertEquals(8, ids.size)
        state.value = state.value.copy(loading = false, pendingImages = emptyList())
        draft.sync()
        assertEquals(ids, state.value.pendingImages.map { it.id })
        assertEquals(8, draft.uploads().size)
        draft.remove(ids.first())
        state.value = state.value.copy(pendingImages = emptyList())
        draft.sync()
        assertEquals(ids.drop(1), state.value.pendingImages.map { it.id })
        assertEquals(7, draft.uploads().size)
    }

    @Test fun changedOwnerStillDropsDraftAndRejectsLatePickerWithoutCrossConversationImages() {
        val state = mutableStateOf(ChatUiState(selectedSessionId = "first", selectedAgentId = "agent"))
        val draft = ChatImageDraft(state)
        draft.attach(listOf(upload to null), emptyList(), "first", "agent")
        state.value = state.value.copy(selectedSessionId = "second")
        draft.sync()
        assertTrue(state.value.pendingImages.isEmpty())
        assertTrue(draft.uploads().isEmpty())
        draft.attach(listOf(upload to null), emptyList(), "first", "agent")
        assertTrue(state.value.pendingImages.isEmpty())
        assertTrue(state.value.status.contains("未添加"))
    }

    @Test fun clearedOrPersistedDraftCannotReappearInAnOlderHistoryProjection() {
        val state = mutableStateOf(ChatUiState(selectedSessionId = "first", selectedAgentId = "agent"))
        val draft = ChatImageDraft(state)
        for (persisted in listOf(false, true)) {
            draft.attach(listOf(upload to null), emptyList(), "first", "agent")
            val stale = state.value
            if (persisted) draft.persisted("first") else draft.clear()
            state.value = stale
            draft.sync()
            assertTrue(state.value.pendingImages.isEmpty())
            assertTrue(draft.uploads().isEmpty())
        }
    }
}
