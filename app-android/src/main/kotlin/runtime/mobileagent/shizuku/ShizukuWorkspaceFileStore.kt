// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.UUID

/**
 * Fixed, relative-path-only file store used by the optional Shizuku UserService.
 *
 * This class is intentionally independent from Android content providers and
 * from command execution.  The production constructor points at one fixed
 * public Download child.  The root can be injected by tests so path and quota
 * logic can be exercised without a Shizuku installation or a real device.
 */
internal class ShizukuWorkspaceFileStore(
    private val rootFile: File = FIXED_ROOT,
    /** Full-device handles must not recursively scan `/` to calculate quota. */
    private val enforceQuota: Boolean = true,
    /** A device-root listing skips symlink entries instead of following them. */
    private val skipSymlinksInList: Boolean = false,
) {
    private val lock = Any()
    private val cursorStore = CursorStore()
    private val rootPath: Path = rootFile.toPath().toAbsolutePath().normalize()

    fun status(): String = synchronized(lock) {
        val result = JSONObject()
            .put("ok", true)
            .put("operation", "status")
            .put("workspace", WORKSPACE_LABEL)
            .put("serviceUid", Process.myUid())
            .put("rootExists", Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS))
        if (Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            runCatching {
                inspectUsage(rootPath)
            }.onSuccess { usage ->
                result.put("files", usage.files)
                    .put("entries", usage.entries)
                    .put("bytes", usage.bytes)
            }.onFailure {
                result.put("rootHealthy", false)
            }
        }
        boundedJson(result)
    }

    /**
     * Lists at most one bounded page.  The cursor is a process-local random
     * capability: it contains neither the directory path nor an offset and it
     * is invalidated by a direct-child change or UserService restart.
     *
     * Pagination has no total-entry ceiling other than [MAX_LISTED_ENTRIES],
     * which is a resource-protection bound (not a pagination capability) and
     * is deliberately separated from the per-page [MAX_DIRECTORY_ENTRIES]
     * budget.  The listing fingerprint is shallow: only the directory's own
     * metadata and its immediate visible children feed it, so a huge or deep
     * child subtree can never poison the parent listing.
     */
    fun list(
        relativePath: String?,
        maxEntries: Int = MAX_DIRECTORY_ENTRIES,
        cursor: String? = null,
    ): String = synchronized(lock) {
        guarded("list") {
            if (maxEntries !in 1..MAX_DIRECTORY_ENTRIES) throw WorkspaceFailure(LIMIT)
            val segments = parsePath(relativePath, allowRoot = true)
            val directory = resolve(segments)
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw WorkspaceFailure(NOT_FOUND)
            }
            requireDirectory(directory)
            val warnings = ListingWarnings()
            val children = listableChildren(directory, warnings)
            if (children.size > MAX_LISTED_ENTRIES) throw WorkspaceFailure(LIMIT)
            val path = relativePath(segments)
            // Directory fingerprints use the same safe, non-following view as list output so a
            // symlink or unsupported node cannot poison pagination for normal entries.
            val fingerprint = shallowDirectoryFingerprint(directory)
            val start = cursor?.let {
                cursorStore.resolve(it, path, fingerprint) ?: throw WorkspaceFailure(INVALID_CURSOR)
            } ?: 0
            if (start > children.size) throw WorkspaceFailure(INVALID_CURSOR)
            val end = minOf(start + maxEntries, children.size)
            val entries = JSONArray()
            var emittedEnd = start
            for (index in start until end) {
                val child = children[index]
                try {
                    val childSegments = segments + child.fileName.toString()
                    val entry = JSONObject()
                        .put("path", relativePath(childSegments))
                    when (classifyChild(child)) {
                        ChildKind.DIRECTORY -> entry.put("type", "directory")
                        ChildKind.FILE -> {
                            val size = Files.size(child)
                            entry.put("type", "file").put("bytes", size).put("version", fileVersion(child))
                        }
                        ChildKind.SYMLINK -> {
                            warnings.add(child, WARNING_SYMLINK_SKIPPED)
                            emittedEnd = index + 1
                            continue
                        }
                        ChildKind.UNSUPPORTED -> {
                            warnings.add(child, WARNING_UNSUPPORTED_ENTRY_SKIPPED)
                            emittedEnd = index + 1
                            continue
                        }
                    }
                if (entry.optString("type") == "directory") {
                    entry.put("version", shallowDirectoryFingerprint(child))
                }
                    entries.put(entry)
                    // Long but valid names must still make progress without
                    // exceeding the Binder-safe JSON envelope.  Reserve room
                    // for a real continuation token before accepting the entry.
                    val probe = success("list", segments)
                        .put("entries", entries)
                        .put("version", fingerprint)
                        .put("truncated", true)
                        .put("nextCursor", "x".repeat(CURSOR_DISPLAY_BYTES))
                    warnings.appendTo(probe)
                    if (probe.toString().toByteArray(StandardCharsets.UTF_8).size > MAX_OUTPUT_BYTES) {
                        entries.remove(entries.length() - 1)
                        break
                    }
                    emittedEnd = index + 1
                } catch (_: SecurityException) {
                    warnings.add(child, WARNING_UNREADABLE_ENTRY_SKIPPED)
                    emittedEnd = index + 1
                } catch (_: IOException) {
                    warnings.add(child, WARNING_TRANSIENT_ENTRY_SKIPPED)
                    emittedEnd = index + 1
                } catch (_: RuntimeException) {
                    warnings.add(child, WARNING_TRANSIENT_ENTRY_SKIPPED)
                    emittedEnd = index + 1
                }
            }
            if (entries.length() == 0 && emittedEnd == start && emittedEnd < children.size) {
                // A valid but oversized model envelope (for example one very long filename)
                // cannot make progress; retain the explicit bounded failure in that case.
                throw WorkspaceFailure(OUTPUT_LIMIT)
            }
            val nextCursor = if (emittedEnd < children.size) cursorStore.issue(path, fingerprint, emittedEnd) else null
            val result = success("list", segments)
                .put("entries", entries)
                .put("version", fingerprint)
                .put("truncated", nextCursor != null)
                .put("nextCursor", nextCursor ?: JSONObject.NULL)
            warnings.appendTo(result)
            result
        }
    }

    fun stat(relativePath: String?): String = synchronized(lock) {
        guarded("stat") {
            val segments = parsePath(relativePath, allowRoot = true)
            ensureRoot()
            val target = resolve(segments)
            rejectSymbolicLink(target)
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw WorkspaceFailure(NOT_FOUND)
            val type = when {
                Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) -> "directory"
                Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) -> "file"
                else -> throw WorkspaceFailure(UNSUPPORTED_ENTRY)
            }
            val bytes = if (type == "file") Files.size(target) else 0L
            success("stat", segments)
                .put("type", type)
                .put("bytes", bytes)
                .put("version", if (type == "file") fileVersion(target) else shallowDirectoryFingerprint(target))
        }
    }

    fun read(relativePath: String?, maxBytes: Int): String = synchronized(lock) {
        readChunkJson(relativePath, maxBytes, offsetBytes = 0L)
    }

    /** Reads one bounded UTF-8 chunk without transporting the whole file. */
    fun readChunk(relativePath: String?, maxBytes: Int, offsetBytes: Long): ReadChunkResult = synchronized(lock) {
        try {
            if (maxBytes !in 1..MAX_READ_BYTES) throw WorkspaceFailure(LIMIT)
            if (offsetBytes < 0L) throw WorkspaceFailure(OFFSET_OUT_OF_RANGE)
            val segments = parsePath(relativePath, allowRoot = false)
            val file = resolve(segments)
            rejectSymbolicLink(file)
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw WorkspaceFailure(NOT_FOUND)
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw WorkspaceFailure(UNSUPPORTED_ENTRY)
            val size = Files.size(file)
            if (size > MAX_FILE_BYTES) throw WorkspaceFailure(FILE_TOO_LARGE)
            if (offsetBytes > size) throw WorkspaceFailure(OFFSET_OUT_OF_RANGE)
            val raw = readBoundedChunk(file, offsetBytes, maxBytes)
            val decoded = decodeChunk(raw)
                ?: throw WorkspaceFailure(INVALID_CONTENT)
            val consumed = decoded.second
            if (raw.isNotEmpty() && consumed == 0) throw WorkspaceFailure(INVALID_CONTENT)
            ReadChunkResult.Success(
                path = relativePath(segments),
                bytes = raw.copyOf(consumed),
                version = fileVersion(file),
                offsetBytes = offsetBytes,
                totalBytes = size,
                eof = offsetBytes + consumed >= size,
            )
        } catch (failure: WorkspaceFailure) {
            ReadChunkResult.Failure(failure.code)
        } catch (_: SecurityException) {
            ReadChunkResult.Failure(PERMISSION_DENIED)
        } catch (_: IOException) {
            ReadChunkResult.Failure(OPERATION_UNAVAILABLE)
        } catch (_: RuntimeException) {
            ReadChunkResult.Failure(OPERATION_UNAVAILABLE)
        }
    }

    private fun readChunkJson(relativePath: String?, maxBytes: Int, offsetBytes: Long): String =
        guarded("read") {
            when (val chunk = readChunk(relativePath, maxBytes, offsetBytes)) {
                is ReadChunkResult.Failure -> throw WorkspaceFailure(chunk.code)
                is ReadChunkResult.Success -> success("read", parsePath(relativePath, allowRoot = false))
                    .put("bytes", chunk.bytes.size)
                    .put("text", decodeUtf8(chunk.bytes))
                    .put("version", chunk.version)
                    .put("offsetBytes", chunk.offsetBytes)
                    .put("totalBytes", chunk.totalBytes)
                    .put("eof", chunk.eof)
            }
        }

    /** Conditionally applies a bounded text patch using an atomic replacement. */
    fun applyPatch(
        relativePath: String?,
        patch: String?,
        expectedVersion: String?,
        format: String?,
    ): String = synchronized(lock) {
        guarded("apply_patch") {
            val patchValue = patch ?: throw WorkspaceFailure(INVALID_CONTENT)
            val patchBytes = strictUtf8(patchValue)
            if (patchBytes.size !in 1..MAX_PATCH_BYTES || patchValue.contains('\u0000')) {
                throw WorkspaceFailure(LIMIT)
            }
            val expected = expectedVersion?.takeIf { it.length in 1..MAX_VERSION_CHARS }
                ?: throw WorkspaceFailure(INVALID_VERSION)
            val segments = parsePath(relativePath, allowRoot = false)
            ensureRoot()
            val target = resolve(segments)
            rejectSymbolicLink(target)
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw WorkspaceFailure(NOT_FOUND)
            val currentVersion = fileVersion(target)
            if (currentVersion != expected) throw WorkspaceFailure(CONFLICT)
            val sourceBytes = readAll(target)
            val sourceText = decodeUtf8(sourceBytes)
            val patchedText = when (format) {
                PATCH_FORMAT_REPLACE -> patchValue
                PATCH_FORMAT_UNIFIED_DIFF -> applyUnifiedDiff(sourceText, patchValue)
                    ?: throw WorkspaceFailure(INVALID_PATCH)
                else -> throw WorkspaceFailure(INVALID_PATCH)
            }
            val patchedBytes = strictUtf8(patchedText)
            if (patchedBytes.size > MAX_FILE_BYTES) throw WorkspaceFailure(FILE_TOO_LARGE)
            if (patchedBytes.contentEquals(sourceBytes)) {
                return@guarded success("apply_patch", segments)
                    .put("type", "file")
                    .put("bytes", patchedBytes.size)
                    .put("created", false)
                    .put("version", currentVersion)
            }
            if (enforceQuota) {
                val usage = inspectUsage(rootPath)
                val retained = usage.bytes - sourceBytes.size
                if (patchedBytes.size > MAX_TOTAL_BYTES - retained) throw WorkspaceFailure(LIMIT)
            }
            val parent = target.parent ?: throw WorkspaceFailure(OUTSIDE_ROOT)
            requireDirectory(parent)
            rejectSymbolicLink(parent)
            val temporary = parent.resolve(".mar-shizuku-${UUID.randomUUID()}.tmp")
            try {
                createAndSync(temporary, patchedBytes)
                rejectSymbolicLink(parent)
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || fileVersion(target) != currentVersion) {
                    throw WorkspaceFailure(CONFLICT)
                }
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    throw WorkspaceFailure(ATOMIC_REPLACE_UNAVAILABLE)
                } catch (_: UnsupportedOperationException) {
                    throw WorkspaceFailure(ATOMIC_REPLACE_UNAVAILABLE)
                }
                rejectSymbolicLink(target)
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) != patchedBytes.size.toLong()) {
                    throw WorkspaceFailure(UNKNOWN_OUTCOME)
                }
                success("apply_patch", segments)
                    .put("type", "file")
                    .put("bytes", patchedBytes.size)
                    .put("created", false)
                    .put("version", fileVersion(target))
            } finally {
                runCatching { Files.deleteIfExists(temporary) }
            }
        }
    }

    fun write(relativePath: String?, utf8Content: ByteArray?, replaceExisting: Boolean): String = synchronized(lock) {
        guarded("write") {
            val content = utf8Content ?: throw WorkspaceFailure(INVALID_CONTENT)
            if (content.size > MAX_FILE_BYTES) throw WorkspaceFailure(LIMIT)
            // Decode before touching the filesystem.  This rejects malformed UTF-8 rather than
            // silently replacing bytes with U+FFFD.
            decodeUtf8(content)
            val segments = parsePath(relativePath, allowRoot = false)
            ensureRoot()
            val target = resolve(segments)
            val parent = target.parent ?: throw WorkspaceFailure(OUTSIDE_ROOT)
            requireDirectory(parent)
            rejectSymbolicLink(parent)

            val existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
            if (existed) {
                rejectSymbolicLink(target)
                if (!replaceExisting || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw WorkspaceFailure(TARGET_EXISTS)
                }
            }

            if (enforceQuota) {
                val usage = inspectUsage(rootPath)
                val oldBytes = if (existed) Files.size(target) else 0L
                val newFiles = usage.files + if (existed) 0 else 1
                val newBytes = usage.bytes - oldBytes + content.size
                if (newFiles > MAX_FILES || newBytes > MAX_TOTAL_BYTES) throw WorkspaceFailure(LIMIT)
            }

            if (!replaceExisting) {
                // Create-only file content goes through the kernel-atomic
                // exclusive create: no temporary file, no rename, and no
                // REPLACE flag anywhere on this path, so a concurrently
                // created target can never be overwritten on any platform.
                try {
                    runtime.mobileagent.workspace.WorkspaceAtomicCommit.writeExclusive(target, content)
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    throw WorkspaceFailure(TARGET_EXISTS)
                }
                rejectSymbolicLink(parent)
                rejectSymbolicLink(target)
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) != content.size.toLong()) {
                    throw WorkspaceFailure(WRITE_UNVERIFIED)
                }
                return@guarded success("write", segments)
                    .put("bytes", content.size)
                    .put("created", true)
            }

            val temporary = parent.resolve(".mar-shizuku-${UUID.randomUUID()}.tmp")
            try {
                createAndSync(temporary, content)
                // Re-check immediately before the replacement.  Atomic replacement does not
                // follow the target if it changed to a link, but rejecting the race keeps the
                // contract explicit and fail-closed.
                rejectSymbolicLink(parent)
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    rejectSymbolicLink(target)
                    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                        throw WorkspaceFailure(TARGET_EXISTS)
                    }
                }
                try {
                    // Replace path only: create-only content uses the exclusive
                    // create above and never reaches this rename.
                    runtime.mobileagent.workspace.WorkspaceAtomicCommit.publish(temporary, target, replaceExisting = true)
                } catch (_: AtomicMoveNotSupportedException) {
                    throw WorkspaceFailure(ATOMIC_REPLACE_UNAVAILABLE)
                } catch (_: UnsupportedOperationException) {
                    throw WorkspaceFailure(ATOMIC_REPLACE_UNAVAILABLE)
                }
                rejectSymbolicLink(target)
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) != content.size.toLong()) {
                    throw WorkspaceFailure(WRITE_UNVERIFIED)
                }
            } finally {
                runCatching { Files.deleteIfExists(temporary) }
            }
            success("write", segments)
                .put("bytes", content.size)
                .put("created", !existed)
        }
    }

    fun mkdir(relativePath: String?): String = synchronized(lock) {
        guarded("mkdir") {
            val segments = parsePath(relativePath, allowRoot = false)
            ensureRoot()
            val directory = resolve(segments)
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                rejectSymbolicLink(directory)
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw WorkspaceFailure(TARGET_EXISTS)
                return@guarded success("mkdir", segments).put("created", false)
            }
            val parent = directory.parent ?: throw WorkspaceFailure(OUTSIDE_ROOT)
            requireDirectory(parent)
            rejectSymbolicLink(parent)
            if (enforceQuota) {
                val usage = inspectUsage(rootPath)
                if (usage.entries + 1 > MAX_ENTRIES) throw WorkspaceFailure(LIMIT)
            }
            try {
                Files.createDirectory(directory)
            } catch (_: IOException) {
                throw WorkspaceFailure(OPERATION_UNAVAILABLE)
            }
            rejectSymbolicLink(directory)
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw WorkspaceFailure(OPERATION_UNAVAILABLE)
            success("mkdir", segments).put("created", true)
        }
    }

    fun delete(relativePath: String?): String = synchronized(lock) {
        guarded("delete") {
            val segments = parsePath(relativePath, allowRoot = false)
            ensureRoot()
            val target = resolve(segments)
            rejectSymbolicLink(target)
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw WorkspaceFailure(NOT_FOUND)
            val type = when {
                Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) -> "file"
                Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) -> {
                    if (directoryEntries(target).isNotEmpty()) throw WorkspaceFailure(NON_EMPTY_DIRECTORY)
                    "directory"
                }
                else -> throw WorkspaceFailure(UNSUPPORTED_ENTRY)
            }

            // Re-check after inspecting the directory and immediately before deletion.
            rejectSymbolicLink(target)
            if (type == "file" && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw WorkspaceFailure(OPERATION_UNAVAILABLE)
            }
            if (type == "directory" &&
                (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) || directoryEntries(target).isNotEmpty())
            ) {
                throw WorkspaceFailure(NON_EMPTY_DIRECTORY)
            }
            if (!Files.deleteIfExists(target) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw WorkspaceFailure(OPERATION_UNAVAILABLE)
            }
            success("delete", segments)
                .put("deleted", true)
                .put("type", type)
        }
    }

    fun move(sourceRelativePath: String?, destinationRelativePath: String?, replaceExisting: Boolean): String =
        synchronized(lock) {
            guarded("move") {
                val sourceSegments = parsePath(sourceRelativePath, allowRoot = false)
                val destinationSegments = parsePath(destinationRelativePath, allowRoot = false)
                if (sourceSegments == destinationSegments) throw WorkspaceFailure(TARGET_EXISTS)
                ensureRoot()

                val source = resolve(sourceSegments)
                rejectSymbolicLink(source)
                if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw WorkspaceFailure(NOT_FOUND)
                val type = when {
                    Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) -> "directory"
                    Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) -> "file"
                    else -> throw WorkspaceFailure(UNSUPPORTED_ENTRY)
                }

                // A directory cannot be moved into itself or one of its descendants.
                if (type == "directory" && destinationSegments.size > sourceSegments.size &&
                    destinationSegments.subList(0, sourceSegments.size) == sourceSegments
                ) {
                    throw WorkspaceFailure(MOVE_INTO_SELF)
                }

                val destination = resolve(destinationSegments)
                val destinationParent = destination.parent ?: throw WorkspaceFailure(OUTSIDE_ROOT)
                requireDirectory(destinationParent)
                rejectSymbolicLink(destinationParent)

                val destinationExists = Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                if (destinationExists) {
                    rejectSymbolicLink(destination)
                    if (!replaceExisting) throw WorkspaceFailure(TARGET_EXISTS)
                    if (Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS) &&
                        directoryEntries(destination).isNotEmpty()
                    ) {
                        throw WorkspaceFailure(NON_EMPTY_DIRECTORY)
                    }
                }

                // Moving within the fixed root does not change usage, but a
                // preflight keeps an already-invalid workspace fail-closed.
                inspectUsage(rootPath)

                // Re-check all mutable boundary conditions immediately before
                // the atomic operation.  No copy/delete fallback is allowed.
                rejectSymbolicLink(source)
                if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw WorkspaceFailure(NOT_FOUND)
                rejectSymbolicLink(destinationParent)
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    rejectSymbolicLink(destination)
                    if (!replaceExisting) throw WorkspaceFailure(TARGET_EXISTS)
                    if (Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS) &&
                        directoryEntries(destination).isNotEmpty()
                    ) {
                        throw WorkspaceFailure(NON_EMPTY_DIRECTORY)
                    }
                }

                try {
                    if (replaceExisting) {
                        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } else if (type == "directory") {
                        // No portable primitive proves a no-replace directory
                        // move: a bare rename silently merges/overwrites on
                        // every major platform (b07 finding C2).  Fail closed.
                        throw WorkspaceFailure(ATOMIC_REPLACE_UNAVAILABLE)
                    } else {
                        // Safe non-atomic no-clobber file move: a destination
                        // created after the pre-check fails with TARGET_EXISTS
                        // instead of being overwritten.  An interruption may
                        // leave source and destination behind → UNKNOWN_OUTCOME.
                        runtime.mobileagent.workspace.WorkspaceAtomicCommit.moveFileNoReplace(source, destination)
                    }
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    // The destination appeared between the pre-check and the commit.
                    throw WorkspaceFailure(TARGET_EXISTS)
                } catch (_: AtomicMoveNotSupportedException) {
                    throw WorkspaceFailure(ATOMIC_REPLACE_UNAVAILABLE)
                } catch (_: UnsupportedOperationException) {
                    throw WorkspaceFailure(ATOMIC_REPLACE_UNAVAILABLE)
                } catch (_: IOException) {
                    // The no-clobber copy may have committed the destination
                    // before a later step failed; the outcome is uncertain and
                    // must not be reported as success or as a clean miss.
                    throw WorkspaceFailure(UNKNOWN_OUTCOME)
                }

                // If the provider reports success but the postcondition cannot
                // be established, the caller must treat the outcome as unknown
                // and must not replay the move automatically.
                if (Files.exists(source, LinkOption.NOFOLLOW_LINKS) ||
                    !Files.exists(destination, LinkOption.NOFOLLOW_LINKS) ||
                    (type == "directory" && !Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) ||
                    (type == "file" && !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS))
                ) {
                    throw WorkspaceFailure(UNKNOWN_OUTCOME)
                }
                val bytes = try {
                    // Any failure while verifying the post-move boundary is
                    // ambiguous because the atomic operation already ran.
                    rejectSymbolicLink(destination)
                    if (type == "file") Files.size(destination) else 0L
                } catch (_: WorkspaceFailure) {
                    throw WorkspaceFailure(UNKNOWN_OUTCOME)
                } catch (_: IOException) {
                    throw WorkspaceFailure(UNKNOWN_OUTCOME)
                }
                if (bytes > MAX_FILE_BYTES) throw WorkspaceFailure(UNKNOWN_OUTCOME)
                success("move", destinationSegments)
                    .put("sourcePath", relativePath(sourceSegments))
                    .put("destinationPath", relativePath(destinationSegments))
                    .put("moved", true)
                    .put("type", type)
                    .put("bytes", bytes)
            }
        }

    private fun guarded(operation: String, action: () -> JSONObject): String = try {
        boundedJson(action())
    } catch (_: InvalidShizukuPath) {
        failure(operation, INVALID_PATH)
    } catch (_: ShizukuPathLimit) {
        failure(operation, LIMIT)
    } catch (error: WorkspaceFailure) {
        failure(operation, error.code)
    } catch (_: SecurityException) {
        failure(operation, PERMISSION_DENIED)
    } catch (_: IOException) {
        failure(operation, OPERATION_UNAVAILABLE)
    } catch (_: RuntimeException) {
        // Do not expose exception text, paths, or provider internals across Binder.
        failure(operation, OPERATION_UNAVAILABLE)
    }

    private fun success(operation: String, segments: List<String>): JSONObject = JSONObject()
        .put("ok", true)
        .put("operation", operation)
        .put("path", relativePath(segments))

    private fun failure(operation: String, code: String): String = boundedJson(
        JSONObject()
            .put("ok", false)
            .put("operation", operation)
            .put("code", code),
    )

    private fun boundedJson(result: JSONObject): String {
        val encoded = result.toString()
        if (encoded.toByteArray(StandardCharsets.UTF_8).size > MAX_OUTPUT_BYTES) {
            return failure("result", OUTPUT_LIMIT)
        }
        return encoded
    }

    private fun parsePath(raw: String?, allowRoot: Boolean): List<String> {
        return ShizukuWorkspacePathPolicy.parse(raw, allowRoot)
    }

    private fun resolve(segments: List<String>): Path {
        val candidate = segments.fold(rootPath) { parent, segment -> parent.resolve(segment) }.normalize()
        if (candidate != rootPath && !candidate.startsWith(rootPath)) throw WorkspaceFailure(OUTSIDE_ROOT)
        var current = rootPath
        rejectSymbolicLink(current)
        segments.forEach { segment ->
            current = current.resolve(segment)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) rejectSymbolicLink(current)
        }
        val canonicalRoot = rootPath.toFile().canonicalFile.toPath()
        val canonicalCandidate = candidate.toFile().canonicalFile.toPath()
        if (canonicalCandidate != canonicalRoot && !canonicalCandidate.startsWith(canonicalRoot)) {
            throw WorkspaceFailure(OUTSIDE_ROOT)
        }
        return candidate
    }

    private fun ensureRoot(): Path {
        if (Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            rejectSymbolicLink(rootPath)
            requireDirectory(rootPath)
            return rootPath
        }
        val parent = rootPath.parent ?: throw WorkspaceFailure(OUTSIDE_ROOT)
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw WorkspaceFailure(OPERATION_UNAVAILABLE)
        // The fixed Download directory is the workspace's parent, so it is intentionally outside
        // rootPath and must not pass the child-containment check used by rejectSymbolicLink().
        // Still reject a direct parent symlink before creating the single fixed child directory.
        if (Files.isSymbolicLink(parent)) throw WorkspaceFailure(SYMLINK_REJECTED)
        try {
            Files.createDirectory(rootPath)
        } catch (_: IOException) {
            throw WorkspaceFailure(OPERATION_UNAVAILABLE)
        }
        rejectSymbolicLink(rootPath)
        requireDirectory(rootPath)
        return rootPath
    }

    private fun requireDirectory(path: Path) {
        rejectSymbolicLink(path)
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw WorkspaceFailure(NOT_FOUND)
    }

    private fun rejectSymbolicLink(path: Path) {
        if (Files.isSymbolicLink(path)) throw WorkspaceFailure(SYMLINK_REJECTED)
        val canonicalRoot = rootPath.toFile().canonicalFile.toPath()
        val canonical = path.toFile().canonicalFile.toPath()
        if (canonical != canonicalRoot && !canonical.startsWith(canonicalRoot)) {
            throw WorkspaceFailure(OUTSIDE_ROOT)
        }
    }

    private fun directoryEntries(directory: Path): List<Path> {
        val entries = ArrayList<Path>()
        Files.newDirectoryStream(directory).use { stream: DirectoryStream<Path> ->
            stream.forEach { entry -> entries.add(entry) }
        }
        return entries
    }

    private enum class ChildKind {
        FILE,
        DIRECTORY,
        SYMLINK,
        UNSUPPORTED,
    }

    /** Classify one child without ever following a symbolic link. */
    private fun classifyChild(child: Path): ChildKind {
        if (Files.isSymbolicLink(child)) return ChildKind.SYMLINK
        val attributes = Files.readAttributes(
            child,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        return when {
            attributes.isDirectory -> ChildKind.DIRECTORY
            attributes.isRegularFile -> ChildKind.FILE
            else -> ChildKind.UNSUPPORTED
        }
    }

    /**
     * Return only nodes that can be represented by the model-facing listing.  The warning
     * accumulator is process-local and never serializes a path or an exception message.
     */
    private fun listableChildren(directory: Path, warnings: ListingWarnings? = null): List<Path> =
        directoryEntries(directory).mapNotNull { child ->
            try {
                when (classifyChild(child)) {
                    ChildKind.FILE,
                    ChildKind.DIRECTORY,
                        -> child
                    ChildKind.SYMLINK -> {
                        warnings?.add(child, WARNING_SYMLINK_SKIPPED)
                        null
                    }
                    ChildKind.UNSUPPORTED -> {
                        warnings?.add(child, WARNING_UNSUPPORTED_ENTRY_SKIPPED)
                        null
                    }
                }
            } catch (_: SecurityException) {
                warnings?.add(child, WARNING_UNREADABLE_ENTRY_SKIPPED)
                null
            } catch (_: IOException) {
                warnings?.add(child, WARNING_TRANSIENT_ENTRY_SKIPPED)
                null
            } catch (_: RuntimeException) {
                warnings?.add(child, WARNING_TRANSIENT_ENTRY_SKIPPED)
                null
            }
        }.sortedBy { it.fileName.toString() }

    private fun fileVersion(file: Path): String {
        val attributes = Files.readAttributes(
            file,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        return sha256(
            buildString {
                append(attributes.size()).append('\u0000')
                append(attributes.lastModifiedTime().toMillis()).append('\u0000')
                append(attributes.creationTime().toMillis()).append('\u0000')
                append(attributes.fileKey()?.toString().orEmpty())
            }.toByteArray(StandardCharsets.UTF_8),
        )
    }

    /**
     * Shallow directory fingerprint for list pagination and directory entry
     * versions.  Only the directory's own metadata and its immediate visible
     * children (name, type, file size, stable file metadata) feed the digest;
     * child subtrees are never descended into, so a huge or overly deep child
     * cannot poison the parent listing or its cursors.  File versions keep
     * their content-backed CAS semantics in [fileVersion].
     */
    private fun shallowDirectoryFingerprint(
        directory: Path,
        warnings: ListingWarnings? = null,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun feed(text: String) = digest.update(text.toByteArray(StandardCharsets.UTF_8))
        try {
            val self = Files.readAttributes(
                directory,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            feed("dir\u0000${self.lastModifiedTime().toMillis()}\u0000${self.creationTime().toMillis()}\u0000${self.fileKey()}\u0000")
        } catch (_: SecurityException) {
            throw WorkspaceFailure(PERMISSION_DENIED)
        } catch (_: IOException) {
            throw WorkspaceFailure(OPERATION_UNAVAILABLE)
        } catch (_: RuntimeException) {
            throw WorkspaceFailure(OPERATION_UNAVAILABLE)
        }
        listableChildren(directory, warnings).forEach { child ->
            try {
                val attributes = Files.readAttributes(
                    child,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                val name = child.fileName.toString()
                if (attributes.isDirectory) {
                    feed("$name\u0000directory\u0000${attributes.lastModifiedTime().toMillis()}\u0000${attributes.fileKey()}\u0000")
                } else {
                    feed("$name\u0000file\u0000${attributes.size()}\u0000${attributes.lastModifiedTime().toMillis()}\u0000${attributes.fileKey()}\u0000")
                }
            } catch (_: SecurityException) {
                warnings?.add(child, WARNING_UNREADABLE_ENTRY_SKIPPED)
            } catch (_: IOException) {
                warnings?.add(child, WARNING_TRANSIENT_ENTRY_SKIPPED)
            } catch (_: RuntimeException) {
                warnings?.add(child, WARNING_TRANSIENT_ENTRY_SKIPPED)
            }
        }
        return digest.digest().toHex()
    }

    /** Bounded category counts used only while constructing one list response. */
    private class ListingWarnings {
        private val seen = HashSet<String>()
        private val counts = LinkedHashMap<String, Int>()
        private var total = 0

        fun add(path: Path, code: String) {
            if (total >= MAX_REPORTED_SKIPPED_ENTRIES) return
            val key = path.toString() + '\u0000' + code
            if (!seen.add(key)) return
            counts[code] = (counts[code] ?: 0) + 1
            total++
        }

        fun appendTo(result: JSONObject) {
            if (total == 0) return
            result.put("skippedEntries", total)
            val warningArray = JSONArray()
            counts.forEach { (code, count) ->
                warningArray.put(JSONObject().put("code", code).put("count", count))
            }
            result.put("warnings", warningArray)
        }
    }

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).toHex()

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) append("%02x".format(byte.toInt() and 0xff))
    }

    private fun inspectUsage(root: Path): Usage {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return Usage(0, 0, 0)
        requireDirectory(root)
        var files = 0
        var bytes = 0L
        var entries = 0

        fun visit(directory: Path, depth: Int) {
            if (depth > MAX_PATH_DEPTH) throw WorkspaceFailure(LIMIT)
            val children = directoryEntries(directory)
            if (children.size > MAX_DIRECTORY_ENTRIES) throw WorkspaceFailure(LIMIT)
            children.forEach { child ->
                rejectSymbolicLink(child)
                entries++
                if (entries > MAX_ENTRIES) throw WorkspaceFailure(LIMIT)
                when {
                    Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) -> visit(child, depth + 1)
                    Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) -> {
                        val size = Files.size(child)
                        if (size > MAX_FILE_BYTES) throw WorkspaceFailure(LIMIT)
                        files++
                        bytes += size
                        if (files > MAX_FILES || bytes > MAX_TOTAL_BYTES) throw WorkspaceFailure(LIMIT)
                    }
                    else -> throw WorkspaceFailure(UNSUPPORTED_ENTRY)
                }
            }
        }

        visit(root, 0)
        return Usage(files, bytes, entries)
    }

    private fun readBounded(file: Path, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(Files.size(file).toInt(), maximum) + 1)
        Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(8 * 1024)
            while (true) {
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (output.size() + count > maximum) throw WorkspaceFailure(LIMIT)
                output.write(buffer.array(), 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun readBoundedChunk(file: Path, offset: Long, maximum: Int): ByteArray {
        val size = Files.size(file)
        val target = minOf(size - offset, maximum.toLong()).toInt()
        if (target <= 0) return byteArrayOf()
        val output = ByteArrayOutputStream(target)
        Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            channel.position(offset)
            val buffer = ByteBuffer.allocate(minOf(8 * 1024, target))
            while (output.size() < target) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity(), target - output.size()))
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer.array(), 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun readAll(file: Path): ByteArray {
        val size = Files.size(file)
        if (size > MAX_PATCH_SOURCE_BYTES || size > Int.MAX_VALUE.toLong()) throw WorkspaceFailure(FILE_TOO_LARGE)
        return readBoundedChunk(file, 0L, size.toInt())
    }

    /** Return a valid UTF-8 prefix, trimming only an incomplete final code point. */
    private fun decodeChunk(bytes: ByteArray): Pair<String, Int>? {
        if (bytes.isEmpty()) return "" to 0
        val first = runCatching { decodeUtf8(bytes) }.getOrNull()
        if (first != null) return first to bytes.size
        val minimum = maxOf(0, bytes.size - 3)
        for (end in (bytes.size - 1) downTo minimum) {
            val text = runCatching { decodeUtf8(bytes.copyOf(end)) }.getOrNull()
            if (text != null) return text to end
        }
        return null
    }

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

    private class CursorStore {
        private val random = SecureRandom()
        private val entries = LinkedHashMap<String, CursorState>()

        fun issue(path: String, version: String, offset: Int): String {
            val bytes = ByteArray(CURSOR_BYTES)
            random.nextBytes(bytes)
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            entries[token] = CursorState(path, version, offset)
            while (entries.size > MAX_CURSORS) entries.remove(entries.keys.first())
            bytes.fill(0)
            return token
        }

        /**
         * Tokens are reusable for retries: a token stays valid until the
         * directory fingerprint changes, the entry is evicted by the bounded
         * cache, or the UserService restarts.  All three cases surface as
         * [INVALID_CURSOR] to the caller.
         */
        fun resolve(token: String, path: String, version: String): Int? {
            if (token.length !in 1..MAX_CURSOR_BYTES || !token.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
                return null
            }
            val state = entries[token] ?: return null
            return state.takeIf { it.path == path && it.version == version }?.offset
        }

        private data class CursorState(val path: String, val version: String, val offset: Int)
    }

    internal sealed interface ReadChunkResult {
        data class Success(
            val path: String,
            val bytes: ByteArray,
            val version: String,
            val offsetBytes: Long,
            val totalBytes: Long,
            val eof: Boolean,
        ) : ReadChunkResult

        data class Failure(val code: String) : ReadChunkResult
    }

    private fun createAndSync(file: Path, bytes: ByteArray) {
        val channel = try {
            Files.newByteChannel(
                file,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: IOException) {
            throw WorkspaceFailure(OPERATION_UNAVAILABLE)
        }
        channel.use {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) it.write(buffer)
            if (it is FileChannel) it.force(true)
        }
        if (Files.size(file) != bytes.size.toLong()) throw WorkspaceFailure(WRITE_UNVERIFIED)
    }

    private fun strictUtf8(value: String): ByteArray = try {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = encoder.encode(CharBuffer.wrap(value))
        ByteArray(encoded.remaining()).also { encoded.get(it) }
    } catch (_: CharacterCodingException) {
        throw WorkspaceFailure(INVALID_CONTENT)
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        throw WorkspaceFailure(INVALID_CONTENT)
    }

    private fun relativePath(segments: List<String>): String = segments.joinToString("/")

    private data class Usage(val files: Int, val bytes: Long, val entries: Int)

    private class WorkspaceFailure(val code: String) : Exception()

    companion object {
        internal const val WORKSPACE_LABEL = "Download/MobileAgentRuntime-Shizuku"
        internal const val MAX_PATH_BYTES = 512
        internal const val MAX_SEGMENT_BYTES = 120
        internal const val MAX_PATH_DEPTH = 16
        /** Individual files may be larger than one Binder/read chunk. */
        internal const val MAX_FILE_BYTES = 16 * 1024 * 1024
        /** Typed reads are bounded to one 256 KiB chunk at the service boundary. */
        internal const val MAX_READ_BYTES = 256 * 1024
        internal const val MAX_PATCH_BYTES = 256 * 1024
        internal const val MAX_PATCH_SOURCE_BYTES = 16 * 1024 * 1024
        internal const val MAX_VERSION_CHARS = 128
        internal const val MAX_CURSOR_BYTES = 512
        internal const val CURSOR_BYTES = 32
        internal const val CURSOR_DISPLAY_BYTES = 43
        internal const val MAX_CURSORS = 1024
        internal const val MAX_TOTAL_BYTES = 4L * 1024 * 1024
        internal const val MAX_FILES = 128
        internal const val MAX_ENTRIES = 512
        internal const val MAX_DIRECTORY_ENTRIES = 256
        /**
         * Resource-protection ceiling on the immediate listable children of one
         * directory.  This is deliberately separated from the per-page
         * [MAX_DIRECTORY_ENTRIES] budget: pagination must page through every
         * entry below this ceiling instead of failing up front.  Directories
         * beyond the ceiling fail with typed `LIMIT`.
         */
        internal const val MAX_LISTED_ENTRIES = 8192
        internal const val MAX_OUTPUT_BYTES = 32 * 1024
        private const val MAX_REPORTED_SKIPPED_ENTRIES = 100_000

        private const val WARNING_SYMLINK_SKIPPED = "SYMLINK_SKIPPED"
        private const val WARNING_UNSUPPORTED_ENTRY_SKIPPED = "UNSUPPORTED_ENTRY_SKIPPED"
        private const val WARNING_TRANSIENT_ENTRY_SKIPPED = "TRANSIENT_ENTRY_SKIPPED"
        private const val WARNING_UNREADABLE_ENTRY_SKIPPED = "UNREADABLE_ENTRY_SKIPPED"

        const val INVALID_PATH = "INVALID_PATH"
        const val INVALID_CONTENT = "INVALID_CONTENT"
        const val FILE_TOO_LARGE = "FILE_TOO_LARGE"
        const val LIMIT = "LIMIT"
        const val OUTSIDE_ROOT = "OUTSIDE_ROOT"
        const val SYMLINK_REJECTED = "SYMLINK_REJECTED"
        const val NOT_FOUND = "NOT_FOUND"
        const val TARGET_EXISTS = "TARGET_EXISTS"
        const val NON_EMPTY_DIRECTORY = "NON_EMPTY_DIRECTORY"
        const val UNSUPPORTED_ENTRY = "UNSUPPORTED_ENTRY"
        const val PERMISSION_DENIED = "PERMISSION_DENIED"
        const val OPERATION_UNAVAILABLE = "OPERATION_UNAVAILABLE"
        const val ATOMIC_REPLACE_UNAVAILABLE = "ATOMIC_REPLACE_UNAVAILABLE"
        const val WRITE_UNVERIFIED = "WRITE_UNVERIFIED"
        const val OUTPUT_LIMIT = "OUTPUT_LIMIT"
        const val UNKNOWN_OUTCOME = "UNKNOWN_OUTCOME"
        const val MOVE_INTO_SELF = "MOVE_INTO_SELF"
        const val INVALID_CURSOR = "INVALID_CURSOR"
        const val INVALID_VERSION = "INVALID_VERSION"
        const val OFFSET_OUT_OF_RANGE = "OFFSET_OUT_OF_RANGE"
        const val CONFLICT = "CONFLICT"
        const val INVALID_PATCH = "INVALID_PATCH"
        const val UNSUPPORTED = "UNSUPPORTED"
        const val PATCH_FORMAT_REPLACE = "REPLACE"
        const val PATCH_FORMAT_UNIFIED_DIFF = "UNIFIED_DIFF"

        internal val FIXED_ROOT = File("/storage/emulated/0/Download/MobileAgentRuntime-Shizuku")
    }
}
