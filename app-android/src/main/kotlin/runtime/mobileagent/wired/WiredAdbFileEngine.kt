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
import java.util.UUID

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
) : PrivilegedFileEngine, RootScopedPrivilegedFileEngine {
    private val lock = Any()
    private val rootPath = root.toAbsolutePath().normalize()

    init {
        require(rootPath.isAbsolute)
    }

    override fun forRoot(rootPath: String, fullDevice: Boolean): PrivilegedFileEngine {
        WiredAdbAbsolutePathPolicy.parse(rootPath)
        return NioPrivilegedFileEngine(Paths.get(rootPath), fullDevice)
    }

    override fun execute(request: WiredAdbFileRequest): WiredAdbFileEngineResult = synchronized(lock) {
        try {
            when (request.operation) {
                WiredAdbFileOperation.LIST -> success(list(request))
                WiredAdbFileOperation.STAT -> success(stat(request))
                WiredAdbFileOperation.READ_TEXT -> success(read(request))
                WiredAdbFileOperation.WRITE_TEXT -> success(write(request))
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
        val children = entries(directory)
        val sortedChildren = children.sortedBy { it.fileName.toString() }
        val output = sortedChildren.take(WIRED_MAX_DIRECTORY_ENTRIES).mapNotNull { child ->
            // Symlinks are not traversable entries. Omit them from directory
            // browsing rather than turning a safe directory listing into a
            // path disclosure or an all-or-nothing failure.
            if (Files.isSymbolicLink(child)) return@mapNotNull null
            runCatching {
                rejectSymlink(child)
                val childSegments = segments + child.fileName.toString()
                when {
                    Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) ->
                        WiredAdbFileEntry(pathOf(childSegments), WiredAdbEntryType.DIRECTORY)
                    Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) ->
                        WiredAdbFileEntry(pathOf(childSegments), WiredAdbEntryType.FILE, Files.size(child))
                    else -> null
                }
            }.getOrNull()
        }
        return WiredAdbFileResult(
            WiredAdbFileOperation.LIST,
            pathOf(segments),
            entries = output,
            truncated = sortedChildren.size > WIRED_MAX_DIRECTORY_ENTRIES,
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
        return WiredAdbFileResult(
            operation = WiredAdbFileOperation.STAT,
            relativePath = pathOf(segments),
            entries = listOf(WiredAdbFileEntry(pathOf(segments), type, Files.size(target).takeIf { type == WiredAdbEntryType.FILE })),
            bytes = Files.size(target).takeIf { type == WiredAdbEntryType.FILE },
        )
    }

    private fun read(request: WiredAdbFileRequest): WiredAdbFileResult {
        val segments = WiredAdbPathPolicy.parse(request.relativePath, allowRoot = false)
        val file = resolve(segments)
        rejectSymlink(file)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw EngineFailure(ERR_NOT_FOUND)
        val size = Files.size(file)
        if (size > WIRED_MAX_FILE_BYTES || size > request.maxBytes) throw EngineFailure(ERR_LIMIT)
        val bytes = readBounded(file, request.maxBytes)
        val text = decodeUtf8(bytes)
        return WiredAdbFileResult(WiredAdbFileOperation.READ_TEXT, pathOf(segments), text = text, bytes = bytes.size.toLong())
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
        }
        val usage = inspectUsage()
        val oldBytes = if (existed) Files.size(target) else 0L
        if (usage.files + (if (existed) 0 else 1) > WIRED_MAX_FILES ||
            usage.bytes - oldBytes + content.size > WIRED_MAX_TOTAL_BYTES
        ) throw EngineFailure(ERR_LIMIT)
        val temporary = parent.resolve(".mar-wired-${UUID.randomUUID()}.tmp")
        try {
            createAndSync(temporary, content)
            rejectSymlink(parent)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) rejectSymlink(target)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
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
        return WiredAdbFileResult(WiredAdbFileOperation.WRITE_TEXT, pathOf(segments), bytes = content.size.toLong(), created = !existed, replaced = existed)
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
        try {
            val options = if (request.replaceExisting) {
                arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                arrayOf(StandardCopyOption.ATOMIC_MOVE)
            }
            Files.move(source, destination, *options)
        } catch (_: AtomicMoveNotSupportedException) {
            throw EngineFailure(ERR_ATOMIC_REPLACE_UNAVAILABLE)
        } catch (_: UnsupportedOperationException) {
            throw EngineFailure(ERR_ATOMIC_REPLACE_UNAVAILABLE)
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

    private fun inspectUsage(): Usage {
        if (fullDevice) return Usage(0, 0L, 0)
        if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) return Usage(0, 0L, 0)
        requireDirectory(rootPath)
        var files = 0
        var bytes = 0L
        var itemCount = 0
        fun visit(directory: Path, depth: Int) {
            if (depth > WIRED_MAX_PATH_DEPTH) throw EngineFailure(ERR_LIMIT)
            val children = entries(directory)
            if (children.size > WIRED_MAX_DIRECTORY_ENTRIES) throw EngineFailure(ERR_LIMIT)
            children.forEach { child ->
                rejectSymlink(child)
                itemCount++
                if (itemCount > WIRED_MAX_ENTRIES) throw EngineFailure(ERR_LIMIT)
                when {
                    Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) -> visit(child, depth + 1)
                    Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) -> {
                        val size = Files.size(child)
                        if (size > WIRED_MAX_FILE_BYTES) throw EngineFailure(ERR_LIMIT)
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

    private fun readBounded(file: Path, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(Files.size(file).toInt(), maximum) + 1)
        Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(8 * 1024)
            while (true) {
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (output.size() + count > maximum) throw EngineFailure(ERR_LIMIT)
                output.write(buffer.array(), 0, count)
            }
        }
        return output.toByteArray()
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

    private fun pathOf(segments: List<String>): String = segments.joinToString("/")
    private fun success(result: WiredAdbFileResult): WiredAdbFileEngineResult = WiredAdbFileEngineResult.Success(result)
    private data class Usage(val files: Int, val bytes: Long, val entries: Int)
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
        const val ERR_PERMISSION_DENIED = "PERMISSION_DENIED"
        const val ERR_OPERATION_UNAVAILABLE = "OPERATION_UNAVAILABLE"
        const val ERR_ATOMIC_REPLACE_UNAVAILABLE = "ATOMIC_REPLACE_UNAVAILABLE"
        const val ERR_WRITE_UNVERIFIED = "WRITE_UNVERIFIED"
    }
}
