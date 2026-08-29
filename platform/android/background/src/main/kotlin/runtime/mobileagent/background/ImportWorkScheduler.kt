// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import runtime.mobileagent.knowledge.ImportJob

/** The process-local bridge from WorkManager to the app's serialized DB owner. */
fun interface ImportJobHandler {
    /** Resume the persisted job from CAS and return its persisted state. */
    fun resume(jobId: String, visionConfigured: Boolean): ImportJob
}

/** Optional cancellation hook owned by the repository/DI layer. */
fun interface ImportCancellationHandler {
    fun cancel(jobId: String)
}

object ImportWorkerRegistry {
    @Volatile
    var handler: ImportJobHandler? = null

    @Volatile
    var cancellationHandler: ImportCancellationHandler? = null
}

/**
 * Enqueues resumable, user-visible import work.  The source document is
 * already in the repository CAS; WorkManager carries only the job id and
 * therefore never serializes user file bytes into its database.
 */
object ImportWorkScheduler {
    // Process-owned cancellation work outlives a screen/ViewModel. A failed
    // persistence hook is observable and does not cancel later stop requests.
    private val cancellationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    const val INPUT_JOB_ID = "runtime.mobileagent.import.JOB_ID"
    const val INPUT_VISION_CONFIGURED = "runtime.mobileagent.import.VISION_CONFIGURED"
    const val TAG = "runtime.mobileagent.import"

    fun enqueue(
        context: Context,
        jobId: String,
        visionConfigured: Boolean,
    ): UUID {
        require(jobId.isNotBlank()) { "jobId must not be blank" }
        val request = request(jobId, visionConfigured)
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName(jobId),
            ExistingWorkPolicy.KEEP,
            request,
        )
        return request.id
    }

    fun resume(
        context: Context,
        jobId: String,
        visionConfigured: Boolean,
    ): UUID = enqueue(context, jobId, visionConfigured)

    fun cancel(context: Context, jobId: String) {
        val applicationContext = context.applicationContext
        cancellationScope.launch {
            try {
                // Send the scheduler stop before waiting on repository locks.
                WorkManager.getInstance(applicationContext).cancelUniqueWork(uniqueName(jobId))
            } catch (failure: Exception) {
                android.util.Log.e("KnowledgeImport", "Work cancellation failed: ${failure.javaClass.simpleName}")
            }
            try {
                // The worker invokes this same idempotent hook on cancellation.
                ImportWorkerRegistry.cancellationHandler?.cancel(jobId)
            } catch (failure: Exception) {
                android.util.Log.e("KnowledgeImport", "Cancellation persistence failed: ${failure.javaClass.simpleName}")
            }
        }
    }

    fun uniqueName(jobId: String): String = "${TAG}:$jobId"

    private fun request(jobId: String, visionConfigured: Boolean): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(
                androidx.work.Data.Builder()
                    .putString(INPUT_JOB_ID, jobId)
                    .putBoolean(INPUT_VISION_CONFIGURED, visionConfigured)
                    .build(),
            )
            .setConstraints(
                Constraints.Builder()
                    // Local CAS work does not require a network.  Keeping the
                    // constraint explicit prevents accidental provider calls
                    // from changing the scheduler's privacy contract.
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(uniqueName(jobId))
            .build()
}
