// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * EMU-046 / EMU-048 regressions:
 *  - terminate() must sweep the remote process tree, not only the direct
 *    child — [remoteDescendantsOf] defines the deepest-first kill order.
 *  - UserService.destroy() must accept the Shizuku server caller (shell/root
 *    uid), which never matches the app handshake uid — [destroyCallerAllowed]
 *    encodes that gate.
 */
class ShizukuRemoteCleanupTest {

    private fun treeOf(vararg edges: Pair<Int, List<Int>>): (Int) -> Sequence<Int> {
        val map = edges.toMap()
        return { pid -> map[pid].orEmpty().asSequence() }
    }

    @Test
    fun `remote descendants are returned deepest first excluding the root`() {
        val children = treeOf(
            100 to listOf(101, 102),
            101 to listOf(103),
            102 to emptyList(),
            103 to listOf(104),
            104 to emptyList(),
        )
        assertEquals(listOf(104, 103, 101, 102), remoteDescendantsOf(100, children))
    }

    @Test
    fun `remote descendants of a leaf process is empty`() {
        val children = treeOf(1 to listOf(2), 2 to emptyList())
        assertEquals(emptyList<Int>(), remoteDescendantsOf(2, children))
    }

    @Test
    fun `remote descendants tolerates cycles without looping`() {
        val children = treeOf(1 to listOf(2), 2 to listOf(1, 3), 3 to emptyList())
        assertEquals(listOf(3, 2), remoteDescendantsOf(1, children))
    }

    @Test
    fun `destroy allows the handshaken app caller`() {
        assertTrue(destroyCallerAllowed(callerUid = 10123, handshakeUid = 10123, serviceUid = 2000))
    }

    @Test
    fun `destroy allows the shizuku server caller sharing the service uid`() {
        assertTrue(destroyCallerAllowed(callerUid = 2000, handshakeUid = 10123, serviceUid = 2000))
    }

    @Test
    fun `destroy allows a root caller for root-started servers`() {
        assertTrue(destroyCallerAllowed(callerUid = 0, handshakeUid = 10123, serviceUid = 2000))
    }

    @Test
    fun `destroy allows the server before any handshake completes`() {
        assertTrue(destroyCallerAllowed(callerUid = 2000, handshakeUid = null, serviceUid = 2000))
    }

    @Test
    fun `destroy refuses an unrelated binder holder`() {
        assertFalse(destroyCallerAllowed(callerUid = 10999, handshakeUid = 10123, serviceUid = 2000))
        assertFalse(destroyCallerAllowed(callerUid = 10999, handshakeUid = null, serviceUid = 2000))
    }
}
