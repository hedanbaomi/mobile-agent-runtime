// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import runtime.mobileagent.knowledge.ImportBatchKind
import runtime.mobileagent.knowledge.ImportJob
import runtime.mobileagent.knowledge.ImportStage

/** Lifecycle regression fixture: only the application coordinator owns the import worker. */
@RunWith(AndroidJUnit4::class)
class KnowledgeImportLifecycleTest {
    @Test
    fun clearingViewModelObserverDoesNotLoseSecondItemOrFinalFence() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ports = LifecyclePorts(blockSecond = true)
        val coordinator = KnowledgeImportCoordinator(appScope, ports)
        val operation = started(coordinator, ports)

        assertTrue(ports.secondEntered.await(3, TimeUnit.SECONDS))
        // A screen/ViewModel observer may disappear; it does not own appScope.
        val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        observerScope.launch { operation.progress.collect {} }
        observerScope.cancel()
        ports.releaseSecond.countDown()

        val result = withTimeout(5_000) { operation.completion.await() }
        assertEquals(KnowledgeImportTerminal.COMPLETED, result.terminal)
        assertEquals(2, ports.bound.size)
        assertEquals(1, ports.fenceCount.get())
        assertEquals(2, result.progress.copied)
        assertEquals(2, ports.persistentCopied)
        appScope.cancel()
    }

    @Test
    fun duplicateTriggerReturnsExistingOperationWithoutOverlappingCopy() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ports = LifecyclePorts(blockSecond = true)
        val coordinator = KnowledgeImportCoordinator(appScope, ports)
        val first = started(coordinator, ports)
        assertTrue(ports.secondEntered.await(3, TimeUnit.SECONDS))

        val duplicate = coordinator.start(inputs(), ImportBatchKind.FILES, "fixture", "kb")
        assertTrue(duplicate is KnowledgeImportStart.AlreadyRunning)
        assertEquals(2, ports.importCalls.get())
        ports.releaseSecond.countDown()
        withTimeout(5_000) { first.completion.await() }
        assertEquals(2, ports.importCalls.get())
        appScope.cancel()
    }

    @Test
    fun userAndSystemCancellationHaveDifferentTerminalClassification() = runBlocking {
        val userScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val userPorts = LifecyclePorts(blockSecond = true)
        val userCoordinator = KnowledgeImportCoordinator(userScope, userPorts)
        val userOperation = started(userCoordinator, userPorts)
        assertTrue(userPorts.secondEntered.await(3, TimeUnit.SECONDS))
        assertTrue(userCoordinator.cancel(userOperation.operationId))
        val userResult = withTimeout(5_000) { userOperation.completion.await() }
        assertEquals(KnowledgeImportTerminal.USER_CANCELLED, userResult.terminal)
        assertEquals(1, userPorts.cancelCalls.get())
        userScope.cancel()

        val systemJob = SupervisorJob()
        val systemScope = CoroutineScope(systemJob + Dispatchers.Default)
        val systemPorts = LifecyclePorts(blockSecond = true)
        val systemCoordinator = KnowledgeImportCoordinator(systemScope, systemPorts)
        val systemOperation = started(systemCoordinator, systemPorts)
        assertTrue(systemPorts.secondEntered.await(3, TimeUnit.SECONDS))
        systemJob.cancel()
        val systemResult = withTimeout(5_000) { systemOperation.completion.await() }
        assertEquals(KnowledgeImportTerminal.SYSTEM_CANCELLED, systemResult.terminal)
        assertEquals(0, systemPorts.cancelCalls.get())
        assertFalse(systemPorts.fenceEnqueued)
    }

    @Test
    fun pauseDuringCopyPreservesManifestAndResumesOnlyRemainingSources() = runBlocking {
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ports = LifecyclePorts(blockSecond = true)
        val first = KnowledgeImportCoordinator(firstScope, ports)
        val operation = started(first, ports)
        assertTrue(first.pauseBatch("batch"))
        val stopped = withTimeout(5_000) { operation.completion.await() }
        assertEquals(KnowledgeImportTerminal.PAUSED, stopped.terminal)
        assertEquals(1, ports.bound.size)
        assertFalse(ports.fenceEnqueued)
        assertTrue(ports.paused)
        firstScope.cancel()

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ports.releaseSecond.countDown()
        val restarted = KnowledgeImportCoordinator(secondScope, ports)
        val resumed = restarted.resumeStaging("batch") as KnowledgeImportStart.Started
        val done = withTimeout(5_000) { resumed.operation.completion.await() }
        assertEquals(KnowledgeImportTerminal.COMPLETED, done.terminal)
        assertEquals(listOf("one.txt", "two.txt"), ports.bound.toList())
        assertEquals(3, ports.importCalls.get()) // first completed, second interrupted, second resumed
        assertEquals(1, ports.fenceCount.get())
        assertTrue(ports.stagingComplete)
        secondScope.cancel()
    }

    @Test
    fun processRecreationRetainsUncopiedMembershipAndOriginalTarget() = runBlocking {
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ports = LifecyclePorts(blockSecond = true)
        val coordinator = KnowledgeImportCoordinator(firstScope, ports)
        val operation = (coordinator.start(inputs(), ImportBatchKind.FILES, "fixture", "kb",
            visionTarget = "vision-test") as KnowledgeImportStart.Started).operation
        assertTrue(ports.secondEntered.await(3, TimeUnit.SECONDS))
        firstScope.cancel()
        withTimeout(5_000) { operation.completion.await() }
        assertFalse(ports.stagingComplete)
        assertEquals(2, ports.plan!!.files.size)
        assertEquals("vision-test", ports.plan!!.visionTarget)
        ports.releaseSecond.countDown()
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val restarted = KnowledgeImportCoordinator(secondScope, ports)
        val resumed = restarted.resumeStaging("batch") as KnowledgeImportStart.Started
        withTimeout(5_000) { resumed.operation.completion.await() }
        assertEquals(listOf("one.txt", "two.txt"), ports.bound.toList())
        assertEquals("vision-test", ports.authorizedTarget)
        secondScope.cancel()
    }

    private suspend fun started(
        coordinator: KnowledgeImportCoordinator,
        ports: LifecyclePorts,
    ): KnowledgeImportOperation {
        val result = coordinator.start(inputs(), ImportBatchKind.FILES, "fixture", "kb")
        val operation = (result as KnowledgeImportStart.Started).operation
        // The first copy is intentionally quick; the second is the lifecycle barrier.
        assertTrue(ports.secondEntered.await(3, TimeUnit.SECONDS))
        return operation
    }

    private fun inputs() = listOf(
        KnowledgeImportInput("one.txt", "one", { ByteArrayInputStream(byteArrayOf(1)) }),
        KnowledgeImportInput("two.txt", "two", { ByteArrayInputStream(byteArrayOf(2)) }),
    )
}

private class LifecyclePorts(private val blockSecond: Boolean) : KnowledgeImportPorts {
    private val nextJob = AtomicInteger()
    val importCalls = AtomicInteger()
    val secondEntered = CountDownLatch(1)
    val releaseSecond = CountDownLatch(1)
    val bound = CopyOnWriteArrayList<String>()
    val fenceCount = AtomicInteger()
    val cancelCalls = AtomicInteger()
    val failure = AtomicReference<String?>(null)
    @Volatile var persistentCopied: Int = 0
    @Volatile var fenceEnqueued: Boolean = false
    private val jobs = mutableMapOf<String, String>()

    var plan: KnowledgeStagingPlan? = null
    var stagingComplete = false
    private val stagedKeys = mutableSetOf<String>()

    override fun beginStaging(files: List<KnowledgeImportInput>, kind: ImportBatchKind,
        label: String, knowledgeBaseId: String, visionTarget: String?): String {
        plan = KnowledgeStagingPlan("batch", files, kind, label, knowledgeBaseId, visionTarget)
        return "batch"
    }
    override fun loadStaging(batchId: String) = plan?.takeUnless { stagingComplete }
    override fun stagingSourceCopied(batchId: String, sourceKey: String) = sourceKey in stagedKeys
    override fun stageInput(batchId: String, input: KnowledgeImportInput, kind: ImportBatchKind,
        knowledgeBaseId: String, checkpoint: () -> Unit): List<String> {
        val job = importOne(input, kind, knowledgeBaseId, false)
        checkpoint()
        bindJobToBatch(batchId, job, input.displayName)
        stagedKeys += input.sourceKey
        return listOf(job.id)
    }
    override fun completeStaging(batchId: String) { stagingComplete = true }
    override fun visionConfigured() = false
    override fun createKnowledgeBase(name: String) = "kb"
    override fun beginBatch(knowledgeBaseId: String, kind: ImportBatchKind, displayName: String) = "batch"

    override fun importOne(
        input: KnowledgeImportInput,
        kind: ImportBatchKind,
        knowledgeBaseId: String,
        visionConfigured: Boolean,
    ): ImportJob {
        importCalls.incrementAndGet()
        if (blockSecond && input.sourceKey == "two") {
            secondEntered.countDown()
            releaseSecond.await()
        }
        val id = "job-${nextJob.incrementAndGet()}"
        val job = ImportJob(id, knowledgeBaseId, "document-$id", stage = ImportStage.COPYING)
        synchronized(jobs) { jobs[id] = "batch" }
        return job
    }

    override fun bindJobToBatch(batchId: String, job: ImportJob, relativePath: String) {
        bound += relativePath
        persistentCopied = bound.size
    }

    override fun jobBatchId(jobId: String) = synchronized(jobs) { jobs[jobId] }
    override fun refreshBatchProgress(batchId: String) = progress()
    override fun readBatchProgress(batchId: String) = progress()
    override fun generationStillCurrent(batchId: String) = true
    override fun failBatch(batchId: String, reason: String) { failure.set(reason) }
    override fun enqueueBatch(batchId: String, visionConfigured: Boolean) = Unit

    override fun enqueueBatchFence(batchId: String, visionConfigured: Boolean) {
        fenceCount.incrementAndGet()
        fenceEnqueued = true
    }

    override fun cancelBatch(batchId: String, jobIds: List<String>) {
        cancelCalls.incrementAndGet()
    }

    override fun visionBindingFingerprint(): String? = "vision-test"

    override fun authorizeBatchVision(batchId: String, expectedTarget: String): Boolean {
        authorizedTarget = expectedTarget
        return authorizedTarget == visionBindingFingerprint()
    }

    override fun pauseBatch(batchId: String): Boolean {
        paused = true
        return true
    }

    override fun resumeBatch(batchId: String): Boolean {
        paused = false
        return true
    }

    @Volatile var authorizedTarget: String? = null
    @Volatile var paused: Boolean = false

    private fun progress() = KnowledgeImportProgress(
        batchId = "batch",
        state = if (paused) "PAUSED" else if (fenceEnqueued) "COMPLETED" else "COPYING",
        totalItems = plan?.files?.size ?: bound.size,
        copied = persistentCopied,
    )
}
