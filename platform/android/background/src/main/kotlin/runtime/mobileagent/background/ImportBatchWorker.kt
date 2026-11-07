// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

class ImportBatchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo =
        KnowledgeImportNotification.BATCH.foregroundInfo(applicationContext, "Preparing import batch…")

    override suspend fun doWork(): Result {
        val batchId = inputData.getString(ImportWorkScheduler.INPUT_BATCH_ID) ?: return Result.failure()
        val visionConfigured = inputData.getBoolean(ImportWorkScheduler.INPUT_VISION_CONFIGURED, false)
        val handler = ImportWorkerRegistry.batchHandler ?: return ImportWorkerRegistry.missingHandler(runAttemptCount)
        setForeground(KnowledgeImportNotification.BATCH.foregroundInfo(applicationContext, "Importing knowledge batch"))
        return try {
            val retryPending = runInterruptible(Dispatchers.IO) { handler.process(batchId, visionConfigured) }
            if (retryPending) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            // WorkManager may stop a worker for system reasons. Only the
            // explicit scheduler cancellation path persists CANCELLED; a
            // stopped worker must leave its checkpoint resumable.
            throw cancelled
        } catch (_: Throwable) {
            Result.failure()
        }
    }
}
