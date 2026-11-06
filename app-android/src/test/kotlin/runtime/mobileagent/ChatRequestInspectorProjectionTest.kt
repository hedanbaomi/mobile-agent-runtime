// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.feature.chat.ChatPromptLayerUi
import runtime.mobileagent.feature.chat.ChatRequestInspectorAvailability.*
import runtime.mobileagent.feature.chat.ChatRequestPreviewUi
import runtime.mobileagent.feature.chat.ChatUiState

class ChatRequestInspectorProjectionTest {
    private val owner = ChatUiState(selectedSessionId = "A", selectedAgentId = "agent")
    private val preview = ChatRequestPreviewUi("POST", "https://example.invalid/chat", "", "synthetic body")
    private val layers = listOf(ChatPromptLayerUi("SYSTEM", "synthetic prompt"))

    @Test fun availabilityCoversEveryCombinationWithoutADevice() {
        for (preview in listOf(false, true)) for (hint in listOf(false, true)) {
            assertEquals(DISABLED, resolveRequestInspectorAvailability(false, preview, hint))
        }
        assertEquals(NOT_PREPARED, resolveRequestInspectorAvailability(true, false, false))
        assertEquals(CONTEXT_LOST, resolveRequestInspectorAvailability(true, false, true))
        assertEquals(READY, resolveRequestInspectorAvailability(true, true, false))
        assertEquals(READY, resolveRequestInspectorAvailability(true, true, true))
    }

    @Test fun historyCanOnlyProjectContentForTheResolvedSessionAndAgent() {
        val store = ChatRequestPreviews()
        store.setLayers("A", "agent", layers)
        store.prepared("A", "agent", preview)
        val captured = store.project(owner, enabled = true, persistedHint = true)
        assertEquals(READY, captured.requestInspectorAvailability)
        assertEquals(preview, captured.requestPreview)
        assertEquals(layers, captured.promptLayers)

        for (other in listOf(captured.copy(selectedSessionId = "B"), captured.copy(selectedAgentId = "another"))) {
            val reloaded = store.project(other, enabled = true, persistedHint = false)
            assertNull(reloaded.requestPreview)
            assertTrue(reloaded.promptLayers.isEmpty())
            assertEquals(NOT_PREPARED, reloaded.requestInspectorAvailability)
        }
        store.leave("A")
        assertEquals(captured, store.project(owner, enabled = true, persistedHint = true))
    }

    @Test fun freshRunAndOptOutDiscardOldContentsEvenWhenTheBooleanHintSurvives() {
        val store = ChatRequestPreviews()
        store.prepared("A", "agent", preview)
        store.begin("A")
        store.setLayers("A", "agent", layers)
        store.leave("A")
        assertTrue(store.project(owner, true, true).promptLayers.isEmpty())
        store.prepared("A", "agent", preview)
        val generation = store.generation
        store.clear()
        assertNotEquals(generation, store.generation)
        assertEquals(DISABLED, store.project(owner, false, true).requestInspectorAvailability)
        val enabled = store.project(owner, true, true)
        assertEquals(CONTEXT_LOST, enabled.requestInspectorAvailability)
        assertNull(enabled.requestPreview)
        assertTrue(enabled.promptLayers.isEmpty())
    }
}
