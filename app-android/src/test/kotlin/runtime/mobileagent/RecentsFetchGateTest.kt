// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.util.concurrent.CountDownLatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Round-4 picker regression: the full refresh and the health refresh are
 * two writers of the same recent-workspaces list.  A fetch claimed earlier
 * but resolved later must never overwrite a newer fetch's result — the
 * gate hands out ordered claims and only the latest may commit.
 */
class RecentsFetchGateTest {

    @Test
    fun `a stale writer cannot overwrite a newer claim`() {
        val gate = RecentsFetchGate()
        // Full refresh claims first, then a health refresh claims later —
        // the full refresh's older result must be rejected at commit time.
        val fullFetch = gate.claim()
        val healthFetch = gate.claim()
        assertTrue(gate.isLatest(healthFetch))
        assertFalse(gate.isLatest(fullFetch))
    }

    @Test
    fun `completion order is irrelevant — only claim order wins`() {
        // Deterministic interleave: the older fetch is held on a latch and
        // released strictly after the newer fetch has committed; its stale
        // result must still be dropped.
        val gate = RecentsFetchGate()
        val holdOld = CountDownLatch(1)
        val committed = java.util.concurrent.atomic.AtomicReference<String>()
        val oldFetch = gate.claim()
        val oldWriter = Thread {
            holdOld.await()
            if (gate.isLatest(oldFetch)) committed.set("old")
        }
        oldWriter.start()
        val newFetch = gate.claim()
        if (gate.isLatest(newFetch)) committed.set("new")
        holdOld.countDown()
        oldWriter.join(5_000)
        assertEquals("new", committed.get())
    }
}
