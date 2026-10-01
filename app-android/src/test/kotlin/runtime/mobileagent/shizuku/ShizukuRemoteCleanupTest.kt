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
 *    child — [remoteDescendantsOf] verifies per-level lineage (each child's
 *    own atomic stat must report ppid == the verified parent, and a parent
 *    that was recycled drops its whole branch) and [sweepRemoteTree]
 *    re-verifies identity plus the ancestor chain before every signal, so a
 *    pid reused or exited between enumeration and kill is never signalled
 *    and an unrelated subtree can never be collected.
 *  - UserService.destroy() must accept the Shizuku server caller, which runs
 *    under the service's own shell/root uid — never an app-embedded or
 *    unrelated uid — [destroyCallerAllowed] encodes that gate.
 */
class ShizukuRemoteCleanupTest {

    private val root = RemoteIdentity(pid = 100, startTime = 1L)

    private fun statOf(vararg rows: Pair<Int, Pair<Int, Long>>): (Int) -> Pair<Int, Long>? {
        val map = rows.toMap()
        return { pid -> map[pid] }
    }

    private fun tree(vararg edges: Pair<Int, List<Int>>): (Int) -> Sequence<Int> {
        val map = edges.toMap()
        return { pid -> map[pid].orEmpty().asSequence() }
    }

    private fun node(pid: Int, start: Long, ppid: Int, parentStart: Long = -1L) =
        RemoteNode(pid, start, ppid, parentStart)

    @Test
    fun `remote descendants are returned deepest first excluding the root`() {
        val children = tree(
            100 to listOf(101, 102),
            101 to listOf(103),
            103 to listOf(104),
        )
        val stat = statOf(
            100 to (0 to 1L),   // root
            101 to (100 to 10L),
            102 to (100 to 20L),
            103 to (101 to 30L),
            104 to (103 to 40L),
        )
        assertEquals(
            listOf(104, 103, 101, 102),
            remoteDescendantsOf(root, children, stat).map { it.pid },
        )
    }

    @Test
    fun `remote descendants of a leaf process is empty`() {
        val children = tree(1 to listOf(2))
        val stat = statOf(1 to (0 to 5L), 2 to (1 to 6L))
        assertEquals(
            emptyList<RemoteNode>(),
            remoteDescendantsOf(RemoteIdentity(2, 6L), children, stat),
        )
    }

    @Test
    fun `remote descendants tolerates cycles without looping`() {
        val children = tree(1 to listOf(2), 2 to listOf(1, 3))
        val stat = statOf(1 to (0 to 1L), 2 to (1 to 5L), 3 to (2 to 6L))
        assertEquals(
            listOf(3, 2),
            remoteDescendantsOf(RemoteIdentity(1, 1L), children, stat).map { it.pid },
        )
    }

    @Test
    fun `a pid recycled between children listing and stat read is rejected`() {
        // Parent 100 legitimately lists child pid 200, but pid 200 now holds a
        // foreign process U whose own stat reports a different parent — the
        // ppid check refuses it and its subtree is never even walked.
        val children = tree(
            100 to listOf(101, 200),
            200 to listOf(201), // foreign grandchild under U
        )
        val stat = statOf(
            100 to (0 to 1L),
            101 to (100 to 10L),
            200 to (999 to 77L), // recycled holder: ppid is not our parent
            201 to (200 to 88L),
        )
        assertEquals(
            listOf(101),
            remoteDescendantsOf(root, children, stat).map { it.pid },
        )
    }

    @Test
    fun `a recycled parent rejects its whole descendant branch`() {
        // Parent 101 exited and its pid now holds a foreign process (start 999);
        // the children listing under it must not be trusted at all.
        val children = tree(
            100 to listOf(101),
            101 to listOf(201, 202),
        )
        val stat = statOf(
            100 to (0 to 1L),
            101 to (100 to 999L), // recycled: start-time moved
            201 to (101 to 20L),
            202 to (101 to 21L),
        )
        assertEquals(emptyList<RemoteNode>(), remoteDescendantsOf(root, children, stat))
    }

    @Test
    fun `sweep signals captured nodes whose identity still matches`() {
        val signalled = mutableListOf<Int>()
        sweepRemoteTree(
            captured = listOf(node(101, 10L, ppid = 100), node(102, 20L, ppid = 100)),
            root = root,
            childrenOf = { emptySequence() },
            statOf = statOf(
                100 to (0 to 1L),
                101 to (100 to 10L),
                102 to (100 to 20L),
            ),
            signal = { signalled.add(it) },
        )
        assertEquals(listOf(101, 102), signalled)
    }

    @Test
    fun `sweep never signals a node recycled or exited after discovery`() {
        val signalled = mutableListOf<Int>()
        sweepRemoteTree(
            captured = listOf(node(101, 10L, ppid = 100), node(102, 20L, ppid = 100)),
            root = root,
            childrenOf = { emptySequence() },
            statOf = statOf(
                100 to (0 to 1L),
                101 to (100 to 999L), // recycled: start moved
                // 102 exited: no stat
            ),
            signal = { signalled.add(it) },
        )
        assertEquals(emptyList<Int>(), signalled)
    }

    @Test
    fun `sweep never signals a node reparented under a foreign live process`() {
        val signalled = mutableListOf<Int>()
        sweepRemoteTree(
            captured = listOf(node(101, 10L, ppid = 100)),
            root = root,
            childrenOf = { emptySequence() },
            statOf = statOf(
                100 to (0 to 1L),
                101 to (777 to 10L), // same start, but foreign live parent
            ),
            signal = { signalled.add(it) },
        )
        assertEquals(emptyList<Int>(), signalled)
    }

    @Test
    fun `sweep still signals a reparented-to-init orphan that is ours`() {
        val signalled = mutableListOf<Int>()
        sweepRemoteTree(
            captured = listOf(node(101, 10L, ppid = 103)),
            root = root,
            childrenOf = { emptySequence() },
            statOf = statOf(
                100 to (0 to 1L),
                101 to (1 to 10L), // parent 103 died; init adopted it — same process
                // 103 absent: dead, so no veto
            ),
            signal = { signalled.add(it) },
        )
        assertEquals(listOf(101), signalled)
    }

    @Test
    fun `a recycled ancestor vetoes the whole descendant branch at signal time`() {
        // Grandchild 202 under parent 201 under 101; parent 101 was recycled
        // (start moved) after enumeration — nothing below it may be signalled.
        val signalled = mutableListOf<Int>()
        sweepRemoteTree(
            captured = listOf(
                node(101, 10L, ppid = 100),
                node(201, 30L, ppid = 101, parentStart = 10L),
                node(202, 40L, ppid = 201, parentStart = 30L),
            ),
            root = root,
            childrenOf = { emptySequence() },
            statOf = statOf(
                100 to (0 to 1L),
                101 to (100 to 999L), // recycled ancestor
                201 to (101 to 30L),
                202 to (201 to 40L),
            ),
            signal = { signalled.add(it) },
        )
        assertEquals(emptyList<Int>(), signalled)
    }

    @Test
    fun `sweep collects descendants forked during the sweep window`() {
        val signalled = mutableListOf<Int>()
        // 101 forks 105 only after the initial enumeration captured the tree;
        // the re-enumeration verifies 105's own ppid before collecting it.
        sweepRemoteTree(
            captured = listOf(node(101, 10L, ppid = 100)),
            root = root,
            childrenOf = { pid -> if (pid == 101) sequenceOf(105) else emptySequence() },
            statOf = statOf(
                100 to (0 to 1L),
                101 to (100 to 10L),
                105 to (101 to 50L),
            ),
            signal = { signalled.add(it) },
            maxPasses = 2,
        )
        assertEquals(listOf(101, 105), signalled)
    }

    @Test
    fun `sweep rejects a forked child that fails lineage verification`() {
        val signalled = mutableListOf<Int>()
        sweepRemoteTree(
            captured = listOf(node(101, 10L, ppid = 100)),
            root = root,
            childrenOf = { pid -> if (pid == 101) sequenceOf(106) else emptySequence() },
            statOf = statOf(
                100 to (0 to 1L),
                101 to (100 to 10L),
                106 to (999 to 60L), // pid reused by a foreign process
            ),
            signal = { signalled.add(it) },
            maxPasses = 2,
        )
        assertEquals(listOf(101), signalled)
    }

    @Test
    fun `sweep is bounded and never signals pids outside the captured tree`() {
        val signalled = mutableListOf<Int>()
        sweepRemoteTree(
            captured = emptyList(),
            root = root,
            childrenOf = { sequenceOf(999) },
            statOf = statOf(999 to (100 to 1L)),
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
