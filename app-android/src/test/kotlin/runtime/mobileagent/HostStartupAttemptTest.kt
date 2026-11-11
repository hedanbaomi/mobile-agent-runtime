// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class HostStartupAttemptTest {
    @Test fun hostRetirementKeepsInterruptionSuppressedThroughStorageClose() {
        var attempts = 0
        Thread.currentThread().interrupt()
        try {
            retireHostAttempt(releaseResources = { Thread.currentThread().interrupt() }, closeStorage = {
                assertFalse(Thread.currentThread().isInterrupted)
                if (++attempts == 1) throw InterruptedException("late cancellation")
            })
            assertEquals(2, attempts)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun partialInitializationClosesInReverseOrderEvenIfCleanupFails() {
        val calls = mutableListOf<Int>()
        val resources = HostResources()
        resources.onClose { calls += 1 }
        resources.onClose { calls += 2; error("synthetic close failure") }
        resources.onClose { calls += 3 }
        resources.close()
        resources.close()
        assertEquals(listOf(3, 2, 1), calls)
        assertThrows(IllegalStateException::class.java) { resources.onClose {} }
        var lateReleased = false
        assertThrows(IllegalStateException::class.java) { resources.own(Unit) { lateReleased = true } }
        assertTrue(lateReleased)
        val retry = HostResources()
        retry.onClose { calls += 4 }
        retry.close()
        assertEquals(listOf(3, 2, 1, 4), calls)
    }

    @Test fun failureExportNeverContainsExceptionMessagesOrStack() {
        val secret = "synthetic-secret /private/path https://invalid.example/?token=test"
        val storage = hostStartupFailure(HostStartupStage.DATABASE, IOException(secret))
        assertEquals(HostStartupError.STORAGE, storage.code)
        assertEquals("host-startup-v1\nstage=DATABASE\ncode=STORAGE\n", storage.diagnosticText())
        val credentials = hostStartupFailure(HostStartupStage.CONTAINER, SecurityException(secret))
        assertEquals(HostStartupError.CREDENTIALS, credentials.code)
        assertFalse(credentials.diagnosticText().contains(secret))
        val linkage = hostStartupFailure(HostStartupStage.MIGRATION, UnsatisfiedLinkError(secret))
        assertEquals(HostStartupError.INITIALIZATION, linkage.code)
        assertEquals("host-startup-v1\nstage=MIGRATION\ncode=INITIALIZATION\n", linkage.diagnosticText())
        assertFalse(linkage.diagnosticText().contains(secret))
    }
}
