// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import runtime.mobileagent.background.ImportCancellationHandler
import runtime.mobileagent.background.ImportJobHandler
import runtime.mobileagent.background.ImportWorkScheduler
import runtime.mobileagent.background.ImportWorker
import runtime.mobileagent.background.ImportWorkerRegistry
import runtime.mobileagent.knowledge.ImportJob
import runtime.mobileagent.knowledge.ImportStage

/**
 * Device/API-matrix coverage for the real WorkManager worker and foreground contract.
 *
 * The instrumentation runner defers MobileAgentApp host initialization, so these fixtures never
 * open the product database, inspect user jobs or initialize provider/network clients.
 * Android 15's cumulative six-hour timeout and Android 16 quota exhaustion require the
 * separate controlled-device procedure documented in foreground-import-matrix.md.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundImportDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var originalHandler: ImportJobHandler
    private var hadOriginalHandler = false
    private lateinit var originalCancellationHandler: ImportCancellationHandler
    private var hadOriginalCancellationHandler = false
    private val scheduledNames = mutableListOf<String>()

    @Before
    fun preserveRegistry() {
        ImportWorkerRegistry.handler?.let { originalHandler = it; hadOriginalHandler = true }
        ImportWorkerRegistry.cancellationHandler?.let {
            originalCancellationHandler = it
            hadOriginalCancellationHandler = true
        }
        assumeTrue("Foreground import matrix starts at Android 12/API 31", Build.VERSION.SDK_INT >= 31)
        ImportWorkerRegistry.handler = null
        ImportWorkerRegistry.cancellationHandler = null
    }

    @After
    fun restoreRegistryAndCancelFixtures() {
        scheduledNames.forEach { name ->
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(name).result.get(10, TimeUnit.SECONDS) }
        }
        ImportWorkerRegistry.handler = if (hadOriginalHandler) originalHandler else null
        ImportWorkerRegistry.cancellationHandler =
            if (hadOriginalCancellationHandler) originalCancellationHandler else null
    }

    @Test(timeout = 60_000)
    fun foregroundInfoAndMergedManifestMatchActualDeviceApi() {
        assertTrue("Matrix starts at Android 12/API 31", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

        val worker = TestListenableWorkerBuilder<ImportWorker>(
            context = context,
            inputData = input("foreground-info"),
            runAttemptCount = 0,
        ).build()
        val info = runBlocking { worker.getForegroundInfo() }

        assertEquals(0x4D41, info.notificationId)
        assertTrue(info.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.CATEGORY_PROGRESS, info.notification.category)
        assertEquals(100, info.notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertTrue(info.notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = notificationManager.getNotificationChannel(info.notification.channelId)
        assertNotNull("Worker must create its real notification channel", channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)

        @Suppress("DEPRECATION")
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, "androidx.work.impl.foreground.SystemForegroundService"),
            PackageManager.GET_META_DATA,
        )
        assertTrue(service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0)
        val permissions = requestedPermissions()
        assertTrue(Manifest.permission.FOREGROUND_SERVICE in permissions)
        assertTrue("android.permission.FOREGROUND_SERVICE_DATA_SYNC" in permissions)
        assertTrue("Current target must exercise Android 15 dataSync rules", context.applicationInfo.targetSdkVersion >= 35)
        if (Build.VERSION.SDK_INT >= 35) {
            val serviceClass = Class.forName("androidx.work.impl.foreground.SystemForegroundService")
            assertTrue(
                "WorkManager 2.10 must own the API 35 timeout callback",
                serviceClass.declaredMethods.any { method ->
                    method.name == "onTimeout" && method.parameterTypes.size == 2 &&
                        method.parameterTypes.all { it == Integer.TYPE }
                },
            )
        }
        Log.i(TAG, "foreground_contract api=${Build.VERSION.SDK_INT} target=${context.applicationInfo.targetSdkVersion} type=${info.foregroundServiceType}")
    }

    @Test(timeout = 90_000)
    fun workManagerWithoutActivityRunsAndReadyWaitingAreTerminalWithoutReplay() {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val stages = mapOf(
            "ready-${UUID.randomUUID()}" to ImportStage.READY,
            "waiting-${UUID.randomUUID()}" to ImportStage.WAITING_FOR_VISION_MODEL,
            "consent-${UUID.randomUUID()}" to ImportStage.AWAITING_EMBEDDING_CONSENT,
        )
        ImportWorkerRegistry.handler = ImportJobHandler { id, _ ->
            calls.getOrPut(id) { AtomicInteger() }.incrementAndGet()
            fixtureJob(id, stages.getValue(id))
        }
        ImportWorkerRegistry.cancellationHandler = ImportCancellationHandler { }

        stages.forEach { (id, stage) ->
            val name = ImportWorkScheduler.uniqueName(id)
            scheduledNames += name
            ImportWorkScheduler.enqueue(context, id, visionConfigured = false)
            val terminal = awaitState(name, setOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED), 45_000)
            assertEquals("$stage must be acknowledged, not scheduled for retry", WorkInfo.State.SUCCEEDED, terminal.state)
            Log.i(TAG, "terminal job=$id stage=$stage state=${terminal.state} attempts=${terminal.runAttemptCount}")
        }

        SystemClock.sleep(1_500)
        stages.keys.forEach { id ->
            assertEquals("Terminal work must execute exactly once", 1, calls.getValue(id).get())
            assertEquals(WorkInfo.State.SUCCEEDED, currentInfo(ImportWorkScheduler.uniqueName(id)).state)
        }
    }

    @Test(timeout = 90_000)
    fun schedulerCancellationInterruptsWorkerPersistsHookAndDoesNotReplay() {
        val jobId = "cancel-${UUID.randomUUID()}"
        val name = ImportWorkScheduler.uniqueName(jobId)
        scheduledNames += name
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val resumeCalls = AtomicInteger()
        val persistedIds = mutableSetOf<String>()
        val persistenceAttempts = AtomicInteger()

        ImportWorkerRegistry.handler = ImportJobHandler { id, _ ->
            resumeCalls.incrementAndGet()
            entered.countDown()
            try {
                release.await()
            } catch (failure: InterruptedException) {
                interrupted.set(true)
                throw failure
            }
            fixtureJob(id, ImportStage.READY)
        }
        ImportWorkerRegistry.cancellationHandler = ImportCancellationHandler { id ->
            synchronized(persistedIds) { persistedIds += id }
            persistenceAttempts.incrementAndGet()
        }

        ImportWorkScheduler.enqueue(context, jobId, visionConfigured = false)
        try {
            assertTrue("Real WorkManager never entered ImportWorker", entered.await(45, TimeUnit.SECONDS))
            ImportWorkScheduler.cancel(context, jobId)
            val cancelled = awaitState(name, setOf(WorkInfo.State.CANCELLED), 30_000)
            assertEquals(WorkInfo.State.CANCELLED, cancelled.state)
            Log.i(TAG, "cancelled job=$jobId stopReason=${cancelled.stopReason}")
            awaitCondition(30_000) { synchronized(persistedIds) { jobId in persistedIds } }
            awaitCondition(30_000) { interrupted.get() }

            // Scheduler and worker may both invoke the deliberately idempotent hook.
            assertTrue(persistenceAttempts.get() >= 1)
            assertEquals(1, synchronized(persistedIds) { persistedIds.size })
            assertEquals(1, resumeCalls.get())
            SystemClock.sleep(1_500)
            assertEquals("Cancellation must not enqueue or replay the import", 1, resumeCalls.get())
            assertEquals(WorkInfo.State.CANCELLED, currentInfo(name).state)
        } finally {
            release.countDown()
        }
    }

    private fun input(jobId: String): Data = Data.Builder()
        .putString(ImportWorkScheduler.INPUT_JOB_ID, jobId)
        .putBoolean(ImportWorkScheduler.INPUT_VISION_CONFIGURED, false)
        .build()

    private fun fixtureJob(id: String, stage: ImportStage): ImportJob = ImportJob(
        id = id,
        knowledgeBaseId = "fixture-kb",
        documentId = "fixture-document-$id",
        stage = stage,
    )

    private fun awaitState(name: String, terminal: Set<WorkInfo.State>, timeoutMs: Long): WorkInfo {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val info = currentInfoOrNull(name)
            if (info != null && info.state in terminal) return info
            SystemClock.sleep(100)
        }
        val last = currentInfoOrNull(name)
        throw AssertionError("Timed out waiting for $name in $terminal; last=$last")
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Condition was not observed within ${timeoutMs}ms", condition())
    }

    private fun currentInfo(name: String): WorkInfo = currentInfoOrNull(name)
        ?: throw AssertionError("No WorkInfo for $name")

    private fun currentInfoOrNull(name: String): WorkInfo? =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).firstOrNull()

    @Suppress("DEPRECATION")
    private fun requestedPermissions(): Set<String> = context.packageManager
        .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions.orEmpty().toSet()

    private companion object {
        const val TAG = "ForegroundImportMatrix"
    }
}
