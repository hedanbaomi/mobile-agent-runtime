// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImportStateMachineTest {
    @Test
    fun imagesWithoutVisionWaitAndAreNotReady() {
        val job = ImportJob("j", "kb", "doc", hasImages = true, visionConfigured = false)
        repeat(6) { ImportStateMachine.advance(job) }
        assertEquals(ImportStage.WAITING_FOR_VISION_MODEL, job.stage)
        assertFalse(ImportStateMachine.isCompleteSuccess(job))
    }

    @Test
    fun publishedTextOnlyIsNotCompleteSuccess() {
        val job = ImportJob("j", "kb", "doc", stage = ImportStage.READY_WITH_VISUAL_GAPS, hasImages = true)
        assertTrue(ImportStateMachine.isPublished(job.stage))
        assertFalse(ImportStateMachine.isCompleteSuccess(job))
    }

    @Test
    fun apiEmbeddingWithoutConsentDoesNotLeaveDevice() {
        val job = ImportJob("j", "kb", "doc", embeddingIsApi = true, embeddingConsent = false, hasImages = false)
        while (job.stage != ImportStage.AWAITING_EMBEDDING_CONSENT && job.stage != ImportStage.READY) {
            val prev = job.stage
            ImportStateMachine.advance(job)
            if (job.stage == prev) break
        }
        assertEquals(ImportStage.AWAITING_EMBEDDING_CONSENT, job.stage)
    }
}
