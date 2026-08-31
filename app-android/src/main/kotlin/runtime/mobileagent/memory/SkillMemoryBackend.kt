// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.memory

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
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Hard limits for one Skill memory space.  The limits are deliberately supplied to the
 * backend, rather than read from model input, so an integration can choose a smaller policy
 * without changing the file protocol.
 */
data class SkillMemoryLimits(
    val maxTotalBytes: Long = 32L * 1024 * 1024,
    val maxFileBytes: Long = 512L * 1024,
    val maxEntries: Int = 1024,
    val maxReadBytes: Int = 512 * 1024,
    val maxPathBytes: Int = 256,
    val maxSearchQueryBytes: Int = 256,
    val maxSearchResults: Int = 20,
    val maxSearchSnippetBytes: Int = 512,
    val maxSearchOutputBytes: Int = 32 * 1024,
    val maxOutputBytes: Int = 128 * 1024,
) {
    init {
        require(maxTotalBytes > 0)
        require(maxFileBytes in 1..maxTotalBytes)
        require(maxEntries > 0)
        require(maxReadBytes.toLong() in 1L..maxFileBytes.coerceAtMost(Int.MAX_VALUE.toLong()))
        require(maxPathBytes > 0)
        require(maxSearchQueryBytes > 0)
        require(maxSearchResults > 0)
        require(maxSearchSnippetBytes > 0)
        require(maxSearchOutputBytes > 0)
        require(maxOutputBytes > 0)
    }
}

/**
 * The binding is host-owned metadata.  It is intentionally not a model-facing DTO: installId,
 * packageHash, grantId and the real memory root never appear in a tool result.
 */
data class SkillMemoryBinding(
    val installId: String,
    val packageHash: String,
    val memorySpaceId: String = "default",
    val agentId: String = "",
    val snapshotId: String = "",
    val capabilities: Set<String> = emptySet(),
    val enabled: Boolean = true,
    val grantId: String = "",
    val grantRevision: Int = 1,
    /** Changes to memory metadata invalidate an approval even if the grant revision is stable. */
    val memoryMetadataRevision: Long = 0L,
)

data class SkillMemoryEntry(
    val path: String,
    val bytes: Long,
    val version: String,
)

data class SkillMemoryListResult(val entries: List<SkillMemoryEntry>)

data class SkillMemorySearchHit(
    val path: String,
    val line: Int,
    val snippet: String,
)

data class SkillMemorySearchResult(
    val hits: List<SkillMemorySearchHit>,
    val truncated: Boolean,
)

data class SkillMemoryReadResult(
    val path: String,
    val text: String,
    val bytes: Int,
    val version: String,
)

data class SkillMemoryWriteResult(
    val path: String,
    val bytes: Int,
    val version: String,
    val created: Boolean,
)

data class SkillMemoryDeleteResult(
    val path: String,
    val version: String,
    val deleted: Boolean,
)

enum class SkillMemoryFailureCode {
    INVALID_PATH,
    INVALID_CONTENT,
    INVALID_QUERY,
    NOT_FOUND,
    CONFLICT,
    SYMLINK_FORBIDDEN,
    ROOT_OPERATION_FORBIDDEN,
    FILE_TOO_LARGE,
    QUOTA_EXCEEDED,
    ENTRY_LIMIT,
    OUTPUT_LIMIT,
    IO_ERROR,
}

/** Internal failure with a stable, path-free code for the model-facing executor. */
class SkillMemoryException(val code: SkillMemoryFailureCode) : Exception(code.name)

/**
 * Pure, storage-free validation shared by the model-facing approval boundary and the backend.
 * Invalid paths must be rejected before an approval is requested, while the backend repeats the
 * same check before resolving anything on disk.
 */
internal object SkillMemoryPathPolicy {
    const val MEMORY_FILE = "MEMORY.md"
    const val JOURNAL_DIR = "journal"
    private val journalPattern = Regex("\\d{4}-\\d{2}-\\d{2}\\.md")

    fun validate(raw: String, maxPathBytes: Int): String {
        val encoded = strictUtf8(raw) ?: throw SkillMemoryException(SkillMemoryFailureCode.INVALID_PATH)
        if (raw.isEmpty() || encoded.size > maxPathBytes || raw.indexOf('\u0000') >= 0 ||
            raw.indexOf('\\') >= 0 || raw.startsWith('/') || raw.contains("//") ||
            raw.contains(':') || raw.any(Char::isISOControl)
        ) {
            throw SkillMemoryException(SkillMemoryFailureCode.INVALID_PATH)
        }
        if (raw == MEMORY_FILE) return raw
        if (!raw.startsWith("$JOURNAL_DIR/") || raw.count { it == '/' } != 1) {
            throw SkillMemoryException(SkillMemoryFailureCode.INVALID_PATH)
        }
        val name = raw.removePrefix("$JOURNAL_DIR/")
        if (!isJournalName(name)) throw SkillMemoryException(SkillMemoryFailureCode.INVALID_PATH)
        return raw
    }

    fun isJournalName(name: String): Boolean {
        if (!name.matches(journalPattern)) return false
        return try {
            LocalDate.parse(name.removeSuffix(".md"))
            true
        } catch (_: DateTimeParseException) {
            false
        }
    }

    private fun strictUtf8(value: String): ByteArray? = try {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = encoder.encode(CharBuffer.wrap(value))
        ByteArray(buffer.remaining()).also(buffer::get)
    } catch (_: CharacterCodingException) {
        null
    }
}

/**
 * Application-private Skill memory backend.
 *
 * The backend is a factory for opaque spaces.  Namespace paths are always SHA-256 output and
 * are never composed from an install ID, package hash, memory-space ID, Agent ID, or snapshot
 * ID.  A package upgrade therefore naturally selects a new empty namespace; no migration or
 * fallback lookup exists here.
 */
@Deprecated("Use the canonical data/sqlite SkillMemoryRepository through SkillMemoryRepositoryPort")
class SkillMemoryBackend(
    private val appPrivateRoot: File,
    private val limits: SkillMemoryLimits = SkillMemoryLimits(),
) {
    /** Convenience constructor for a single space, useful for small host integrations/tests. */
    constructor(
        appPrivateRoot: File,
        installId: String,
        packageHash: String,
        memorySpaceId: String = "default",
        limits: SkillMemoryLimits = SkillMemoryLimits(),
    ) : this(appPrivateRoot, limits) {
        defaultBinding = SkillMemoryBinding(installId, packageHash, memorySpaceId)
    }

    private var defaultBinding: SkillMemoryBinding? = null

    /** Open an independently namespaced memory space. */
    fun space(binding: SkillMemoryBinding): SkillMemorySpace =
        SkillMemorySpace(appPrivateRoot, binding, limits)

    /** Convenience operations for the single-space constructor. */
    fun list(): SkillMemoryListResult = singleSpace().list()
    fun read(path: String, maxBytes: Int = limits.maxReadBytes): SkillMemoryReadResult = singleSpace().read(path, maxBytes)
    fun search(query: String, maxResults: Int = limits.maxSearchResults): SkillMemorySearchResult = singleSpace().search(query, maxResults)
    fun append(path: String, text: String, expectedVersion: String? = null): SkillMemoryWriteResult =
        singleSpace().append(path, text, expectedVersion)
    fun replace(path: String, text: String, expectedVersion: String? = null): SkillMemoryWriteResult =
        singleSpace().replace(path, text, expectedVersion)
    fun write(path: String, text: String, expectedVersion: String? = null): SkillMemoryWriteResult =
        singleSpace().write(path, text, expectedVersion)
    fun delete(path: String, expectedVersion: String? = null): SkillMemoryDeleteResult =
        singleSpace().delete(path, expectedVersion)

    private fun singleSpace(): SkillMemorySpace =
        defaultBinding?.let(::space) ?: throw IllegalStateException("A binding is required")

    companion object {
        private const val NAMESPACE_CONTAINER = "skill-memory"

        /** Stable opaque namespace; all three inputs are included in the preimage. */
        @JvmStatic
        fun namespaceFor(installId: String, packageHash: String, memorySpaceId: String): String =
            SkillMemoryHandle.namespaceFor(installId, packageHash, memorySpaceId)

        /** Stable opaque handle.  The executor resolves it to a binding; it is not a path. */
        @JvmStatic
        fun opaqueHandleFor(installId: String, packageHash: String, memorySpaceId: String): String =
            SkillMemoryHandle.forBinding(installId, packageHash, memorySpaceId)

        internal fun containerName(): String = NAMESPACE_CONTAINER
    }
}

/** Opaque identity derivation shared by canonical adapters and the legacy backend. */
object SkillMemoryHandle {
    private const val NAMESPACE_DOMAIN = "mobile-agent-runtime/skill-memory/v1"
    private const val HANDLE_DOMAIN = "mobile-agent-runtime/skill-memory-handle/v1"

    @JvmStatic
    fun namespaceFor(installId: String, packageHash: String, memorySpaceId: String): String =
        sha256("$NAMESPACE_DOMAIN\u0000${installId.length}:$installId\u0000${packageHash.length}:$packageHash\u0000${memorySpaceId.length}:$memorySpaceId")

    @JvmStatic
    fun forBinding(installId: String, packageHash: String, memorySpaceId: String): String =
        sha256("$HANDLE_DOMAIN\u0000${installId.length}:$installId\u0000${packageHash.length}:$packageHash\u0000${memorySpaceId.length}:$memorySpaceId")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}

/** One opaque Skill memory namespace opened by [SkillMemoryBackend.space]. */
class SkillMemorySpace internal constructor(
    private val appPrivateRoot: File,
    private val binding: SkillMemoryBinding,
    private val limits: SkillMemoryLimits,
) {
    private data class Usage(val entries: Int, val bytes: Long)

    private val lock: Any = GLOBAL_LOCK

    fun namespace(): String = SkillMemoryHandle.namespaceFor(binding.installId, binding.packageHash, binding.memorySpaceId)

    fun list(): SkillMemoryListResult = synchronized(lock) {
        val root = namespacePath(create = false) ?: return@synchronized SkillMemoryListResult(emptyList())
        val usage = inspect(root)
        if (usage.entries > limits.maxEntries) throw SkillMemoryException(SkillMemoryFailureCode.ENTRY_LIMIT)
        val entries = allowedFiles(root).map { file ->
            val bytes = checkedFileSize(file)
            SkillMemoryEntry(
                relativePath(root, file),
                bytes,
                currentVersion(file) ?: throw SkillMemoryException(SkillMemoryFailureCode.NOT_FOUND),
            )
        }.sortedBy { it.path }
        SkillMemoryListResult(entries)
    }

    /**
     * Literal, case-sensitive search over strict UTF-8 lines.  Only bounded relative paths,
     * one-based line numbers and short context snippets leave this backend.
     */
    fun search(query: String, maxResults: Int = limits.maxSearchResults): SkillMemorySearchResult = synchronized(lock) {
        val queryBytes = try {
            encodeUtf8(query)
        } catch (error: SkillMemoryException) {
            if (error.code == SkillMemoryFailureCode.INVALID_CONTENT) {
                throw SkillMemoryException(SkillMemoryFailureCode.INVALID_QUERY)
            }
            throw error
        }
        if (query.isBlank() || query.any(Char::isISOControl) || queryBytes.size > limits.maxSearchQueryBytes) {
            throw SkillMemoryException(SkillMemoryFailureCode.INVALID_QUERY)
        }
        if (maxResults !in 1..limits.maxSearchResults) {
            throw SkillMemoryException(SkillMemoryFailureCode.INVALID_QUERY)
        }
        val root = namespacePath(create = false) ?: return@synchronized SkillMemorySearchResult(emptyList(), false)
        inspect(root)
        val hits = mutableListOf<SkillMemorySearchHit>()
        var outputBytes = 0
        var truncated = false
        for (file in allowedFiles(root).sortedBy { relativePath(root, it) }) {
            if (truncated) break
            val path = relativePath(root, file)
            val content = decodeUtf8(readBounded(file, limits.maxFileBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
            val lines = content.split('\n')
            for ((lineIndex, rawLine) in lines.withIndex()) {
                val line = rawLine.removeSuffix("\r")
                val matchStart = line.indexOf(query)
                if (matchStart < 0) continue
                if (hits.size >= maxResults) {
                    truncated = true
                    break
                }
                val snippet = searchSnippet(line, matchStart, query.length)
                val hitBytes = searchHitJsonBytes(path, lineIndex + 1, snippet) + if (hits.isEmpty()) 0 else 1
                if (SEARCH_RESULT_SHELL_BYTES + outputBytes + hitBytes > limits.maxSearchOutputBytes) {
                    truncated = true
                    break
                }
                hits += SkillMemorySearchHit(path, lineIndex + 1, snippet)
                outputBytes += hitBytes
            }
        }
        SkillMemorySearchResult(hits, truncated)
    }

    fun read(path: String, maxBytes: Int = limits.maxReadBytes): SkillMemoryReadResult = synchronized(lock) {
        val normalized = allowedFilePath(path)
        if (maxBytes !in 1..limits.maxReadBytes) throw SkillMemoryException(SkillMemoryFailureCode.FILE_TOO_LARGE)
        val root = namespacePath(create = false) ?: throw SkillMemoryException(SkillMemoryFailureCode.NOT_FOUND)
        inspect(root)
        val file = resolve(root, normalized)
        requireRegularFile(file)
        val size = Files.size(file)
        if (size > limits.maxFileBytes || size > maxBytes) throw SkillMemoryException(SkillMemoryFailureCode.FILE_TOO_LARGE)
        val bytes = readBounded(file, maxBytes)
        val text = decodeUtf8(bytes)
        SkillMemoryReadResult(normalized, text, bytes.size, sha256(bytes))
    }

    fun append(path: String, text: String, expectedVersion: String? = null): SkillMemoryWriteResult = synchronized(lock) {
        mutate(path, text, expectedVersion, append = true)
    }

    fun replace(path: String, text: String, expectedVersion: String? = null): SkillMemoryWriteResult = synchronized(lock) {
        mutate(path, text, expectedVersion, append = false)
    }

    /** Compatibility alias for host code; agent-facing tools use [append] and [replace]. */
    fun write(path: String, text: String, expectedVersion: String? = null): SkillMemoryWriteResult = replace(path, text, expectedVersion)

    private fun mutate(path: String, text: String, expectedVersion: String?, append: Boolean): SkillMemoryWriteResult {
        val normalized = allowedFilePath(path)
        val suppliedBytes = encodeUtf8(text)
        if (suppliedBytes.size.toLong() > limits.maxFileBytes) throw SkillMemoryException(SkillMemoryFailureCode.FILE_TOO_LARGE)
        val root = namespacePath(create = true) ?: throw SkillMemoryException(SkillMemoryFailureCode.IO_ERROR)
        val parent = if (normalized == MEMORY_FILE) root else checkedDirectory(root.resolve(JOURNAL_DIR), create = true)
        val target = resolve(root, normalized)
        val current = currentVersion(target)
        if (!expectedMatches(expectedVersion, current)) throw SkillMemoryException(SkillMemoryFailureCode.CONFLICT)
        val bytes = if (append && current != null) {
            val existing = decodeUtf8(readBounded(target, limits.maxFileBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
            encodeUtf8(existing + text)
        } else {
            suppliedBytes
        }
        if (bytes.size.toLong() > limits.maxFileBytes) throw SkillMemoryException(SkillMemoryFailureCode.FILE_TOO_LARGE)
        val usage = inspect(root)
        val newEntries = usage.entries + if (current == null) 1 else 0
        val newBytes = usage.bytes - (current?.let { Files.size(target) } ?: 0L) + bytes.size
        if (newEntries > limits.maxEntries) throw SkillMemoryException(SkillMemoryFailureCode.ENTRY_LIMIT)
        if (newBytes > limits.maxTotalBytes) throw SkillMemoryException(SkillMemoryFailureCode.QUOTA_EXCEEDED)
        atomicReplace(parent, target, bytes, current)
        val version = sha256(bytes)
        return SkillMemoryWriteResult(normalized, bytes.size, version, current == null)
    }

    fun delete(path: String, expectedVersion: String? = null): SkillMemoryDeleteResult = synchronized(lock) {
        val normalized = allowedFilePath(path)
        val root = namespacePath(create = false) ?: throw SkillMemoryException(SkillMemoryFailureCode.NOT_FOUND)
        inspect(root)
        val target = resolve(root, normalized)
        requireRegularFile(target)
        val current = currentVersion(target) ?: throw SkillMemoryException(SkillMemoryFailureCode.NOT_FOUND)
        if (!expectedMatches(expectedVersion, current)) throw SkillMemoryException(SkillMemoryFailureCode.CONFLICT)
        rejectSymlink(target)
        try {
            Files.delete(target)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw SkillMemoryException(SkillMemoryFailureCode.IO_ERROR)
        } catch (error: SkillMemoryException) {
            throw error
        } catch (_: Exception) {
            throw SkillMemoryException(SkillMemoryFailureCode.IO_ERROR)
        }
        SkillMemoryDeleteResult(normalized, current, deleted = true)
    }

    private fun atomicReplace(parent: Path, target: Path, bytes: ByteArray, expected: String?) {
        rejectSymlink(parent)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) rejectSymlink(target)
        // Re-check immediately before the external side effect.  This catches a concurrent
        // process changing the file between the optimistic version check and the move.
        if (!expectedMatches(expected, currentVersion(target))) throw SkillMemoryException(SkillMemoryFailureCode.CONFLICT)
        val temporary = parent.resolve(".mar-memory-${UUID.randomUUID()}.tmp")
        try {
            writeAndSync(temporary, bytes)
            rejectSymlink(temporary)
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                forceDirectory(parent)
            } catch (_: AtomicMoveNotSupportedException) {
                // A non-atomic replacement could expose a partial memory file; fail closed.
                throw SkillMemoryException(SkillMemoryFailureCode.IO_ERROR)
            } catch (_: UnsupportedOperationException) {
                throw SkillMemoryException(SkillMemoryFailureCode.IO_ERROR)
            }
            rejectSymlink(target)
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) != bytes.size.toLong()) {
                throw SkillMemoryException(SkillMemoryFailureCode.IO_ERROR)
            }
        } catch (error: SkillMemoryException) {
            throw error
        } catch (_: Exception) {
            throw SkillMemoryException(SkillMemoryFailureCode.IO_ERROR)
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    private fun writeAndSync(temporary: Path, bytes: ByteArray) {
        Files.newByteChannel(
            temporary,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            if (channel is FileChannel) channel.force(true)
            else throw SkillMemoryException(SkillMemoryFailureCode.IO_ERROR)
        }
        if (Files.size(temporary) != bytes.size.toLong()) throw SkillMemoryException(SkillMemoryFailureCode.IO_ERROR)
    }

    private fun forceDirectory(directory: Path) {
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // Android providers may not permit opening a directory as a channel.  The file was
            // still fsynced, while unsupported atomic replacement remains a hard failure.
        } catch (_: UnsupportedOperationException) {
            // Same rationale as the IOException branch.
        }
    }

    private fun namespacePath(create: Boolean): Path? {
        val root = checkedRoot()
        val container = root.resolve(SkillMemoryBackend.containerName())
        val namespace = container.resolve(namespace())
        // Check the parent even for a read of a missing namespace.  A symlink at this level must
        // never turn a harmless-looking empty read into traversal outside app-private storage.
        rejectSymlink(container)
        if (!Files.exists(container, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) return null
            checkedDirectory(container, create = true)
        } else {
            checkedDirectory(container, create = false)
        }
        if (!create && !Files.exists(namespace, LinkOption.NOFOLLOW_LINKS)) return null
        checkedDirectory(namespace, create)
        return namespace
    }

    private fun checkedRoot(): Path {
        val root = appPrivateRoot.toPath()
        rejectSymlink(root)
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectories(root)
            } catch (_: Exception) {
                throw SkillMemoryException(SkillMemoryFailureCode.IO_ERROR)
            }
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw SkillMemoryException(SkillMemoryFailureCode.IO_ERROR)
        return root
    }

    private fun checkedDirectory(path: Path, create: Boolean): Path {
        rejectSymlink(path)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) && create) {
            try {
                Files.createDirectory(path)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // Re-check below; another process may have created a symlink instead.
            } catch (_: Exception) {
                throw SkillMemoryException(SkillMemoryFailureCode.IO_ERROR)
            }
        }
        rejectSymlink(path)
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw SkillMemoryException(SkillMemoryFailureCode.IO_ERROR)
        return path
    }

    private fun resolve(root: Path, normalized: String): Path {
        val target = root.resolve(normalized)
        val parent = target.parent ?: throw SkillMemoryException(SkillMemoryFailureCode.INVALID_PATH)
        checkedDirectory(parent, create = false)
        rejectSymlink(target)
        val canonicalRoot = root.toFile().canonicalFile.toPath()
        val canonicalTarget = target.toFile().canonicalFile.toPath()
        if (canonicalTarget != canonicalRoot && !canonicalTarget.startsWith(canonicalRoot)) {
            throw SkillMemoryException(SkillMemoryFailureCode.INVALID_PATH)
        }
        return target
    }

    private fun inspect(root: Path): Usage {
        rejectSymlink(root)
        var entries = 0
        var bytes = 0L
        forEachChild(root) { child ->
            rejectSymlink(child)
            when {
                child.fileName.toString() == MEMORY_FILE -> {
                    requireRegularFile(child)
                    entries++
                    bytes += checkedUtf8FileSize(child)
                }
                child.fileName.toString() == JOURNAL_DIR -> {
                    checkedDirectory(child, create = false)
                    forEachChild(child) { journalFile ->
                        rejectSymlink(journalFile)
                        val name = journalFile.fileName.toString()
                        if (!isJournalName(name)) throw SkillMemoryException(SkillMemoryFailureCode.INVALID_PATH)
                        requireRegularFile(journalFile)
                        entries++
                        bytes += checkedUtf8FileSize(journalFile)
                    }
                }
                else -> throw SkillMemoryException(SkillMemoryFailureCode.INVALID_PATH)
            }
            if (entries > limits.maxEntries) throw SkillMemoryException(SkillMemoryFailureCode.ENTRY_LIMIT)
            if (bytes > limits.maxTotalBytes) throw SkillMemoryException(SkillMemoryFailureCode.QUOTA_EXCEEDED)
        }
        return Usage(entries, bytes)
    }

    private fun allowedFiles(root: Path): List<Path> {
        val files = mutableListOf<Path>()
        forEachChild(root) { child ->
            rejectSymlink(child)
            when {
                child.fileName.toString() == MEMORY_FILE -> {
                    requireRegularFile(child)
                    files.add(child)
                }
                child.fileName.toString() == JOURNAL_DIR -> {
                    checkedDirectory(child, create = false)
                    forEachChild(child) { item ->
                        rejectSymlink(item)
                        if (!isJournalName(item.fileName.toString())) throw SkillMemoryException(SkillMemoryFailureCode.INVALID_PATH)
                        requireRegularFile(item)
                        files.add(item)
                    }
                }
                else -> throw SkillMemoryException(SkillMemoryFailureCode.INVALID_PATH)
            }
        }
        return files
    }

    /**
     * `Stream.toList()` was added in Java 16 and is absent from Android API 31's core library.
     * Iterating the Java 8 stream directly keeps the backend compatible with the app's API floor.
     */
    private fun forEachChild(directory: Path, action: (Path) -> Unit) {
        Files.list(directory).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                action(iterator.next())
            }
        }
    }

    private fun relativePath(root: Path, file: Path): String =
        root.relativize(file).toString().replace(File.separatorChar, '/')

    private fun searchSnippet(line: String, matchStart: Int, queryLength: Int): String {
        val context = 96
        var start = (matchStart - context).coerceAtLeast(0)
        var end = (matchStart + queryLength + context).coerceAtMost(line.length)
        if (start > 0 && line[start].isLowSurrogate()) start--
        if (end < line.length && line[end - 1].isHighSurrogate()) end++
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < line.length) "…" else ""
        val safeFragment = buildString(end - start) {
            line.substring(start, end).forEach { character ->
                if (character.isISOControl()) append(' ') else append(character)
            }
        }
        return utf8Prefix("$prefix$safeFragment$suffix", limits.maxSearchSnippetBytes)
    }

    private fun searchHitJsonBytes(path: String, line: Int, snippet: String): Int =
        "{\"path\":".toByteArray(StandardCharsets.UTF_8).size +
            jsonStringBytes(path) +
            ",\"line\":".toByteArray(StandardCharsets.UTF_8).size +
            line.toString().toByteArray(StandardCharsets.UTF_8).size +
            ",\"snippet\":".toByteArray(StandardCharsets.UTF_8).size +
            jsonStringBytes(snippet) +
            1 // closing brace

    private fun jsonStringBytes(value: String): Int {
        var bytes = value.toByteArray(StandardCharsets.UTF_8).size + 2 // opening and closing quotes
        value.forEach { character ->
            bytes += when {
                character == '"' || character == '\\' -> 1
                character == '\b' || character == '\t' || character == '\n' ||
                    character == '\r' || character == '\u000C' -> 1
                character.isISOControl() -> 5
                else -> 0
            }
        }
        return bytes
    }

    private fun utf8Prefix(value: String, maxBytes: Int): String {
        var end = value.length
        while (end > 0 && value.substring(0, end).toByteArray(StandardCharsets.UTF_8).size > maxBytes) {
            end--
        }
        if (end > 0 && value[end - 1].isHighSurrogate()) end--
        return value.substring(0, end)
    }

    private fun allowedFilePath(raw: String): String {
        return SkillMemoryPathPolicy.validate(raw, limits.maxPathBytes)
    }

    private fun isJournalName(name: String): Boolean = SkillMemoryPathPolicy.isJournalName(name)

    private fun requireRegularFile(path: Path) {
        rejectSymlink(path)
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && path.fileName.toString() == MEMORY_FILE) {
                throw SkillMemoryException(SkillMemoryFailureCode.ROOT_OPERATION_FORBIDDEN)
            }
            throw SkillMemoryException(SkillMemoryFailureCode.NOT_FOUND)
        }
    }

    private fun checkedFileSize(path: Path): Long {
        val size = try { Files.size(path) } catch (_: Exception) { throw SkillMemoryException(SkillMemoryFailureCode.IO_ERROR) }
        if (size > limits.maxFileBytes) throw SkillMemoryException(SkillMemoryFailureCode.FILE_TOO_LARGE)
        return size
    }

    private fun checkedUtf8FileSize(path: Path): Long {
        val size = checkedFileSize(path)
        decodeUtf8(readBounded(path, size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
        return size
    }

    private fun currentVersion(path: Path): String? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        rejectSymlink(path)
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw SkillMemoryException(SkillMemoryFailureCode.NOT_FOUND)
        val bytes = readBounded(path, limits.maxFileBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        decodeUtf8(bytes)
        return sha256(bytes)
    }

    private fun readBounded(path: Path, maximum: Int): ByteArray {
        try {
            val output = ByteArrayOutputStream(minOf(Files.size(path), maximum.toLong()).toInt() + 1)
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (output.size() + count > maximum) throw SkillMemoryException(SkillMemoryFailureCode.FILE_TOO_LARGE)
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        } catch (error: SkillMemoryException) {
            throw error
        } catch (_: Exception) {
            throw SkillMemoryException(SkillMemoryFailureCode.IO_ERROR)
        }
    }

    private fun encodeUtf8(value: String): ByteArray {
        return try {
            val encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val buffer = encoder.encode(CharBuffer.wrap(value))
            ByteArray(buffer.remaining()).also(buffer::get)
        } catch (_: CharacterCodingException) {
            throw SkillMemoryException(SkillMemoryFailureCode.INVALID_CONTENT)
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw SkillMemoryException(SkillMemoryFailureCode.INVALID_CONTENT)
        }
    }

    private fun rejectSymlink(path: Path) {
        if (Files.isSymbolicLink(path)) throw SkillMemoryException(SkillMemoryFailureCode.SYMLINK_FORBIDDEN)
    }

    private fun expectedMatches(expected: String?, current: String?): Boolean {
        return when (expected) {
            // Omitted expectedVersion means the caller intentionally opted out of optimistic
            // checking.  The explicit markers are reserved for create-only writes/deletes.
            null -> true
            "missing", "0" -> current == null
            else -> expected == current
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private const val MEMORY_FILE = SkillMemoryPathPolicy.MEMORY_FILE
        private const val JOURNAL_DIR = SkillMemoryPathPolicy.JOURNAL_DIR
        private val SEARCH_RESULT_SHELL_BYTES =
            "{\"hits\":[],\"truncated\":false}".toByteArray(StandardCharsets.UTF_8).size
        private val GLOBAL_LOCK = Any()
    }
}

/** Explicit alias for DI code that wants to name the Android storage implementation. */
@Deprecated("Use the canonical data/sqlite SkillMemoryRepository through SkillMemoryRepositoryPort")
typealias AppPrivateSkillMemoryBackend = SkillMemoryBackend
