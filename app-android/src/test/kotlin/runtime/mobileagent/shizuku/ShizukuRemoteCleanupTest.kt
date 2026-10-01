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
 *    child — [remoteDescendantsOf] defines the deepest-first kill order and
 *    [sweepRemoteTree] enforces the discovery-time identity snapshot, so a
 *    pid reused or exited between enumeration and kill is never signalled.
 *  - UserService.destroy() must accept the Shizuku server caller, which runs
 *    under the service's own shell/root uid — never an app-embedded or
 *    unrelated uid — [destroyCallerAllowed] encodes that gate.
 */
class ShizukuRemoteCleanupTest {

    private fun treeOf(vararg edges: Pair<Int, List<Pair<Int, Long>>>): (Int) -> Sequence<Pair<Int, Long>> {
        val map = edges.toMap()
        return { pid -> map[pid].orEmpty().asSequence() }
    }

    private fun children(vararg pids: Pair<Int, Long>) = pids.toList()

    @Test
    fun `remote descendants are returned deepest first excluding the root`() {
        val children = treeOf(
            100 to children(101 to 10L, 102 to 20L),
            101 to children(103 to 30L),
            102 to children(),
            103 to children(104 to 40L),
            104 to children(),
        )
        assertEquals(
            listOf(104 to 40L, 103 to 30L, 101 to 10L, 102 to 20L),
            remoteDescendantsOf(100, children),
        )
    }

    @Test
    fun `remote descendants of a leaf process is empty`() {
        val children = treeOf(1 to children(2 to 5L), 2 to children())
        assertEquals(emptyList<Pair<Int, Long>>(), remoteDescendantsOf(2, children))
    }

    @Test
    fun `remote descendants tolerates cycles without looping`() {
        val children = treeOf(
            1 to children(2 to 5L),
            2 to children(1 to 1L, 3 to 6L),
            3 to children(),
        )
        assertEquals(listOf(3 to 6L, 2 to 5L), remoteDescendantsOf(1, children))
    }

    @Test
    fun `sweep signals captured processes whose identity still matches`() {
        val signalled = mutableListOf<Int>()
        sweepRemoteTree(
            captured = listOf(101 to 10L, 102 to 20L),
            childrenOf = { emptySequence() },
            startTimeOf = { pid -> mapOf(101 to 10L, 102 to 20L)[pid] },
            signal = { signalled.add(it) },
        )
        assertEquals(listOf(101, 102), signalled)
    }

    @Test
    fun `sweep never signals a pid whose process exited or was recycled`() {
        val signalled = mutableListOf<Int>()
        sweepRemoteTree(
            captured = listOf(101 to 10L, 102 to 20L),
            childrenOf = { emptySequence() },
            startTimeOf = { pid ->
                when (pid) {
                    101 -> 999L // pid recycled: start-time moved since discovery
                    102 -> null // exited between enumeration and kill
                    else -> null
                }
            },
            signal = { signalled.add(it) },
        )
        assertEquals(emptyList<Int>(), signalled)
    }

    @Test
    fun `sweep collects descendants forked during the sweep window`() {
        val signalled = mutableListOf<Int>()
        // 101 forks 105 only after the initial enumeration captured the tree.
        sweepRemoteTree(
            captured = listOf(101 to 10L),
            childrenOf = { pid -> if (pid == 101) sequenceOf(105 to 50L) else emptySequence() },
            startTimeOf = { pid -> mapOf(101 to 10L, 105 to 50L)[pid] },
            signal = { signalled.add(it) },
            maxPasses = 2,
        )
        assertEquals(listOf(101, 105), signalled)
    }

    @Test
    fun `sweep is bounded and never signals pids outside the captured tree`() {
        val signalled = mutableListOf<Int>()
        sweepRemoteTree(
            captured = emptyList(),
            childrenOf = { sequenceOf(999 to 1L) },
            startTimeOf = { 1L },
            signal = { signalled.add(it) },
        )
        assertEquals(emptyList<Int>(), signalled)
    }

    @Test
    fun `destroy allows the shizuku server caller sharing the shell service uid`() {
        assertTrue(destroyCallerAllowed(callerUid = 2000, serviceUid = 2000))
    }

    @Test
    fun `destroy allows the shizuku server caller sharing the root service uid`() {
        assertTrue(destroyCallerAllowed(callerUid = 0, serviceUid = 0))
    }

    @Test
    fun `destroy fails closed when the service is embedded in the app process`() {
        // caller == service uid, but the uid is an app uid, not shell/root.
        assertFalse(destroyCallerAllowed(callerUid = 10123, serviceUid = 10123))
    }

    @Test
    fun `destroy refuses cross-uid and unrelated binder holders`() {
        assertFalse(destroyCallerAllowed(callerUid = 0, serviceUid = 2000))
        assertFalse(destroyCallerAllowed(callerUid = 2000, serviceUid = 0))
        assertFalse(destroyCallerAllowed(callerUid = 10999, serviceUid = 2000))
        assertFalse(destroyCallerAllowed(callerUid = 10123, serviceUid = 2000))
    }
}
