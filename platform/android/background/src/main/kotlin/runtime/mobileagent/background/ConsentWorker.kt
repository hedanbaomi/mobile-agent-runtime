// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

class ConsentWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo =
        KnowledgeImportNotification.CONSENT.foregroundInfo(applicationContext, "Applying confirmed consent…")

    override suspend fun doWork(): Result {
        val ticketId = inputData.getString(ImportWorkScheduler.INPUT_TICKET_ID) ?: return Result.failure()
        val visionConfigured = inputData.getBoolean(ImportWorkScheduler.INPUT_VISION_CONFIGURED, false)
        val handler = ImportWorkerRegistry.consentHandler ?: return ImportWorkerRegistry.missingHandler(runAttemptCount) {
            persistFailure(ticketId)
        }
        return try {
            setForeground(KnowledgeImportNotification.CONSENT.foregroundInfo(
                applicationContext, "Continuing after explicit consent"))
            runInterruptible(Dispatchers.IO) { handler.apply(ticketId, visionConfigured) }
            Result.success()
        } catch (_: Throwable) {
            // The repository normally converts provider/Vision uncertainty into
            // a durable job state. This hook covers failures before that
            // writeback boundary; process death is handled by the repository's
            // consumed-ticket recovery gate on the next process start.
            persistFailure(ticketId)
            Result.failure()
        }
    }

    private fun persistFailure(ticketId: String) {
        runCatching { ImportWorkerRegistry.consentFailureHandler?.fail(ticketId) }
    }
}
