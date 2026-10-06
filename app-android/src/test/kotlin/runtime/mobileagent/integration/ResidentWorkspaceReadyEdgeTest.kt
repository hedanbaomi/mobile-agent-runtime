// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.wired.*

class ResidentWorkspaceReadyEdgeTest {
    private fun status(ready: Boolean, generation: String?) = WiredAdbStatus(
        state = if (ready) WiredAdbLifecycleState.READY else WiredAdbLifecycleState.DISCONNECTED,
        userIntent = WiredAdbUserIntent.ENABLED,
        platformGrant = WiredAdbPlatformGrant.GRANTED,
        availability = if (ready) WiredAdbAvailability.READY else WiredAdbAvailability.TEMPORARILY_UNAVAILABLE,
        connection = if (ready) WiredAdbConnectionState.CONNECTED else WiredAdbConnectionState.DISCONNECTED,
        trusted = true,
        serviceSessionId = generation,
    )

    @Test fun `same daemon remains attached and a new generation invalidates handles even if ready edge was conflated`() {
        val initial = status(true, "first")
        assertFalse(residentWorkspaceHandlesNeedReattach(null, initial))
        assertFalse(residentWorkspaceHandlesNeedReattach(initial, initial))
        assertTrue(residentWorkspaceHandlesNeedReattach(initial, status(true, "second")))
    }

    @Test fun `service loss alone preserves bindings and reconnect requires fresh handles`() {
        val up = status(true, "first")
        val down = status(false, null)
        assertFalse(residentWorkspaceHandlesNeedReattach(up, down))
        assertTrue(residentWorkspaceHandlesNeedReattach(down, up))
    }

    @Test fun `resident attachment needs sealed recovery binding while legacy desktop remains compatible`() {
        assertFalse(missingBindingIsOrphan(runtime.mobileagent.domain.Authority.WIRED_ADB))
        assertTrue(missingBindingIsOrphan(runtime.mobileagent.domain.Authority.WIRED_ADB, residentWired = true))
    }
}
