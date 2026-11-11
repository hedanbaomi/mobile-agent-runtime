// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChatRunSessionTest {
    @Test fun timeoutRevokesProjectionBeforeCancellingAndSettlesApproval() = runBlocking {
        val session = ChatRunSession(ChatRunOwner("run", "conversation", 1))
        val job = Job()
        val approval = CompletableDeferred<Boolean>()
        val sequence = mutableListOf<String>()
        session.job = job
        session.approval = approval
        session.approvalCallId = "call"
        job.invokeOnCompletion { sequence += "cancel" }
        session.watch(this, ChatRunExecution(awaitWatchdog = { _, _ -> }), 1, AtomicLong(), { false },
            { sequence += "detach" }, { sequence += "diagnostic" }).join()
        assertEquals(listOf("detach", "cancel", "diagnostic"), sequence)
        assertFalse(approval.await())
        assertNull(session.approvalCallId)
    }

    @Test fun completedRunIsNotCancelledAndTeardownErasesSecrets() = runBlocking {
        val session = ChatRunSession(ChatRunOwner("run", "conversation", 1))
        session.job = Job()
        session.watch(this, ChatRunExecution(awaitWatchdog = { _, _ -> }), 1, AtomicLong(), { true },
            { fail("completed run detached") }, { fail("completed run diagnosed") }).join()
        assertTrue(session.job!!.isActive)
        val secret = charArrayOf('x', 'y')
        session.closeBindings(secret)
        assertArrayEquals(charArrayOf('\u0000', '\u0000'), secret)
        session.job!!.cancel()
    }
}
