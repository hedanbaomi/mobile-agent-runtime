// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RootWorkspaceDisplayNameTest {
    @Test
    fun nameIsRelativeDistinguishableAndBounded() {
        val device = rootWorkspaceDisplayName("/")
        val temporary = rootWorkspaceDisplayName("/data/local/tmp")
        val emulated = rootWorkspaceDisplayName("/storage/emulated/0")
        assertEquals("Root · device", device)
        assertEquals("Root · data/local/tmp", temporary)
        assertEquals("Root · storage/emulated/0", emulated)
        assertNotEquals(device, temporary)
        assertNotEquals(temporary, emulated)
        assertFalse(device.startsWith("/"))
        assertFalse(temporary.startsWith("/"))
        assertFalse(emulated.startsWith("/"))
        assertTrue(rootWorkspaceDisplayName("/" + "a".repeat(400)).length <= 200)
    }
}
