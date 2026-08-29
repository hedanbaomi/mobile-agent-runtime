// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.background

import android.content.Context
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
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
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Preparing import…", 0)

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(ImportWorkScheduler.INPUT_JOB_ID)
            ?: return Result.failure()
        val visionConfigured = inputData.getBoolean(ImportWorkScheduler.INPUT_VISION_CONFIGURED, false)
        // Application.onCreate normally installs the bridge before WorkManager
        // starts this worker.  A short bounded retry covers the startup race;
        // an unbounded retry would keep resurrecting a broken configuration.
        val handler = ImportWorkerRegistry.handler ?: return if (runAttemptCount < MAX_HANDLER_STARTUP_RETRIES) {
            Result.retry()
        } else {
            Result.failure()
        }
        setForeground(foregroundInfo("Importing knowledge", 0))
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
            when (job.stage) {
                ImportStage.READY,
                ImportStage.CANCELLED,
                ImportStage.FAILED,
                ImportStage.PAUSED,
                ImportStage.WAITING_FOR_VISION_MODEL,
                ImportStage.AWAITING_UPLOAD_CONSENT,
                ImportStage.AWAITING_EMBEDDING_CONSENT,
                -> Result.success()
                ImportStage.RETRY_WAIT -> Result.retry()
                else -> Result.success()
            }
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

    private fun foregroundInfo(message: String, progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Knowledge imports", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Knowledge import")
            .setContentText(message)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val CHANNEL_ID = "runtime.mobileagent.knowledge.import"
        const val NOTIFICATION_ID = 0x4D41
        const val MAX_HANDLER_STARTUP_RETRIES = 3
    }
}
