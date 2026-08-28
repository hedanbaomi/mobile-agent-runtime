// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.announcements

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RolloutTest {
    @Test
    fun goldenVectors() {
        assertEquals(
            44,
            Rollout.bucket("security-demo", "stable-salt", "00000000-0000-4000-8000-000000000001"),
        )
        assertEquals(
            27,
            Rollout.bucket("security-demo", "stable-salt", "00000000-0000-4000-8000-000000000002"),
        )
        assertFalse(Rollout.hits("security-demo", "stable-salt", "00000000-0000-4000-8000-000000000001", 30))
        assertTrue(Rollout.hits("security-demo", "stable-salt", "00000000-0000-4000-8000-000000000002", 30))
    }
}
