// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.observation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.AuthorityUserIntent
import runtime.mobileagent.skills.tooling.*
import runtime.mobileagent.tooling.AuthorityManagerState

class ScreenActionAdmissionTest {
    private val ready = AuthorityState(Authority.SHIZUKU, AuthorityUserIntent.SHIZUKU,
        PlatformGrant.GRANTED, Availability.READY, Connection.CONNECTED, configured = true)
    private fun state(selected: Authority? = Authority.SHIZUKU, status: AuthorityState = ready, revision: Long = 1) =
        AuthorityManagerState(revision, selected, mapOf(Authority.SHIZUKU to status))

    @Test fun selectedReadyAuthorityAndNonDebugBuildAreBothRequired() {
        assertEquals(ScreenActionAuthority(Authority.SHIZUKU, 1), ScreenActionAdmission.from(state(), true))
        val wired = ready.copy(authority = Authority.WIRED_ADB, userIntent = AuthorityUserIntent.WIRED_ADB)
        assertEquals(ScreenActionAuthority(Authority.WIRED_ADB, 5), ScreenActionAdmission.from(
            AuthorityManagerState(5, Authority.WIRED_ADB, mapOf(Authority.WIRED_ADB to wired)), true))
        assertNull(ScreenActionAdmission.from(state(), false))
        assertNull(ScreenActionAdmission.from(state(null), true))
        assertNull(ScreenActionAdmission.from(state(Authority.NONE), true))
        assertNull(ScreenActionAdmission.from(state(Authority.WIRED_ADB), true), "never borrow a ready unselected Shizuku")
        for (status in listOf(ready.copy(grant = PlatformGrant.DENIED),
            ready.copy(connection = Connection.DISCONNECTED), ready.copy(availability = Availability.TEMPORARILY_UNAVAILABLE),
            ready.copy(configured = false), ready.copy(userIntent = AuthorityUserIntent.NONE))) {
            assertNull(ScreenActionAdmission.from(state(status = status), true))
        }
        assertNotEquals(ScreenActionAdmission.from(state(), true), ScreenActionAdmission.from(state(revision = 2), true))
    }
}
