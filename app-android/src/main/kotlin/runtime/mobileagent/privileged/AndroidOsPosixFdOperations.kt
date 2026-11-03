// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import runtime.mobileagent.domain.RootFdNodeIdentity
import runtime.mobileagent.domain.RootRecoveryLocatorCodec

interface PosixRetainedFd : AutoCloseable {
    val fdNumber: Int
    val isOpen: Boolean
}

class PosixOpException(
    val errnoName: String,
    val selinuxConfirmed: Boolean = false,
    val unsupportedAttribute: String? = null,
) : Exception(errnoName)

data class PosixRawStat(
    val deviceId: Long,
    val inode: Long,
    val mode: Int,
    val hardlinkCount: Long,
    val uid: Int,
    val gid: Int,
    val sizeBytes: Long,
    val mtimeSec: Long,
    val ctimeSec: Long,
)

interface PosixKernelBridge {
    fun open(path: String, flags: Int, mode: Int): Pair<FileDescriptor, Int>
    fun close(rawFd: FileDescriptor, fdNumber: Int)
    fun fstat(rawFd: FileDescriptor, fdNumber: Int): PosixRawStat
    fun lstat(path: String): PosixRawStat
    fun readlink(path: String): String
    fun readMountId(fdNumber: Int): Long?
    fun getxattr(path: String, name: String): ByteArray
    fun setxattr(path: String, name: String, value: ByteArray, flags: Int)
    fun listxattr(path: String): List<String> = emptyList()
    fun preadSampleDigest(rawFd: FileDescriptor, fdNumber: Int, sizeBytes: Long): String
    fun fchmod(rawFd: FileDescriptor, fdNumber: Int, mode: Int)
    fun fchown(rawFd: FileDescriptor, fdNumber: Int, uid: Int, gid: Int)
    fun writeAllAndFsync(rawFd: FileDescriptor, fdNumber: Int, bytes: ByteArray)
    fun writeChunksAndFsync(
        rawFd: FileDescriptor,
        fdNumber: Int,
        writer: ((ByteArray, Int, Int) -> Unit) -> Unit,
    ) {
        val out = java.io.ByteArrayOutputStream()
        writer { buf, off, len -> if (len > 0) out.write(buf, off, len) }
        writeAllAndFsync(rawFd, fdNumber, out.toByteArray())
    }
    fun fsync(rawFd: FileDescriptor, fdNumber: Int)
    fun rename(src: String, dst: String)
    fun link(src: String, dst: String)
    fun remove(path: String)

    /** Full-content SHA-256; production fails closed when the object is unreadable or too large. */
    fun preadFullDigest(rawFd: FileDescriptor, fdNumber: Int, sizeBytes: Long): String =
        preadSampleDigest(rawFd, fdNumber, sizeBytes)

    fun preadBytes(rawFd: FileDescriptor, fdNumber: Int, offsetBytes: Long, length: Int): ByteArray =
        throw PosixOpException("EOPNOTSUPP")

    fun listNames(fdNumber: Int): List<String> = throw PosixOpException("EOPNOTSUPP")

    fun mkdirAt(parentFdNumber: Int, name: String, mode: Int): Unit = throw PosixOpException("EOPNOTSUPP")

    fun renameAt(srcParentFdNumber: Int, srcName: String, dstParentFdNumber: Int, dstName: String): Unit =
        throw PosixOpException("EOPNOTSUPP")

    fun linkAt(srcParentFdNumber: Int, srcName: String, dstParentFdNumber: Int, dstName: String): Unit =
        throw PosixOpException("EOPNOTSUPP")
}

object AndroidOsKernelBridge : PosixKernelBridge {
    override fun open(path: String, flags: Int, mode: Int): Pair<FileDescriptor, Int> {
        val rawFd = Os.open(path, flags, mode)
        val num = PosixKernelReads.extractFdNumber(rawFd)
        if (num < 0) {
            runCatching { Os.close(rawFd) }
            throw PosixOpException("EBADF")
        }
        return rawFd to num
    }

    override fun close(rawFd: FileDescriptor, fdNumber: Int) {
        runCatching { Os.close(rawFd) }
    }

    override fun fstat(rawFd: FileDescriptor, fdNumber: Int): PosixRawStat {
        val st = Os.fstat(rawFd)
        return PosixRawStat(
            deviceId = st.st_dev,
            inode = st.st_ino,
            mode = st.st_mode,
            hardlinkCount = st.st_nlink,
            uid = st.st_uid,
            gid = st.st_gid,
            sizeBytes = st.st_size,
            mtimeSec = st.st_mtime,
            ctimeSec = st.st_ctime,
        )
    }

    override fun lstat(path: String): PosixRawStat {
        val st = Os.lstat(path)
        return PosixRawStat(
            deviceId = st.st_dev,
            inode = st.st_ino,
            mode = st.st_mode,
            hardlinkCount = st.st_nlink,
            uid = st.st_uid,
            gid = st.st_gid,
            sizeBytes = st.st_size,
            mtimeSec = st.st_mtime,
            ctimeSec = st.st_ctime,
        )
    }

    override fun readlink(path: String): String = Os.readlink(path)

    override fun readMountId(fdNumber: Int): Long? {
        val infoFile = File("/proc/self/fdinfo/$fdNumber")
        val lines = runCatching { infoFile.readLines() }.getOrNull() ?: return null
        val mntLine = lines.firstOrNull { it.startsWith("mnt_id:") } ?: return null
        return mntLine.substringAfter("mnt_id:").trim().toLongOrNull()?.takeIf { it > 0L }
    }

    override fun getxattr(path: String, name: String): ByteArray = Os.getxattr(path, name)

    override fun setxattr(path: String, name: String, value: ByteArray, flags: Int) {
        Os.setxattr(path, name, value, flags)
    }

    override fun listxattr(path: String): List<String> = PosixXattrAndAncestorSupport.invokeOsListXattr(path)

    override fun preadSampleDigest(rawFd: FileDescriptor, fdNumber: Int, sizeBytes: Long): String {
        val cap = sizeBytes.coerceIn(0L, 65_536L).toInt()
        if (cap == 0) return "empty"
        val buf = ByteArray(cap)
        val read = Os.pread(rawFd, buf, 0, cap, 0L)
        if (read != cap) throw PosixOpException("EIO")
        val digest = MessageDigest.getInstance("SHA-256")
        if (read > 0) digest.update(buf, 0, read)
        return digest.digest().take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    override fun fchmod(rawFd: FileDescriptor, fdNumber: Int, mode: Int) {
        Os.fchmod(rawFd, mode)
    }

    override fun fchown(rawFd: FileDescriptor, fdNumber: Int, uid: Int, gid: Int) {
        Os.fchown(rawFd, uid, gid)
    }

    override fun writeAllAndFsync(rawFd: FileDescriptor, fdNumber: Int, bytes: ByteArray) {
        writeChunksAndFsync(rawFd, fdNumber) { emit -> emit(bytes, 0, bytes.size) }
    }

    override fun writeChunksAndFsync(
        rawFd: FileDescriptor,
        fdNumber: Int,
        writer: ((ByteArray, Int, Int) -> Unit) -> Unit,
    ) {
        writer { bytes, startOffset, length ->
            var offset = startOffset
            val end = startOffset + length
            while (offset < end) {
                if (Thread.currentThread().isInterrupted) throw PosixOpException("ECANCELED")
                val written = Os.write(rawFd, bytes, offset, minOf(end - offset, 65_536))
                if (written <= 0) throw PosixOpException("EIO")
                offset += written
            }
        }
        if (Thread.currentThread().isInterrupted) throw PosixOpException("ECANCELED")
        Os.fsync(rawFd)
    }

    override fun fsync(rawFd: FileDescriptor, fdNumber: Int) {
        Os.fsync(rawFd)
    }

    override fun rename(src: String, dst: String) {
        Os.rename(src, dst)
    }

    override fun link(src: String, dst: String) {
        Os.link(src, dst)
    }

    override fun remove(path: String) {
        Os.remove(path)
    }

    override fun preadFullDigest(rawFd: FileDescriptor, fdNumber: Int, sizeBytes: Long): String =
        PosixKernelReads.fullContentDigest(rawFd, sizeBytes)

    override fun preadBytes(rawFd: FileDescriptor, fdNumber: Int, offsetBytes: Long, length: Int): ByteArray =
        PosixKernelReads.readRange(rawFd, offsetBytes, length)

    override fun listNames(fdNumber: Int): List<String> = PosixKernelReads.listNames(fdNumber)

    override fun mkdirAt(parentFdNumber: Int, name: String, mode: Int): Unit =
        PosixKernelMutations.mkdirAt(parentFdNumber, name, mode)

    override fun renameAt(srcParentFdNumber: Int, srcName: String, dstParentFdNumber: Int, dstName: String): Unit =
        PosixKernelMutations.renameAt(srcParentFdNumber, srcName, dstParentFdNumber, dstName)

    override fun linkAt(srcParentFdNumber: Int, srcName: String, dstParentFdNumber: Int, dstName: String): Unit =
        PosixKernelMutations.linkAt(srcParentFdNumber, srcName, dstParentFdNumber, dstName)
}

interface PosixFdOperations {
    fun openDirectoryAbsoluteNoFollow(absolutePath: String): PosixRetainedFd?
    fun openDirectoryWithAncestorsNoFollow(
        absolutePath: String,
    ): Pair<PosixRetainedFd, List<RootFdNodeIdentity>>? =
        openDirectoryAbsoluteNoFollow(absolutePath)?.let { it to emptyList() }
    fun openChildDirectoryAtNoFollow(parentFd: PosixRetainedFd, childName: String): PosixRetainedFd?
    fun openTargetAt(
        parentFd: PosixRetainedFd,
        childName: String,
        followSymlink: Boolean,
        writable: Boolean,
    ): PosixRetainedFd
    fun statAtNoFollow(parentFd: PosixRetainedFd, childName: String): RootFdNodeIdentity?
    fun fstatIdentity(fd: PosixRetainedFd): RootFdNodeIdentity?
    fun fstatSizeBytes(fd: PosixRetainedFd): Long?
    fun fstatVersionStamp(fd: PosixRetainedFd): String = ""
    fun readFdResolvedPath(fd: PosixRetainedFd): String?
    fun fchmod(fd: PosixRetainedFd, modeOctal: Int)
    fun fchown(fd: PosixRetainedFd, ownerUid: Int, ownerGid: Int)
    fun fsetSelinuxLabel(fd: PosixRetainedFd, selinuxLabel: String)
    fun flistxattr(fd: PosixRetainedFd): List<String> = emptyList()
    fun fgetxattr(fd: PosixRetainedFd, name: String): ByteArray? = null
    fun fsetxattr(fd: PosixRetainedFd, name: String, value: ByteArray) = Unit
    fun createSiblingTempAt(parentFd: PosixRetainedFd, tempName: String, modeOctal: Int): PosixRetainedFd
    fun writeAllAndFsync(fd: PosixRetainedFd, bytes: ByteArray)
    fun writeChunksAndFsync(fd: PosixRetainedFd, writer: ((ByteArray, Int, Int) -> Unit) -> Unit) {
        val out = java.io.ByteArrayOutputStream()
        writer { buf, off, len -> if (len > 0) out.write(buf, off, len) }
        writeAllAndFsync(fd, out.toByteArray())
    }
    fun fsyncDirectory(dirFd: PosixRetainedFd)
    fun renameReplaceAt(parentFd: PosixRetainedFd, tempName: String, targetName: String)
    fun linkNoReplaceAt(parentFd: PosixRetainedFd, tempName: String, targetName: String)
    fun unlinkAt(parentFd: PosixRetainedFd, childName: String): Boolean

    /** Bounded window read under an already-open FD; never follows/reopens by path string. */
    fun readFdRange(fd: PosixRetainedFd, offsetBytes: Long, maxBytes: Int): ByteArray =
        throw PosixOpException("EOPNOTSUPP")

    /** Full-content SHA-256 for the complete-content version; null only when unsupported. */
    fun fstatContentDigest(fd: PosixRetainedFd): String? = null

    fun listChildNames(dirFd: PosixRetainedFd): List<String> = throw PosixOpException("EOPNOTSUPP")

    fun mkdirAt(parentFd: PosixRetainedFd, childName: String, modeOctal: Int): Unit =
        throw PosixOpException("EOPNOTSUPP")

    fun renameAt(
        srcParentFd: PosixRetainedFd,
        srcName: String,
        dstParentFd: PosixRetainedFd,
        dstName: String,
    ): Unit = throw PosixOpException("EOPNOTSUPP")

    fun linkAtNoReplace(
        srcParentFd: PosixRetainedFd,
        srcName: String,
        dstParentFd: PosixRetainedFd,
        dstName: String,
    ): Unit = throw PosixOpException("EOPNOTSUPP")

    /**
     * Links the inode referenced by [sourceFd] into `parentFd/leafName` without replacing an
     * existing entry (`linkat(AT_EMPTY_PATH)` semantics).
     *
     * The source is an FD rather than a name, so a directory owner that swaps the staged name
     * cannot substitute its own bytes: only the inode this driver wrote can be committed.
     * Platforms without the primitive fail closed instead of falling back to a name lookup.
     */
    fun linkAtFdNoReplace(
        parentFd: PosixRetainedFd,
        sourceFd: PosixRetainedFd,
        leafName: String,
    ): Unit = throw PosixOpException("EOPNOTSUPP")

    /** Removes an empty directory entry (`unlinkat(AT_REMOVEDIR)`); unsupported by default. */
    fun removeDirectoryAt(parentFd: PosixRetainedFd, childName: String): Unit =
        throw PosixOpException("EOPNOTSUPP")

    fun inspectDirectoryIdentityNoFollow(path: Path): RootFdNodeIdentity? {
        val opened = openDirectoryAbsoluteNoFollow(AndroidRootPosixFileDriver.toPosixAbsolutePath(path)) ?: return null
        return opened.use { fd -> fstatIdentity(fd) }
    }
}

/**
 * Production Android/Linux [PosixFdOperations] backed by [android.system.Os], `/proc/self/fd`,
 * and `/proc/self/fdinfo`. Reads and writes `security.selinux` extended attributes directly on
 * `/proc/self/fd/<n>` by default and fails closed when any kernel observation is unavailable.
 */
class AndroidOsPosixFdOperations(
    private val selinuxLabelReader: ((Path) -> String?)? = null,
    private val selinuxLabelWriter: ((Path, String) -> Boolean)? = null,

    internal val kernel: PosixKernelBridge = AndroidOsKernelBridge,
    private val selinuxDenialDetector: ((String, Int) -> Boolean)? = null,
) : PosixFdOperations {

    internal inner class AndroidFdHandle(
        val rawFd: FileDescriptor,
        override val fdNumber: Int,
    ) : PosixRetainedFd {
        private val closed = AtomicBoolean(false)
        override val isOpen: Boolean
            get() = !closed.get() && fdNumber >= 0

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                kernel.close(rawFd, fdNumber)
            }
        }
    }

    internal fun openRootSlashDirectory(): PosixRetainedFd? {
        val flags = OsConstants.O_RDONLY or O_CLOEXEC_FLAG or O_DIRECTORY_FLAG or O_NOFOLLOW_FLAG
        return openRawHandle("/", flags, 0)
    }

    internal fun inspectOwnAppDirectoryIdentityNoFollow(path: Path): RootFdNodeIdentity? =
        PosixXattrAndAncestorSupport.inspectOwnAppDirectoryIdentity(this, kernel, path)

    override fun openDirectoryWithAncestorsNoFollow(
        absolutePath: String,
    ): Pair<PosixRetainedFd, List<RootFdNodeIdentity>>? =
        PosixXattrAndAncestorSupport.openDirectoryWithAncestors(this, kernel, absolutePath)

    override fun openDirectoryAbsoluteNoFollow(absolutePath: String): PosixRetainedFd? =
        PosixXattrAndAncestorSupport.openDirectoryAbsolute(this, kernel, absolutePath)

    override fun openChildDirectoryAtNoFollow(parentFd: PosixRetainedFd, childName: String): PosixRetainedFd? {
        val path = procFdChildPath(parentFd, childName) ?: return null
        val flags = OsConstants.O_RDONLY or O_CLOEXEC_FLAG or O_DIRECTORY_FLAG or O_NOFOLLOW_FLAG
        return openRawHandle(path, flags, 0)
    }

    override fun openTargetAt(
        parentFd: PosixRetainedFd,
        childName: String,
        followSymlink: Boolean,
        writable: Boolean,
    ): PosixRetainedFd {
        val path = procFdChildPath(parentFd, childName) ?: throw PosixOpException("EINVAL")
        val noFollow = if (followSymlink) 0 else O_NOFOLLOW_FLAG
        val rwMode = if (writable) OsConstants.O_RDWR else OsConstants.O_RDONLY
        val fileFlags = rwMode or O_CLOEXEC_FLAG or OsConstants.O_NONBLOCK or noFollow
        return try {
            openRawOrThrow(path, fileFlags, 0)
        } catch (err: PosixOpException) {
            if (err.errnoName != "EISDIR" || writable) throw err
            val dirFlags = OsConstants.O_RDONLY or O_CLOEXEC_FLAG or O_DIRECTORY_FLAG or noFollow
            openRawOrThrow(path, dirFlags, 0)
        }
    }

    override fun statAtNoFollow(parentFd: PosixRetainedFd, childName: String): RootFdNodeIdentity? =
        PosixXattrAndAncestorSupport.statAtNoFollow(this, kernel, parentFd, childName)

    override fun fstatIdentity(fd: PosixRetainedFd): RootFdNodeIdentity? {
        val handle = fd as? AndroidFdHandle ?: return null
        if (!handle.isOpen) return null
        val st = runCatching { kernel.fstat(handle.rawFd, handle.fdNumber) }.getOrNull() ?: return null
        val mountId = kernel.readMountId(handle.fdNumber) ?: return null
        val resolvedPath = readFdResolvedPath(handle) ?: return null
        val selinuxLabel = readFdSelinuxLabel(handle.fdNumber, resolvedPath) ?: return null
        if (st.deviceId <= 0L || st.inode <= 0L || mountId <= 0L || st.hardlinkCount < 0L) return null
        return RootFdNodeIdentity(
            deviceId = st.deviceId,
            inode = st.inode,
            mountId = mountId,
            isDirectory = isDirMode(st.mode),
            isRegularFile = isRegMode(st.mode),
            isSymlink = isSymlinkMode(st.mode),
            hardlinkCount = st.hardlinkCount,
            modeOctal = st.mode and 0b111_111_111_111,
            ownerUid = st.uid,
            ownerGid = st.gid,
            selinuxLabel = selinuxLabel,
        )
    }

    override fun fstatSizeBytes(fd: PosixRetainedFd): Long? {
        val handle = fd as? AndroidFdHandle ?: return null
        val st = runCatching { kernel.fstat(handle.rawFd, handle.fdNumber) }.getOrNull() ?: return null
        return st.sizeBytes.takeIf { it >= 0L }
    }

    override fun fstatVersionStamp(fd: PosixRetainedFd): String {
        val handle = requireOpenHandle(fd)
        val st = runOsOrThrow { kernel.fstat(handle.rawFd, handle.fdNumber) }
        return "${st.mtimeSec}:${st.ctimeSec}"
    }

    override fun readFdResolvedPath(fd: PosixRetainedFd): String? {
        if (!fd.isOpen || fd.fdNumber < 0) return null
        val target = runCatching { kernel.readlink("/proc/self/fd/${fd.fdNumber}") }.getOrNull() ?: return null
        return target.takeIf { RootRecoveryLocatorCodec.isValidAbsoluteRootPath(it) }
    }

    override fun fchmod(fd: PosixRetainedFd, modeOctal: Int) {
        val handle = requireOpenHandle(fd)
        runOsOrThrow { kernel.fchmod(handle.rawFd, handle.fdNumber, modeOctal and 0b111_111_111_111) }
    }

    override fun fchown(fd: PosixRetainedFd, ownerUid: Int, ownerGid: Int) {
        val handle = requireOpenHandle(fd)
        runOsOrThrow { kernel.fchown(handle.rawFd, handle.fdNumber, ownerUid, ownerGid) }
    }

    override fun fsetSelinuxLabel(fd: PosixRetainedFd, selinuxLabel: String) {
        val handle = requireOpenHandle(fd)
        val normalized = selinuxLabel.trim()
        if (normalized.isEmpty() || normalized.contains('\u0000')) {
            throw PosixOpException("EINVAL")
        }
        val customWriter = selinuxLabelWriter
        if (customWriter != null) {
            val resolved = readFdResolvedPath(handle) ?: "/proc/self/fd/${handle.fdNumber}"
            val applied = runCatching { customWriter(Paths.get(resolved), normalized) }.getOrDefault(false)
            if (!applied) throw PosixOpException("EOPNOTSUPP", unsupportedAttribute = XATTR_SELINUX)
            return
        }
        val payload = "$normalized\u0000".toByteArray(StandardCharsets.UTF_8)
        runOsOrThrow(unsupportedAttr = XATTR_SELINUX) {
            kernel.setxattr("/proc/self/fd/${handle.fdNumber}", XATTR_SELINUX, payload, 0)
        }
    }

    override fun flistxattr(fd: PosixRetainedFd): List<String> {
        val handle = requireOpenHandle(fd)
        return try {
            runOsOrThrow(unsupportedAttr = "xattr.list") {
                kernel.listxattr("/proc/self/fd/${handle.fdNumber}")
            }
        } catch (err: PosixOpException) {
            if (PosixErrnoMapping.isUnsupportedXattr(err.errnoName)) emptyList() else throw err
        }
    }

    override fun fgetxattr(fd: PosixRetainedFd, name: String): ByteArray? {
        val handle = requireOpenHandle(fd)
        return runOsOrThrow(unsupportedAttr = name) {
            kernel.getxattr("/proc/self/fd/${handle.fdNumber}", name)
        }
    }

    override fun fsetxattr(fd: PosixRetainedFd, name: String, value: ByteArray) {
        val handle = requireOpenHandle(fd)
        runOsOrThrow(unsupportedAttr = name) {
            kernel.setxattr("/proc/self/fd/${handle.fdNumber}", name, value, 0)
        }
    }

    override fun createSiblingTempAt(parentFd: PosixRetainedFd, tempName: String, modeOctal: Int): PosixRetainedFd {
        val path = procFdChildPath(parentFd, tempName) ?: throw PosixOpException("EINVAL")
        val flags = OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_RDWR or
            O_CLOEXEC_FLAG or O_NOFOLLOW_FLAG
        return openRawOrThrow(path, flags, modeOctal and 0b111_111_111)
    }

    override fun writeAllAndFsync(fd: PosixRetainedFd, bytes: ByteArray) {
        val handle = requireOpenHandle(fd)
        runOsOrThrow { kernel.writeAllAndFsync(handle.rawFd, handle.fdNumber, bytes) }
    }

    override fun writeChunksAndFsync(fd: PosixRetainedFd, writer: ((ByteArray, Int, Int) -> Unit) -> Unit) {
        val handle = requireOpenHandle(fd)
        runOsOrThrow { kernel.writeChunksAndFsync(handle.rawFd, handle.fdNumber, writer) }
    }

    override fun fsyncDirectory(dirFd: PosixRetainedFd) {
        val handle = requireOpenHandle(dirFd)
        runOsOrThrow { kernel.fsync(handle.rawFd, handle.fdNumber) }
    }

    override fun renameReplaceAt(parentFd: PosixRetainedFd, tempName: String, targetName: String) {
        val src = procFdChildPath(parentFd, tempName) ?: throw PosixOpException("EINVAL")
        val dst = procFdChildPath(parentFd, targetName) ?: throw PosixOpException("EINVAL")
        runOsOrThrow { kernel.rename(src, dst) }
    }

    override fun linkNoReplaceAt(parentFd: PosixRetainedFd, tempName: String, targetName: String) {
        val src = procFdChildPath(parentFd, tempName) ?: throw PosixOpException("EINVAL")
        val dst = procFdChildPath(parentFd, targetName) ?: throw PosixOpException("EINVAL")
        runOsOrThrow { kernel.link(src, dst) }
    }

    override fun unlinkAt(parentFd: PosixRetainedFd, childName: String): Boolean {
        val target = procFdChildPath(parentFd, childName) ?: return false
        return runCatching {
            kernel.remove(target)
            true
        }.getOrDefault(false)
    }

    override fun linkAtFdNoReplace(
        parentFd: PosixRetainedFd,
        sourceFd: PosixRetainedFd,
        leafName: String,
    ) {
        val source = requireOpenHandle(sourceFd)
        val parent = requireOpenHandle(parentFd)
        if (!isValidSingleComponent(leafName)) throw PosixOpException("EINVAL")
        // `linkat(AT_EMPTY_PATH)` is not exposed by android.system.Os; the shipped supervisor
        // library provides it. Fail closed rather than falling back to a name-based link.
        if (!RootNativeSupervisorJni.ensureLoaded()) throw PosixOpException("EOPNOTSUPP")
        val rc = runCatching {
            RootNativeSupervisorJni.nativeLinkAtEmptyPath(parent.fdNumber, source.fdNumber, leafName)
        }.getOrDefault(-OsConstants.ENOSYS)
        if (rc != 0) throw PosixOpException(PosixErrnoMapping.name(-rc, "EIO"))
    }

    override fun removeDirectoryAt(parentFd: PosixRetainedFd, childName: String) {
        val parent = requireOpenHandle(parentFd)
        if (!isValidSingleComponent(childName)) throw PosixOpException("EINVAL")
        if (!RootNativeSupervisorJni.ensureLoaded()) throw PosixOpException("EOPNOTSUPP")
        val rc = runCatching {
            RootNativeSupervisorJni.nativeRemoveDirectoryAt(parent.fdNumber, childName)
        }.getOrDefault(-OsConstants.ENOSYS)
        if (rc != 0) throw PosixOpException(PosixErrnoMapping.name(-rc, "EIO"))
    }

    override fun readFdRange(fd: PosixRetainedFd, offsetBytes: Long, maxBytes: Int): ByteArray {
        val handle = requireOpenHandle(fd)
        return runOsOrThrow { kernel.preadBytes(handle.rawFd, handle.fdNumber, offsetBytes, maxBytes) }
    }

    override fun fstatContentDigest(fd: PosixRetainedFd): String? {
        val handle = requireOpenHandle(fd)
        val st = runOsOrThrow { kernel.fstat(handle.rawFd, handle.fdNumber) }
        if (!isRegMode(st.mode) || st.sizeBytes < 0L || st.sizeBytes > runtime.mobileagent.domain.RootContentVersionPolicy.MAX_CONTENT_BYTES) {
            return null
        }
        return runOsOrThrow { kernel.preadFullDigest(handle.rawFd, handle.fdNumber, st.sizeBytes) }
    }

    override fun listChildNames(dirFd: PosixRetainedFd): List<String> {
        val handle = requireOpenHandle(dirFd)
        return runOsOrThrow { kernel.listNames(handle.fdNumber) }
    }

    override fun mkdirAt(parentFd: PosixRetainedFd, childName: String, modeOctal: Int) {
        val handle = requireOpenHandle(parentFd)
        runOsOrThrow { kernel.mkdirAt(handle.fdNumber, childName, modeOctal and 0b111_111_111) }
    }

    override fun renameAt(
        srcParentFd: PosixRetainedFd,
        srcName: String,
        dstParentFd: PosixRetainedFd,
        dstName: String,
    ) {
        val src = requireOpenHandle(srcParentFd)
        val dst = requireOpenHandle(dstParentFd)
        runOsOrThrow { kernel.renameAt(src.fdNumber, srcName, dst.fdNumber, dstName) }
    }

    override fun linkAtNoReplace(
        srcParentFd: PosixRetainedFd,
        srcName: String,
        dstParentFd: PosixRetainedFd,
        dstName: String,
    ) {
        val src = requireOpenHandle(srcParentFd)
        val dst = requireOpenHandle(dstParentFd)
        runOsOrThrow { kernel.linkAt(src.fdNumber, srcName, dst.fdNumber, dstName) }
    }

    private val identityReader = AndroidOsFdIdentityReader(this)

    internal val selinuxLabelReaderOrNull: ((Path) -> String?)?
        get() = selinuxLabelReader

    internal fun requireOpenHandle(fd: PosixRetainedFd): AndroidFdHandle {
        val handle = fd as? AndroidFdHandle ?: throw PosixOpException("EBADF")
        if (!handle.isOpen) throw PosixOpException("EBADF")
        return handle
    }

    internal fun procFdChildPath(parentFd: PosixRetainedFd, childName: String): String? {
        if (!parentFd.isOpen || parentFd.fdNumber < 0 || !isValidSingleComponent(childName)) return null
        return "/proc/self/fd/${parentFd.fdNumber}/$childName"
    }

    private fun openRawHandle(path: String, flags: Int, mode: Int): PosixRetainedFd? =
        runCatching { openRawOrThrow(path, flags, mode) }.getOrNull()

    private fun openRawOrThrow(path: String, flags: Int, mode: Int): PosixRetainedFd {
        val (rawFd, num) = runOsOrThrow { kernel.open(path, flags, mode) }
        return AndroidFdHandle(rawFd, num)
    }

    private fun <T> runOsOrThrow(unsupportedAttr: String? = null, block: () -> T): T = try {
        block()
    } catch (already: PosixOpException) {
        throw already
    } catch (errno: ErrnoException) {
        val fallback = runCatching { Os.strerror(errno.errno) }.getOrNull() ?: "ERRNO_${errno.errno}"
        val mapped = PosixErrnoMapping.name(errno.errno, fallback)
        val selinuxDenied = (mapped == "EACCES" || mapped == "EPERM") &&
            runCatching { selinuxDenialDetector?.invoke(mapped, errno.errno) == true }.getOrDefault(false)
        throw PosixOpException(
            errnoName = mapped,
            selinuxConfirmed = selinuxDenied,
            unsupportedAttribute = if (PosixErrnoMapping.isUnsupportedXattr(mapped)) unsupportedAttr else null,
        )
    } catch (_: Throwable) {
        throw PosixOpException("EIO")
    }

    private fun readFdSelinuxLabel(fdNumber: Int, resolvedPath: String): String? =
        identityReader.selinuxLabel(fdNumber, resolvedPath)

    companion object {
        const val XATTR_SELINUX = "security.selinux"
        private const val S_IFMT_MASK = 0b1111_000_000_000_000
        private const val S_IFDIR_BITS = 0b0100_000_000_000_000
        private const val S_IFREG_BITS = 0b1000_000_000_000_000
        private const val S_IFLNK_BITS = 0b1010_000_000_000_000

        private val O_DIRECTORY_FLAG: Int = runCatching {
            OsConstants::class.java.getField("O_DIRECTORY").getInt(null)
        }.getOrDefault(0x10000)

        private val O_NOFOLLOW_FLAG: Int = runCatching {
            OsConstants::class.java.getField("O_NOFOLLOW").getInt(null)
        }.getOrDefault(0x20000)

        private val O_CLOEXEC_FLAG: Int = runCatching {
            OsConstants::class.java.getField("O_CLOEXEC").getInt(null)
        }.getOrDefault(0x80000)

        internal fun isDirMode(mode: Int): Boolean =
            runCatching { OsConstants.S_ISDIR(mode) }.getOrDefault((mode and S_IFMT_MASK) == S_IFDIR_BITS)

        internal fun isRegMode(mode: Int): Boolean =
            runCatching { OsConstants.S_ISREG(mode) }.getOrDefault((mode and S_IFMT_MASK) == S_IFREG_BITS)

        internal fun isSymlinkMode(mode: Int): Boolean =
            runCatching { OsConstants.S_ISLNK(mode) }.getOrDefault((mode and S_IFMT_MASK) == S_IFLNK_BITS)

        internal fun isValidSingleComponent(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && !name.contains('\u0000')
    }
}
