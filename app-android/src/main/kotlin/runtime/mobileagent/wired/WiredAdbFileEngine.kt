// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.wired

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.text.Normalizer
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import runtime.mobileagent.skills.tooling.WorkspaceListing
import runtime.mobileagent.skills.tooling.WorkspaceListingWarning
import runtime.mobileagent.skills.tooling.WorkspaceListingWarningCode

/**
 * Port used by [runtime.mobileagent.bridge.AdbHelperMain].  A production
 * backend can adapt the existing workspace engine here; the helper itself
 * never receives a free-form command or a secret.
 */
fun interface PrivilegedFileEngine {
    fun execute(request: WiredAdbFileRequest): WiredAdbFileEngineResult
}

/**
 * Optional capability used by the shell-UID helper to bind one authenticated
 * request stream to a user-selected device directory. Implementations must
 * return a fresh engine whose root is validated before any operation; a
 * caller must never use this seam to bypass the authority/grant checks.
 */
interface RootScopedPrivilegedFileEngine {
    fun forRoot(rootPath: String, fullDevice: Boolean): PrivilegedFileEngine

    /** The binding is authenticated metadata used only to seal pagination cursors. */
    fun forRoot(rootPath: String, fullDevice: Boolean, workspaceBinding: String?): PrivilegedFileEngine =
        forRoot(rootPath, fullDevice)
}

sealed interface WiredAdbFileEngineResult {
    data class Success(val result: WiredAdbFileResult) : WiredAdbFileEngineResult
    data class Failure(val code: String) : WiredAdbFileEngineResult
}

/** Relative path validator shared by the Android bridge and the helper. */
object WiredAdbPathPolicy {
    fun parse(raw: String?, allowRoot: Boolean): List<String> {
        val value = raw ?: if (allowRoot) "" else throw InvalidWiredAdbPath()
        if (value.isEmpty()) {
            if (allowRoot) return emptyList()
            throw InvalidWiredAdbPath()
        }
        if (value.indexOf('\u0000') >= 0 || value.indexOf('\\') >= 0 || value.contains(':')) {
            throw InvalidWiredAdbPath()
        }
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
        if (normalized != value || normalized.startsWith('/') || normalized.endsWith('/') || normalized.contains("//")) {
            throw InvalidWiredAdbPath()
        }
        val pathBytes = strictUtf8(normalized) ?: throw InvalidWiredAdbPath()
        if (pathBytes.size > WIRED_MAX_PATH_BYTES) throw WiredAdbPathLimit()
        val pieces = normalized.split('/')
        if (pieces.size > WIRED_MAX_PATH_DEPTH) throw WiredAdbPathLimit()
        pieces.forEach { piece ->
            val bytes = strictUtf8(piece) ?: throw InvalidWiredAdbPath()
            if (piece.isBlank() || piece == "." || piece == ".." || bytes.size > WIRED_MAX_SEGMENT_BYTES ||
                piece.any(Char::isISOControl)
            ) {
                throw InvalidWiredAdbPath()
            }
        }
        return pieces
    }

    fun isValid(raw: String?, allowRoot: Boolean): Boolean = runCatching { parse(raw, allowRoot) }.isSuccess

    private fun strictUtf8(value: String): ByteArray? = try {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        encoder.encode(CharBuffer.wrap(value)).let { encoded ->
            ByteArray(encoded.remaining()).also { encoded.get(it) }
        }
    } catch (_: CharacterCodingException) {
        null
    }
}

/** Absolute device-path validation; canonical/symlink checks happen in NIO. */
object WiredAdbAbsolutePathPolicy {
    fun parse(raw: String): String {
        require(raw.isNotEmpty() && raw.startsWith('/'))
        require(!raw.contains('\u0000') && !raw.contains('\\') && !raw.contains(':'))
        require(Normalizer.normalize(raw, Normalizer.Form.NFC) == raw)
        require(raw == "/" || !raw.endsWith('/') && !raw.contains("//"))
        val pieces = raw.split('/').drop(1)
        require(raw == "/" || pieces.isNotEmpty())
        require(pieces.size <= WIRED_MAX_ABSOLUTE_PATH_DEPTH)
        pieces.forEach { piece ->
            val bytes = strictUtf8(piece) ?: throw InvalidWiredAdbPath()
            require(piece.isNotEmpty() && piece != "." && piece != "..")
            require(bytes.size <= WIRED_MAX_SEGMENT_BYTES && piece.none(Char::isISOControl))
        }
        require(strictUtf8(raw)?.size ?: 0 <= WIRED_MAX_ABSOLUTE_PATH_BYTES)
        return raw
    }

    fun isValid(raw: String): Boolean = runCatching { parse(raw) }.isSuccess

    private fun strictUtf8(value: String): ByteArray? = try {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        encoder.encode(CharBuffer.wrap(value)).let { encoded ->
            ByteArray(encoded.remaining()).also { encoded.get(it) }
        }
    } catch (_: CharacterCodingException) {
        null
    }
}

class InvalidWiredAdbPath : Exception()
class WiredAdbPathLimit : Exception()

private const val WIRED_MAX_ABSOLUTE_PATH_BYTES = 4 * 1024
private const val WIRED_MAX_ABSOLUTE_PATH_DEPTH = 64

/**
 * Conservative file implementation for the shell-UID helper.  It is fixed
 * to one workspace root and performs lstat/canonical checks before each
 * operation.  All failure messages are stable codes without path details.
 */
class NioPrivilegedFileEngine(
    root: Path = File("/sdcard/Download/MobileAgentRuntime-Wired").toPath(),
    private val fullDevice: Boolean = false,
    private val workspaceBinding: String? = null,
) : PrivilegedFileEngine, RootScopedPrivilegedFileEngine {
    private val lock = Any()
    private val rootPath = root.toAbsolutePath().normalize()

    init {
        require(rootPath.isAbsolute)
    }

    override fun forRoot(rootPath: String, fullDevice: Boolean): PrivilegedFileEngine {
        WiredAdbAbsolutePathPolicy.parse(rootPath)
        return NioPrivilegedFileEngine(Paths.get(rootPath), fullDevice, workspaceBinding = null)
    }

    override fun forRoot(rootPath: String, fullDevice: Boolean, workspaceBinding: String?): PrivilegedFileEngine {
        WiredAdbAbsolutePathPolicy.parse(rootPath)
        return NioPrivilegedFileEngine(Paths.get(rootPath), fullDevice, workspaceBinding)
    }

    override fun execute(request: WiredAdbFileRequest): WiredAdbFileEngineResult = synchronized(lock) {
        try {
            when (request.operation) {
                WiredAdbFileOperation.LIST -> success(list(request))
                WiredAdbFileOperation.STAT -> success(stat(request))
                WiredAdbFileOperation.READ_TEXT -> success(read(request))
                WiredAdbFileOperation.WRITE_TEXT -> success(write(request))
                WiredAdbFileOperation.APPLY_PATCH -> success(applyPatch(request))
                WiredAdbFileOperation.CREATE_DIRECTORY -> success(mkdir(request))
                WiredAdbFileOperation.MOVE -> success(move(request))
                WiredAdbFileOperation.DELETE -> success(delete(request))
            }
        } catch (_: InvalidWiredAdbPath) {
            WiredAdbFileEngineResult.Failure(ERR_INVALID_PATH)
        } catch (_: WiredAdbPathLimit) {
            WiredAdbFileEngineResult.Failure(ERR_LIMIT)
        } catch (error: EngineFailure) {
            WiredAdbFileEngineResult.Failure(error.code)
        } catch (_: SecurityException) {
            WiredAdbFileEngineResult.Failure(ERR_PERMISSION_DENIED)
        } catch (_: IOException) {
            WiredAdbFileEngineResult.Failure(ERR_OPERATION_UNAVAILABLE)
        } catch (_: RuntimeException) {
            WiredAdbFileEngineResult.Failure(ERR_OPERATION_UNAVAILABLE)
        }
    }

    private fun list(request: WiredAdbFileRequest): WiredAdbFileResult {
        val segments = WiredAdbPathPolicy.parse(request.relativePath, allowRoot = true)
        val directory = resolve(segments)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw EngineFailure(ERR_NOT_FOUND)
        }
        requireDirectory(directory)
        val warnings = ListingWarnings()
        val sortedChildren = safeEntries(directory, warnings).sortedBy { it.fileName.toString() }
        if (sortedChildren.size > WIRED_MAX_ENTRIES) throw EngineFailure(ERR_LIMIT)
        val directoryVersion = versionOf(directory, isDirectory = true, bestEffort = true)
        val start = decodeCursor(request.cursor, pathOf(segments), directoryVersion, request.workspaceBinding)
        if (start > sortedChildren.size) throw EngineFailure(ERR_INVALID_CURSOR)
        val pageSize = minOf(request.maxEntries, WIRED_MAX_DIRECTORY_ENTRIES)
        val end = minOf(start + pageSize, sortedChildren.size)
        val output = sortedChildren.subList(start, end).mapNotNull { child ->
            // Symlinks are not traversable entries. Omit them from directory
            // browsing rather than turning a safe directory listing into a
            // path disclosure or an all-or-nothing failure.
            try {
                rejectSymlink(child)
                val childSegments = segments + child.fileName.toString()
                when {
                    Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) ->
                        WiredAdbFileEntry(
                            pathOf(childSegments),
                            WiredAdbEntryType.DIRECTORY,
                            version = versionOf(child, isDirectory = true, bestEffort = true),
                        )
                    Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) ->
                        WiredAdbFileEntry(
                            pathOf(childSegments),
                            WiredAdbEntryType.FILE,
                            Files.size(child),
                            versionOf(child, isDirectory = false),
                        )
                    else -> {
                        warnings.add(WorkspaceListingWarningCode.UNSUPPORTED_ENTRY_SKIPPED)
                        null
                    }
                }
            } catch (failure: EngineFailure) {
                warnings.add(
                    if (failure.code == ERR_SYMLINK_FORBIDDEN) WorkspaceListingWarningCode.SYMLINK_SKIPPED
                    else WorkspaceListingWarningCode.TRANSIENT_ENTRY_SKIPPED,
                )
                null
            } catch (_: SecurityException) {
                warnings.add(WorkspaceListingWarningCode.UNREADABLE_ENTRY_SKIPPED)
                null
            } catch (_: IOException) {
                warnings.add(WorkspaceListingWarningCode.TRANSIENT_ENTRY_SKIPPED)
                null
            } catch (_: RuntimeException) {
                warnings.add(WorkspaceListingWarningCode.TRANSIENT_ENTRY_SKIPPED)
                null
            }
        }
        return WiredAdbFileResult(
            WiredAdbFileOperation.LIST,
            pathOf(segments),
            entries = output,
            truncated = end < sortedChildren.size,
            nextCursor = if (end < sortedChildren.size) {
                encodeCursor(pathOf(segments), directoryVersion, end, request.workspaceBinding)
            } else null,
            skippedEntries = warnings.skippedEntries,
            listingWarnings = warnings.snapshot(),
            version = directoryVersion,
        )
    }

    private fun stat(request: WiredAdbFileRequest): WiredAdbFileResult {
        val segments = WiredAdbPathPolicy.parse(request.relativePath, allowRoot = false)
        val target = resolve(segments)
        rejectSymlink(target)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw EngineFailure(ERR_NOT_FOUND)
        val type = when {
            Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) -> WiredAdbEntryType.FILE
            Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) -> WiredAdbEntryType.DIRECTORY
            else -> throw EngineFailure(ERR_UNSUPPORTED_ENTRY)
        }
        val size = Files.size(target)
        return WiredAdbFileResult(
            operation = WiredAdbFileOperation.STAT,
            relativePath = pathOf(segments),
            entries = listOf(WiredAdbFileEntry(
                pathOf(segments),
                type,
                size.takeIf { type == WiredAdbEntryType.FILE },
                versionOf(target, type == WiredAdbEntryType.DIRECTORY),
            )),
            bytes = size.takeIf { type == WiredAdbEntryType.FILE },
            version = versionOf(target, type == WiredAdbEntryType.DIRECTORY),
        )
    }

    private fun read(request: WiredAdbFileRequest): WiredAdbFileResult {
        val segments = WiredAdbPathPolicy.parse(request.relativePath, allowRoot = false)
        val file = resolve(segments)
        rejectSymlink(file)
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw EngineFailure(ERR_NOT_FOUND)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw EngineFailure(ERR_UNSUPPORTED_ENTRY)
        val size = Files.size(file)
        if (size > WIRED_MAX_FILE_BYTES) throw EngineFailure(ERR_FILE_TOO_LARGE)
        if (request.offsetBytes > size) throw EngineFailure(ERR_OFFSET_OUT_OF_RANGE)
        val bytes = readBounded(file, request.offsetBytes, request.maxBytes)
        val decoded = decodeUtf8Chunk(bytes)
        if (decoded.consumedBytes == 0 && bytes.isNotEmpty()) {
            // A caller cannot make progress when its requested budget is
            // smaller than the first code point.  Do not return an empty,
            // non-EOF chunk with the same offset; that would make a client
            // loop forever.  The caller can retry with a larger maxBytes.
            throw EngineFailure(ERR_INVALID_CONTENT)
        }
        val version = versionOf(file, isDirectory = false)
        return WiredAdbFileResult(
            operation = WiredAdbFileOperation.READ_TEXT,
            relativePath = pathOf(segments),
            text = decoded.text,
            // Advance by the valid UTF-8 prefix, not by the raw buffer.  The
            // raw buffer may end halfway through a multi-byte code point.
            bytes = decoded.consumedBytes.toLong(),
            version = version,
            offsetBytes = request.offsetBytes,
            totalBytes = size,
            eof = decoded.consumedBytes.toLong() >= size - request.offsetBytes,
        )
    }

    private fun applyPatch(request: WiredAdbFileRequest): WiredAdbFileResult {
        val segments = WiredAdbPathPolicy.parse(request.relativePath, allowRoot = false)
        val patchBytes = request.patchUtf8 ?: throw EngineFailure(ERR_INVALID_PATCH)
        val expectedVersion = request.expectedVersion ?: throw EngineFailure(ERR_CONFLICT)
        if (patchBytes.size > WIRED_MAX_PATCH_BYTES) throw EngineFailure(ERR_LIMIT)
        val patch = decodeUtf8(patchBytes)
        ensureRoot()
        val target = resolve(segments)
        rejectSymlink(target)
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw EngineFailure(ERR_NOT_FOUND)
        val initialVersion = versionOf(target, isDirectory = false)
        if (initialVersion != expectedVersion) throw EngineFailure(ERR_CONFLICT)
        val currentBytes = readAll(target)
        val currentText = decodeUtf8(currentBytes)
        val patchedText = applyPatchText(currentText, patch, request.patchFormat)
            ?: throw EngineFailure(ERR_INVALID_PATCH)
        val patchedBytes = strictUtf8(patchedText)
        if (patchedBytes.size.toLong() > WIRED_MAX_TOTAL_BYTES) throw EngineFailure(ERR_LIMIT)
        if (patchedBytes.contentEquals(currentBytes)) {
            return WiredAdbFileResult(
                operation = WiredAdbFileOperation.APPLY_PATCH,
                relativePath = pathOf(segments),
                bytes = patchedBytes.size.toLong(),
                version = initialVersion,
            )
        }
        val usage = inspectUsage(enforceIndividualFileLimit = false)
        val retainedBytes = usage.bytes - currentBytes.size.toLong()
        if (patchedBytes.size.toLong() > WIRED_MAX_TOTAL_BYTES - retainedBytes) throw EngineFailure(ERR_LIMIT)
        val parent = target.parent ?: throw EngineFailure(ERR_OUTSIDE_ROOT)
        requireDirectory(parent)
        rejectSymlink(parent)
        val temporary = parent.resolve(".mar-wired-${UUID.randomUUID()}.tmp")
        return try {
            createAndSync(temporary, patchedBytes)
            rejectSymlink(parent)
            rejectSymlink(target)
            val latestVersion = versionOf(target, isDirectory = false)
            if (latestVersion != initialVersion) throw EngineFailure(ERR_CONFLICT)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                throw EngineFailure(ERR_ATOMIC_REPLACE_UNAVAILABLE)
            } catch (_: UnsupportedOperationException) {
                throw EngineFailure(ERR_ATOMIC_REPLACE_UNAVAILABLE)
            }
            rejectSymlink(target)
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) != patchedBytes.size.toLong()) {
                throw EngineFailure(ERR_WRITE_UNVERIFIED)
            }
            WiredAdbFileResult(
                operation = WiredAdbFileOperation.APPLY_PATCH,
                relativePath = pathOf(segments),
                bytes = patchedBytes.size.toLong(),
                version = versionOf(target, isDirectory = false),
            )
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    private fun applyPatchText(current: String, patch: String, format: WiredAdbPatchFormat): String? = when (format) {
        WiredAdbPatchFormat.REPLACE -> patch.takeIf { !it.contains('\u0000') }
        WiredAdbPatchFormat.UNIFIED_DIFF -> applyUnifiedDiff(current, patch)
    }

    /** Small, deterministic unified-diff parser; it accepts no paths or commands. */
    private fun applyUnifiedDiff(current: String, patch: String): String? {
        val source = current.split("\n", ignoreCase = false, limit = Int.MAX_VALUE)
        val diff = patch.split("\n", ignoreCase = false, limit = Int.MAX_VALUE)
        if (diff.isEmpty()) return null
        var index = 0
        if (diff.getOrNull(0)?.startsWith("--- ") == true) {
            if (diff.getOrNull(1)?.startsWith("+++ ") != true) return null
            index = 2
        }
        val output = ArrayList<String>(source.size)
        var sourceIndex = 0
        var hunkCount = 0
        val hunkPattern = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$")
        while (index < diff.size) {
            if (diff[index].isEmpty() && index == diff.lastIndex) break
            val header = hunkPattern.matchEntire(diff[index]) ?: return null
            index++
            val oldStart = header.groupValues[1].toIntOrNull() ?: return null
            val oldCount = header.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
            val newCount = header.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
            if (oldStart < 0 || oldCount < 0 || newCount < 0) return null
            val targetIndex = if (oldStart == 0) 0 else oldStart - 1
            if (targetIndex < sourceIndex || targetIndex > source.size) return null
            while (sourceIndex < targetIndex) output += source[sourceIndex++]
            var consumedOld = 0
            var consumedNew = 0
            while (index < diff.size && !diff[index].startsWith("@@ ")) {
                val line = diff[index++]
                if (line.isEmpty() && index == diff.size) break
                if (line == "\\ No newline at end of file") continue
                if (line.isEmpty()) return null
                when (line[0]) {
                    ' ' -> {
                        if (consumedOld >= oldCount || sourceIndex >= source.size || source[sourceIndex] != line.substring(1)) return null
                        output += source[sourceIndex++]
                        consumedOld++
                        consumedNew++
                    }
                    '-' -> {
                        if (consumedOld >= oldCount || sourceIndex >= source.size || source[sourceIndex] != line.substring(1)) return null
                        sourceIndex++
                        consumedOld++
                    }
                    '+' -> {
                        if (consumedNew >= newCount) return null
                        val added = line.substring(1)
                        if (added.contains('\u0000')) return null
                        output += added
                        consumedNew++
                    }
                    else -> return null
                }
            }
            if (consumedOld != oldCount || consumedNew != newCount) return null
            hunkCount++
        }
        if (hunkCount == 0) return null
        while (sourceIndex < source.size) output += source[sourceIndex++]
        return output.joinToString("\n")
    }

    private fun write(request: WiredAdbFileRequest): WiredAdbFileResult {
        val segments = WiredAdbPathPolicy.parse(request.relativePath, allowRoot = false)
        val content = request.contentUtf8 ?: throw EngineFailure(ERR_INVALID_CONTENT)
        if (content.size > WIRED_MAX_FILE_BYTES) throw EngineFailure(ERR_LIMIT)
        decodeUtf8(content)
        ensureRoot()
        val target = resolve(segments)
        val parent = target.parent ?: throw EngineFailure(ERR_OUTSIDE_ROOT)
        requireDirectory(parent)
        rejectSymlink(parent)
        val existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
        if (existed) {
            rejectSymlink(target)
            if (!request.replaceExisting || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw EngineFailure(ERR_TARGET_EXISTS)
            }
            if (request.expectedVersion != null && versionOf(target, isDirectory = false) != request.expectedVersion) {
                throw EngineFailure(ERR_CONFLICT)
            }
        } else if (request.expectedVersion != null) {
            throw EngineFailure(ERR_CONFLICT)
        }
        val usage = inspectUsage()
        val oldBytes = if (existed) Files.size(target) else 0L
        if (usage.files + (if (existed) 0 else 1) > WIRED_MAX_FILES ||
            usage.bytes - oldBytes + content.size > WIRED_MAX_TOTAL_BYTES
        ) throw EngineFailure(ERR_LIMIT)
        if (!request.replaceExisting) {
            // Create-only file content goes through the kernel-atomic exclusive
            // create: no temporary file, no rename, and no REPLACE flag anywhere
            // on this path, so a concurrently created target can never be
            // overwritten on any platform.
            try {
                runtime.mobileagent.workspace.WorkspaceAtomicCommit.writeExclusive(target, content)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                throw EngineFailure(ERR_TARGET_EXISTS)
            }
            rejectSymlink(parent)
            rejectSymlink(target)
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) != content.size.toLong()) {
                throw EngineFailure(ERR_WRITE_UNVERIFIED)
            }
            return WiredAdbFileResult(
                WiredAdbFileOperation.WRITE_TEXT,
                pathOf(segments),
                bytes = content.size.toLong(),
                created = true,
                replaced = false,
                version = versionOf(target, isDirectory = false),
            )
        }
        val temporary = parent.resolve(".mar-wired-${UUID.randomUUID()}.tmp")
        try {
            createAndSync(temporary, content)
            rejectSymlink(parent)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) rejectSymlink(target)
            try {
                // Replace path only: create-only content uses the exclusive
                // create above and never reaches this rename.
                runtime.mobileagent.workspace.WorkspaceAtomicCommit.publish(temporary, target, replaceExisting = true)
            } catch (_: AtomicMoveNotSupportedException) {
                throw EngineFailure(ERR_ATOMIC_REPLACE_UNAVAILABLE)
            } catch (_: UnsupportedOperationException) {
                throw EngineFailure(ERR_ATOMIC_REPLACE_UNAVAILABLE)
            }
            rejectSymlink(target)
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) != content.size.toLong()) {
                throw EngineFailure(ERR_WRITE_UNVERIFIED)
            }
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
        return WiredAdbFileResult(
            WiredAdbFileOperation.WRITE_TEXT,
            pathOf(segments),
            bytes = content.size.toLong(),
            created = !existed,
            replaced = existed,
            version = versionOf(target, isDirectory = false),
        )
    }

    private fun mkdir(request: WiredAdbFileRequest): WiredAdbFileResult {
        val segments = WiredAdbPathPolicy.parse(request.relativePath, allowRoot = false)
        ensureRoot()
        val directory = resolve(segments)
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            rejectSymlink(directory)
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw EngineFailure(ERR_TARGET_EXISTS)
            return WiredAdbFileResult(WiredAdbFileOperation.CREATE_DIRECTORY, pathOf(segments), created = false)
        }
        val parent = directory.parent ?: throw EngineFailure(ERR_OUTSIDE_ROOT)
        requireDirectory(parent)
        rejectSymlink(parent)
        if (inspectUsage().entries + 1 > WIRED_MAX_ENTRIES) throw EngineFailure(ERR_LIMIT)
        Files.createDirectory(directory)
        rejectSymlink(directory)
        return WiredAdbFileResult(WiredAdbFileOperation.CREATE_DIRECTORY, pathOf(segments), created = true)
    }

    private fun move(request: WiredAdbFileRequest): WiredAdbFileResult {
        val sourceSegments = WiredAdbPathPolicy.parse(request.relativePath, allowRoot = false)
        val destinationSegments = WiredAdbPathPolicy.parse(request.destinationRelativePath, allowRoot = false)
        ensureRoot()
        val source = resolve(sourceSegments)
        val destination = resolve(destinationSegments)
        rejectSymlink(source)
        val parent = destination.parent ?: throw EngineFailure(ERR_OUTSIDE_ROOT)
        requireDirectory(parent)
        rejectSymlink(parent)
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw EngineFailure(ERR_NOT_FOUND)
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            rejectSymlink(destination)
            if (!request.replaceExisting || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw EngineFailure(ERR_TARGET_EXISTS)
            }
        }
        if (!request.replaceExisting) {
            // Scheme A (3f75 finding B): no-replace move is refused for every
            // node kind.  A non-atomic copy+delete cannot prove "the deleted
            // source is the copied source".  Copy + delete stay available as
            // two explicit steps.
            throw EngineFailure(ERR_ATOMIC_REPLACE_UNAVAILABLE)
        }
        try {
            Files.move(
                source,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            // Defensive: the replace path passes REPLACE_EXISTING.
            throw EngineFailure(ERR_TARGET_EXISTS)
        } catch (_: AtomicMoveNotSupportedException) {
            throw EngineFailure(ERR_ATOMIC_REPLACE_UNAVAILABLE)
        } catch (_: UnsupportedOperationException) {
            throw EngineFailure(ERR_ATOMIC_REPLACE_UNAVAILABLE)
        } catch (_: IOException) {
            // The atomic operation already ran; a failure afterwards is
            // ambiguous and must not be reported as success.
            throw EngineFailure(ERR_UNKNOWN_OUTCOME)
        }
        rejectSymlink(destination)
        return WiredAdbFileResult(WiredAdbFileOperation.MOVE, pathOf(sourceSegments), bytes = if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) Files.size(destination) else null, replaced = request.replaceExisting)
    }

    private fun delete(request: WiredAdbFileRequest): WiredAdbFileResult {
        val segments = WiredAdbPathPolicy.parse(request.relativePath, allowRoot = false)
        ensureRoot()
        val target = resolve(segments)
        rejectSymlink(target)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw EngineFailure(ERR_NOT_FOUND)
        val type = when {
            Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) -> WiredAdbEntryType.FILE
            Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) -> {
                if (entries(target).isNotEmpty()) throw EngineFailure(ERR_NON_EMPTY_DIRECTORY)
                WiredAdbEntryType.DIRECTORY
            }
            else -> throw EngineFailure(ERR_UNSUPPORTED_ENTRY)
        }
        rejectSymlink(target)
        if (type == WiredAdbEntryType.DIRECTORY && entries(target).isNotEmpty()) throw EngineFailure(ERR_NON_EMPTY_DIRECTORY)
        if (!Files.deleteIfExists(target) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw EngineFailure(ERR_OPERATION_UNAVAILABLE)
        return WiredAdbFileResult(WiredAdbFileOperation.DELETE, pathOf(segments), deleted = true)
    }

    private fun resolve(segments: List<String>): Path {
        val candidate = segments.fold(rootPath) { parent, segment -> parent.resolve(segment) }.normalize()
        if (candidate != rootPath && !candidate.startsWith(rootPath)) throw EngineFailure(ERR_OUTSIDE_ROOT)
        var current = rootPath
        rejectSymlink(rootPath)
        segments.forEach { segment ->
            current = current.resolve(segment)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) rejectSymlink(current)
        }
        val rootCanonical = rootPath.toFile().canonicalFile.toPath()
        val candidateCanonical = candidate.toFile().canonicalFile.toPath()
        if (candidateCanonical != rootCanonical && !candidateCanonical.startsWith(rootCanonical)) throw EngineFailure(ERR_OUTSIDE_ROOT)
        return candidate
    }

    private fun ensureRoot() {
        if (Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            rejectSymlink(rootPath)
            requireDirectory(rootPath)
            return
        }
        val parent = rootPath.parent ?: throw EngineFailure(ERR_OUTSIDE_ROOT)
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) throw EngineFailure(ERR_OPERATION_UNAVAILABLE)
        Files.createDirectory(rootPath)
        rejectSymlink(rootPath)
    }

    private fun requireDirectory(path: Path) {
        rejectSymlink(path)
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw EngineFailure(ERR_NOT_FOUND)
    }

    private fun rejectSymlink(path: Path) {
        if (Files.isSymbolicLink(path)) throw EngineFailure(ERR_SYMLINK_FORBIDDEN)
        val rootCanonical = rootPath.toFile().canonicalFile.toPath()
        val canonical = path.toFile().canonicalFile.toPath()
        if (canonical != rootCanonical && !canonical.startsWith(rootCanonical)) throw EngineFailure(ERR_OUTSIDE_ROOT)
    }

    private fun entries(directory: Path): List<Path> = ArrayList<Path>().also { result ->
        Files.newDirectoryStream(directory).use { stream: DirectoryStream<Path> -> stream.forEach(result::add) }
    }

    private fun safeEntries(directory: Path, warnings: ListingWarnings? = null): List<Path> =
        entries(directory).mapNotNull { child ->
            try {
                when {
                    Files.isSymbolicLink(child) -> {
                        warnings?.add(WorkspaceListingWarningCode.SYMLINK_SKIPPED)
                        null
                    }
                    Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) ||
                        Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) -> child
                    else -> {
                        warnings?.add(WorkspaceListingWarningCode.UNSUPPORTED_ENTRY_SKIPPED)
                        null
                    }
                }
            } catch (_: SecurityException) {
                warnings?.add(WorkspaceListingWarningCode.UNREADABLE_ENTRY_SKIPPED)
                null
            } catch (_: RuntimeException) {
                warnings?.add(WorkspaceListingWarningCode.TRANSIENT_ENTRY_SKIPPED)
                null
            }
        }

    private class ListingWarnings {
        private val counts = linkedMapOf<WorkspaceListingWarningCode, Int>()

        fun add(code: WorkspaceListingWarningCode) {
            if (skippedEntries >= WorkspaceListing.MAX_SKIPPED_ENTRIES) return
            counts[code] = (counts[code] ?: 0) + 1
        }

        val skippedEntries: Int
            get() = counts.values.sum()

        fun snapshot(): List<WorkspaceListingWarning> = counts.map { (code, count) ->
            WorkspaceListingWarning(code, count)
        }
    }

    private fun inspectUsage(enforceIndividualFileLimit: Boolean = true): Usage {
        if (fullDevice) return Usage(0, 0L, 0)
        if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) return Usage(0, 0L, 0)
        requireDirectory(rootPath)
        var files = 0
        var bytes = 0L
        var itemCount = 0
        fun visit(directory: Path, depth: Int) {
            if (depth > WIRED_MAX_PATH_DEPTH) throw EngineFailure(ERR_LIMIT)
            val children = entries(directory)
            children.forEach { child ->
                rejectSymlink(child)
                itemCount++
                if (itemCount > WIRED_MAX_ENTRIES) throw EngineFailure(ERR_LIMIT)
                when {
                    Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) -> visit(child, depth + 1)
                    Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) -> {
                        val size = Files.size(child)
                        if (enforceIndividualFileLimit && size > WIRED_MAX_FILE_BYTES) throw EngineFailure(ERR_LIMIT)
                        files++
                        bytes += size
                        if (files > WIRED_MAX_FILES || bytes > WIRED_MAX_TOTAL_BYTES) throw EngineFailure(ERR_LIMIT)
                    }
                    else -> throw EngineFailure(ERR_UNSUPPORTED_ENTRY)
                }
            }
        }
        visit(rootPath, 0)
        return Usage(files, bytes, itemCount)
    }

    private fun readBounded(file: Path, offset: Long, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf((Files.size(file) - offset).coerceAtLeast(0L), maximum.toLong()).toInt())
        Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            if (channel is java.nio.channels.SeekableByteChannel) channel.position(offset)
            val buffer = ByteBuffer.allocate(8 * 1024)
            var remaining = maximum
            while (true) {
                if (remaining == 0) break
                buffer.clear()
                buffer.limit(minOf(buffer.capacity(), remaining))
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer.array(), 0, count)
                remaining -= count
            }
        }
        return output.toByteArray()
    }

    private fun readAll(file: Path): ByteArray {
        val size = Files.size(file)
        if (size > WIRED_MAX_TOTAL_BYTES || size > Int.MAX_VALUE.toLong()) throw EngineFailure(ERR_LIMIT)
        return readBounded(file, 0L, size.toInt())
    }

    private fun createAndSync(file: Path, bytes: ByteArray) {
        Files.newByteChannel(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            if (channel is FileChannel) channel.force(true)
        }
        if (Files.size(file) != bytes.size.toLong()) throw EngineFailure(ERR_WRITE_UNVERIFIED)
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        throw EngineFailure(ERR_INVALID_CONTENT)
    }

    /**
     * Decodes the largest valid UTF-8 prefix of a raw chunk.  At most the
     * final incomplete code point is withheld; malformed bytes anywhere else
     * are rejected.  The consumed byte count is part of the result so the
     * caller can advance the next offset without skipping the withheld tail.
     */
    private fun decodeUtf8Chunk(bytes: ByteArray): DecodedUtf8Chunk {
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index].toInt() and 0xff
            val width = when {
                first <= 0x7f -> 1
                first in 0xc2..0xdf -> 2
                first in 0xe0..0xef -> 3
                first in 0xf0..0xf4 -> 4
                else -> throw EngineFailure(ERR_INVALID_CONTENT)
            }
            if (index + width > bytes.size) break
            if (width >= 2) {
                val second = bytes[index + 1].toInt() and 0xff
                val secondValid = when (first) {
                    0xe0 -> second in 0xa0..0xbf
                    0xed -> second in 0x80..0x9f
                    0xf0 -> second in 0x90..0xbf
                    0xf4 -> second in 0x80..0x8f
                    else -> second in 0x80..0xbf
                }
                if (!secondValid) throw EngineFailure(ERR_INVALID_CONTENT)
                for (continuation in 2 until width) {
                    val value = bytes[index + continuation].toInt() and 0xff
                    if (value !in 0x80..0xbf) throw EngineFailure(ERR_INVALID_CONTENT)
                }
            }
            index += width
        }
        if (index == 0 && bytes.isNotEmpty()) throw EngineFailure(ERR_INVALID_CONTENT)
        return DecodedUtf8Chunk(
            text = decodeUtf8(bytes.copyOf(index)),
            consumedBytes = index,
        )
    }

    private fun strictUtf8(value: String): ByteArray = try {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = encoder.encode(CharBuffer.wrap(value))
        ByteArray(encoded.remaining()).also { encoded.get(it) }
    } catch (_: CharacterCodingException) {
        throw EngineFailure(ERR_INVALID_CONTENT)
    }

    private fun pathOf(segments: List<String>): String = segments.joinToString("/")

    /** Numeric projection of metadata; the full path and hash never cross the helper boundary. */
    private fun versionOf(path: Path, isDirectory: Boolean, bestEffort: Boolean = false): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(if (isDirectory) 0x44 else 0x46)
        if (isDirectory) {
            safeEntries(path).sortedBy { it.fileName.toString() }.forEach { child ->
                try {
                    val name = child.fileName.toString().toByteArray(StandardCharsets.UTF_8)
                    digest.update(ByteBuffer.allocate(4).putInt(name.size).array())
                    digest.update(name)
                    digest.update(if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) 0x44 else 0x46)
                    if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                        digest.update(ByteBuffer.allocate(8).putLong(Files.size(child)).array())
                    }
                    digest.update(ByteBuffer.allocate(8).putLong(
                        Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS).toMillis(),
                    ).array())
                } catch (failure: RuntimeException) {
                    if (!bestEffort) throw failure
                } catch (failure: IOException) {
                    if (!bestEffort) throw failure
                }
            }
        } else {
            digest.update(ByteBuffer.allocate(8).putLong(Files.size(path)).array())
            digest.update(ByteBuffer.allocate(8).putLong(
                Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis(),
            ).array())
        }
        return ByteBuffer.wrap(digest.digest()).long and Long.MAX_VALUE
    }

    /** Cursor tokens are random-looking, path-free, and MAC-bound to this workspace. */
    private fun encodeCursor(path: String, version: Long, offset: Int, binding: String?): String {
        val body = ByteBuffer.allocate(CURSOR_BODY_BYTES)
            .putLong(version)
            .putLong(offset.toLong())
            .put(pathDigest(path))
            .put(ByteArray(CURSOR_NONCE_BYTES).also { java.security.SecureRandom().nextBytes(it) })
            .array()
        val mac = hmac(cursorKey(binding), body).copyOf(CURSOR_MAC_BYTES)
        return (body + mac).toHexString()
    }

    private fun decodeCursor(raw: String?, path: String, version: Long, binding: String?): Int {
        if (raw == null) return 0
        val bytes = runCatching { raw.hexBytes() }.getOrNull()
            ?: throw EngineFailure(ERR_INVALID_CURSOR)
        if (bytes.size != CURSOR_BODY_BYTES + CURSOR_MAC_BYTES) throw EngineFailure(ERR_INVALID_CURSOR)
        val body = bytes.copyOf(CURSOR_BODY_BYTES)
        val expected = hmac(cursorKey(binding), body).copyOf(CURSOR_MAC_BYTES)
        if (!MessageDigest.isEqual(expected, bytes.copyOfRange(CURSOR_BODY_BYTES, bytes.size))) {
            throw EngineFailure(ERR_INVALID_CURSOR)
        }
        val input = ByteBuffer.wrap(body)
        val tokenVersion = input.long
        val offset = input.long
        val tokenPath = ByteArray(CURSOR_PATH_DIGEST_BYTES).also(input::get)
        if (!MessageDigest.isEqual(tokenPath, pathDigest(path))) throw EngineFailure(ERR_INVALID_CURSOR)
        if (tokenVersion != version) throw EngineFailure(ERR_INVALID_CURSOR)
        if (offset !in 0..Int.MAX_VALUE.toLong()) throw EngineFailure(ERR_INVALID_CURSOR)
        return offset.toInt()
    }

    private fun cursorKey(binding: String?): ByteArray = binding?.let {
        runCatching { it.hexBytes() }.getOrNull()
    } ?: MessageDigest.getInstance("SHA-256")
        .digest(("MAR-WIRED-CURSOR:" + rootPath).toByteArray(StandardCharsets.UTF_8))

    private fun pathDigest(path: String): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(path.toByteArray(StandardCharsets.UTF_8))
        .copyOf(CURSOR_PATH_DIGEST_BYTES)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.hexBytes(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { index ->
            val hi = digitToInt(this[index * 2])
            val lo = digitToInt(this[index * 2 + 1])
            ((hi shl 4) or lo).toByte()
        }
    }

    private fun digitToInt(value: Char): Int = when (value) {
        in '0'..'9' -> value - '0'
        in 'a'..'f' -> value - 'a' + 10
        in 'A'..'F' -> value - 'A' + 10
        else -> throw IllegalArgumentException("invalid hex")
    }

    private fun success(result: WiredAdbFileResult): WiredAdbFileEngineResult = WiredAdbFileEngineResult.Success(result)
    private data class Usage(val files: Int, val bytes: Long, val entries: Int)
    private data class DecodedUtf8Chunk(val text: String, val consumedBytes: Int)
    private class EngineFailure(val code: String) : Exception()

    companion object {
        const val ERR_INVALID_PATH = "INVALID_PATH"
        const val ERR_INVALID_CONTENT = "INVALID_CONTENT"
        const val ERR_LIMIT = "LIMIT"
        const val ERR_OUTSIDE_ROOT = "OUTSIDE_ROOT"
        const val ERR_SYMLINK_FORBIDDEN = "SYMLINK_FORBIDDEN"
        const val ERR_NOT_FOUND = "NOT_FOUND"
        const val ERR_TARGET_EXISTS = "TARGET_EXISTS"
        const val ERR_NON_EMPTY_DIRECTORY = "NON_EMPTY_DIRECTORY"
        const val ERR_UNSUPPORTED_ENTRY = "UNSUPPORTED_ENTRY"
        const val ERR_FILE_TOO_LARGE = "FILE_TOO_LARGE"
        const val ERR_PERMISSION_DENIED = "PERMISSION_DENIED"
        const val ERR_OPERATION_UNAVAILABLE = "OPERATION_UNAVAILABLE"
        const val ERR_UNKNOWN_OUTCOME = "UNKNOWN_OUTCOME"
        const val ERR_ATOMIC_REPLACE_UNAVAILABLE = "ATOMIC_REPLACE_UNAVAILABLE"
        const val ERR_WRITE_UNVERIFIED = "WRITE_UNVERIFIED"
        const val ERR_CONFLICT = "CONFLICT"
        const val ERR_OFFSET_OUT_OF_RANGE = "OFFSET_OUT_OF_RANGE"
        const val ERR_INVALID_PATCH = "INVALID_PATCH"
        const val ERR_INVALID_CURSOR = "INVALID_CURSOR"

        private const val CURSOR_PATH_DIGEST_BYTES = 16
        private const val CURSOR_NONCE_BYTES = 16
        private const val CURSOR_MAC_BYTES = 16
        private const val CURSOR_BODY_BYTES = 8 + 8 + CURSOR_PATH_DIGEST_BYTES + CURSOR_NONCE_BYTES
    }
}
