// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*
import runtime.mobileagent.feature.chat.*
import runtime.mobileagent.knowledge.Citation

class ChatRunInputPreparationTest {
    @Test fun unknownOutcomeRestoresDraftAndRequiresExplicitRetry() = runBlocking {
        val ui = Projection()
        val result = prepareChatRunInput("draft", ui.owner, ui) { ChatPreflightResult.Unknown("prior-run") }
        assertNull(result)
        assertEquals("prior-run", ui.unknownRetry.value)
        assertEquals("draft", ui.state.value.input)
        assertFalse(ui.state.value.streaming)
        assertEquals(1, ui.draftSaves)
    }

    @Test fun lateUnknownCannotProjectIntoAnotherSelectedConversation() = runBlocking {
        val ui = Projection()
        prepareChatRunInput("old draft", ui.owner, ui) {
            ui.state.value = ChatUiState(selectedSessionId = "other", input = "new draft")
            ChatPreflightResult.Unknown("prior-run")
        }
        assertNull(ui.unknownRetry.value)
        assertEquals("new draft", ui.state.value.input)
        assertEquals(0, ui.draftSaves)
    }

    @Test fun failedPersistedUserDoesNotRestoreAlreadyConsumedDraft() = runBlocking {
        val ui = Projection()
        ui.state.value = ui.state.value.copy(input = "")
        prepareChatRunInput("sent draft", ui.owner, ui) {
            ChatPreflightResult.Failed(IllegalStateException("configuration"), userMessagePersisted = true)
        }
        assertEquals("", ui.state.value.input)
        assertEquals("configuration", ui.state.value.error)
        assertEquals(0, ui.draftSaves)
    }

    @Test fun rejectedBeforeUserPersistenceRestoresDraft() = runBlocking {
        val ui = Projection()
        prepareChatRunInput("saved draft", ui.owner, ui) {
            ChatPreflightResult.Failed(IllegalStateException("admission"), userMessagePersisted = false)
        }
        assertEquals("saved draft", ui.state.value.input)
        assertEquals(1, ui.draftSaves)
    }

    private class Projection : ChatExecutionUi {
        val owner = ChatRunOwner("run", "conversation", 1)
        override val state = mutableStateOf(ChatUiState(selectedSessionId = owner.conversationId, streaming = true))
        override val unknownRetry = mutableStateOf<String?>(null)
        override val inputRevision = 0L
        override val citations = emptyMap<String, Pair<Citation, String>>()
        override val imageDraft = ChatImageDraft(state)
        override val requestPreviews = ChatRequestPreviews()
        var draftSaves = 0
        override fun canProjectRun(owner: ChatRunOwner) = this.owner == owner && state.value.selectedSessionId == owner.conversationId
        override fun publishRunState(owner: ChatRunOwner, next: ChatUiState) { if (canProjectRun(owner)) state.value = next }
        override fun saveRunDraft(owner: ChatRunOwner) { if (canProjectRun(owner)) draftSaves++ }
        override fun failRun(owner: ChatRunOwner, failure: Exception) = publishRunState(owner, state.value.copy(error = failure.message))
        override fun messageUi(message: Message): ChatMessageUi = error("unexpected message")
        override fun citationUis(source: Map<String, Pair<Citation, String>>): List<ChatCitationUi> = error("unexpected citations")
        override fun publishRunCitations(owner: ChatRunOwner, source: Map<String, Pair<Citation, String>>) = error("unexpected citations")
        override suspend fun refreshMessages(owner: ChatRunOwner) = error("unexpected refresh")
        override fun compactionUi(record: ContextCompactionRecord): ChatCompactionUi = error("unexpected compaction")
        override fun reloadChat(preserveTerminalStatus: Boolean) = error("unexpected reload")
    }
}
