// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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

class ImportBatchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Preparing import batch…")

    override suspend fun doWork(): Result {
        val batchId = inputData.getString(ImportWorkScheduler.INPUT_BATCH_ID) ?: return Result.failure()
        val visionConfigured = inputData.getBoolean(ImportWorkScheduler.INPUT_VISION_CONFIGURED, false)
        val handler = ImportWorkerRegistry.batchHandler ?: return if (runAttemptCount < 3) Result.retry() else Result.failure()
        setForeground(foregroundInfo("Importing knowledge batch"))
        return try {
            runInterruptible(Dispatchers.IO) { handler.process(batchId, visionConfigured) }
            Result.success()
        } catch (cancelled: CancellationException) {
            // A stopped batch worker must persist the same idempotent durable
            // cancellation as the scheduler path, then preserve WorkManager's
            // CANCELLED state instead of converting it to FAILED.  The
            // repository applies its pre-dispatch/unknown-outcome rules per
            // item, so a provider boundary is never rewritten as clean cancel.
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    ImportWorkerRegistry.batchCancellationHandler?.cancel(batchId)
                } catch (failure: Exception) {
                    android.util.Log.e("KnowledgeImport", "Batch cancellation persistence failed: ${failure.javaClass.simpleName}")
                    cancelled.addSuppressed(failure)
                }
            }
            throw cancelled
        } catch (_: Throwable) {
            Result.failure()
        }
    }

    private fun foregroundInfo(message: String): ForegroundInfo {
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
        const val NOTIFICATION_ID = 0x4D42
    }
}
