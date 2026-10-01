// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.skills.tooling.AuthorityState
import runtime.mobileagent.skills.tooling.Availability
import runtime.mobileagent.skills.tooling.Connection
import runtime.mobileagent.skills.tooling.ElevatedAuthority

/**
 * EMU-044 review regression: the privileged ready-edge sweep must fire on the
 * authority that actually came back — including a Shizuku service restart
 * observed while a different authority is selected — and wired-ADB
 * workspaces must never be swept as orphans for lacking a binding row.
 */
class PrivilegedReadyEdgeTest {

    private fun state(authority: Authority, ready: Boolean) = AuthorityState.configured(
        authority = authority,
        availability = if (ready) Availability.READY else Availability.TEMPORARILY_UNAVAILABLE,
        connection = if (ready) Connection.CONNECTED else Connection.DISCONNECTED,
    )

    @Test
    fun `shizuku restart edge fires even while another authority is selected`() {
        val seen = mutableMapOf<ElevatedAuthority, Boolean>()
        // Initial observation: both authorities ready.
        assertTrue(
            privilegedReadyEdgeAuthorities(
                mapOf(
                    ElevatedAuthority.SHIZUKU to state(Authority.SHIZUKU, true),
                    ElevatedAuthority.WIRED_ADB to state(Authority.WIRED_ADB, true),
                ),
                seen,
            ).isEmpty(),
        )
        // Shizuku service goes down while wired is the selected authority.
        assertTrue(
            privilegedReadyEdgeAuthorities(
                mapOf(
                    ElevatedAuthority.SHIZUKU to state(Authority.SHIZUKU, false),
                    ElevatedAuthority.WIRED_ADB to state(Authority.WIRED_ADB, true),
                ),
                seen,
            ).isEmpty(),
        )
        // Shizuku comes back still unselected: the edge fires for SHIZUKU so
        // its stale remote handles are swept before the user switches back.
        assertEquals(
            setOf(ElevatedAuthority.SHIZUKU),
            privilegedReadyEdgeAuthorities(
                mapOf(
                    ElevatedAuthority.SHIZUKU to state(Authority.SHIZUKU, true),
                    ElevatedAuthority.WIRED_ADB to state(Authority.WIRED_ADB, true),
                ),
                seen,
            ),
        )
    }

    @Test
    fun `steady ready state fires no edge and edge fires only once`() {
        val seen = mutableMapOf<ElevatedAuthority, Boolean>()
        val down = mapOf(ElevatedAuthority.SHIZUKU to state(Authority.SHIZUKU, false))
        assertTrue(privilegedReadyEdgeAuthorities(down, seen).isEmpty())
        val up = mapOf(ElevatedAuthority.SHIZUKU to state(Authority.SHIZUKU, true))
        assertEquals(setOf(ElevatedAuthority.SHIZUKU), privilegedReadyEdgeAuthorities(up, seen))
        // The same edge is consumed: subsequent ready observations do not
        // re-fire and do not unregister freshly re-attached workspaces.
        assertTrue(privilegedReadyEdgeAuthorities(up, seen).isEmpty())
    }

    @Test
    fun `first observation of an already-ready authority is not an edge`() {
        val seen = mutableMapOf<ElevatedAuthority, Boolean>()
        assertTrue(
            privilegedReadyEdgeAuthorities(
                mapOf(ElevatedAuthority.SHIZUKU to state(Authority.SHIZUKU, true)),
                seen,
            ).isEmpty(),
        )
    }

    @Test
    fun `missing binding row orphans non-wired workspaces only`() {
        assertTrue(missingBindingIsOrphan(Authority.SHIZUKU))
        assertFalse(missingBindingIsOrphan(Authority.WIRED_ADB))
        assertTrue(missingBindingIsOrphan(Authority.NONE))
        assertTrue(missingBindingIsOrphan(null))
    }
}
