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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

/** Explicit user cancellation hook for a durable batch. */
fun interface ImportBatchCancellationHandler {
    fun cancel(batchId: String)
}

fun interface ImportBatchHandler {
    fun process(batchId: String, visionConfigured: Boolean)
}

fun interface ConsentTicketHandler {
    fun apply(ticketId: String, visionConfigured: Boolean)
}

/**
 * Persists a terminal consent-worker failure in the app-owned repository so a
 * consumed ticket cannot leave its import looking indefinitely active.
 */
fun interface ConsentFailureHandler {
    fun fail(ticketId: String)
}

object ImportWorkerRegistry {
    @Volatile
    var handler: ImportJobHandler? = null

    @Volatile
    var cancellationHandler: ImportCancellationHandler? = null

    @Volatile
    var batchCancellationHandler: ImportBatchCancellationHandler? = null

    @Volatile
    var batchHandler: ImportBatchHandler? = null

    @Volatile
    var consentHandler: ConsentTicketHandler? = null

    @Volatile
    var consentFailureHandler: ConsentFailureHandler? = null
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
    const val INPUT_BATCH_ID = "runtime.mobileagent.import.BATCH_ID"
    const val INPUT_TICKET_ID = "runtime.mobileagent.import.TICKET_ID"
    const val TAG = "runtime.mobileagent.import"

    /** Emits on work-state changes, including a waiting consent starting or finishing. */
    fun activeWork(context: Context): Flow<Boolean> = WorkManager.getInstance(context)
        .getWorkInfosByTagFlow(TAG).map { work -> work.any { !it.state.isFinished } }

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

    fun enqueueBatch(context: Context, batchId: String, visionConfigured: Boolean): UUID {
        require(batchId.isNotBlank()) { "batchId must not be blank" }
        val request = batchRequest(batchId, visionConfigured)
        WorkManager.getInstance(context).enqueueUniqueWork(
            batchUniqueName(batchId),
            ExistingWorkPolicy.KEEP,
            request,
        )
        return request.id
    }

    /**
     * Append one final durable drain after staging finishes. Unlike the per-item KEEP wakeups,
     * this fence cannot be lost when the current worker has observed an empty queue but has not
     * yet transitioned to a finished WorkManager state.
     */
    fun enqueueBatchFence(context: Context, batchId: String, visionConfigured: Boolean): UUID {
        require(batchId.isNotBlank()) { "batchId must not be blank" }
        val request = batchRequest(batchId, visionConfigured)
        WorkManager.getInstance(context).enqueueUniqueWork(
            batchUniqueName(batchId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        return request.id
    }

    fun enqueueConsent(
        context: Context,
        ticketId: String,
        visionConfigured: Boolean,
        jobId: String? = null,
    ): UUID {
        require(ticketId.isNotBlank()) { "ticketId must not be blank" }
        require(jobId == null || jobId.isNotBlank()) { "jobId must not be blank" }
        val requestBuilder = OneTimeWorkRequestBuilder<ConsentWorker>()
            .setInputData(
                androidx.work.Data.Builder()
                    .putString(INPUT_TICKET_ID, ticketId)
                    .putBoolean(INPUT_VISION_CONFIGURED, visionConfigured)
                    .build(),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag("$TAG:consent:$ticketId")
        if (jobId != null) requestBuilder.addTag(uniqueName(jobId))
        val request = requestBuilder.build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$TAG:consent:$ticketId",
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
                // Send the scheduler stop for every worker associated with this job before
                // waiting on repository locks. This includes consent work, whose unique name
                // is ticket-scoped rather than job-scoped.
                WorkManager.getInstance(applicationContext)
                    .cancelAllWorkByTag(uniqueName(jobId))
                    .result
                    .get()
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

    /**
     * Stop the unique batch work before entering the serialized repository
     * cancellation hook.  The hook is process-owned and idempotent; queued
     * batches have no running worker to perform that durable transition.
     */
    fun cancelBatch(context: Context, batchId: String) {
        require(batchId.isNotBlank()) { "batchId must not be blank" }
        val applicationContext = context.applicationContext
        cancellationScope.launch {
            try {
                WorkManager.getInstance(applicationContext)
                    .cancelUniqueWork(batchUniqueName(batchId))
                    .result
                    .get()
            } catch (failure: Exception) {
                android.util.Log.e("KnowledgeImport", "Batch work cancellation failed: ${failure.javaClass.simpleName}")
            }
            try {
                ImportWorkerRegistry.batchCancellationHandler?.cancel(batchId)
            } catch (failure: Exception) {
                android.util.Log.e("KnowledgeImport", "Batch cancellation persistence failed: ${failure.javaClass.simpleName}")
            }
        }
    }

    fun uniqueName(jobId: String): String = "${TAG}:$jobId"

    fun batchUniqueName(batchId: String): String = "$TAG:batch:$batchId"

    private fun batchRequest(batchId: String, visionConfigured: Boolean): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<ImportBatchWorker>()
            .setInputData(
                androidx.work.Data.Builder()
                    .putString(INPUT_BATCH_ID, batchId)
                    .putBoolean(INPUT_VISION_CONFIGURED, visionConfigured)
                    .build(),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(batchUniqueName(batchId))
            .build()

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
