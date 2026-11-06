// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import runtime.mobileagent.knowledge.ImportStage

class ImportWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo =
        KnowledgeImportNotification.DOCUMENT.foregroundInfo(applicationContext, "Preparing import…", 0)

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(ImportWorkScheduler.INPUT_JOB_ID)
            ?: return Result.failure()
        val visionConfigured = inputData.getBoolean(ImportWorkScheduler.INPUT_VISION_CONFIGURED, false)
        val handler = ImportWorkerRegistry.handler ?: return ImportWorkerRegistry.missingHandler(runAttemptCount)
        setForeground(KnowledgeImportNotification.DOCUMENT.foregroundInfo(applicationContext, "Importing knowledge", 0))
        return try {
            val job = runInterruptible(Dispatchers.IO) {
                handler.resume(jobId, visionConfigured)
            }
            setProgress(
                androidx.work.Data.Builder()
                    .putString("stage", job.stage.name)
                    .putString("job_id", job.id)
                    .build(),
            )
            if (job.stage == ImportStage.RETRY_WAIT) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            // Cancellation must interrupt the synchronous repository/adapter
            // bridge, then durably record the stop even though this worker's
            // Job is already cancelled. This path never resumes provider work.
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    ImportWorkerRegistry.cancellationHandler?.cancel(jobId)
                } catch (failure: Exception) {
                    android.util.Log.e("KnowledgeImport", "Cancellation persistence failed: ${failure.javaClass.simpleName}")
                    cancelled.addSuppressed(failure)
                }
            }
            throw cancelled
        } catch (_: Throwable) {
            // The repository owns the durable stage.  A normal terminal
            // FAILED/CANCELLED/READY result (including FAILED with an
            // UNKNOWN_OUTCOME error) is acknowledged above and is never
            // scheduled again.  If the handler itself throws, the outcome of
            // any external Vision call is not knowable here, so retrying could
            // duplicate a provider call.  Fail the WorkManager attempt and
            // require an explicit user action/retry gate instead.
            Result.failure()
        }
    }
}
