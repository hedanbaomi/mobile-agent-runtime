// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.RunRecord

class ChatRunLifecycleTest {
    @Test fun failedBootstrapReadReleasesOwnerAndReportsFailure() = runBlocking {
        val ownership = ChatRunOwnership()
        val resources = ChatRunSession(ownership.begin("conversation"))
        var streaming = true
        var failed: Exception? = null
        val readFailure = IllegalStateException("initial grant read failed")
        var finished = 0
        runChatSessionLifecycle(ownership, resources,
            onFailure = { failed = it },
            onFinished = {
                assertTrue(ownership.canProject(resources.owner, "conversation"))
                streaming = false
                finished++
            },
        ) {
            resources.job = currentCoroutineContext()[Job]
            // USER persistence consumed the draft before this first run-bootstrap repository read.
            throw readFailure
        }
        assertSame(readFailure, failed)
        assertFalse(streaming)
        assertEquals(1, finished)
        assertNull(ownership.active)
        assertFalse(ownership.canProject(resources.owner, "conversation"))
        assertNotEquals(resources.owner, ownership.begin("conversation"))
    }

    @Test fun cancellationDuringSuspendedBootstrapReadCannotBlockNextSend() = runBlocking {
        val ownership = ChatRunOwnership()
        val resources = ChatRunSession(ownership.begin("conversation"))
        val reading = CompletableDeferred<Unit>()
        var streaming = true
        var failures = 0
        var finished = 0
        val job = launch {
            runChatSessionLifecycle(ownership, resources,
                onFailure = { failures++ },
                onFinished = { streaming = false; finished++ },
            ) {
                resources.job = currentCoroutineContext()[Job]
                reading.complete(Unit)
                awaitCancellation()
            }
        }
        reading.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(streaming)
        assertEquals(0, failures)
        assertEquals(1, finished)
        assertNull(ownership.active)
        assertNotEquals(resources.owner, ownership.begin("conversation"))
    }

    @Test fun projectionCallbackFailureStillReleasesAdmission() = runBlocking {
        val ownership = ChatRunOwnership()
        val resources = ChatRunSession(ownership.begin("conversation"))
        val failure = runCatching {
            runChatSessionLifecycle(ownership, resources, onFailure = {},
                onFinished = { error("projection unavailable") }, block = {})
        }.exceptionOrNull()
        assertEquals("projection unavailable", failure?.message)
        assertNull(ownership.active)
    }

    @Test fun queuedDispatcherBindsSessionJobBeforePreflightFinallyRuns() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val queuedDispatcher = executor.asCoroutineDispatcher()
        val ioExecutor = Executors.newSingleThreadExecutor()
        val ioDispatcher = ioExecutor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + queuedDispatcher)
        try {
            val ownership = ChatRunOwnership()
            val resources = ChatRunSession(ownership.begin("conversation"))
            var runJob: Job? = null
            val suspendedInRun = CompletableDeferred<Unit>()
            val preflight = scope.launch {
                try {
                    launchChatSessionLifecycle(
                        scope = scope,
                        ownership = ownership,
                        resources = resources,
                        onStarted = { runJob = it },
                        onFailure = {},
                        onFinished = { if (runJob === resources.job) runJob = null },
                    ) {
                        withContext(ioDispatcher) {
                            suspendedInRun.complete(Unit)
                            awaitCancellation()
                        }
                    }
                } finally {
                    // Simulate ChatViewModel.send preflight finally checking admitted.job == null.
                    if (resources.job == null) ownership.finish(resources.owner)
                }
            }
            preflight.join()
            suspendedInRun.await()
            assertNotNull(resources.job)
            assertSame(resources.job, runJob)
            assertTrue(ownership.canProject(resources.owner, "conversation"))
            requireNotNull(resources.job).cancelAndJoin()
            assertNull(ownership.active)
            assertNull(runJob)
        } finally {
            ioDispatcher.close()
            queuedDispatcher.close()
            ioExecutor.shutdownNow()
            executor.shutdownNow()
        }
    }

    @Test fun tryStampChatRunManifestRethrowsCancellationAndMapsFailureToNull() = runBlocking {
        val record = RunRecord(
            runId = "run-1",
            snapshotId = "snap-1",
            conversationId = "conv-1",
            createdAt = "2026-11-11T00:00:00Z",
        )
        assertSame(record, tryStampChatRunManifest { record })
        assertNull(tryStampChatRunManifest { throw IllegalStateException("sqlite busy") })
        val cancellation = CancellationException("cancelled while stamping manifest")
        val actual = assertThrows(CancellationException::class.java) {
            runBlocking { tryStampChatRunManifest { throw cancellation } }
        }
        assertSame(cancellation, actual)
    }
}
