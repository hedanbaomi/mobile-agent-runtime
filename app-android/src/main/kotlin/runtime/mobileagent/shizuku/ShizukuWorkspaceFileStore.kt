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

    fun list(relativePath: String?): String = synchronized(lock) {
        guarded("list") {
            val segments = parsePath(relativePath, allowRoot = true)
            val directory = resolve(segments)
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                return@guarded success("list", segments).put("entries", JSONArray())
            }
            requireDirectory(directory)
            val children = directoryEntries(directory)
            if (children.size > MAX_DIRECTORY_ENTRIES) throw WorkspaceFailure(LIMIT)
            val entries = JSONArray()
            children.sortedBy { it.fileName.toString() }.forEach { child ->
                if (Files.isSymbolicLink(child)) {
                    if (skipSymlinksInList) return@forEach
                    rejectSymbolicLink(child)
                }
                val childSegments = segments + child.fileName.toString()
                val entry = JSONObject()
                    .put("path", relativePath(childSegments))
                when {
                    Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) -> entry.put("type", "directory")
                    Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) -> {
                        val size = Files.size(child)
                        if (size > MAX_FILE_BYTES) throw WorkspaceFailure(FILE_TOO_LARGE)
                        entry.put("type", "file").put("bytes", size)
                    }
                    else -> throw WorkspaceFailure(UNSUPPORTED_ENTRY)
                }
                entries.put(entry)
            }
            success("list", segments).put("entries", entries)
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
            if (bytes > MAX_FILE_BYTES) throw WorkspaceFailure(FILE_TOO_LARGE)
            success("stat", segments)
                .put("type", type)
                .put("bytes", bytes)
        }
    }

    fun read(relativePath: String?, maxBytes: Int): String = synchronized(lock) {
        guarded("read") {
            if (maxBytes !in 1..MAX_READ_BYTES) throw WorkspaceFailure(LIMIT)
            val segments = parsePath(relativePath, allowRoot = false)
            val file = resolve(segments)
            rejectSymbolicLink(file)
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw WorkspaceFailure(NOT_FOUND)
            }
            val size = Files.size(file)
            if (size > MAX_FILE_BYTES) throw WorkspaceFailure(FILE_TOO_LARGE)
            if (size > maxBytes.toLong()) throw WorkspaceFailure(LIMIT)
            val bytes = readBounded(file, maxBytes)
            val text = decodeUtf8(bytes)
            success("read", segments)
                .put("bytes", bytes.size)
                .put("text", text)
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
                    Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
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
                    val options = if (replaceExisting) {
                        arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } else {
                        arrayOf(StandardCopyOption.ATOMIC_MOVE)
                    }
                    Files.move(source, destination, *options)
                } catch (_: AtomicMoveNotSupportedException) {
                    throw WorkspaceFailure(ATOMIC_REPLACE_UNAVAILABLE)
                } catch (_: UnsupportedOperationException) {
                    throw WorkspaceFailure(ATOMIC_REPLACE_UNAVAILABLE)
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
        internal const val MAX_FILE_BYTES = 256 * 1024
        internal const val MAX_READ_BYTES = 24 * 1024
        internal const val MAX_TOTAL_BYTES = 4L * 1024 * 1024
        internal const val MAX_FILES = 128
        internal const val MAX_ENTRIES = 512
        internal const val MAX_DIRECTORY_ENTRIES = 256
        internal const val MAX_OUTPUT_BYTES = 32 * 1024

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

        internal val FIXED_ROOT = File("/storage/emulated/0/Download/MobileAgentRuntime-Shizuku")
    }
}
