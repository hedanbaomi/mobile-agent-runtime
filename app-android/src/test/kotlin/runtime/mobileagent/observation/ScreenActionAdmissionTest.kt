// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.observation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.skills.tooling.Availability
import runtime.mobileagent.skills.tooling.Connection
import runtime.mobileagent.skills.tooling.ElevatedAuthority
import runtime.mobileagent.skills.tooling.PlatformGrant
import runtime.mobileagent.tooling.AuthorityManager

class ScreenActionAdmissionTest {
    @Test
    fun admitsOnlyReadyAdbAuthoritiesAndRejectsRootOrNone() {
        fun readyStateFor(selected: Authority, elevated: ElevatedAuthority?): runtime.mobileagent.tooling.AuthorityManagerState {
            val manager = AuthorityManager()
            if (elevated != null) {
                manager.setUserIntent(elevated, true)
                manager.setConfigured(elevated, true)
                manager.updatePlatformGrant(elevated, PlatformGrant.GRANTED)
                manager.updateAvailability(elevated, Availability.READY)
                manager.updateConnection(elevated, Connection.CONNECTED, "fixture")
            }
            manager.selectAuthority(selected)
            return manager.state.value
        }

        val shizuku = ScreenActionAdmission.from(readyStateFor(Authority.SHIZUKU, ElevatedAuthority.SHIZUKU), buildAllowed = true)
        assertEquals(Authority.SHIZUKU, shizuku?.authority)

        val wired = ScreenActionAdmission.from(readyStateFor(Authority.WIRED_ADB, ElevatedAuthority.WIRED_ADB), buildAllowed = true)
        assertEquals(Authority.WIRED_ADB, wired?.authority)

        assertNull(ScreenActionAdmission.from(readyStateFor(Authority.NONE, null), buildAllowed = true))
        assertNull(ScreenActionAdmission.from(readyStateFor(Authority.ROOT_DIRECT, ElevatedAuthority.ROOT_DIRECT), buildAllowed = true))
        assertNull(ScreenActionAdmission.from(readyStateFor(Authority.SHIZUKU_ROOT, ElevatedAuthority.SHIZUKU_ROOT), buildAllowed = true))
        assertNull(ScreenActionAdmission.from(readyStateFor(Authority.SHIZUKU, ElevatedAuthority.SHIZUKU), buildAllowed = false))
    }
}
