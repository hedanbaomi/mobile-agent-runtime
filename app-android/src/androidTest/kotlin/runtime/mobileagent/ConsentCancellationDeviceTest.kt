// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.background.ConsentTicketHandler
import runtime.mobileagent.background.ImportWorkScheduler
import runtime.mobileagent.background.ImportWorkerRegistry

/** Verifies that job cancellation reaches consent work with ticket-scoped uniqueness. */
@RunWith(AndroidJUnit4::class)
class ConsentCancellationDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val scheduledNames = mutableListOf<String>()
    private var originalConsentHandler: ConsentTicketHandler? = null

    @Before
    fun disableConsentHandler() {
        originalConsentHandler = ImportWorkerRegistry.consentHandler
        ImportWorkerRegistry.consentHandler = null
    }

    @After
    fun cancelFixtures() {
        scheduledNames.forEach { name ->
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(name).result.get(10, TimeUnit.SECONDS) }
        }
        ImportWorkerRegistry.consentHandler = originalConsentHandler
    }

    @Test(timeout = 60_000)
    fun cancellingJobStopsAssociatedConsentWork() {
        val jobId = "consent-cancel-${UUID.randomUUID()}"
        val ticketId = "ticket-${UUID.randomUUID()}"
        val jobTag = ImportWorkScheduler.uniqueName(jobId)
        val ticketName = "${ImportWorkScheduler.TAG}:consent:$ticketId"
        scheduledNames += ticketName

        val workId = ImportWorkScheduler.enqueueConsent(
            context,
            ticketId,
            visionConfigured = false,
            jobId = jobId,
        )

        val tagged = WorkManager.getInstance(context)
            .getWorkInfosByTag(jobTag)
            .get(5, TimeUnit.SECONDS)
        assertTrue("Consent work must carry its job cancellation tag", tagged.any { it.id == workId })
        assertEquals(workId, WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ticketName)
            .get(5, TimeUnit.SECONDS)
            .single()
            .id)

        ImportWorkScheduler.cancel(context, jobId)

        val cancelled = awaitState(ticketName, WorkInfo.State.CANCELLED, 30_000)
        assertEquals(WorkInfo.State.CANCELLED, cancelled.state)
    }

    private fun awaitState(name: String, expected: WorkInfo.State, timeoutMs: Long): WorkInfo {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val info = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(name)
                .get(5, TimeUnit.SECONDS)
                .firstOrNull()
            if (info?.state == expected) return info
            SystemClock.sleep(100)
        }
        val last = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(name)
            .get(5, TimeUnit.SECONDS)
            .firstOrNull()
        throw AssertionError("Timed out waiting for $name to reach $expected; last=$last")
    }
}
