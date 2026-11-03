// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.file.Paths
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.RootControlPlaneBoundary
import runtime.mobileagent.domain.RootFdNodeIdentity
import runtime.mobileagent.domain.RootFileOperationReason
import runtime.mobileagent.domain.RootFileOperationResult
import runtime.mobileagent.domain.RootServiceTerminalOutcome
import runtime.mobileagent.domain.RootTypedArguments
import runtime.mobileagent.domain.RootWorkspaceFileExecutor
import runtime.mobileagent.domain.RootWriteDomainKind

private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    .joinToString("") { "%02x".format(it.toInt() and 255) }

private data class Node(
    var inode: Long,
    var dir: Boolean,
    var path: String,
    var bytes: ByteArray = ByteArray(0),
    var mode: Int = 384,
    var uid: Int = 10100,
    var gid: Int = 10100,
    var links: Long = 1,
    var mount: Long = 77,
    var stamp: String = "1",
    var selinux: String = "u:object_r:app_data_file:s0",
    val xattrs: MutableMap<String, ByteArray> = linkedMapOf(),
)

/**
 * FD table with the real `linkat(AT_EMPTY_PATH)` shape: linking follows the *inode* a descriptor
 * refers to, never the staged name a directory owner could swap.
 */
private class PinnedFdOps : PosixFdOperations {
    var root = Node(1000, true, "/workspace", mode = 448, links = 2, uid = 0, gid = 0)
    val nodes = linkedMapOf<String, Node>()
    var ancestorsAboveRoot: List<RootFdNodeIdentity> = emptyList()
    var serial = 2000L
    var staged = 0
    var onLink: (() -> Unit)? = null
    var swapStagedNameBeforeLink = false
    var onMkdir: ((String) -> Unit)? = null
    var failFsyncAfterCommit = false
    var dirFsyncCount = 0

    inner class F(val node: Node, val openPath: String) : PosixRetainedFd {
        override val fdNumber = (++serial).toInt()
        override var isOpen = true
        override fun close() { isOpen = false }
    }

    fun identity(n: Node) = RootFdNodeIdentity(1, n.inode, n.mount, n.dir, !n.dir, false, n.links, n.mode, n.uid, n.gid, n.selinux)

    fun add(name: String, text: String) {
        nodes[name] = Node(serial++, false, "${root.path}/$name", text.toByteArray(), uid = root.uid, gid = root.gid, selinux = root.selinux)
    }

    override fun openDirectoryAbsoluteNoFollow(absolutePath: String): PosixRetainedFd = F(root, root.path)

    override fun openDirectoryWithAncestorsNoFollow(absolutePath: String) =
        openDirectoryAbsoluteNoFollow(absolutePath) to ancestorsAboveRoot

    override fun openChildDirectoryAtNoFollow(parentFd: PosixRetainedFd, childName: String): PosixRetainedFd =
        nodes[childName]?.takeIf { it.dir }?.let { F(it, "${(parentFd as F).openPath}/$childName") }
            ?: throw PosixOpException("ENOENT")

    override fun openTargetAt(
        parentFd: PosixRetainedFd,
        childName: String,
        followSymlink: Boolean,
        writable: Boolean,
    ): PosixRetainedFd {
        val node = nodes[childName] ?: throw PosixOpException("ENOENT")
        return F(node, "${(parentFd as F).openPath}/$childName")
    }

    override fun statAtNoFollow(parentFd: PosixRetainedFd, childName: String) = nodes[childName]?.let(::identity)

    override fun fstatIdentity(fd: PosixRetainedFd) = if (fd.isOpen) identity((fd as F).node) else null

    override fun fstatSizeBytes(fd: PosixRetainedFd) = (fd as F).node.bytes.size.toLong()

    override fun fstatVersionStamp(fd: PosixRetainedFd) = (fd as F).node.stamp

    override fun fstatContentDigest(fd: PosixRetainedFd) = sha((fd as F).node.bytes.toString(Charsets.ISO_8859_1))

    override fun listChildNames(dirFd: PosixRetainedFd): List<String> = nodes.keys.sorted()

    override fun readFdResolvedPath(fd: PosixRetainedFd) = (fd as F).openPath

    override fun fchmod(fd: PosixRetainedFd, modeOctal: Int) {
        (fd as F).node.mode = modeOctal
        (fd as F).node.stamp += "c"
    }

    override fun fchown(fd: PosixRetainedFd, ownerUid: Int, ownerGid: Int) {
        val node = (fd as F).node
        node.uid = ownerUid
        node.gid = ownerGid
        node.mode = node.mode and 0b111_111_111
        node.stamp += "o"
    }

    override fun fsetSelinuxLabel(fd: PosixRetainedFd, selinuxLabel: String) {
        (fd as F).node.selinux = selinuxLabel
    }

    override fun flistxattr(fd: PosixRetainedFd): List<String> = (fd as F).node.xattrs.keys.toList()

    override fun fgetxattr(fd: PosixRetainedFd, name: String): ByteArray? = (fd as F).node.xattrs[name]?.copyOf()

    override fun fsetxattr(fd: PosixRetainedFd, name: String, value: ByteArray) {
        (fd as F).node.xattrs[name] = value.copyOf()
    }

    override fun createSiblingTempAt(parentFd: PosixRetainedFd, tempName: String, modeOctal: Int): PosixRetainedFd {
        staged++
        val node = Node(serial++, false, "${(parentFd as F).node.path}/$tempName", mode = modeOctal)
        nodes[tempName] = node
        return F(node, "${(parentFd as F).openPath}/$tempName")
    }

    override fun writeAllAndFsync(fd: PosixRetainedFd, bytes: ByteArray) {
        (fd as F).node.bytes = bytes.copyOf()
        (fd as F).node.stamp += "w"
    }

    override fun fsyncDirectory(dirFd: PosixRetainedFd) {
        dirFsyncCount++
        if (failFsyncAfterCommit && dirFsyncCount > 0) throw PosixOpException("ENOSPC")
    }

    override fun renameReplaceAt(parentFd: PosixRetainedFd, tempName: String, targetName: String) {
        val node = nodes.remove(tempName) ?: throw PosixOpException("ENOENT")
        node.path = "${(parentFd as F).node.path}/$targetName"
        nodes[targetName] = node
    }

    override fun linkNoReplaceAt(parentFd: PosixRetainedFd, tempName: String, targetName: String) {
        if (nodes.containsKey(targetName)) throw PosixOpException("EEXIST")
        val node = nodes[tempName] ?: throw PosixOpException("ENOENT")
        nodes[targetName] = node
        node.links += 1
    }

    /** The property under test: the link source is the inode behind the descriptor. */
    override fun linkAtFdNoReplace(parentFd: PosixRetainedFd, sourceFd: PosixRetainedFd, leafName: String) {
        onLink?.invoke()
        if (swapStagedNameBeforeLink) {
            // A directory owner rewrites every staged name it can see (rename-over semantics:
            // the replaced inode loses that link). The FD-pinned link must still commit the inode
            // the service wrote, not this replacement.
            nodes.keys.filter { it.startsWith(".mar-root-") }.toList().forEach { name ->
                nodes.remove(name)?.let { it.links = maxOf(0, it.links - 1) }
                nodes[name] = Node(serial++, false, "${root.path}/$name", "EVIL".toByteArray(), uid = root.uid, gid = root.gid)
            }
        }
        if (nodes.containsKey(leafName)) throw PosixOpException("EEXIST")
        val node = (sourceFd as F).node
        nodes[leafName] = node
        node.links += 1
    }

    override fun mkdirAt(parentFd: PosixRetainedFd, childName: String, modeOctal: Int) {
        val parentNode = (parentFd as F).node
        // The service creates as UID 0 even inside an app-owned parent.
        nodes[childName] = Node(
            serial++, true, "${parentNode.path}/$childName",
            mode = modeOctal, uid = 0, gid = 0, links = 2,
        )
        onMkdir?.invoke(childName)
    }

    /** Cross-directory rename with replace, as `renameat(2)` provides. */
    override fun renameAt(
        srcParentFd: PosixRetainedFd,
        srcName: String,
        dstParentFd: PosixRetainedFd,
        dstName: String,
    ) {
        val node = nodes.remove(srcName) ?: throw PosixOpException("ENOENT")
        nodes[dstName] = node
        node.path = "${(dstParentFd as F).node.path}/$dstName"
    }

    override fun removeDirectoryAt(parentFd: PosixRetainedFd, childName: String) {
        val node = nodes[childName] ?: throw PosixOpException("ENOENT")
        if (!node.dir) throw PosixOpException("ENOTDIR")
        if (nodes.values.any { it !== node && it.path.startsWith(node.path + "/") }) {
            throw PosixOpException("ENOTEMPTY")
        }
        nodes.remove(childName)
    }

    /** Cross-directory, no-replace link (source name lives in the protected directory). */
    override fun linkAtNoReplace(
        srcParentFd: PosixRetainedFd,
        srcName: String,
        dstParentFd: PosixRetainedFd,
        dstName: String,
    ) {
        if (nodes.containsKey(dstName)) throw PosixOpException("EEXIST")
        val node = nodes[srcName] ?: throw PosixOpException("ENOENT")
        nodes[dstName] = node
        node.links += 1
    }

    override fun unlinkAt(parentFd: PosixRetainedFd, childName: String): Boolean {
        val node = nodes.remove(childName) ?: return false
        node.links = maxOf(0, node.links - 1)
        return true
    }
}

private fun boundary() = RootControlPlaneBoundary(
    "/data/user/0/app",
    RootFdNodeIdentity(1, 9001, 77, true, false, false, 2, 448, 10100, 10100),
    "/data/user_de/0/app",
    RootFdNodeIdentity(1, 9002, 77, true, false, false, 2, 448, 10100, 10100),
    listOf(RootFdNodeIdentity(1, 9003, 77, true, false, false, 2, 448, 10100, 10100)),
)

/**
 * N2: the SINGLE_APP_PRIVATE write domain can only offer *unconditional* content writes.
 *
 * Conditional replacement needs a directory-entry CAS that POSIX does not provide, and the target
 * app can rewrite its own 0700 directory at any moment, so `expected_version` and `apply_patch`
 * are refused before any staging. The remaining unconditional write commits the staged inode
 * through its FD (`linkat(AT_EMPTY_PATH)`), so swapping the staged *name* cannot commit foreign
 * bytes.
 */
class RootPrivateDomainCommitTest {
    private val appPrivateAncestors = listOf(
        RootFdNodeIdentity(1, 1, 1, true, false, false, 2, 493, 0, 0, "u:object_r:rootfs:s0"),
        RootFdNodeIdentity(1, 2, 77, true, false, false, 2, 505, 1000, 1000, "u:object_r:system_data_file:s0"),
        RootFdNodeIdentity(1, 3, 77, true, false, false, 2, 457, 1000, 1000, "u:object_r:system_data_file:s0"),
        RootFdNodeIdentity(1, 4, 77, true, false, false, 2, 505, 1000, 1000, "u:object_r:system_data_file:s0"),
        RootFdNodeIdentity(1, 5000, 77, true, false, false, 2, 448, 10200, 10200, "u:object_r:app_data_file:s0"),
    )

    private fun appPrivateOps(): PinnedFdOps = PinnedFdOps().apply {
        root = Node(5001L, true, "/data/user/0/com.example.fixture/files", mode = 448, uid = 10200, gid = 10200, links = 2)
        ancestorsAboveRoot = appPrivateAncestors
        add("file", "old!")
    }

    private fun uid0Ops(): PinnedFdOps = PinnedFdOps().apply {
        root = Node(7001L, true, "/data/local/root-workspace", mode = 448, uid = 0, gid = 0, links = 2)
        add("file", "old!")
    }

    private class Env(val ops: PinnedFdOps) {
        val driver = AndroidRootPosixFileDriver(Paths.get(ops.root.path), ops)
        val executor = RootWorkspaceFileExecutor(ops.identity(ops.root), boundary(), driver)

        fun version(): String =
            (executor.executeGetMetadata(RootTypedArguments.FileGetMetadata("ws", "file")) as RootFileOperationResult.Succeeded)
                .value.version

        fun write(text: String = "NEW!", expectedVersion: String? = null, replace: Boolean = true, path: String = "file") =
            executor.executeAtomicWriteText(
                RootTypedArguments.FileWriteText("ws", path, sha(text), text.toByteArray().size, replace, expectedVersion),
                text.toByteArray(),
            )
    }

    @Test
    fun appPrivateDirectoryIsRecognisedAsSingleAppDomain() {
        val ops = appPrivateOps()
        val env = Env(ops)
        val opened = requireNotNull(env.driver.inspectOpenedTarget("ws", "file", followFinalSymlink = false))
        try {
            assertEquals(RootWriteDomainKind.SINGLE_APP_PRIVATE, opened.writeDomain.kind)
        } finally {
            env.driver.releaseOpenedTarget(opened.retainedFdHandle)
            env.driver.close()
        }
    }

    @Test
    fun unconditionalReplaceStaysAvailableInThePrivateDomain() {
        val ops = appPrivateOps()
        val env = Env(ops)
        try {
            val result = env.write("NEW!")
            assertTrue(result is RootFileOperationResult.Succeeded, "got $result")
            assertEquals("NEW!", ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
            assertEquals(10200, ops.nodes["file"]!!.uid)
            assertEquals(384, ops.nodes["file"]!!.mode)
        } finally {
            env.driver.close()
        }
    }

    @Test
    fun unconditionalCreateStaysAvailableInThePrivateDomain() {
        val ops = appPrivateOps()
        val env = Env(ops)
        try {
            val result = env.write("hello", path = "created.txt")
            assertTrue(result is RootFileOperationResult.Succeeded, "got $result")
            assertEquals("hello", ops.nodes["created.txt"]!!.bytes.toString(Charsets.UTF_8))
            assertEquals(1L, ops.nodes["created.txt"]!!.links)
        } finally {
            env.driver.close()
        }
    }

    @Test
    fun expectedVersionIsRefusedBeforeStagingInThePrivateDomain() {
        val ops = appPrivateOps()
        val env = Env(ops)
        try {
            val version = env.version()
            val stagedBefore = ops.staged
            val result = env.write("NEW!", expectedVersion = version)
            assertTrue(result is RootFileOperationResult.Rejected, "got $result")
            assertEquals(RootFileOperationReason.WRITE_DOMAIN_VERSION_UNSUPPORTED, (result as RootFileOperationResult.Rejected).reason)
            assertEquals(stagedBefore, ops.staged, "A refused conditional write must not stage anything")
            assertEquals("old!", ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
        } finally {
            env.driver.close()
        }
    }

    @Test
    fun applyPatchIsRefusedInThePrivateDomain() {
        val ops = appPrivateOps()
        val env = Env(ops)
        try {
            val version = env.version()
            val patch = """{"format":"search_replace","operations":[]}""".toByteArray()
            val result = env.executor.executeApplyPatch(
                RootTypedArguments.FileApplyPatch(
                    workspaceId = "ws",
                    relativePath = "file",
                    patchSha256 = sha(String(patch)),
                    patchByteLength = patch.size,
                    expectedVersion = version,
                    format = "search_replace",
                ),
                patch,
            )
            assertTrue(result is RootFileOperationResult.Rejected, "got $result")
            assertEquals(
                RootFileOperationReason.WRITE_DOMAIN_VERSION_UNSUPPORTED,
                (result as RootFileOperationResult.Rejected).reason,
            )
            assertEquals("old!", ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
        } finally {
            env.driver.close()
        }
    }

    @Test
    fun swappingTheStagedNameCannotCommitForeignBytes() {
        val ops = appPrivateOps()
        val env = Env(ops)
        try {
            ops.swapStagedNameBeforeLink = true
            val result = env.write("NEW!")
            assertTrue(result is RootFileOperationResult.Succeeded, "got $result")
            assertEquals(
                "NEW!",
                ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8),
                "The commit must follow the staged inode, not the name the directory owner replaced",
            )
            assertFalse(
                ops.nodes.values.any { it.bytes.toString(Charsets.UTF_8) == "EVIL" && it.path == ops.root.path + "/file" },
                "Foreign bytes must never end up at the committed name",
            )
        } finally {
            env.driver.close()
        }
    }

    @Test
    fun uid0ExclusiveDomainStillAcceptsConditionalWrites() {
        val ops = uid0Ops()
        val env = Env(ops)
        try {
            val version = env.version()
            val result = env.write("NEW!", expectedVersion = version)
            assertTrue(result is RootFileOperationResult.Succeeded, "got $result")
            assertEquals("NEW!", ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
        } finally {
            env.driver.close()
        }
    }

    @Test
    fun aStaleConditionalVersionIsStillAConflictOnUid0Exclusive() {
        val ops = uid0Ops()
        val env = Env(ops)
        try {
            val stale = env.version()
            ops.add("file", "foreign")
            val result = env.write("NEW!", expectedVersion = stale)
            assertTrue(result is RootFileOperationResult.Rejected, "got $result")
            assertEquals("foreign", ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
        } finally {
            env.driver.close()
        }
    }

    @Test
    fun refusedConditionalWriteLeavesNoRetainedFd() {
        val ops = appPrivateOps()
        val env = Env(ops)
        try {
            val before = env.driver.openFdCount()
            val version = env.version()
            env.write("NEW!", expectedVersion = version)
            assertEquals(before, env.driver.openFdCount())
            assertNull(env.driver.inspectOpenedTarget("ws", "missing", followFinalSymlink = false))
            assertFalse(ops.nodes.containsKey("missing"))
        } finally {
            env.driver.close()
        }
    }

    /**
     * The staging directory must be proven through its FD: a directory the app swaps in after
     * `mkdirat` is not root-owned, so it is refused and never written to or removed.
     */
    @Test
    fun swappingTheStagingDirectoryForAnAppOwnedOneIsRejected() {
        val ops = appPrivateOps()
        val env = Env(ops)
        try {
            ops.onMkdir = { name ->
                val foreign = Node(
                    ops.serial++, true, "${ops.root.path}/$name",
                    mode = 448, uid = 10200, gid = 10200, links = 2,
                )
                ops.nodes[name] = foreign
            }
            val result = env.write("NEW!")
            assertTrue(
                result is RootFileOperationResult.Rejected || result is RootFileOperationResult.PermissionDenied,
                "a substituted staging directory must be refused, got $result",
            )
            assertEquals("old!", ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
            assertTrue(
                ops.nodes.values.any { it.dir && it.uid == 10200 },
                "the app-owned directory must be left untouched",
            )
        } finally {
            env.driver.close()
        }
    }

    /** Post-commit directory sync failure is UNKNOWN, not success and not a rollback claim. */
    @Test
    fun directorySyncFailureAfterCommitIsReportedAsUnknownOutcome() {
        val ops = appPrivateOps()
        val env = Env(ops)
        try {
            ops.failFsyncAfterCommit = true
            val result = env.write("NEW!")
            assertTrue(result is RootFileOperationResult.PostMutationFailure, "got $result")
            assertEquals(
                RootServiceTerminalOutcome.UNKNOWN_OUTCOME,
                (result as RootFileOperationResult.PostMutationFailure).terminalOutcome,
            )
            assertEquals("NEW!", ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
        } finally {
            env.driver.close()
        }
    }

    @Test
    fun protectedStagingLeavesNoPayloadOrDirectoryBehind() {
        val ops = appPrivateOps()
        val env = Env(ops)
        try {
            assertTrue(env.write("NEW!") is RootFileOperationResult.Succeeded)
            assertEquals("NEW!", ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
            assertEquals(1L, ops.nodes["file"]!!.links)
            assertTrue(ops.nodes.keys.none { it.startsWith(".mar-root-stage-") || it == "payload" }, "staging litter: ${ops.nodes.keys}")
            assertTrue(ops.nodes.values.none { it.dir }, "staging directory was not removed")
        } finally {
            env.driver.close()
        }
    }

}
