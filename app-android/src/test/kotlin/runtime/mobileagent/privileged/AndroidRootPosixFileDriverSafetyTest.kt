// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.privileged

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import runtime.mobileagent.domain.*
import java.nio.file.Paths
import java.security.MessageDigest

private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    .joinToString("") { "%02x".format(it.toInt() and 255) }

private data class N(
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
    /** A symlink entry: `bytes` holds the link text and the kernel never follows it implicitly. */
    var symlink: Boolean = false,
    val xattrs: MutableMap<String, ByteArray> = linkedMapOf(),
)

/** In-memory FD table with injectable final-window races and close hooks. */
private class Ops : PosixFdOperations {
    var root = N(1000, true, "/workspace", mode = 448, links = 2, uid = 0, gid = 0)
    var parent = root
    val nodes = linkedMapOf<String, N>()
    val directories = mutableMapOf<String, N>()
    var ancestorsAboveRoot: List<RootFdNodeIdentity> = emptyList()
    var serial = 2000L
    var opened = 0
    var closed = 0
    var onStage: (() -> Unit)? = null
    var onOpen: (() -> Unit)? = null
    var onRename: (() -> Unit)? = null
    var onAfterRename: (() -> Unit)? = null
    /** Injects a destination entry that appears inside the `rename(2)` window. */
    var onBeforeRename: (() -> Unit)? = null
    var swapTempNameOnCommit = false
    var failLabel = false
    var failSetXattrName: String? = null
    var failCleanup = false
    var failFsync = false
    var failWrite = false
    var failWriteBeforeBytes = false
    var failTruncate = false
    var failRead = false
    var failMkdir = false
    var closedDriverDuringWrite: (() -> Unit)? = null
    /** Mount-type switch: the same in-memory tree served as FUSE/sdcardfs. */
    var emulated = false
    /** FUSE/sdcardfs synthesize directory owner/mode/label; nothing may force them afterwards. */
    var fuseDirectoryMode = 0b111_111_000
    var fuseDirectoryGid = 9997
    var fuseDirectoryLabel = "u:object_r:fuse:s0"
    var onAfterMkdir: (() -> Unit)? = null
    /**
     * `renameat2(RENAME_NOREPLACE)` is a kernel/filesystem capability, not a mount-type proxy; the
     * shipped supervisor library provides it, so the default models a kernel that supports it.
     */
    var atomicNoReplaceRenameSupported = true
    var onBeforeNoReplaceRename: (() -> Unit)? = null
    /** Errno a directory fsync reports instead of succeeding (`EINVAL`/`ENOSYS`/`EIO`, ...). */
    var failFsyncErrno: String? = null
    var afterWrite: (() -> Unit)? = null
    var writableOpens = 0
    var truncates = 0
    var writeCalls = 0
    var stageCalls = 0
    var renameCalls = 0
    var renameAtNoReplaceCalls = 0
    var linkCalls = 0
    var unlinkCalls = 0
    var chmodCalls = 0
    var chownCalls = 0
    var labelCalls = 0

    inner class F(
        val node: N,
        val openPath: String = node.path,
        /** True for a descriptor from [openWritableFd]: writes do not shorten the file by themselves. */
        val inPlace: Boolean = false,
    ) : PosixRetainedFd {
        override val fdNumber = ++opened
        override var isOpen = true
        override fun close() {
            if (isOpen) {
                closed++
                isOpen = false
            }
        }
    }

    fun identity(n: N) = RootFdNodeIdentity(
        1, n.inode, n.mount, n.dir, !n.dir && !n.symlink, n.symlink, n.links, n.mode, n.uid, n.gid,
        n.selinux,
    )

    fun add(name: String, text: String = "old!") {
        nodes[name] = N(serial++, false, "${parent.path}/$name", text.toByteArray(), uid = parent.uid, gid = parent.gid, selinux = parent.selinux)
    }

    fun addDir(name: String) {
        nodes[name] = N(serial++, true, "${parent.path}/$name", mode = 448, links = 2, uid = parent.uid, gid = parent.gid, selinux = parent.selinux)
    }

    override fun openDirectoryAbsoluteNoFollow(absolutePath: String): PosixRetainedFd {
        onOpen?.invoke()
        return F(root, root.path)
    }

    override fun openDirectoryWithAncestorsNoFollow(
        absolutePath: String,
    ): Pair<PosixRetainedFd, List<RootFdNodeIdentity>> =
        openDirectoryAbsoluteNoFollow(absolutePath) to ancestorsAboveRoot

    override fun openChildDirectoryAtNoFollow(parentFd: PosixRetainedFd, childName: String): PosixRetainedFd {
        val node = directories[childName] ?: nodes[childName]?.takeIf { it.dir }
            ?: throw PosixOpException("ENOENT")
        return F(node, "${(parentFd as F).openPath}/$childName")
    }

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

    override fun usesEmulatedStoragePermissions(fd: PosixRetainedFd) = emulated

    /** Reopens the retained descriptor itself, never a directory entry, for in-place writes. */
    override fun openWritableFd(fd: PosixRetainedFd): PosixRetainedFd {
        writableOpens++
        val source = fd as F
        return F(source.node, source.openPath, inPlace = true)
    }

    /** ftruncate(2) on the existing file: the old tail is gone before the first byte is written. */
    override fun truncate(fd: PosixRetainedFd, length: Long) {
        truncates++
        if (failTruncate) throw PosixOpException("EROFS")
        val node = (fd as F).node
        node.bytes = node.bytes.copyOf(minOf(length, node.bytes.size.toLong()).toInt())
    }

    override fun fstatContentDigest(fd: PosixRetainedFd) = sha((fd as F).node.bytes.toString(Charsets.ISO_8859_1))

    override fun readFdRange(fd: PosixRetainedFd, offsetBytes: Long, maxBytes: Int): ByteArray {
        if (failRead) throw PosixOpException("EIO")
        val bytes = (fd as F).node.bytes
        val from = offsetBytes.toInt()
        return bytes.copyOfRange(from, minOf(bytes.size, from + maxBytes))
    }

    override fun listChildNames(dirFd: PosixRetainedFd): List<String> = nodes.keys.sorted()

    override fun readFdResolvedPath(fd: PosixRetainedFd) = (fd as F).openPath

    override fun fchmod(fd: PosixRetainedFd, modeOctal: Int) {
        chmodCalls++
        (fd as F).node.mode = modeOctal
        fd.node.stamp += "c"
    }

    override fun fchown(fd: PosixRetainedFd, ownerUid: Int, ownerGid: Int) {
        chownCalls++
        val node = (fd as F).node
        node.uid = ownerUid
        node.gid = ownerGid
        // Real fchown clears setuid/setgid; the staging order must re-apply them afterwards.
        node.mode = node.mode and 0b111_111_111
        node.stamp += "o"
    }

    override fun fsetSelinuxLabel(fd: PosixRetainedFd, selinuxLabel: String) {
        labelCalls++
        if (failLabel) throw PosixOpException("EOPNOTSUPP", unsupportedAttribute = "security.selinux")
        (fd as F).node.selinux = selinuxLabel
    }

    override fun flistxattr(fd: PosixRetainedFd): List<String> =
        (fd as F).node.xattrs.keys.toList()

    override fun fgetxattr(fd: PosixRetainedFd, name: String): ByteArray? =
        (fd as F).node.xattrs[name]?.copyOf()

    override fun fsetxattr(fd: PosixRetainedFd, name: String, value: ByteArray) {
        if (failSetXattrName == name) {
            throw PosixOpException("EOPNOTSUPP", unsupportedAttribute = name)
        }
        (fd as F).node.xattrs[name] = value.copyOf()
    }

    override fun createSiblingTempAt(parentFd: PosixRetainedFd, tempName: String, modeOctal: Int): PosixRetainedFd {
        stageCalls++
        onStage?.invoke()
        // O_CREAT | O_EXCL: an entry that appeared after inspection is never overwritten.
        if (nodes.containsKey(tempName)) throw PosixOpException("EEXIST")
        val node = N(serial++, false, "${(parentFd as F).node.path}/$tempName", mode = modeOctal)
        nodes[tempName] = node
        return F(node, "${(parentFd as F).openPath}/$tempName")
    }

    override fun writeAllAndFsync(fd: PosixRetainedFd, bytes: ByteArray) {
        writeCalls++
        if (failWriteBeforeBytes) throw PosixOpException("EIO")
        val handle = fd as F
        if (handle.inPlace) {
            // pwrite(2) at offset 0 of an existing file: only truncate(2) may shorten it.
            val merged = handle.node.bytes.copyOf(maxOf(handle.node.bytes.size, bytes.size))
            bytes.copyInto(merged)
            handle.node.bytes = merged
        } else {
            handle.node.bytes = bytes.copyOf()
        }
        handle.node.stamp += "w"
        closedDriverDuringWrite?.invoke()
        afterWrite?.invoke()
        if (failWrite) throw PosixOpException("EIO")
    }

    override fun fsyncDirectory(dirFd: PosixRetainedFd) {
        if (failFsync) throw PosixOpException("EIO")
        failFsyncErrno?.let { throw PosixOpException(it) }
    }

    override fun renameReplaceAt(parentFd: PosixRetainedFd, tempName: String, targetName: String) {
        renameCalls++
        onRename?.invoke()
        if (swapTempNameOnCommit) {
            nodes[tempName] = N(serial++, false, "${(parentFd as F).node.path}/$tempName", "EVIL".toByteArray())
        }
        val node = nodes.remove(tempName) ?: throw PosixOpException("ENOENT")
        node.path = "${(parentFd as F).node.path}/$targetName"
        nodes[targetName] = node
        onAfterRename?.invoke()
    }

    override fun linkNoReplaceAt(parentFd: PosixRetainedFd, tempName: String, targetName: String) {
        if (nodes.containsKey(targetName)) throw PosixOpException("EEXIST")
        val node = nodes[tempName] ?: throw PosixOpException("ENOENT")
        nodes[targetName] = node
        node.links += 1
    }

    /** FD-pinned link: commits the inode the descriptor refers to, never a staged name. */
    override fun linkAtFdNoReplace(parentFd: PosixRetainedFd, sourceFd: PosixRetainedFd, leafName: String) {
        linkCalls++
        if (swapTempNameOnCommit) {
            nodes.keys.filter { it.startsWith(".mar-root-") }.toList().forEach { name ->
                nodes.remove(name)?.let { it.links = maxOf(0, it.links - 1) }
                nodes[name] = N(serial++, false, "${root.path}/$name", "EVIL".toByteArray())
            }
        }
        if (nodes.containsKey(leafName)) throw PosixOpException("EEXIST")
        val node = (sourceFd as F).node
        nodes[leafName] = node
        node.links += 1
    }

    override fun removeDirectoryAt(parentFd: PosixRetainedFd, childName: String) {
        val node = nodes[childName] ?: throw PosixOpException("ENOENT")
        if (!node.dir) throw PosixOpException("ENOTDIR")
        if (nodes.values.any { it !== node && it.path.startsWith(node.path + "/") }) {
            throw PosixOpException("ENOTEMPTY")
        }
        nodes.remove(childName)
    }

    override fun unlinkAt(parentFd: PosixRetainedFd, childName: String): Boolean {
        unlinkCalls++
        if (failCleanup || !parentFd.isOpen) return false
        val node = nodes.remove(childName) ?: return false
        node.links = maxOf(0, node.links - 1)
        return true
    }

    override fun mkdirAt(parentFd: PosixRetainedFd, childName: String, modeOctal: Int) {
        if (failMkdir) throw PosixOpException("EPERM")
        if (nodes.containsKey(childName)) throw PosixOpException("EEXIST")
        val parentNode = (parentFd as F).node
        val node = N(
            serial++,
            true,
            "${parentNode.path}/$childName",
            mode = modeOctal,
            // mkdirat(2) runs as the service (UID 0); the caller fchowns afterwards.
            uid = 0,
            gid = 0,
            links = 2,
            selinux = parentNode.selinux,
        )
        if (emulated) {
            // FUSE/sdcardfs synthesize the directory attributes; chmod/chown/chcon are refused.
            node.mode = fuseDirectoryMode
            node.gid = fuseDirectoryGid
            node.selinux = fuseDirectoryLabel
        }
        nodes[childName] = node
        onAfterMkdir?.invoke()
    }

    /**
     * `renameat2(..., RENAME_NOREPLACE)`: never replaces an existing destination entry, and the
     * kernel removes the source name as part of the same atomic step.
     */
    override fun renameAtNoReplace(
        srcParentFd: PosixRetainedFd,
        srcName: String,
        dstParentFd: PosixRetainedFd,
        dstName: String,
    ) {
        renameAtNoReplaceCalls++
        if (!atomicNoReplaceRenameSupported) throw PosixOpException("EOPNOTSUPP")
        onBeforeNoReplaceRename?.invoke()
        if (nodes.containsKey(dstName)) throw PosixOpException("EEXIST")
        val node = nodes.remove(srcName) ?: throw PosixOpException("ENOENT")
        nodes[dstName] = node
    }

    /**
     * POSIX `rename(2)`: replaces the destination *entry* itself (a symlink is replaced, never
     * followed) and lets the kernel refuse incompatible types — a non-directory source cannot
     * replace a directory (`EISDIR`), a directory source cannot replace a file (`ENOTDIR`), and a
     * non-empty directory destination is refused (`ENOTEMPTY`).
     */
    override fun renameAt(
        srcParentFd: PosixRetainedFd,
        srcName: String,
        dstParentFd: PosixRetainedFd,
        dstName: String,
    ) {
        renameCalls++
        onBeforeRename?.invoke()
        val source = nodes[srcName] ?: throw PosixOpException("ENOENT")
        val destination = nodes[dstName]
        if (destination != null) {
            if (destination.dir && !source.dir) throw PosixOpException("EISDIR")
            if (!destination.dir && source.dir) throw PosixOpException("ENOTDIR")
            if (destination.dir && destination.children().isNotEmpty()) throw PosixOpException("ENOTEMPTY")
        }
        nodes.remove(srcName)
        nodes[dstName] = source
        source.path = "${(dstParentFd as F).node.path}/$dstName"
    }

    private fun N.children(): List<N> =
        nodes.values.filter { it !== this && it.path.startsWith(path.trimEnd('/') + "/") }

    override fun linkAtNoReplace(
        srcParentFd: PosixRetainedFd,
        srcName: String,
        dstParentFd: PosixRetainedFd,
        dstName: String,
    ) {
        linkCalls++
        if (nodes.containsKey(dstName)) throw PosixOpException("EEXIST")
        val node = nodes[srcName] ?: throw PosixOpException("ENOENT")
        nodes[dstName] = node
        node.links += 1
    }
}

private fun boundary() = RootControlPlaneBoundary(
    "/data/user/0/app",
    RootFdNodeIdentity(1, 9001, 77, true, false, false, 2, 448, 10100, 10100),
    "/data/user_de/0/app",
    RootFdNodeIdentity(1, 9002, 77, true, false, false, 2, 448, 10100, 10100),
    listOf(RootFdNodeIdentity(1, 9003, 77, true, false, false, 2, 448, 10100, 10100)),
)

private class Env {
    val ops = Ops()
    val originalRoot = ops.identity(ops.root)
    val driver = AndroidRootPosixFileDriver(Paths.get("/workspace"), ops)
    val exe = RootWorkspaceFileExecutor(originalRoot, boundary(), driver)

    init {
        ops.add("file")
    }

    fun version(): String =
        (exe.executeGetMetadata(RootTypedArguments.FileGetMetadata("ws", "file")) as RootFileOperationResult.Succeeded)
            .value.version

    fun write(version: String? = null, text: String = "NEW!") = exe.executeAtomicWriteText(
        RootTypedArguments.FileWriteText("ws", "file", sha(text), text.toByteArray().size, true, version),
        text.toByteArray(),
    )
}

/** [Env] whose workspace is served by emulated storage (FUSE/sdcardfs mount semantics). */
private fun fuseEnv() = Env().also { it.ops.emulated = true }

private fun assertUnknown(r: RootFileOperationResult<*>) {
    assertTrue(r is RootFileOperationResult.PostMutationFailure, "expected post-mutation failure, got $r")
    r as RootFileOperationResult.PostMutationFailure
    assertEquals(RootServiceTerminalOutcome.UNKNOWN_OUTCOME, r.terminalOutcome)
    assertTrue(r.requiresDegradedEpoch)
}

class AndroidRootPosixFileDriverSafetyTest {
    @Test
    fun literalWhitespacePathIsNotTrimmed() {
        val e = Env()
        e.ops.add("file ", "tail")
        e.ops.add(" file", "lead")
        for (name in listOf("file ", " file")) {
            val r = e.exe.executeGetMetadata(RootTypedArguments.FileGetMetadata("ws", name))
            assertTrue(r is RootFileOperationResult.Succeeded)
            assertEquals(e.ops.nodes[name]!!.inode, (r as RootFileOperationResult.Succeeded).value.inode)
        }
        e.driver.close()
    }

    @Test
    fun postCommitAttributeDriftIsUnknownAndDegrades() {
        val e = Env()
        e.ops.onAfterRename = { e.ops.nodes["file"]!!.uid = 9999 }
        assertUnknown(e.write())
        e.driver.close()
    }

    @Test
    fun failedAttributeCleanupIsUnknownAndDegrades() {
        val e = Env()
        e.ops.failLabel = true
        e.ops.failCleanup = true
        assertUnknown(e.write())
        assertEquals(1, e.ops.nodes.keys.count { it.startsWith(".mar-root-tmp-") })
        e.driver.close()
    }

    @Test
    fun directoryFsyncFailureIsUnknownAndDegrades() {
        val e = Env()
        e.ops.failFsync = true
        assertUnknown(e.write())
        e.driver.close()
    }

    @Test
    fun closeDuringWriteClosesInflightFdsAndPreventsCommit() {
        val e = Env()
        var openAfterClose = -1
        e.ops.closedDriverDuringWrite = {
            e.driver.close()
            openAfterClose = e.ops.opened - e.ops.closed
        }
        val result = e.write()
        assertUnknown(result)
        assertEquals("old!", e.ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
        assertEquals(0, openAfterClose)
        assertNull(e.driver.inspectOpenedTarget("ws", "file", false))
        assertNull(e.driver.inspectParentForCreate("ws", "missing"))
        e.driver.close()
    }

    @Test
    fun interruptBeforeCommitLeavesOriginalFileUntouched() {
        val e = Env()
        e.ops.closedDriverDuringWrite = { Thread.currentThread().interrupt() }
        try {
            assertFalse(e.write() is RootFileOperationResult.Succeeded)
            assertEquals("old!", e.ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
        } finally {
            Thread.interrupted()
            e.driver.close()
        }
    }

    @Test
    fun rootReplacementOnSameMountIsRejected() {
        val e = Env()
        e.ops.root = N(9999, true, "/workspace", mode = 448, links = 2, uid = 0, gid = 0)
        e.ops.parent = e.ops.root
        assertTrue(e.write() is RootFileOperationResult.Rejected)
        e.driver.close()
    }

    @Test
    fun protectedRootAliasIsRejected() {
        val e = Env()
        e.ops.root = N(9003, true, "/alias", mode = 448, links = 2, mount = 88, uid = 0, gid = 0)
        e.ops.parent = N(9100, true, "/alias/subdir", mode = 448, links = 2, mount = 88, uid = 0, gid = 0)
        e.ops.add("secret")
        e.ops.nodes["secret"]!!.mount = 88
        val driver = AndroidRootPosixFileDriver(Paths.get("/alias"), e.ops)
        val exe = RootWorkspaceFileExecutor(e.ops.identity(e.ops.root), boundary(), driver)
        assertTrue(
            exe.executeGetMetadata(RootTypedArguments.FileGetMetadata("ws", "subdir/secret"))
                is RootFileOperationResult.Rejected,
        )
        driver.close()
        e.driver.close()
    }

    @Test
    fun changedVersionWhileStagingIsRejected() {
        val e = Env()
        val v = e.version()
        e.ops.onStage = {
            e.ops.nodes["file"]!!.bytes = "EDIT".toByteArray()
            e.ops.nodes["file"]!!.stamp = "changed"
        }
        assertTrue(e.write(v) is RootFileOperationResult.Rejected)
        assertEquals("EDIT", e.ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
        e.driver.close()
    }

    @Test
    fun metadataRechecksHardlinksAndVersionBeforeMutation() {
        for (change in listOf<(N) -> Unit>({ it.links = 2 }, { it.bytes = "EDIT".toByteArray() })) {
            val e = Env()
            val target = e.driver.inspectOpenedTarget("ws", "file", false)!!
            change(e.ops.nodes["file"]!!)
            assertEquals(
                DriverMutationStatus.RaceOrDisappeared,
                e.driver.applyMetadataOnOpenedFd(target, 420, null, null, null),
            )
            assertEquals(384, e.ops.nodes["file"]!!.mode)
            e.driver.close()
        }
    }

    @Test
    fun everyAncestorIsCheckedBeforeOpeningItsChild() {
        for (ancestor in listOf(
            N(9003, true, "/workspace/alias", links = 2, uid = 0, gid = 0),
            N(9004, true, "/workspace/mount", links = 2, mount = 88, uid = 0, gid = 0),
        )) {
            val e = Env()
            e.ops.directories["first"] = ancestor
            e.ops.parent = N(9100, true, "/workspace/first/last", links = 2, uid = 0, gid = 0)
            e.ops.add("secret")
            val r = e.exe.executeGetMetadata(RootTypedArguments.FileGetMetadata("ws", "first/last/secret"))
            assertTrue(r is RootFileOperationResult.Rejected)
            assertEquals(0, e.ops.opened - e.ops.closed)
            e.driver.close()
        }
    }

    @Test
    fun closeWhileOpeningDoesNotPublishRetainedHandle() {
        val e = Env()
        e.ops.onOpen = { e.driver.close() }
        assertNull(e.driver.inspectOpenedTarget("ws", "file", false))
        assertEquals(0, e.ops.opened - e.ops.closed)
    }

    @Test
    fun closeFromAnotherThreadStopsPausedWrite() {
        val e = Env()
        val staged = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<RootFileOperationResult<*>>()
        e.ops.closedDriverDuringWrite = {
            staged.countDown()
            check(resume.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        val worker = Thread { result.set(e.write()) }
        worker.start()
        try {
            assertTrue(staged.await(5, java.util.concurrent.TimeUnit.SECONDS))
            e.driver.close()
            assertEquals(0, e.ops.opened - e.ops.closed)
        } finally {
            resume.countDown()
            worker.join(5000)
        }
        assertFalse(worker.isAlive)
        assertFalse(result.get() is RootFileOperationResult.Succeeded)
        assertEquals("old!", e.ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun tempCreatedDuringReentrantCloseIsNotReportedAsOrdinaryDenial() {
        val e = Env()
        e.ops.onStage = { e.driver.close() }
        assertUnknown(e.write())
        assertEquals("old!", e.ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun absoluteRootOpenWalksEachComponentWithoutFollowingLinks() {
        val paths = mutableListOf<String>()
        var fd = 40
        val kernel = java.lang.reflect.Proxy.newProxyInstance(
            PosixKernelBridge::class.java.classLoader,
            arrayOf(PosixKernelBridge::class.java),
        ) { _, method, args ->
            when (method.name) {
                "open" -> {
                    paths += args!![0] as String
                    java.io.FileDescriptor() to ++fd
                }
                "close" -> Unit
                else -> error("Unexpected kernel call ${method.name}")
            }
        } as PosixKernelBridge
        val ops = AndroidOsPosixFdOperations(kernel = kernel)
        ops.openDirectoryAbsoluteNoFollow("/workspace/nested")!!.close()
        assertEquals(listOf("/", "/proc/self/fd/41/workspace", "/proc/self/fd/42/nested"), paths)
    }

    @Test
    fun unavailableFullContentDigestFailsClosed() {
        val kernel = object : PosixKernelBridge {
            override fun open(path: String, flags: Int, mode: Int) = java.io.FileDescriptor() to 41
            override fun close(rawFd: java.io.FileDescriptor, fdNumber: Int) = Unit
            override fun fstat(rawFd: java.io.FileDescriptor, fdNumber: Int) =
                PosixRawStat(1, 2, 0x8000 or 384, 1, 10100, 10100, 4, 1, 1)
            override fun lstat(path: String) = fstat(java.io.FileDescriptor(), 41)
            override fun readlink(path: String) = "/data"
            override fun readMountId(fdNumber: Int) = 77L
            override fun getxattr(path: String, name: String) = "u:object_r:app_data_file:s0".toByteArray()
            override fun setxattr(path: String, name: String, value: ByteArray, flags: Int) = Unit
            override fun preadSampleDigest(rawFd: java.io.FileDescriptor, fdNumber: Int, sizeBytes: Long): String =
                throw PosixOpException("EIO")
            override fun preadFullDigest(rawFd: java.io.FileDescriptor, fdNumber: Int, sizeBytes: Long): String =
                throw PosixOpException("EIO")
            override fun fchmod(rawFd: java.io.FileDescriptor, fdNumber: Int, mode: Int) = Unit
            override fun fchown(rawFd: java.io.FileDescriptor, fdNumber: Int, uid: Int, gid: Int) = Unit
            override fun writeAllAndFsync(rawFd: java.io.FileDescriptor, fdNumber: Int, bytes: ByteArray) = Unit
            override fun fsync(rawFd: java.io.FileDescriptor, fdNumber: Int) = Unit
            override fun rename(src: String, dst: String) = Unit
            override fun link(src: String, dst: String) = Unit
            override fun remove(path: String) = Unit
        }
        val ops = AndroidOsPosixFdOperations(kernel = kernel)
        val fd = ops.openDirectoryAbsoluteNoFollow("/")!!
        try {
            assertThrows(PosixOpException::class.java) { ops.fstatContentDigest(fd) }
        } finally {
            fd.close()
        }
    }

    @Test
    fun literalWhitespaceMetadataAndWriteLeaveNeighbourUntouched() {
        val e = Env()
        e.ops.add("file ", "tail")
        e.ops.add(" file", "lead")
        for (name in listOf("file ", " file")) {
            val before = e.exe.executeGetMetadata(RootTypedArguments.FileGetMetadata("ws", name))
                as RootFileOperationResult.Succeeded
            val changed = e.exe.executeSetMetadata(
                RootTypedArguments.FileSetMetadata("ws", name, before.value.version, mode = 420),
            )
            assertTrue(changed is RootFileOperationResult.Succeeded)
            val result = e.exe.executeAtomicWriteText(
                RootTypedArguments.FileWriteText("ws", name, sha("NEW!"), 4, true),
                "NEW!".toByteArray(),
            )
            assertTrue(result is RootFileOperationResult.Succeeded)
            assertEquals("NEW!", e.ops.nodes[name]!!.bytes.toString(Charsets.UTF_8))
        }
        assertEquals(384, e.ops.nodes["file"]!!.mode)
        assertEquals("old!", e.ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
        e.driver.close()
    }

    @Test
    fun writeFailureWithUnconfirmedCleanupIsUnknown() {
        val e = Env()
        e.ops.failWrite = true
        e.ops.failCleanup = true
        assertUnknown(e.write())
        e.driver.close()
    }

    @Test
    fun observedRaceWithUnconfirmedCleanupIsUnknown() {
        val e = Env()
        e.ops.onStage = { e.ops.nodes["file"]!!.links = 2 }
        e.ops.failCleanup = true
        assertUnknown(e.write())
        e.driver.close()
    }

    @Test
    fun successfulCreateWithUnconfirmedTempUnlinkIsUnknown() {
        val e = Env()
        e.ops.nodes.remove("file")
        e.ops.failCleanup = true
        assertUnknown(e.write())
        assertEquals("NEW!", e.ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
        e.driver.close()
    }

    @Test
    fun confirmedCleanupAllowsOrdinaryUnsupportedResult() {
        val e = Env()
        e.ops.failLabel = true
        assertTrue(e.write() is RootFileOperationResult.UnsupportedAttributePreservation)
        assertEquals(listOf("file"), e.ops.nodes.keys.toList())
        e.driver.close()
    }

    // ---- Round 1: non-UID0 parent refuses before creating a temp or touching the target ----
    @Test
    fun sharedParentRejectsConditionalWriteBeforeAnySideEffect() {
        val e = Env()
        e.ops.root = N(1000, true, "/workspace", mode = 448, links = 2, uid = 10_100, gid = 10_100)
        e.ops.parent = e.ops.root
        val driver = AndroidRootPosixFileDriver(Paths.get("/workspace"), e.ops)
        val exe = RootWorkspaceFileExecutor(e.ops.identity(e.ops.root), boundary(), driver)
        val serialBefore = e.ops.serial
        val result = exe.executeAtomicWriteText(
            RootTypedArguments.FileWriteText("ws", "file", sha("NEW!"), 4, true, e.version()),
            "NEW!".toByteArray(),
        )
        assertTrue(result is RootFileOperationResult.Rejected, "got $result")
        assertEquals(RootConfinementRejection.WRITE_DOMAIN_VERSION_UNSUPPORTED, (result as RootFileOperationResult.Rejected).rejection)
        assertEquals("old!", e.ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
        assertEquals(serialBefore, e.ops.serial, "no temp file may be created before the write-domain refusal")
        driver.close()
    }

    // ---- Round 2: final target rename swap stays behind the write-domain gate ----
    @Test
    fun round2FinalTargetSwapNeverSucceedsWithoutExclusiveDomain() {
        val e = Env()
        e.ops.root = N(1000, true, "/workspace", mode = 448, links = 2, uid = 10_100, gid = 10_100)
        e.ops.parent = e.ops.root
        val driver = AndroidRootPosixFileDriver(Paths.get("/workspace"), e.ops)
        val exe = RootWorkspaceFileExecutor(e.ops.identity(e.ops.root), boundary(), driver)
        e.ops.onRename = { e.ops.add("file", "RACE") }
        val result = exe.executeAtomicWriteText(
            RootTypedArguments.FileWriteText("ws", "file", sha("NEW!"), 4, true, e.version()),
            "NEW!".toByteArray(),
        )
        assertTrue(result is RootFileOperationResult.Rejected)
        assertEquals("old!", e.ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
        driver.close()
    }

    // ---- Round 3: temporary source swap is detected and never returns success ----
    @Test
    fun round3TempSourceSwapIsDetectedAndNeverSucceeds() {
        val e = Env()
        e.ops.swapTempNameOnCommit = true
        val result = e.write()
        assertFalse(result is RootFileOperationResult.Succeeded, "temp source swap must never report success")
        assertEquals(0, e.ops.nodes.keys.count { it.startsWith(".mar-root-tmp-") })
        e.driver.close()
    }

    // ---- Round 4: same-second in-place content race is caught by the full-content version ----
    @Test
    fun round4SameSecondTailEditIsCaughtByFullContentVersion() {
        val e = Env()
        val big = "a".repeat(100_000) + "TAIL"
        e.ops.nodes["file"]!!.bytes = big.toByteArray()
        val v = e.version()
        e.ops.onStage = {
            val bytes = e.ops.nodes["file"]!!.bytes.copyOf()
            bytes[bytes.lastIndex] = 'X'.code.toByte()
            e.ops.nodes["file"]!!.bytes = bytes
        }
        assertTrue(e.write(v) is RootFileOperationResult.Rejected)
        e.driver.close()
    }

    // ---- Round 5: metadata preconditions re-checked immediately before mutation ----
    @Test
    fun round5MetadataRechecksHardlinkAndVersionAtMutationTime() {
        val e = Env()
        val target = e.driver.inspectOpenedTarget("ws", "file", false)!!
        e.ops.nodes["file"]!!.links = 2
        assertEquals(
            DriverMutationStatus.RaceOrDisappeared,
            e.driver.applyMetadataOnOpenedFd(target, 420, null, null, null),
        )
        assertEquals(384, e.ops.nodes["file"]!!.mode)
        e.driver.close()
    }

    @Test
    fun readOnlyInspectionReleasesRetainedFdsToBaseline() {
        val e = Env()
        repeat(3) { e.exe.executeGetMetadata(RootTypedArguments.FileGetMetadata("ws", "file")) }
        assertEquals(0, e.ops.opened - e.ops.closed)
        e.exe.executeGetMetadata(RootTypedArguments.FileGetMetadata("ws", "missing"))
        assertEquals(0, e.ops.opened - e.ops.closed)
        e.driver.close()
    }

    @Test
    fun specialModeBitsArePreservedByContentReplacement() {
        val e = Env()
        e.ops.nodes["file"]!!.mode = 0b100_111_101_101 // 04755, includes setuid
        val v = e.version()
        assertEquals(0b100_111_101_101, (e.exe.executeGetMetadata(
            RootTypedArguments.FileGetMetadata("ws", "file"),
        ) as RootFileOperationResult.Succeeded).value.modeOctal)
        val result = e.write(v)
        assertTrue(result is RootFileOperationResult.Succeeded, "got $result")
        assertEquals(0b100_111_101_101, e.ops.nodes["file"]!!.mode)
        e.driver.close()
    }

    @Test
    fun metadataMutationOnSpecialModeFileIsExplicitlyRejected() {
        val e = Env()
        e.ops.nodes["file"]!!.mode = 0b100_111_101_101
        val before = e.exe.executeGetMetadata(RootTypedArguments.FileGetMetadata("ws", "file"))
            as RootFileOperationResult.Succeeded
        val result = e.exe.executeSetMetadata(
            RootTypedArguments.FileSetMetadata("ws", "file", before.value.version, mode = 420),
        )
        assertEquals(
            RootFileOperationResult.UnsupportedAttributePreservation("mode.special_bits"),
            result,
        )
        assertEquals(0b100_111_101_101, e.ops.nodes["file"]!!.mode)
        e.driver.close()
    }

    @Test
    fun listReadCreateDeleteMoveAndPatchPrimitivesWorkUnderProvenDomain() {
        val e = Env()
        e.ops.addDir("docs")
        e.ops.add("note.txt", "hello\n")
        e.exe.executeList(RootTypedArguments.FileList("ws", "", maxEntries = 10)) as RootFileOperationResult.Succeeded

        val read = e.exe.executeReadText(RootTypedArguments.FileReadText("ws", "note.txt", 0, 2))
            as RootFileOperationResult.Succeeded
        assertEquals("he", read.value.content.toString(Charsets.UTF_8))
        assertTrue(read.value.truncated)

        val mkdir = e.exe.executeCreateDirectory(RootTypedArguments.FileCreateDirectory("ws", "newdir"))
        assertTrue(mkdir is RootFileOperationResult.Succeeded, "got $mkdir")
        assertTrue(e.ops.nodes["newdir"]!!.dir)

        val move = e.exe.executeMove(RootTypedArguments.FileMove("ws", "note.txt", "moved.txt"))
        assertTrue(move is RootFileOperationResult.Succeeded, "got $move")
        assertNull(e.ops.nodes["note.txt"])
        assertEquals("hello\n", e.ops.nodes["moved.txt"]!!.bytes.toString(Charsets.UTF_8))

        val patchVersion = (e.exe.executeGetMetadata(RootTypedArguments.FileGetMetadata("ws", "moved.txt"))
            as RootFileOperationResult.Succeeded).value.version
        val patch = """{"search":"hello","replace":"hi"}""".toByteArray()
        val patched = e.exe.executeApplyPatch(
            RootTypedArguments.FileApplyPatch(
                "ws", "moved.txt", sha(String(patch)), patch.size, patchVersion,
                format = "search_replace",
            ),
            patch,
        )
        assertTrue(patched is RootFileOperationResult.Succeeded, "got $patched")
        assertEquals("hi\n", e.ops.nodes["moved.txt"]!!.bytes.toString(Charsets.UTF_8))

        val delete = e.exe.executeDelete(RootTypedArguments.FileDelete("ws", "moved.txt"))
        assertTrue(delete is RootFileOperationResult.Succeeded, "got $delete")
        assertNull(e.ops.nodes["moved.txt"])
        e.driver.close()
    }

    @Test
    fun applyPatchRejectsAmbiguousAndOversizeResultBeforeSideEffects() {
        val e = Env()
        e.ops.add("dup.txt", "aaaa")
        val version = (e.exe.executeGetMetadata(RootTypedArguments.FileGetMetadata("ws", "dup.txt"))
            as RootFileOperationResult.Succeeded).value.version
        val ambiguous = """{"search":"aa","replace":"b"}""".toByteArray()
        val ambiguousResult = e.exe.executeApplyPatch(
            RootTypedArguments.FileApplyPatch(
                "ws", "dup.txt", sha(String(ambiguous)), ambiguous.size, version,
                format = "search_replace",
            ),
            ambiguous,
        )
        assertTrue(ambiguousResult is RootFileOperationResult.Rejected)
        assertEquals("aaaa", e.ops.nodes["dup.txt"]!!.bytes.toString(Charsets.UTF_8))

        val insertion = """{"search":"a","replace":"aa","replaceAll":true}""".toByteArray()
        e.ops.nodes["dup.txt"]!!.bytes = "a".repeat(200_000).toByteArray()
        val oversizeVersion = (e.exe.executeGetMetadata(RootTypedArguments.FileGetMetadata("ws", "dup.txt"))
            as RootFileOperationResult.Succeeded).value.version
        val oversizeResult = e.exe.executeApplyPatch(
            RootTypedArguments.FileApplyPatch(
                "ws", "dup.txt", sha(String(insertion)), insertion.size, oversizeVersion,
                format = "search_replace",
            ),
            insertion,
        )
        assertTrue(oversizeResult is RootFileOperationResult.Rejected, "got $oversizeResult")
        assertTrue((oversizeResult as RootFileOperationResult.Rejected).reason.contains("patch_result_exceeds"))
        assertEquals(200_000, e.ops.nodes["dup.txt"]!!.bytes.size)
        e.driver.close()
    }

    @Test
    fun numericConfirmedMountWithoutFrozenRootIdentityDoesNotAuthorizeAlias() {
        val e = Env()
        e.ops.root = N(1000, true, "/alias", mode = 448, links = 2, mount = 88, uid = 0, gid = 0)
        e.ops.parent = N(9100, true, "/alias/subdir", mode = 448, links = 2, mount = 99, uid = 0, gid = 0)
        e.ops.directories["subdir"] = N(9200, true, "/alias/subdir", mode = 448, links = 2, mount = 99, uid = 0, gid = 0)
        e.ops.add("secret")
        e.ops.nodes["secret"]!!.mount = 99
        val driver = AndroidRootPosixFileDriver(Paths.get("/alias"), e.ops)
        val exe = RootWorkspaceFileExecutor(
            e.ops.identity(e.ops.root),
            boundary(),
            driver,
            confirmedMountIds = setOf(99L),
        )
        val r = exe.executeGetMetadata(RootTypedArguments.FileGetMetadata("ws", "subdir/secret"))
        assertTrue(r is RootFileOperationResult.Rejected, "got $r")
        driver.close()
    }

    @Test
    fun productionWalkKeepsKernelInodeZeroForFuseAncestors() {
        val kernel = object : MemoryKernel() {
            override fun fstat(rawFd: java.io.FileDescriptor, fdNumber: Int): PosixRawStat {
                val observed = super.fstat(rawFd, fdNumber)
                return if (readlink("/proc/self/fd/$fdNumber") == "/") observed.copy(inode = 0L) else observed
            }
        }
        kernel.files["/workspace/file"] = "fuse seed".toByteArray()
        val ops = AndroidOsPosixFdOperations(kernel = kernel)
        val walk = ops.openDirectoryWithAncestorsNoFollow("/workspace")!!
        assertEquals(0L, walk.second.single().inode)
        assertTrue(walk.second.single().hasValidKernelIdentity)
        walk.first.use { parent ->
            ops.openTargetAt(parent, "file", false, false).use { fd ->
                assertEquals("fuse seed", String(ops.readFdRange(fd, 0, 64), Charsets.UTF_8))
            }
        }
        assertTrue(RootServiceRootIdentity(23L, 0L, 48L, 0, RootWorkspaceRootKind.DIRECTORY).isValidKernelObject)
        assertFalse(RootServiceRootIdentity(23L, -1L, 48L, 0, RootWorkspaceRootKind.DIRECTORY).isValidKernelObject)
    }

    @Test
    fun productionOpsReportMode07777FullContentDigestAndCloseReadOnlyFd() {
        val kernel = MemoryKernel()
        kernel.files["/workspace/file"] = ByteArray(100_000) { 'a'.code.toByte() }
        kernel.modes["/workspace/file"] = 0b100_111_101_101 // 04755
        val ops = AndroidOsPosixFdOperations(kernel = kernel)
        val dir = ops.openDirectoryAbsoluteNoFollow("/workspace")!!
        val fd = ops.openTargetAt(dir, "file", followSymlink = false, writable = false)
        val identity = ops.fstatIdentity(fd)!!
        assertEquals(0b100_111_101_101, identity.modeOctal, "production fstat must expose full 07777 bits")
        assertTrue(identity.isRegularFile)
        val before = ops.fstatContentDigest(fd)!!
        kernel.files["/workspace/file"]!![99_999] = 'b'.code.toByte()
        val after = ops.fstatContentDigest(fd)!!
        assertNotEquals(before, after, "tail edits beyond the old 64 KiB sample must change the version digest")
        assertEquals("aaaa", String(ops.readFdRange(fd, 0, 4), Charsets.UTF_8))
        val closesBefore = kernel.closeCount
        fd.close()
        assertEquals(closesBefore + 1, kernel.closeCount)
        assertFalse(fd.isOpen)
        assertThrows(PosixOpException::class.java) { ops.fstatContentDigest(fd) }
        dir.close()
    }

    @Test
    fun errnoExceptionForUnsupportedXattrMapsToUnsupportedAttributePreservation() {
        val kernel = object : MemoryKernel() {
            override fun setxattr(path: String, name: String, value: ByteArray, flags: Int) {
                throw android.system.ErrnoException("setxattr", 95).also { err ->
                    android.system.ErrnoException::class.java.getDeclaredField("errno")
                        .apply { isAccessible = true }
                        .setInt(err, 95)
                }
            }
        }
        kernel.files["/workspace/file"] = "hello".toByteArray()
        val ops = AndroidOsPosixFdOperations(kernel = kernel)
        val dir = ops.openDirectoryAbsoluteNoFollow("/workspace")!!
        val fd = ops.openTargetAt(dir, "file", followSymlink = false, writable = false)
        val err = assertThrows(PosixOpException::class.java) {
            ops.fsetSelinuxLabel(fd, "u:object_r:app_data_file:s0")
        }
        assertEquals("EOPNOTSUPP", err.errnoName)
        assertEquals("security.selinux", err.unsupportedAttribute)
        fd.close()
        dir.close()
    }

    @Test
    fun w4CrossApp0700PrivateDirectoryMutationsPreserveOwnerModeLabelXattrsAndRejectUntrustedAncestors() {
        val fixturePath = "/data/user/0/com.example.fixture/files"
        val fixtureLabel = "u:object_r:app_data_file:s0:c200,c256,c512,c768"
        val ops = Ops().apply {
            root = N(
                inode = 5001L,
                dir = true,
                path = fixturePath,
                mode = 448, // 0700
                uid = 10200,
                gid = 10200,
                links = 2L,
                selinux = fixtureLabel,
            )
            parent = root
            ancestorsAboveRoot = listOf(
                RootFdNodeIdentity(1, 1, 1, true, false, false, 2, 493, 0, 0, "u:object_r:rootfs:s0"),
                RootFdNodeIdentity(1, 2, 77, true, false, false, 2, 505, 1000, 1000, "u:object_r:system_data_file:s0"),
                RootFdNodeIdentity(1, 3, 77, true, false, false, 2, 457, 1000, 1000, "u:object_r:system_data_file:s0"),
                RootFdNodeIdentity(1, 4, 77, true, false, false, 2, 505, 1000, 1000, "u:object_r:system_data_file:s0"),
                RootFdNodeIdentity(1, 5000, 77, true, false, false, 2, 448, 10200, 10200, fixtureLabel),
            )
            add("config.json", "{\"v\":1}")
            nodes["config.json"]!!.mode = 416 // 0640
            nodes["config.json"]!!.xattrs["user.tag"] = "preserved-tag".toByteArray(Charsets.UTF_8)
        }
        val driver = AndroidRootPosixFileDriver(Paths.get(fixturePath), ops)
        val exe = RootWorkspaceFileExecutor(ops.identity(ops.root), boundary(), driver)

        // 1. Conditional replacement is refused in a domain the target app can rewrite; the
        //    unconditional replacement still preserves ownerUid/ownerGid/mode/selinuxLabel/xattrs.
        val v1 = (exe.executeGetMetadata(RootTypedArguments.FileGetMetadata("ws", "config.json"))
            as RootFileOperationResult.Succeeded).value.version
        val updatedJson = "{\"v\":2}"
        val conditionalRes = exe.executeAtomicWriteText(
            RootTypedArguments.FileWriteText(
                workspaceId = "ws",
                relativePath = "config.json",
                contentSha256 = sha(updatedJson),
                contentByteLength = updatedJson.length,
                replaceExisting = true,
                expectedVersion = v1,
            ),
            updatedJson.toByteArray(Charsets.UTF_8),
        )
        assertTrue(
            conditionalRes is RootFileOperationResult.Rejected,
            "app-private conditional write must be refused before mutation, got $conditionalRes",
        )
        assertEquals(
            RootFileOperationReason.WRITE_DOMAIN_VERSION_UNSUPPORTED,
            (conditionalRes as RootFileOperationResult.Rejected).reason,
        )
        assertEquals("{\"v\":1}", ops.nodes["config.json"]!!.bytes.toString(Charsets.UTF_8))
        val writeRes = exe.executeAtomicWriteText(
            RootTypedArguments.FileWriteText(
                workspaceId = "ws",
                relativePath = "config.json",
                contentSha256 = sha(updatedJson),
                contentByteLength = updatedJson.length,
                replaceExisting = true,
            ),
            updatedJson.toByteArray(Charsets.UTF_8),
        )
        assertTrue(writeRes is RootFileOperationResult.Succeeded, "expected write success, got $writeRes")
        val snap = (writeRes as RootFileOperationResult.Succeeded).value
        assertEquals(10200, snap.ownerUid)
        assertEquals(10200, snap.ownerGid)
        assertEquals(416, snap.modeOctal)
        assertEquals(fixtureLabel, snap.selinuxLabel)
        assertEquals(
            "preserved-tag",
            ops.nodes["config.json"]!!.xattrs["user.tag"]?.toString(Charsets.UTF_8),
        )

        // 2. Create new file, apply patch, create directory, move, and delete in 0700 app directory.
        val createdText = "created-in-0700\n"
        val createRes = exe.executeAtomicWriteText(
            RootTypedArguments.FileWriteText(
                workspaceId = "ws",
                relativePath = "created.txt",
                contentSha256 = sha(createdText),
                contentByteLength = createdText.length,
                replaceExisting = false,
            ),
            createdText.toByteArray(Charsets.UTF_8),
        ) as RootFileOperationResult.Succeeded
        assertEquals(10200, createRes.value.ownerUid)
        assertEquals(10200, createRes.value.ownerGid)
        assertEquals(fixtureLabel, createRes.value.selinuxLabel)

        val patchPayload = """{"search":"created","replace":"patched"}""".toByteArray(Charsets.UTF_8)
        // Reading then replacing cannot be bound to the read version here, so patching is
        // refused before any side effect rather than silently applied unconditionally.
        val patchRes = exe.executeApplyPatch(
            RootTypedArguments.FileApplyPatch(
                workspaceId = "ws",
                relativePath = "created.txt",
                patchSha256 = sha(String(patchPayload)),
                patchByteLength = patchPayload.size,
                expectedVersion = createRes.value.version,
                format = "search_replace",
            ),
            patchPayload,
        )
        assertTrue(
            patchRes is RootFileOperationResult.Rejected,
            "app-private patch must be refused before mutation, got $patchRes",
        )
        assertEquals(
            RootFileOperationReason.WRITE_DOMAIN_VERSION_UNSUPPORTED,
            (patchRes as RootFileOperationResult.Rejected).reason,
        )
        assertEquals("created-in-0700\n", ops.nodes["created.txt"]!!.bytes.toString(Charsets.UTF_8))

        val mkdirRes = exe.executeCreateDirectory(
            RootTypedArguments.FileCreateDirectory("ws", "sub0700"),
        ) as RootFileOperationResult.Succeeded
        assertEquals(10200, mkdirRes.value.ownerUid)
        assertEquals(10200, mkdirRes.value.ownerGid)
        assertEquals(448, mkdirRes.value.modeOctal)
        assertEquals(fixtureLabel, mkdirRes.value.selinuxLabel)

        val moveRes = exe.executeMove(
            RootTypedArguments.FileMove("ws", "created.txt", "renamed.txt"),
        ) as RootFileOperationResult.Succeeded
        assertEquals(10200, moveRes.value.snapshot.ownerUid)

        val deleteRes = exe.executeDelete(RootTypedArguments.FileDelete("ws", "renamed.txt"))
        assertTrue(deleteRes is RootFileOperationResult.Succeeded)

        // 3. Xattr preservation failure fails closed with UnsupportedAttributePreservation and cleans temp file.
        ops.failSetXattrName = "user.tag"
        val failedXattrWrite = exe.executeAtomicWriteText(
            RootTypedArguments.FileWriteText(
                workspaceId = "ws",
                relativePath = "config.json",
                contentSha256 = sha("{\"v\":3}"),
                contentByteLength = 7,
                replaceExisting = true,
            ),
            "{\"v\":3}".toByteArray(Charsets.UTF_8),
        )
        assertEquals(
            RootFileOperationResult.UnsupportedAttributePreservation("user.tag"),
            failedXattrWrite,
        )
        assertEquals("{\"v\":2}", ops.nodes["config.json"]!!.bytes.toString(Charsets.UTF_8))
        assertEquals(0, ops.nodes.keys.count { it.startsWith(".mar-root-tmp-") })
        ops.failSetXattrName = null

        // 4. A writable ancestor above the verified workspace root does not remove ordinary Root write access.
        ops.ancestorsAboveRoot = listOf(
            RootFdNodeIdentity(1, 99, 77, true, false, false, 2, 505, 2000, 2000, "u:object_r:shell_data_file:s0"),
        )
        val rejectedAncestorWrite = exe.executeAtomicWriteText(
            RootTypedArguments.FileWriteText(
                workspaceId = "ws",
                relativePath = "config.json",
                contentSha256 = sha("{\"v\":4}"),
                contentByteLength = 7,
                replaceExisting = true,
            ),
            "{\"v\":4}".toByteArray(Charsets.UTF_8),
        )
        assertTrue(rejectedAncestorWrite is RootFileOperationResult.Succeeded)
        assertEquals("{\"v\":4}", ops.nodes["config.json"]!!.bytes.toString(Charsets.UTF_8))

        // 5. Control-plane ancestor above workspace root causes immediate walk rejection.
        ops.ancestorsAboveRoot = listOf(
            RootFdNodeIdentity(1, 9001, 77, true, false, false, 2, 448, 10100, 10100),
        )
        assertNull(driver.inspectOpenedTarget("ws", "config.json", false))
        assertEquals(0, driver.openFdCount())
        driver.close()
        assertEquals(0, ops.opened - ops.closed)

        // 6. statAtNoFollow on an existing symlink returns a symlink identity rather than null.
        val symlinkKernel = object : MemoryKernel() {
            override fun lstat(path: String): PosixRawStat = PosixRawStat(
                deviceId = 1L,
                inode = 777L,
                mode = 0b1010_000_000_000_000 or 0b111_111_111,
                hardlinkCount = 1L,
                uid = 10200,
                gid = 10200,
                sizeBytes = 12L,
                mtimeSec = 1L,
                ctimeSec = 1L,
            )
        }
        val symlinkOps = AndroidOsPosixFdOperations(kernel = symlinkKernel)
        val wsDir = symlinkOps.openDirectoryAbsoluteNoFollow("/workspace")!!
        val symlinkStat = symlinkOps.statAtNoFollow(wsDir, "dangling_link")
        assertNotNull(symlinkStat, "statAtNoFollow must not mask an existing symlink as null")
        assertTrue(symlinkStat!!.isSymlink)
        wsDir.close()
    }

    @Test
    fun w5LargeFileStreamingStatReadPatchGlobGrepAndExactErrnoSelinuxMapping() {
        val e = Env()
        // 1. Create a >8 MiB (9 MiB) file with structured lines for streaming stat, read, unified & search_replace patch, and grep.
        val lineTemplate = "line-%06d-payload-" + "x".repeat(80) + "\n"
        val lineCount = 95_000 // ~9.5 MiB (> 8 MiB threshold)
        val bigBuilder = StringBuilder(lineCount * 100)
        for (i in 1..lineCount) {
            bigBuilder.append(String.format(java.util.Locale.ROOT, lineTemplate, i))
        }
        val bigBytes = bigBuilder.toString().toByteArray(Charsets.UTF_8)
        assertTrue(bigBytes.size > 8 * 1024 * 1024, "expected >8 MiB fixture, got ${bigBytes.size}")
        e.ops.add("large.log", "")
        e.ops.nodes["large.log"]!!.bytes = bigBytes

        val stat1 = (e.exe.executeGetMetadata(RootTypedArguments.FileGetMetadata("ws", "large.log"))
            as RootFileOperationResult.Succeeded).value
        assertEquals(bigBytes.size.toLong(), stat1.sizeBytes)
        assertTrue(stat1.version.isNotBlank())

        // Windowed read near the tail of the >8 MiB file.
        val tailOffset = bigBytes.size.toLong() - 200L
        val tailRead = (e.exe.executeReadText(RootTypedArguments.FileReadText("ws", "large.log", tailOffset, 200))
            as RootFileOperationResult.Succeeded).value
        assertEquals(200, tailRead.content.size)
        assertFalse(tailRead.truncated)
        assertEquals(stat1.version, tailRead.version)

        // Streaming search_replace patch on the >8 MiB file.
        val srPatch = """{"search":"line-090000-payload-","replace":"line-090000-PATCHED-"}""".toByteArray(Charsets.UTF_8)
        val srResult = e.exe.executeApplyPatch(
            RootTypedArguments.FileApplyPatch(
                workspaceId = "ws",
                relativePath = "large.log",
                patchSha256 = sha(String(srPatch)),
                patchByteLength = srPatch.size,
                expectedVersion = stat1.version,
                format = "search_replace",
            ),
            srPatch,
        )
        assertTrue(srResult is RootFileOperationResult.Succeeded, "expected >8 MiB search_replace success, got $srResult")
        val stat2 = (srResult as RootFileOperationResult.Succeeded).value
        assertNotEquals(stat1.version, stat2.version)

        // Stale expected_version on >8 MiB file is rejected without mutating the file or leaking temp FDs.
        val stalePatch = e.exe.executeApplyPatch(
            RootTypedArguments.FileApplyPatch(
                workspaceId = "ws",
                relativePath = "large.log",
                patchSha256 = sha(String(srPatch)),
                patchByteLength = srPatch.size,
                expectedVersion = stat1.version,
                format = "search_replace",
            ),
            srPatch,
        )
        assertTrue(stalePatch is RootFileOperationResult.Rejected)
        assertEquals(0, e.ops.nodes.keys.count { it.startsWith(".mar-root-patch-") })

        // Streaming unified diff patch on the >8 MiB file (patching line 1).
        val firstLine = String.format(java.util.Locale.ROOT, lineTemplate, 1).trimEnd('\n')
        val secondLine = String.format(java.util.Locale.ROOT, lineTemplate, 2).trimEnd('\n')
        val replacedFirst = firstLine.replace("line-000001-payload-", "line-000001-UNIFIED-")
        val unifiedPatch = "--- a/large.log\n+++ b/large.log\n@@ -1,2 +1,2 @@\n-$firstLine\n+$replacedFirst\n $secondLine\n"
            .toByteArray(Charsets.UTF_8)
        val unifiedRes = e.exe.executeApplyPatch(
            RootTypedArguments.FileApplyPatch(
                workspaceId = "ws",
                relativePath = "large.log",
                patchSha256 = sha(String(unifiedPatch)),
                patchByteLength = unifiedPatch.size,
                expectedVersion = stat2.version,
                format = "unified",
            ),
            unifiedPatch,
        )
        assertTrue(unifiedRes is RootFileOperationResult.Succeeded, "expected >8 MiB unified patch success, got $unifiedRes")

        // 2. file_glob and file_grep through RootServiceFileDispatch and RootToolArguments.
        e.ops.add("notes.md", "alpha beta\ngamma NEEDLE delta\n")
        val globReq = requireNotNull(
            RootToolArguments.normalize("file_glob", """{"workspace_id":"ws","pattern":"*.md"}"""),
        )
        val statusSnap = RootControlStatusSnapshot(
            requestId = "req-w5",
            envelopeSha256 = "a".repeat(64),
            acceptedSequence = 1L,
            stage = RootServiceExecutionStage.RUNNING,
        )
        val globResp = RootServiceFileDispatch.execute(
            executor = e.exe,
            args = globReq.typed,
            acceptedStatus = statusSnap,
            payloadBytes = ByteArray(0),
        )
        assertEquals(RootServiceTerminalOutcome.SUCCEEDED, globResp.terminalOutcome)
        assertTrue(globResp.fileResultJson!!.contains("\"notes.md\""))
        assertFalse(globResp.fileResultJson!!.contains("\"large.log\""))

        val grepReq = requireNotNull(
            RootToolArguments.normalize(
                "file_grep",
                """{"workspace_id":"ws","query":"NEEDLE","case_sensitive":true,"max_scanned_bytes":4096}""",
            ),
        )
        val grepResp = RootServiceFileDispatch.execute(
            executor = e.exe,
            args = grepReq.typed,
            acceptedStatus = statusSnap,
            payloadBytes = ByteArray(0),
        )
        assertEquals(RootServiceTerminalOutcome.SUCCEEDED, grepResp.terminalOutcome)
        assertTrue(grepResp.fileResultJson!!.contains("\"NEEDLE\""))

        // 3. Exact errno and SELinux denial mapping through PosixErrnoMapping and RootBridgeErrorCode.
        assertEquals("ENOSPC", PosixErrnoMapping.name(28, "UNKNOWN"))
        assertEquals("EROFS", PosixErrnoMapping.name(30, "UNKNOWN"))
        assertEquals("EINVAL", PosixErrnoMapping.name(22, "UNKNOWN"))
        assertEquals("ENAMETOOLONG", PosixErrnoMapping.name(36, "UNKNOWN"))
        assertEquals("ETIMEDOUT", PosixErrnoMapping.name(110, "UNKNOWN"))

        val selinuxErr = RootBridgeErrorCode.fromExecuteFailure(
            RootServiceExecuteResponse(
                submitStage = "ACCEPTED",
                terminalOutcome = RootServiceTerminalOutcome.FAILED,
                fileErrno = "EACCES",
                fileSelinuxDenied = true,
            ),
        )
        assertEquals(runtime.mobileagent.skills.tooling.ToolErrorCode.PERMISSION_DENIED, selinuxErr.code)
        assertEquals("eacces", selinuxErr.details["errno"])
        assertEquals("true", selinuxErr.details["selinux_denied"])

        val rofsErr = RootBridgeErrorCode.fromExecuteFailure(
            RootServiceExecuteResponse(
                submitStage = "ACCEPTED",
                terminalOutcome = RootServiceTerminalOutcome.FAILED,
                fileErrno = "EROFS",
            ),
        )
        assertEquals(runtime.mobileagent.skills.tooling.ToolErrorCode.WORKSPACE_READ_ONLY, rofsErr.code)

        val conflictErr = RootBridgeErrorCode.fromExecuteFailure(
            RootServiceExecuteResponse(
                submitStage = "ACCEPTED",
                terminalOutcome = RootServiceTerminalOutcome.FAILED,
                errorReason = RootFileOperationReason.PATCH_CONTEXT_MISMATCH,
            ),
        )
        assertEquals(runtime.mobileagent.skills.tooling.ToolErrorCode.CONFLICT, conflictErr.code)

        // 4. file_stat does not require full-file content read and file_read_text fits MAX_OUTPUT_BYTES with eof/byte_length.
        val statDispatchReq = requireNotNull(
            RootToolArguments.normalize("file_stat", """{"workspace_id":"ws","relative_path":"large.log"}"""),
        )
        val statDispatchResp = RootServiceFileDispatch.execute(
            executor = e.exe,
            args = statDispatchReq.typed,
            acceptedStatus = statusSnap,
            payloadBytes = ByteArray(0),
        )
        assertEquals(RootServiceTerminalOutcome.SUCCEEDED, statDispatchResp.terminalOutcome)
        assertTrue(statDispatchResp.fileResultJson!!.contains("\"size_bytes\":"))

        val windowedReadReq = requireNotNull(
            RootToolArguments.normalize(
                "file_read_text",
                """{"workspace_id":"ws","relative_path":"large.log","offset_bytes":0,"max_bytes":65536}""",
            ),
        )
        val windowedReadResp = RootServiceFileDispatch.execute(
            executor = e.exe,
            args = windowedReadReq.typed,
            acceptedStatus = statusSnap,
            payloadBytes = ByteArray(0),
        )
        assertEquals(RootServiceTerminalOutcome.SUCCEEDED, windowedReadResp.terminalOutcome)
        assertTrue(windowedReadResp.fileResultJson!!.toByteArray(Charsets.UTF_8).size <= RootExecutionBudget.MAX_OUTPUT_BYTES)
        assertTrue(windowedReadResp.fileResultJson!!.contains("\"eof\":false"))
        assertTrue(windowedReadResp.fileResultJson!!.contains("\"byte_length\":65536"))

        val oversizedReadReq = requireNotNull(
            RootToolArguments.normalize(
                "file_read_text",
                """{"workspace_id":"ws","relative_path":"large.log","offset_bytes":0,"max_bytes":262144}""",
            ),
        )
        val oversizedReadResp = RootServiceFileDispatch.execute(
            executor = e.exe,
            args = oversizedReadReq.typed,
            acceptedStatus = statusSnap,
            payloadBytes = ByteArray(0),
        )
        assertEquals(RootServiceTerminalOutcome.SUCCEEDED, oversizedReadResp.terminalOutcome)
        val page = requireNotNull(oversizedReadResp.fileResultJson)
        assertTrue(page.toByteArray(Charsets.UTF_8).size <= RootExecutionBudget.MAX_OUTPUT_BYTES)
        assertTrue(page.contains("\"eof\":false"))
        assertTrue(page.contains("\"next_offset_bytes\":"))

        // 5. Subdirectory file_glob and file_list pagination cursor preservation.
        e.ops.addDir("sub")
        e.ops.addDir("nested")
        e.ops.add("deep.txt", "deep needle\n")
        val subGlobReq = requireNotNull(
            RootToolArguments.normalize(
                "file_glob",
                """{"workspace_id":"ws","relative_path":"sub","pattern":"nested/deep.txt","max_depth":2}""",
            ),
        )
        val subGlobResp = RootServiceFileDispatch.execute(
            executor = e.exe,
            args = subGlobReq.typed,
            acceptedStatus = statusSnap,
            payloadBytes = ByteArray(0),
        )
        assertEquals(RootServiceTerminalOutcome.SUCCEEDED, subGlobResp.terminalOutcome)
        assertTrue(subGlobResp.fileResultJson!!.contains("\"sub/nested/deep.txt\""))

        val page1 = (e.exe.executeList(
            RootTypedArguments.FileList(workspaceId = "ws", relativePath = "", maxEntries = 1, cursor = null),
        ) as RootFileOperationResult.Succeeded).value
        assertTrue(page1.truncated)
        assertNotNull(page1.nextCursor)

        e.driver.close()
        val closedGlob = e.exe.executeGlob(
            RootTypedArguments.FileGlob(workspaceId = "ws", relativePath = "", pattern = "*.md"),
        )
        assertTrue(closedGlob is RootFileOperationResult.Rejected)
    }

    // --- FUSE/sdcardfs ordinary writes: bound FD only, no staging, no rename ---------------------

    /**
     * Emulated storage cannot carry a UID0 0700 stage or a hardlink commit, so an ordinary create
     * uses the exclusive bound-FD create: an entry that appears between the parent inspection and the
     * create is never overwritten and never removed.
     */
    @Test
    fun fuseCreateIsExclusiveAndNeverOverwritesATargetThatAppearsLate() {
        val e = fuseEnv()
        e.ops.onStage = {
            e.ops.nodes["fresh.txt"] = N(e.ops.serial++, false, "/workspace/fresh.txt", "ARRIVED".toByteArray())
        }
        val text = "created"
        val result = e.exe.executeAtomicWriteText(
            RootTypedArguments.FileWriteText("ws", "fresh.txt", sha(text), text.toByteArray().size, false, null),
            text.toByteArray(),
        )
        assertTrue(result is RootFileOperationResult.Rejected, "O_EXCL create must reject an appearing target, got $result")
        assertEquals(
            RootConfinementRejection.RENAME_RACE_DETECTED,
            (result as RootFileOperationResult.Rejected).rejection,
        )
        assertEquals("ARRIVED", e.ops.nodes["fresh.txt"]!!.bytes.toString(Charsets.UTF_8))
        assertEquals(0, e.ops.writeCalls, "the appearing target must never be written through")
        assertEquals(0, e.ops.unlinkCalls, "and must never be removed")
        e.driver.close()
    }

    /** The new status carries the difference instead of claiming a permission-preserving replace. */
    @Test
    fun fuseBoundFdReplaceReportsInPlaceAndCreateReportsFilesystemDefaults() {
        val created = fuseEnv()
        val parent = requireNotNull(created.driver.inspectParentForCreate("ws", "fresh.txt"))
        val createStatus = created.driver.createNewRegularFile(parent, "fresh.txt", "fresh".toByteArray())
        assertTrue(createStatus is DriverWriteStatus.Replaced, "expected bound-FD create, got $createStatus")
        createStatus as DriverWriteStatus.Replaced
        assertFalse(createStatus.inPlace)
        assertTrue(createStatus.createdWithFilesystemDefaults)
        assertEquals("fresh", created.ops.nodes["fresh.txt"]!!.bytes.toString(Charsets.UTF_8))
        assertEquals(1, created.ops.stageCalls)
        assertEquals(0, created.ops.renameCalls, "no rename-based staging on emulated storage")
        assertEquals(0, created.ops.linkCalls, "no hardlink-based commit on emulated storage")
        created.driver.close()

        val replaced = fuseEnv()
        val target = requireNotNull(replaced.driver.inspectOpenedTarget("ws", "file", false))
        val replaceStatus = replaced.driver.writeAtomicSiblingAndReplace(
            target = target,
            utf8Bytes = "NEW!".toByteArray(),
            preserveModeOctal = 384,
            preserveOwnerUid = 10100,
            preserveOwnerGid = 10100,
            preserveSelinuxLabel = "u:object_r:app_data_file:s0",
        )
        assertTrue(replaceStatus is DriverWriteStatus.Replaced, "expected bound-FD replace, got $replaceStatus")
        replaceStatus as DriverWriteStatus.Replaced
        assertTrue(replaceStatus.inPlace)
        assertFalse(replaceStatus.createdWithFilesystemDefaults)
        assertEquals("NEW!", replaced.ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
        assertEquals(1, replaced.ops.writableOpens)
        assertEquals(0, replaced.ops.stageCalls, "an in-place replace must not stage a sibling")
        assertEquals(0, replaced.ops.renameCalls)
        replaced.driver.close()
    }

    /**
     * A shorter content must truncate the bound descriptor: writing alone would leave the old tail
     * and the post-write size check would fail. Owner, mode and label stay untouched.
     */
    @Test
    fun fuseShortWriteTruncatesTheBoundFdAndPreservesOwnerModeLabel() {
        val e = fuseEnv()
        val label = "u:object_r:app_data_file:s0:c200,c256,c512,c768"
        e.ops.nodes["file"]!!.apply {
            bytes = "0123456789abcdef".toByteArray()
            mode = 416 // 0640
            uid = 10200
            gid = 10200
            selinux = label
        }
        val text = "short"
        val result = e.exe.executeAtomicWriteText(
            RootTypedArguments.FileWriteText("ws", "file", sha(text), text.toByteArray().size, true, null),
            text.toByteArray(),
        )
        assertTrue(result is RootFileOperationResult.Succeeded, "bound-fd replace must succeed, got $result")
        val snapshot = (result as RootFileOperationResult.Succeeded).value
        assertEquals(text, e.ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8), "a short write must not keep the old tail")
        assertEquals(text.toByteArray().size.toLong(), snapshot.sizeBytes)
        assertEquals(10200, snapshot.ownerUid)
        assertEquals(10200, snapshot.ownerGid)
        assertEquals(416, snapshot.modeOctal)
        assertEquals(label, snapshot.selinuxLabel)
        assertEquals(1, e.ops.truncates, "the shorter content must truncate the existing descriptor")
        assertEquals(1, e.ops.writableOpens)
        assertEquals(0, e.ops.stageCalls, "emulated replace must not stage a sibling file")
        e.driver.close()
    }

    /** A failed or interrupted bound-FD write is UNKNOWN: never SUCCESS, never cleaned up, never retried. */
    @Test
    fun fuseWriteFailureOrServiceLossIsUnknownWithoutCleanupOrRetry() {
        for (loseService in listOf(false, true)) {
            val e = fuseEnv()
            if (loseService) e.ops.closedDriverDuringWrite = { e.driver.close() } else e.ops.failWrite = true
            val result = e.write()
            assertUnknown(result)
            assertEquals(1, e.ops.writeCalls, "loseService=$loseService: a failed write must not be retried")
            assertEquals(0, e.ops.unlinkCalls, "loseService=$loseService: nothing may be removed after a partial write")
            assertEquals(0, e.ops.renameCalls, "loseService=$loseService: no replace may follow a failed write")
            e.driver.close()
        }
    }

    /** A target replaced after inspection is caught before any write (last-window race). */
    @Test
    fun fuseTargetSwapAfterInspectionIsRejectedWithoutTouchingTheSwappedTarget() {
        val e = fuseEnv()
        val target = requireNotNull(e.driver.inspectOpenedTarget("ws", "file", false))
        e.ops.nodes["file"] = N(e.ops.serial++, false, "/workspace/file", "IMPOSTOR".toByteArray())
        val status = e.driver.writeAtomicSiblingAndReplace(
            target = target,
            utf8Bytes = "NEW!".toByteArray(),
            preserveModeOctal = 384,
            preserveOwnerUid = 10100,
            preserveOwnerGid = 10100,
            preserveSelinuxLabel = null,
        )
        assertEquals(DriverWriteStatus.RaceDetectedTempCleanedUp, status)
        assertEquals("IMPOSTOR", e.ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
        assertEquals(0, e.ops.writableOpens, "the stale handle must be dropped before the write")
        assertEquals(0, e.ops.writeCalls)
        assertEquals(0, e.ops.unlinkCalls)
        e.driver.close()
    }

    /** A target that drifts after the write is an UNKNOWN post-commit outcome, not a success. */
    @Test
    fun fuseTargetDriftAfterTheWriteIsUnknownAndNeverSuccess() {
        val e = fuseEnv()
        e.ops.afterWrite = {
            e.ops.nodes["file"] = N(e.ops.serial++, false, "/workspace/file", "DRIFTED".toByteArray())
        }
        assertUnknown(e.write())
        assertEquals(1, e.ops.writeCalls, "a drifted post-write observation must not be retried")
        assertEquals(0, e.ops.unlinkCalls)
        e.driver.close()
    }

    /** expected_version and patch read-then-replace stay refused in a shared directory, with no write. */
    @Test
    fun fuseConditionalVersionAndPatchAreRejectedBeforeAnyWrite() {
        val e = fuseEnv()
        val version = e.version()
        val conditional = e.write(version)
        assertTrue(conditional is RootFileOperationResult.Rejected, "expected_version must be refused, got $conditional")
        assertEquals(
            RootFileOperationReason.WRITE_DOMAIN_VERSION_UNSUPPORTED,
            (conditional as RootFileOperationResult.Rejected).reason,
        )
        assertEquals("old!", e.ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
        assertEquals(0, e.ops.writeCalls)
        assertEquals(0, e.ops.writableOpens)

        val patchPayload = """{"search":"old","replace":"new"}""".toByteArray(Charsets.UTF_8)
        val patch = e.exe.executeApplyPatch(
            RootTypedArguments.FileApplyPatch(
                workspaceId = "ws",
                relativePath = "file",
                patchSha256 = sha(String(patchPayload)),
                patchByteLength = patchPayload.size,
                expectedVersion = version,
                format = "search_replace",
            ),
            patchPayload,
        )
        assertTrue(patch is RootFileOperationResult.Rejected, "patch must be refused, got $patch")
        assertEquals(
            RootFileOperationReason.WRITE_DOMAIN_VERSION_UNSUPPORTED,
            (patch as RootFileOperationResult.Rejected).reason,
        )
        assertEquals(0, e.ops.writeCalls, "the refused patch must not write")
        assertEquals("old!", e.ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
        e.driver.close()
    }

    // --- post-mutation status: only a visible change may become UNKNOWN ---------------------------

    /**
     * A truncate refused before any byte is written is a permission failure, not an UNKNOWN outcome:
     * nothing changed on disk, so the caller may report the real errno.
     */
    @Test
    fun fuseTruncateRejectionBeforeMutationIsPermissionDeniedWithOldBytesIntact() {
        val e = fuseEnv()
        e.ops.failTruncate = true
        val result = e.write()
        assertTrue(result is RootFileOperationResult.PermissionDenied, "expected permission denial, got $result")
        result as RootFileOperationResult.PermissionDenied
        assertEquals("EROFS", result.errnoName)
        assertFalse(result.selinuxConfirmed)
        assertEquals("old!", e.ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8))
        assertEquals(1, e.ops.truncates)
        assertEquals(0, e.ops.writeCalls, "no byte may be written after a refused truncate")
        assertEquals(0, e.ops.unlinkCalls)
        assertEquals(0, e.ops.renameCalls)
        e.driver.close()
    }

    /** The truncate already shortened the file, so a following write failure is UNKNOWN, not denied. */
    @Test
    fun fuseWriteFailureAfterSuccessfulTruncateIsUnknownBecauseTheChangeIsVisible() {
        val e = fuseEnv()
        e.ops.failWriteBeforeBytes = true
        assertUnknown(e.write())
        assertEquals(0, e.ops.nodes["file"]!!.bytes.size, "the successful truncate is already visible")
        assertEquals(1, e.ops.truncates)
        assertEquals(1, e.ops.writeCalls)
        assertEquals(0, e.ops.unlinkCalls, "an UNKNOWN outcome must not be cleaned up by name")
        assertEquals(0, e.ops.renameCalls)
        e.driver.close()
    }

    // --- emulated storage: directory creation, no-replace move, directory durability --------------

    /**
     * FUSE/`sdcardfs` own the attributes of a new directory (synthesized 0770 plus a `:fuse`
     * label); forcing 0700 with `fchown`/`chcon` afterwards used to turn every
     * `file_create_directory` into `UNKNOWN` and poisoned the Root channel. The filesystem result
     * must be reported as-is, with no attribute syscalls at all.
     */
    @Test
    fun fuseCreateDirectoryKeepsFilesystemAttributesAndNeverDegrades() {
        val e = fuseEnv()
        val result = e.exe.executeCreateDirectory(RootTypedArguments.FileCreateDirectory("ws", "newdir"))
        assertTrue(result is RootFileOperationResult.Succeeded, "emulated mkdir must succeed, got $result")
        val snapshot = (result as RootFileOperationResult.Succeeded).value
        assertEquals(0b111_111_000, snapshot.modeOctal, "the FUSE-synthesized mode must be reported unchanged")
        assertEquals(9997, snapshot.ownerGid, "the filesystem owner must not be overwritten")
        assertEquals("u:object_r:fuse:s0", snapshot.selinuxLabel)
        assertEquals(0, e.ops.chmodCalls, "no fchmod(0700) on emulated storage")
        assertEquals(0, e.ops.chownCalls, "no fchown on emulated storage")
        assertEquals(0, e.ops.labelCalls, "no security.selinux attribute on emulated storage")
        assertTrue(e.ops.nodes["newdir"]!!.dir)
        e.driver.close()
    }

    /** A directory that appeared but can no longer be observed stays UNKNOWN; nothing is removed. */
    @Test
    fun fuseCreateDirectoryObservationFailureIsUnknownWithoutCleanup() {
        val e = fuseEnv()
        e.ops.onAfterMkdir = { e.ops.nodes.remove("newdir") }
        assertUnknown(e.exe.executeCreateDirectory(RootTypedArguments.FileCreateDirectory("ws", "newdir")))
        assertEquals(0, e.ops.unlinkCalls, "an UNKNOWN mkdir must not be cleaned up by name")
        e.driver.close()
    }

    /**
     * `file_move` defaults to no-replace. Emulated storage has no hard links, so the commit needs
     * `renameat2(RENAME_NOREPLACE)`; a kernel or filesystem without that flag must produce an
     * explicit unsupported result with no side effect — never a check-then-rename, a hard-link
     * attempt or a degraded channel.
     */
    @Test
    fun fuseMoveWithoutReplaceIsExplicitlyUnsupportedAndTouchesNothing() {
        for (occupied in listOf(false, true)) {
            val e = fuseEnv()
            if (occupied) e.ops.add("moved.txt", "OCCUPANT")
            e.ops.atomicNoReplaceRenameSupported = false
            val result = e.exe.executeMove(RootTypedArguments.FileMove("ws", "file", "moved.txt"))
            if (occupied) {
                assertTrue(result is RootFileOperationResult.Rejected, "got $result")
                assertEquals(
                    RootConfinementRejection.RENAME_RACE_DETECTED,
                    (result as RootFileOperationResult.Rejected).rejection,
                )
                assertEquals("OCCUPANT", e.ops.nodes["moved.txt"]!!.bytes.toString(Charsets.UTF_8))
                assertEquals(0, e.ops.renameAtNoReplaceCalls, "an occupied destination is refused before the commit")
            } else {
                assertEquals(
                    RootFileOperationResult.UnsupportedAttributePreservation("atomic_no_replace_rename"),
                    result,
                    "a missing no-replace rename primitive must be reported explicitly",
                )
                assertEquals(1, e.ops.renameAtNoReplaceCalls)
                assertNull(e.ops.nodes["moved.txt"])
            }
            assertEquals("old!", e.ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8), "occupied=$occupied")
            assertEquals(0, e.ops.linkCalls, "emulated storage must not attempt a hard link")
            assertEquals(0, e.ops.unlinkCalls)
            e.driver.close()
        }
    }

    @Test
    fun invalidRenameRelationshipIsNotMisreportedAsMissingKernelPrimitive() {
        for (directory in listOf(false, true)) {
            val e = Env()
            if (directory) e.ops.addDir("source-dir")
            val source = if (directory) "source-dir" else "file"
            val inode = e.ops.nodes.getValue(source).inode
            e.ops.onBeforeNoReplaceRename = { throw PosixOpException("EINVAL") }
            val result = e.exe.executeMove(RootTypedArguments.FileMove("ws", source, "new-name"))
            assertEquals(RootFileOperationResult.PermissionDenied(false, "EINVAL"), result)
            assertEquals(DriverMutationStatus.Denied("EINVAL"), RootPosixMoveCommit.refusal(PosixOpException("EINVAL")))
            assertEquals(inode, e.ops.nodes.getValue(source).inode)
            assertNull(e.ops.nodes["new-name"])
            assertEquals(1, e.ops.renameAtNoReplaceCalls)
            assertEquals(0, e.ops.renameCalls)
            assertEquals(0, e.ops.unlinkCalls)
            e.driver.close()
        }
    }

    /** With the atomic primitive present the move commits in one step and keeps the inode. */
    @Test
    fun fuseMoveWithoutReplaceUsesAtomicRenameWhenThePrimitiveExists() {
        val e = fuseEnv()
        val inode = e.ops.nodes["file"]!!.inode
        e.ops.atomicNoReplaceRenameSupported = true
        val result = e.exe.executeMove(RootTypedArguments.FileMove("ws", "file", "moved.txt"))
        assertTrue(result is RootFileOperationResult.Succeeded, "got $result")
        assertEquals(inode, (result as RootFileOperationResult.Succeeded).value.snapshot.inode)
        assertNull(e.ops.nodes["file"])
        assertEquals("old!", e.ops.nodes["moved.txt"]!!.bytes.toString(Charsets.UTF_8))
        assertEquals(1, e.ops.renameAtNoReplaceCalls)
        assertEquals(0, e.ops.linkCalls)
        assertEquals(0, e.ops.unlinkCalls, "renameat2 already removed the source name")
        e.driver.close()
    }

    /** An entry that appears inside the rename window makes the primitive fail; it is never replaced. */
    @Test
    fun fuseMoveWithoutReplaceNeverClobbersADestinationThatAppearsInsideTheCommit() {
        val e = fuseEnv()
        e.ops.atomicNoReplaceRenameSupported = true
        e.ops.onBeforeNoReplaceRename = {
            e.ops.nodes["moved.txt"] = N(e.ops.serial++, false, "/workspace/moved.txt", "ARRIVED".toByteArray())
        }
        val result = e.exe.executeMove(RootTypedArguments.FileMove("ws", "file", "moved.txt"))
        assertTrue(result is RootFileOperationResult.Rejected, "got $result")
        assertEquals(
            RootConfinementRejection.RENAME_RACE_DETECTED,
            (result as RootFileOperationResult.Rejected).rejection,
        )
        assertEquals("ARRIVED", e.ops.nodes["moved.txt"]!!.bytes.toString(Charsets.UTF_8))
        assertEquals("old!", e.ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8), "the source must stay in place")
        assertEquals(0, e.ops.unlinkCalls, "the appearing entry must never be removed")
        e.driver.close()
    }

    /**
     * A no-replace POSIX move is now one atomic `renameat2(RENAME_NOREPLACE)`: no `linkat` step and
     * therefore no window in which a swapped source name could be unlinked.
     */
    @Test
    fun posixMoveWithoutReplaceIsOneAtomicRename() {
        val e = Env()
        val inode = e.ops.nodes["file"]!!.inode
        val result = e.exe.executeMove(RootTypedArguments.FileMove("ws", "file", "moved.txt"))
        assertTrue(result is RootFileOperationResult.Succeeded, "got $result")
        assertEquals(inode, (result as RootFileOperationResult.Succeeded).value.snapshot.inode)
        assertNull(e.ops.nodes["file"])
        assertEquals("old!", e.ops.nodes["moved.txt"]!!.bytes.toString(Charsets.UTF_8))
        assertEquals(1, e.ops.renameAtNoReplaceCalls)
        assertEquals(0, e.ops.linkCalls, "the two-phase linkat+unlinkat commit is gone")
        assertEquals(0, e.ops.unlinkCalls, "nothing may be deleted by name")
        assertEquals(0, e.ops.renameCalls)
        e.driver.close()
    }

    /**
     * A source name swapped inside the commit window is what the namespace moves (rename follows
     * the *name*, not our descriptor). The post-commit identity check must turn that into UNKNOWN,
     * and this request must never delete the swapped entry.
     */
    @Test
    fun noReplaceMoveDetectsASourceNameSwapAtCommitAndNeverDeletesIt() {
        val e = Env()
        val originalInode = e.ops.nodes["file"]!!.inode
        e.ops.onBeforeNoReplaceRename = {
            e.ops.nodes["file"] = N(e.ops.serial++, false, "/workspace/file", "IMPOSTOR".toByteArray())
        }
        val result = e.exe.executeMove(RootTypedArguments.FileMove("ws", "file", "moved.txt"))
        assertUnknown(result)
        assertNull(e.ops.nodes["file"], "the source name disappeared together with the rename")
        assertEquals("IMPOSTOR", e.ops.nodes["moved.txt"]!!.bytes.toString(Charsets.UTF_8))
        assertNotEquals(originalInode, e.ops.nodes["moved.txt"]!!.inode)
        assertEquals(0, e.ops.linkCalls)
        assertEquals(0, e.ops.unlinkCalls, "a swapped source must never be deleted")
        e.driver.close()
    }

    /** A directory move never replaces, even when `replace_existing = true` was requested. */
    @Test
    fun directoryMoveWithReplaceExistingStillCommitsNoReplace() {
        val e = Env()
        e.ops.addDir("docs")
        val result = e.exe.executeMove(
            RootTypedArguments.FileMove("ws", "docs", "docs2", replaceExisting = true),
        )
        assertTrue(result is RootFileOperationResult.Succeeded, "got $result")
        assertNull(e.ops.nodes["docs"])
        assertTrue(e.ops.nodes["docs2"]!!.dir)
        assertEquals(1, e.ops.renameAtNoReplaceCalls)
        assertEquals(0, e.ops.renameCalls, "rename(2) could replace an empty destination directory")
        e.driver.close()
    }

    /**
     * A requested regular-file replace is the namespace's unconditional entry replacement, the same
     * contract as the Internal/Wired `ATOMIC_MOVE + REPLACE_EXISTING` backends: a symlink that
     * appears inside the window is replaced as an entry and its target is never followed or
     * modified.
     */
    @Test
    fun fileReplaceReplacesASymlinkThatAppearsInTheCommitWindowWithoutFollowingIt() {
        val e = Env()
        e.ops.add("link-target", "TARGET")
        e.ops.onBeforeRename = {
            e.ops.nodes["moved.txt"] = N(
                e.ops.serial++,
                false,
                "/workspace/moved.txt",
                "/workspace/link-target".toByteArray(),
                symlink = true,
            )
        }
        val result = e.exe.executeMove(
            RootTypedArguments.FileMove("ws", "file", "moved.txt", replaceExisting = true),
        )
        assertTrue(result is RootFileOperationResult.Succeeded, "got $result")
        assertFalse(e.ops.nodes["moved.txt"]!!.symlink, "the link entry was replaced, not followed")
        assertEquals("old!", e.ops.nodes["moved.txt"]!!.bytes.toString(Charsets.UTF_8))
        assertEquals(
            "TARGET",
            e.ops.nodes["link-target"]!!.bytes.toString(Charsets.UTF_8),
            "the symlink target stays untouched",
        )
        assertEquals(1, e.ops.renameCalls)
        assertEquals(0, e.ops.renameAtNoReplaceCalls, "a requested regular-file replace uses rename(2)")
        assertEquals(0, e.ops.unlinkCalls)
        e.driver.close()
    }

    /**
     * A directory that appears inside the replace window makes the kernel refuse the rename
     * (`EISDIR`); the appearing directory survives and the source stays in place.
     */
    @Test
    fun fileReplaceRefusesADirectoryThatAppearsInTheCommitWindow() {
        val e = Env()
        e.ops.onBeforeRename = {
            e.ops.nodes["moved.txt"] = N(e.ops.serial++, true, "/workspace/moved.txt", mode = 448, links = 2)
        }
        val result = e.exe.executeMove(
            RootTypedArguments.FileMove("ws", "file", "moved.txt", replaceExisting = true),
        )
        assertTrue(result is RootFileOperationResult.Rejected, "got $result")
        assertEquals(
            RootConfinementRejection.RENAME_RACE_DETECTED,
            (result as RootFileOperationResult.Rejected).rejection,
        )
        assertTrue(e.ops.nodes["moved.txt"]!!.dir, "the appearing directory must survive")
        assertEquals("old!", e.ops.nodes["file"]!!.bytes.toString(Charsets.UTF_8), "the source must stay")
        assertEquals(0, e.ops.unlinkCalls)
        e.driver.close()
    }

    /**
     * Directory moves use the same no-replace primitive: `rename(2)` replaces an empty
     * destination directory, which the default (`replace_existing = false`) must never do.
     */
    @Test
    fun emulatedDirectoryMoveWithoutReplaceUsesAtomicRename() {
        val e = fuseEnv()
        e.ops.addDir("docs")
        val result = e.exe.executeMove(RootTypedArguments.FileMove("ws", "docs", "docs2"))
        assertTrue(result is RootFileOperationResult.Succeeded, "got $result")
        assertNull(e.ops.nodes["docs"])
        assertTrue(e.ops.nodes["docs2"]!!.dir)
        assertEquals(1, e.ops.renameAtNoReplaceCalls)
        assertEquals(0, e.ops.renameCalls, "rename(2) would replace an empty destination directory")
        e.driver.close()
    }

    /** The same primitive covers directories on POSIX filesystems, with the FD checks intact. */
    @Test
    fun posixDirectoryMoveWithoutReplaceUsesAtomicRename() {
        val e = Env()
        e.ops.addDir("docs")
        val result = e.exe.executeMove(RootTypedArguments.FileMove("ws", "docs", "docs2"))
        assertTrue(result is RootFileOperationResult.Succeeded, "got $result")
        assertNull(e.ops.nodes["docs"])
        assertTrue(e.ops.nodes["docs2"]!!.dir)
        assertEquals(1, e.ops.renameAtNoReplaceCalls)
        assertEquals(0, e.ops.renameCalls)
        e.driver.close()
    }

    /**
     * The production bridge keeps the fail-closed shape of the other entry primitives: without the
     * shipped native library the primitive reports `EOPNOTSUPP`, which the move path turns into an
     * explicit unsupported result instead of guessing.
     */
    @Test
    fun noReplaceRenameWithoutTheShippedLibraryFailsClosed() {
        if (RootNativeSupervisorJni.ensureLoaded()) return
        val failure = assertThrows(PosixOpException::class.java) {
            PosixKernelMutations.renameAtNoReplace(1, "source", 2, "target")
        }
        assertEquals("EOPNOTSUPP", failure.errnoName)
    }

    /**
     * A directory fsync the filesystem cannot express (`EINVAL`/`ENOSYS`/`EOPNOTSUPP`) is advisory
     * because the entry is already visible and attributable; `EIO` means the kernel could not
     * answer and keeps the outcome UNKNOWN. Nothing is retried either way.
     */
    @Test
    fun fuseDirectoryFsyncUnsupportedErrnosAreAdvisoryWhileEioStaysUnknown() {
        for (errno in listOf("EINVAL", "ENOSYS", "EOPNOTSUPP")) {
            val e = fuseEnv()
            e.ops.failFsyncErrno = errno
            val text = "created-$errno"
            val result = e.exe.executeAtomicWriteText(
                RootTypedArguments.FileWriteText("ws", "fresh-$errno.txt", sha(text), text.toByteArray().size, false, null),
                text.toByteArray(),
            )
            assertTrue(result is RootFileOperationResult.Succeeded, "$errno must be advisory, got $result")
            assertEquals(text, e.ops.nodes["fresh-$errno.txt"]!!.bytes.toString(Charsets.UTF_8))
            assertEquals(1, e.ops.writeCalls, "$errno must not be retried")
            e.driver.close()
        }
        val e = fuseEnv()
        e.ops.failFsyncErrno = "EIO"
        val text = "created-eio"
        val result = e.exe.executeAtomicWriteText(
            RootTypedArguments.FileWriteText("ws", "fresh-eio.txt", sha(text), text.toByteArray().size, false, null),
            text.toByteArray(),
        )
        assertUnknown(result)
        assertEquals(text, e.ops.nodes["fresh-eio.txt"]!!.bytes.toString(Charsets.UTF_8), "the visible create is kept")
        assertEquals(1, e.ops.writeCalls, "an EIO outcome must not be retried")
        assertEquals(0, e.ops.unlinkCalls, "an UNKNOWN create must not be removed by name")
        e.driver.close()
    }

    /**
     * The mount-type predicate must cover the FUSE successors and the foreign block filesystems
     * that have no POSIX attributes or hard links; a missing observation is never guessed.
     */
    @Test
    fun emulatedStorageMountTypesCoverFuseSdcardfsAndForeignBlockFilesystems() {
        for (type in listOf("fuse", "sdcardfs", "vfat", "msdos", "exfat", "texfat", "fuseblk")) {
            assertTrue(isEmulatedMount(type), "expected $type to use emulated-storage semantics")
        }
        for (type in listOf("ext4", "f2fs", "tmpfs")) {
            assertFalse(isEmulatedMount(type), "expected $type to keep POSIX semantics")
        }
        assertFalse(isEmulatedMount(null), "an unavailable mount type must not be guessed")
    }
}

/** Minimal kernel double for production [AndroidOsPosixFdOperations] probes. */
private open class MemoryKernel : PosixKernelBridge {
    val files = mutableMapOf<String, ByteArray>()
    val modes = mutableMapOf<String, Int>()
    private val directories = setOf("/", "/workspace")
    private val fdPaths = mutableMapOf<Int, String>()
    private var nextFd = 100
    var closeCount = 0

    private fun resolve(path: String): String {
        val proc = "/proc/self/fd/"
        if (!path.startsWith(proc)) return path
        val rest = path.removePrefix(proc)
        val number = rest.substringBefore('/').toIntOrNull() ?: throw PosixOpException("EBADF")
        val base = fdPaths[number] ?: throw PosixOpException("EBADF")
        val child = rest.substringAfter('/', "")
        return when {
            child.isEmpty() -> base
            base == "/" -> "/$child"
            else -> "$base/$child"
        }
    }

    private fun node(path: String): String {
        val resolved = resolve(path)
        if (resolved !in directories && resolved !in files) throw PosixOpException("ENOENT")
        return resolved
    }

    override fun open(path: String, flags: Int, mode: Int): Pair<java.io.FileDescriptor, Int> {
        val resolved = node(path)
        val fd = nextFd++
        fdPaths[fd] = resolved
        return java.io.FileDescriptor() to fd
    }

    override fun close(rawFd: java.io.FileDescriptor, fdNumber: Int) {
        fdPaths.remove(fdNumber)
        closeCount += 1
    }

    override fun fstat(rawFd: java.io.FileDescriptor, fdNumber: Int): PosixRawStat {
        val path = fdPaths[fdNumber] ?: throw PosixOpException("EBADF")
        val bytes = files[path]
        val isDirectory = path in directories
        val typeBits = if (isDirectory) 0b0100_000_000_000_000 else 0b1000_000_000_000_000
        return PosixRawStat(
            deviceId = 1L,
            inode = path.hashCode().toLong() and 0xFFFF,
            mode = typeBits or (modes[path] ?: 448),
            hardlinkCount = 1L,
            uid = 0,
            gid = 0,
            sizeBytes = bytes?.size?.toLong() ?: 0L,
            mtimeSec = 10L,
            ctimeSec = 10L,
        )
    }

    override fun lstat(path: String): PosixRawStat = throw PosixOpException("EOPNOTSUPP")

    override fun readlink(path: String): String = resolve(path)

    override fun readMountId(fdNumber: Int): Long = 77L

    override fun getxattr(path: String, name: String): ByteArray =
        "u:object_r:app_data_file:s0".toByteArray()

    override fun setxattr(path: String, name: String, value: ByteArray, flags: Int) = Unit

    override fun preadSampleDigest(rawFd: java.io.FileDescriptor, fdNumber: Int, sizeBytes: Long): String =
        preadFullDigest(rawFd, fdNumber, sizeBytes)

    override fun preadFullDigest(rawFd: java.io.FileDescriptor, fdNumber: Int, sizeBytes: Long): String {
        val path = fdPaths[fdNumber] ?: throw PosixOpException("EBADF")
        val bytes = files[path] ?: throw PosixOpException("EISDIR")
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    override fun preadBytes(rawFd: java.io.FileDescriptor, fdNumber: Int, offsetBytes: Long, length: Int): ByteArray {
        val path = fdPaths[fdNumber] ?: throw PosixOpException("EBADF")
        val bytes = files[path] ?: throw PosixOpException("EISDIR")
        val from = offsetBytes.toInt()
        return bytes.copyOfRange(from, minOf(bytes.size, from + length))
    }

    override fun fchmod(rawFd: java.io.FileDescriptor, fdNumber: Int, mode: Int) = Unit

    override fun fchown(rawFd: java.io.FileDescriptor, fdNumber: Int, uid: Int, gid: Int) = Unit

    override fun writeAllAndFsync(rawFd: java.io.FileDescriptor, fdNumber: Int, bytes: ByteArray) = Unit

    override fun fsync(rawFd: java.io.FileDescriptor, fdNumber: Int) = Unit

    override fun rename(src: String, dst: String) = Unit

    override fun link(src: String, dst: String) = Unit

    override fun remove(path: String) = Unit
}

/**
 * Runs the production mount-type predicate against a synthetic `mountinfo` observation for an
 * already-open directory descriptor (the real predicate never looks at a path string).
 */
private fun isEmulatedMount(fileSystemType: String?): Boolean {
    val kernel = object : MemoryKernel() {
        override fun readFileSystemType(fdNumber: Int): String? = fileSystemType
    }
    val ops = AndroidOsPosixFdOperations(kernel = kernel)
    return ops.openDirectoryAbsoluteNoFollow("/workspace")!!.use { ops.usesEmulatedStoragePermissions(it) }
}
