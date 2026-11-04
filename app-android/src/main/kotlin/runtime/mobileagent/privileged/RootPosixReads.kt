// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import runtime.mobileagent.domain.RootDirectoryScan
import runtime.mobileagent.domain.RootDirectoryScanEntry
import runtime.mobileagent.domain.RootFdRangeRead
import runtime.mobileagent.domain.RootFileEntryKind
import runtime.mobileagent.domain.RootOpenedFileTarget

/** Bounded FD reads and one-directory listings. */
internal class RootPosixReads(
    private val fdOps: PosixFdOperations,
    private val lifecycle: RootPosixLifecycle,
    private val fileIo: RootPosixFileIo,
    private val inspection: RootPosixInspection,
) {
    fun read(target: RootOpenedFileTarget, offsetBytes: Long, maxBytes: Int): RootFdRangeRead? {
        val entry = inspection.consumeTarget(target.retainedFdHandle) ?: return null
        return entry.use {
            if (!inspection.verifyEntry(entry, target, requireContentDigestRecheck = false)) return null
            val sizeBytes = fdOps.fstatSizeBytes(entry.targetFd) ?: return null
            if (offsetBytes < 0L || offsetBytes > sizeBytes || maxBytes <= 0) return null
            val window = minOf(maxBytes.toLong(), sizeBytes - offsetBytes).toInt()
            val bytes = runCatching { fdOps.readFdRange(entry.targetFd, offsetBytes, window) }.getOrNull()
                ?: return null
            val identity = fdOps.fstatIdentity(entry.targetFd) ?: return null
            val contentDigest = target.contentSha256?.let { fdOps.fstatContentDigest(entry.targetFd) }
            if (!inspection.verifyEntry(entry, target, requireContentDigestRecheck = false) ||
                fdOps.fstatSizeBytes(entry.targetFd) != sizeBytes) return null
            val version = fileIo.versionOf(entry.targetFd, identity, sizeBytes, contentDigest) ?: return null
            RootFdRangeRead(
                bytes = bytes,
                totalSizeBytes = sizeBytes,
                version = version,
                contentSha256 = contentDigest,
            )
        }
    }

    fun list(relativePath: String, maxEntries: Int, cursor: String?): RootDirectoryScan? {
        val walk = inspection.openDirectoryWalk(relativePath) ?: return null
        return walk.use {
            val identity = fdOps.fstatIdentity(walk.fd) ?: return null
            val resolvedPath = fdOps.readFdResolvedPath(walk.fd) ?: return null
            if (!identity.isDirectory || identity.isSymlink) return null
            val allNames = runCatching { fdOps.listChildNames(walk.fd) }.getOrNull() ?: return null
            val names = allNames
                .filter(PosixDirectoryNames::isValidEntryName)
                .sorted()
                .filter { cursor == null || it > cursor }
            var skipped = 0
            var truncated = false
            val entries = mutableListOf<RootDirectoryScanEntry>()
            for (name in names) {
                if (!lifecycle.isActive()) return null
                if (entries.size >= maxEntries) {
                    truncated = true
                    break
                }
                val stat = runCatching {
                    val childFd = lifecycle.openTracked {
                        fdOps.openTargetAt(walk.fd, name, followSymlink = false, writable = false)
                    } ?: return@runCatching null
                    childFd.use { child ->
                        val childIdentity = fdOps.fstatIdentity(child) ?: return@use null
                        val childSize = fdOps.fstatSizeBytes(child) ?: 0L
                        childIdentity to childSize
                    }
                }.getOrNull()
                if (stat == null || stat.first.isSymlink ||
                    !(stat.first.isDirectory || stat.first.isRegularFile)
                ) {
                    skipped += 1
                    continue
                }
                entries += RootDirectoryScanEntry(
                    name = name,
                    entryKind = if (stat.first.isDirectory) {
                        RootFileEntryKind.DIRECTORY
                    } else {
                        RootFileEntryKind.REGULAR_FILE
                    },
                    sizeBytes = if (stat.first.isRegularFile) stat.second else 0L,
                )
            }
            if (!lifecycle.isActive()) return null
            RootDirectoryScan(
                relativePath = relativePath,
                resolvedAbsolutePath = resolvedPath,
                directoryIdentity = identity,
                directoryChain = walk.chain,
                entries = entries.sortedBy { it.name },
                skippedSpecialEntries = skipped,
                truncatedByScan = truncated,
                ancestorsAboveRoot = walk.ancestorsAboveRoot,
            )
        }
    }
}
