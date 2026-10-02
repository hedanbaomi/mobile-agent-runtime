// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.feature.knowledge.KnowledgePendingImportUi
import runtime.mobileagent.feature.knowledge.KnowledgeUiState

class KnowledgeImportSelectionTest {
    private val pending = KnowledgePendingImportUi("choice-1", emptyList(), "files")
    private fun operation() = KnowledgeImportOperation(
        "staging", CompletableDeferred(), MutableStateFlow(KnowledgeImportProgress("staging")),
        AtomicBoolean(false), Job(),
    )

    @Test fun busyStagingRetainsChoiceEvenWhenTheOperationWasAlreadyObserved() {
        val state = KnowledgeUiState(pendingImport = pending, selectedBaseId = "kb-1")
        val first = applyKnowledgeImportStart(state, KnowledgeImportStart.AlreadyRunning(operation()), pending.id)
        val second = applyKnowledgeImportStart(first, KnowledgeImportStart.AlreadyRunning(operation()), pending.id)
        assertSame(pending, second.pendingImport)
        assertEquals("kb-1", second.selectedBaseId)
        assertFalse(second.error.isNullOrBlank())
        assertEquals(second.error, second.status)
    }

    @Test fun onlyAcceptanceClearsTheSubmittedChoice() {
        val state = KnowledgeUiState(pendingImport = pending)
        assertSame(pending, applyKnowledgeImportStart(state, KnowledgeImportStart.Rejected("unreadable"), pending.id).pendingImport)
        assertNull(applyKnowledgeImportStart(state, KnowledgeImportStart.Started(operation()), pending.id).pendingImport)
    }

    @Test fun lateAcceptanceCannotClearANewerPickerResultOrRetargetItsBase() {
        val newer = pending.copy(id = "choice-2", selectedVisionTargetFingerprint = "new-target")
        val state = KnowledgeUiState(pendingImport = newer, selectedBaseId = "kb-2")
        val result = applyKnowledgeImportStart(state, KnowledgeImportStart.Started(operation()), pending.id)
        assertSame(newer, result.pendingImport)
        assertEquals("kb-2", result.selectedBaseId)
    }

    @Test fun preparingConfirmationKeepsItsChosenVisionTarget() {
        val original = pending.copy(selectedVisionTargetFingerprint = "confirmed-target")
        val preparing = KnowledgeUiState(pendingImport = original, importSubmitting = true)
        val lateClick = selectKnowledgeImportVisionTarget(preparing, "later-target")
        assertEquals("confirmed-target", lateClick.pendingImport?.selectedVisionTargetFingerprint)
        assertSame(original, lateClick.pendingImport)
        assertNull(applyKnowledgeImportStart(lateClick, KnowledgeImportStart.Started(operation()), pending.id).pendingImport)
        val ready = selectKnowledgeImportVisionTarget(preparing.copy(importSubmitting = false), "later-target")
        assertEquals("later-target", ready.pendingImport?.selectedVisionTargetFingerprint)
    }

    @Test fun aDismissedSelectionIsNotRestoredByTheOldResult() {
        val state = KnowledgeUiState(pendingImport = null)
        assertNull(applyKnowledgeImportStart(state, KnowledgeImportStart.Started(operation()), pending.id).pendingImport)
        assertNull(applyKnowledgeImportStart(state, KnowledgeImportStart.AlreadyRunning(operation()), pending.id).pendingImport)
    }
}
