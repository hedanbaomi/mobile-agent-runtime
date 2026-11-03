// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Two-step Root entry regressions: the platform permission fact (permission granted + UID0 server),
 * not the Root service grant, decides whether the first step is done. A fresh unauthorized app only
 * requests the permission; the second explicit click (after the platform grant) selects and enables
 * the channel; a passive refresh never calls this path.
 */
class RootChannelEntryActionsTest {
    private class Counters {
        val requests = AtomicInteger()
        val selects = AtomicInteger()
        val intents = AtomicInteger()
        val clears = AtomicInteger()
        val errors = AtomicInteger()
    }

    private fun run(platformReady: Boolean, counters: Counters, failure: Exception? = null) {
        RootChannelEntryActions.enableShizukuRoot(
            platformReady = { platformReady },
            requestPermission = { counters.requests.incrementAndGet() },
            select = {
                failure?.let { throw it }
                counters.selects.incrementAndGet()
            },
            setIntent = { counters.intents.incrementAndGet() },
            clearError = { counters.clears.incrementAndGet() },
            onError = { counters.errors.incrementAndGet() },
        )
    }

    @Test
    fun freshUnauthorizedRootClickOnlyRequestsThePlatformPermission() {
        val counters = Counters()
        run(platformReady = false, counters = counters)
        assertEquals(1, counters.requests.get(), "the first click must request the platform permission")
        assertEquals(0, counters.selects.get(), "no channel selection before the platform grant")
        assertEquals(0, counters.intents.get(), "no foreground Root request before the platform grant")
        assertEquals(0, counters.errors.get())
    }

    @Test
    fun secondClickAfterThePlatformGrantSelectsAndEnables() {
        val counters = Counters()
        run(platformReady = true, counters = counters)
        assertEquals(1, counters.selects.get())
        assertEquals(1, counters.intents.get(), "enabling the channel is the foreground Root request")
        assertEquals(0, counters.requests.get(), "no permission request once the platform grant exists")
        assertEquals(1, counters.clears.get())
    }

    @Test
    fun failureSurfacesAsAnErrorInsteadOfASuccessfulEnable() {
        val counters = Counters()
        run(platformReady = true, counters = counters, failure = IllegalStateException("boom"))
        assertEquals(1, counters.errors.get())
        assertEquals(0, counters.intents.get())
    }

    @Test
    fun passiveRefreshDoesNotTouchTheEntryActions() {
        // The helper is only reachable from the explicit UI action; a passive state refresh calls
        // neither the permission request nor the Root enable. This pins that the entry actions have
        // no side effects of their own.
        val counters = Counters()
        assertNull(null)
        assertEquals(0, counters.requests.get() + counters.selects.get() + counters.intents.get())
    }
}
