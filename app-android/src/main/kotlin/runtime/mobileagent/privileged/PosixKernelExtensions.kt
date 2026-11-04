// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import runtime.mobileagent.domain.RootContentVersionPolicy
import runtime.mobileagent.domain.RootFdNodeIdentity
import runtime.mobileagent.domain.RootRecoveryLocatorCodec

/** Kernel-backed full-content hashing, bounded range reads and directory listing. */
internal object PosixKernelReads {
    private const val COPY_BUFFER_BYTES = 64 * 1024
    private val FD_STRING_REGEX = Regex("""FileDescriptor\[(\d+)]""")

    fun extractFdNumber(rawFd: FileDescriptor): Int {
        val candidates = listOf(
            runCatching {
                FileDescriptor::class.java.getDeclaredField("descriptor").apply { isAccessible = true }.getInt(rawFd)
            }.getOrDefault(-1),
            runCatching {
                FileDescriptor::class.java.getDeclaredMethod("getInt$").invoke(rawFd).toString().toIntOrNull() ?: -1
            }.getOrDefault(-1),
            FD_STRING_REGEX.find(rawFd.toString())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1,
        )
        return candidates.firstOrNull { it >= 0 } ?: -1
    }

    fun fullContentDigest(rawFd: FileDescriptor, sizeBytes: Long): String {
        if (sizeBytes < 0L) throw PosixOpException("EIO")
        if (sizeBytes > RootContentVersionPolicy.MAX_CONTENT_BYTES) throw PosixOpException("EOVERFLOW")
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(minOf(COPY_BUFFER_BYTES.toLong(), maxOf(sizeBytes, 1L)).toInt())
        var offset = 0L
        while (offset < sizeBytes) {
            if (Thread.currentThread().isInterrupted) throw PosixOpException("ECANCELED")
            val chunk = minOf(buffer.size.toLong(), sizeBytes - offset).toInt()
            val read = Os.pread(rawFd, buffer, 0, chunk, offset)
            if (read != chunk) throw PosixOpException("EIO")
            digest.update(buffer, 0, read)
            offset += read
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun readRange(rawFd: FileDescriptor, offsetBytes: Long, maxBytes: Int): ByteArray {
        if (offsetBytes < 0L || maxBytes < 0 || maxBytes > runtime.mobileagent.domain.RootExecutionBudget.MAX_OUTPUT_BYTES) {
            throw PosixOpException("EINVAL")
        }
        val output = ByteArray(maxBytes)
        var read = 0
        while (read < maxBytes) {
            if (Thread.currentThread().isInterrupted) throw PosixOpException("ECANCELED")
            val chunk = Os.pread(rawFd, output, read, maxBytes - read, offsetBytes + read)
            if (chunk < 0) throw PosixOpException("EIO")
            if (chunk == 0) break
            read += chunk
        }
        return if (read == output.size) output else output.copyOf(read)
    }

    fun listNames(fdNumber: Int): List<String> {
        val names = File("/proc/self/fd/$fdNumber").list() ?: throw PosixOpException("EIO")
        return names.filter(PosixDirectoryNames::isValidEntryName).sorted()
    }
}

/** Kernel-backed entry mutations addressed through already-open parent directory FDs. */
internal object PosixKernelMutations {
    fun mkdirAt(parentFdNumber: Int, name: String, modeBits: Int) {
        Os.mkdir(PosixProcFd.childPath(parentFdNumber, name), modeBits)
    }

    fun renameAt(srcParentFdNumber: Int, srcName: String, dstParentFdNumber: Int, dstName: String) {
        Os.rename(
            PosixProcFd.childPath(srcParentFdNumber, srcName),
            PosixProcFd.childPath(dstParentFdNumber, dstName),
        )
    }

    fun linkAt(srcParentFdNumber: Int, srcName: String, dstParentFdNumber: Int, dstName: String) {
        Os.link(
            PosixProcFd.childPath(srcParentFdNumber, srcName),
            PosixProcFd.childPath(dstParentFdNumber, dstName),
        )
    }

    /**
     * `renameat2(2, ..., RENAME_NOREPLACE)`: the kernel refuses an existing destination entry
     * instead of replacing it, so the source name disappears only together with a successful
     * rename. Every no-replace move and every directory move commits through it — emulated storage
     * (`fuse`/`sdcardfs`/vfat/exFAT/`fuseblk`) cannot create hard links at all, and `rename(2)`
     * would replace an empty destination directory.
     *
     * The shipped supervisor library performs the syscall on the retained parent FDs. A missing
     * symbol, an old kernel or a filesystem that rejects the flag is reported as
     * `EOPNOTSUPP`/`ENOSYS`/`EINVAL` (mapped by the caller to an explicit unsupported result); it
     * is never retried as a check-then-rename.
     */
    fun renameAtNoReplace(
        srcParentFdNumber: Int,
        srcName: String,
        dstParentFdNumber: Int,
        dstName: String,
    ) {
        // Same single-component validation as every other entry mutation: the native call gets FD
        // numbers, so neither an invalid fd nor a name with a separator may reach it.
        PosixProcFd.childPath(srcParentFdNumber, srcName)
        PosixProcFd.childPath(dstParentFdNumber, dstName)
        if (!RootNativeSupervisorJni.ensureLoaded()) throw PosixOpException("EOPNOTSUPP")
        val rc = runCatching {
            RootNativeSupervisorJni.nativeRenameAtNoReplace(
                srcParentFdNumber,
                srcName,
                dstParentFdNumber,
                dstName,
            )
        }.getOrDefault(-OsConstants.ENOSYS)
        if (rc != 0) throw PosixOpException(PosixErrnoMapping.name(-rc, "EIO"))
    }
}

internal object PosixProcFd {
    fun childPath(fdNumber: Int, name: String): String {
        if (fdNumber < 0 || !PosixDirectoryNames.isValidEntryName(name)) {
            throw PosixOpException("EINVAL")
        }
        return "/proc/self/fd/$fdNumber/$name"
    }
}

/**
 * Directory durability classification for `fsync(2)` on a directory FD.
 *
 * FUSE, `sdcardfs`, vfat, exFAT and other foreign filesystems cannot express a directory sync and
 * fail with `EINVAL`/`ENOSYS`/`EOPNOTSUPP`; the entry change is already visible and attributable,
 * so those errors must not be reported as an uncertain mutation. `EIO` — and every other errno —
 * means the kernel could not answer, so the caller keeps the outcome `UNKNOWN`.
 */
internal object PosixDirectoryDurability {
    private val UNSUPPORTED_DIRECTORY_SYNC_ERRNOS = setOf("EINVAL", "ENOSYS", "EOPNOTSUPP", "ENOTSUP")

    fun isUnsupportedDirectorySync(errnoName: String): Boolean = errnoName in UNSUPPORTED_DIRECTORY_SYNC_ERRNOS
}

internal object PosixDirectoryNames {
    private const val MAX_NAME_BYTES = 255

    /**
     * Authorization names are literal: leading/trailing whitespace is preserved. Rejects path
     * separators, NUL/control characters, dot entries, replacement characters from non-UTF8
     * on-disk names, and names beyond the ext4/f2fs byte limit.
     */
    fun isValidEntryName(name: String): Boolean {
        val forbidden = setOf("", ".", "..")
        if (name in forbidden || '/' in name || '\u0000' in name) return false
        if (name.any { it.code < 32 || it.code == 127 || it == '\uFFFD' }) return false
        return name.toByteArray(StandardCharsets.UTF_8).size <= MAX_NAME_BYTES
    }
}

internal object PosixXattrAndAncestorSupport {
    private const val USER_ZERO_PREFIX = "/data/user/0"
    private const val DATA_DATA_PREFIX = "/data/data"
    private const val SYSTEM_UID = 1000

    fun invokeOsListXattr(path: String): List<String> {
        val method = runCatching { Os::class.java.getMethod("listxattr", String::class.java) }.getOrNull()
            ?: return emptyList()
        val raw = try {
            method.invoke(null, path) as? Array<*>
        } catch (inv: java.lang.reflect.InvocationTargetException) {
            throw (inv.targetException ?: inv)
        }
        return raw?.mapNotNull { it as? String }.orEmpty()
    }

    fun openDirectoryAbsolute(
        ops: AndroidOsPosixFdOperations,
        kernel: PosixKernelBridge,
        absolutePath: String,
    ): PosixRetainedFd? =
        openDirectoryWithAncestors(ops, kernel, absolutePath, collectAncestors = false)?.first

    fun inspectOwnAppDirectoryIdentity(
        ops: AndroidOsPosixFdOperations,
        kernel: PosixKernelBridge,
        path: java.nio.file.Path,
    ): RootFdNodeIdentity? {
        ops.inspectDirectoryIdentityNoFollow(path)?.let { return it }
        val posixPath = AndroidRootPosixFileDriver.toPosixAbsolutePath(path)
        val effectivePath = resolveAndroidUserZeroAlias(ops, kernel, posixPath)
        val st = listOf(effectivePath, posixPath)
            .firstNotNullOfOrNull { p -> runCatching { kernel.lstat(p) }.getOrNull() }
            ?: return null
        val validDir = !AndroidOsPosixFdOperations.isSymlinkMode(st.mode) &&
            AndroidOsPosixFdOperations.isDirMode(st.mode) &&
            st.deviceId > 0L && st.inode >= 0L
        return RootFdNodeIdentity(
            deviceId = st.deviceId,
            inode = st.inode,
            mountId = 1L,
            isDirectory = true,
            isRegularFile = false,
            isSymlink = false,
            hardlinkCount = maxOf(st.hardlinkCount, 1L),
            modeOctal = st.mode and 0b111_111_111_111,
            ownerUid = st.uid,
            ownerGid = st.gid,
            selinuxLabel = "u:object_r:app_data_file:s0",
        ).takeIf { validDir }
    }

    fun resolveAndroidUserZeroAlias(
        ops: AndroidOsPosixFdOperations,
        kernel: PosixKernelBridge,
        absolutePath: String,
    ): String {
        if (absolutePath != USER_ZERO_PREFIX && !absolutePath.startsWith("$USER_ZERO_PREFIX/")) {
            return absolutePath
        }
        val userDir = walkWithAncestors(ops, "/data/user", collectAncestors = false)?.first ?: return absolutePath
        val isTrustedAlias = userDir.use { dirFd ->
            val zeroPath = "/proc/self/fd/${dirFd.fdNumber}/0"
            val st = runCatching { kernel.lstat(zeroPath) }.getOrNull() ?: return@use false
            val target = runCatching { kernel.readlink(zeroPath) }.getOrNull() ?: return@use false
            AndroidOsPosixFdOperations.isSymlinkMode(st.mode) &&
                st.uid in setOf(0, SYSTEM_UID) &&
                target in setOf(DATA_DATA_PREFIX, "../data")
        }
        return if (isTrustedAlias) {
            DATA_DATA_PREFIX + absolutePath.removePrefix(USER_ZERO_PREFIX)
        } else {
            absolutePath
        }
    }

    fun openDirectoryWithAncestors(
        ops: AndroidOsPosixFdOperations,
        kernel: PosixKernelBridge,
        absolutePath: String,
        collectAncestors: Boolean = true,
    ): Pair<PosixRetainedFd, List<RootFdNodeIdentity>>? {
        if (!RootRecoveryLocatorCodec.isValidAbsoluteRootPath(absolutePath)) return null
        walkWithAncestors(ops, absolutePath, collectAncestors)?.let { return it }
        val effectivePath = resolveAndroidUserZeroAlias(ops, kernel, absolutePath)
        return if (effectivePath != absolutePath) walkWithAncestors(ops, effectivePath, collectAncestors) else null
    }

    private fun walkWithAncestors(
        ops: AndroidOsPosixFdOperations,
        absolutePath: String,
        collectAncestors: Boolean,
    ): Pair<PosixRetainedFd, List<RootFdNodeIdentity>>? {
        val segments = absolutePath.split('/').filter { it.isNotEmpty() }
        var current = ops.openRootSlashDirectory() ?: return null
        if (segments.isEmpty()) return current to emptyList()
        val ancestors = if (collectAncestors) mutableListOf<RootFdNodeIdentity>() else null
        for (segment in segments) {
            if (ancestors != null) {
                val identity = runCatching { ops.fstatIdentity(current) }.getOrNull()
                if (identity == null) {
                    current.close()
                    return null
                }
                ancestors += identity
            }
            val next = try {
                ops.openChildDirectoryAtNoFollow(current, segment)
            } finally {
                current.close()
            } ?: return null
            current = next
        }
        return current to (ancestors ?: emptyList())
    }

    fun statAtNoFollow(
        ops: AndroidOsPosixFdOperations,
        kernel: PosixKernelBridge,
        parentFd: PosixRetainedFd,
        childName: String,
    ): RootFdNodeIdentity? {
        val path = ops.procFdChildPath(parentFd, childName) ?: return null
        val lstat = runCatching { kernel.lstat(path) }.getOrNull()
        if (lstat != null && AndroidOsPosixFdOperations.isSymlinkMode(lstat.mode)) {
            return symlinkNodeIdentity(lstat)
        }
        val opened = try {
            ops.openTargetAt(parentFd, childName, followSymlink = false, writable = false)
        } catch (err: PosixOpException) {
            return if (err.errnoName == "ELOOP") symlinkNodeIdentity(lstat) else null
        } catch (_: Throwable) {
            return null
        }
        return opened.use { fd ->
            val currentLstat = runCatching { kernel.lstat(path) }.getOrNull() ?: return@use null
            if (AndroidOsPosixFdOperations.isSymlinkMode(currentLstat.mode)) {
                symlinkNodeIdentity(currentLstat)
            } else {
                ops.fstatIdentity(fd)
            }
        }
    }

    private fun symlinkNodeIdentity(lstat: PosixRawStat?): RootFdNodeIdentity = RootFdNodeIdentity(
        deviceId = maxOf(lstat?.deviceId ?: 1L, 1L),
        inode = maxOf(lstat?.inode ?: 1L, 1L),
        mountId = 1L,
        isDirectory = false,
        isRegularFile = false,
        isSymlink = true,
        hardlinkCount = maxOf(lstat?.hardlinkCount ?: 1L, 1L),
        modeOctal = (lstat?.mode ?: 0b111_111_111) and 0b111_111_111_111,
        ownerUid = lstat?.uid ?: 0,
        ownerGid = lstat?.gid ?: 0,
        selinuxLabel = "symlink",
    )

    fun copyPreservableXattrs(
        fdOps: PosixFdOperations,
        sourceFd: PosixRetainedFd?,
        targetFd: PosixRetainedFd,
    ) {
        if (sourceFd == null) return
        val names = fdOps.flistxattr(sourceFd)
        for (name in names) {
            if (name == AndroidOsPosixFdOperations.XATTR_SELINUX || name.isBlank()) continue
            if (!name.startsWith("user.") && !name.startsWith("security.")) {
                throw PosixOpException("EOPNOTSUPP", unsupportedAttribute = name)
            }
            val value = try {
                fdOps.fgetxattr(sourceFd, name)
            } catch (err: PosixOpException) {
                throw PosixOpException(err.errnoName, err.selinuxConfirmed, unsupportedAttribute = name)
            } ?: throw PosixOpException("EOPNOTSUPP", unsupportedAttribute = name)
            try {
                fdOps.fsetxattr(targetFd, name, value)
            } catch (err: PosixOpException) {
                throw PosixOpException(err.errnoName, err.selinuxConfirmed, unsupportedAttribute = name)
            }
        }
    }
}
