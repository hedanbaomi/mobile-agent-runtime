// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.LinkedHashMap
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.skills.tooling.WorkspaceListingWarning
import runtime.mobileagent.skills.tooling.WorkspaceListingWarningCode

/** The only backend kinds exposed by the typed workspace layer. */
internal enum class InternalWorkspaceBackendType {
    INTERNAL,
    SAF_TREE,
}

internal enum class InternalWorkspaceEntryType {
    FILE,
    DIRECTORY,
}

/** Canonical capability ids used by Android workspace adapters. */
internal object InternalWorkspaceCapabilities {
    val ENUMERATE = CapabilityId(CapabilityId.WORKSPACE_ENUMERATE)
    val LIST = CapabilityId(CapabilityId.FILE_LIST)
    val STAT = CapabilityId(CapabilityId.FILE_STAT)
    val READ_TEXT = CapabilityId(CapabilityId.FILE_READ_TEXT)
    val WRITE_TEXT = CapabilityId(CapabilityId.FILE_WRITE_TEXT)
    val CREATE_DIRECTORY = CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY)
    val MOVE = CapabilityId(CapabilityId.FILE_MOVE)
    val DELETE = CapabilityId(CapabilityId.FILE_DELETE)
    val APPLY_PATCH = CapabilityId("file.apply_patch")
}

/** Stable, non-path-bearing error codes for model-facing workspace results. */
internal enum class InternalWorkspaceErrorCode {
    INVALID_ARGUMENT,
    INVALID_CURSOR,
    INVALID_PATH,
    PATH_OUT_OF_SCOPE,
    SYMLINK_FORBIDDEN,
    PROVIDER_ALIAS_AMBIGUOUS,
    WORKSPACE_NOT_FOUND,
    ENTRY_NOT_FOUND,
    ENTRY_UNSUPPORTED,
    ENTRY_EXISTS,
    ROOT_OPERATION_FORBIDDEN,
    NON_EMPTY_DIRECTORY,
    READ_ONLY,
    PERMISSION_DENIED,
    GRANT_LOST,
    FILE_TOO_LARGE,
    READ_LIMIT_EXCEEDED,
    QUOTA_EXCEEDED,
    DEPTH_LIMIT_EXCEEDED,
    ENTRY_LIMIT_EXCEEDED,
    INVALID_UTF8,
    OFFSET_OUT_OF_RANGE,
    INVALID_PATCH,
    CONFLICT,
    UNSUPPORTED,
    IO_ERROR,
    UNKNOWN_OUTCOME,
}

/** A bounded error.  It intentionally has no path, URI, provider text, or exception field. */
internal data class InternalWorkspaceError(
    val code: InternalWorkspaceErrorCode,
    val retryable: Boolean = code in setOf(
        InternalWorkspaceErrorCode.IO_ERROR,
        InternalWorkspaceErrorCode.GRANT_LOST,
        InternalWorkspaceErrorCode.UNKNOWN_OUTCOME,
    ),
) {
    val userMessage: String
        get() = when (code) {
            InternalWorkspaceErrorCode.INVALID_ARGUMENT -> "Workspace arguments are invalid."
            InternalWorkspaceErrorCode.INVALID_CURSOR -> "The workspace list cursor is invalid or expired."
            InternalWorkspaceErrorCode.INVALID_PATH -> "Workspace path is invalid."
            InternalWorkspaceErrorCode.PATH_OUT_OF_SCOPE -> "Workspace path is outside the workspace."
            InternalWorkspaceErrorCode.SYMLINK_FORBIDDEN -> "Symbolic links are not allowed in a workspace."
            InternalWorkspaceErrorCode.PROVIDER_ALIAS_AMBIGUOUS -> "The workspace provider returned ambiguous entries."
            InternalWorkspaceErrorCode.WORKSPACE_NOT_FOUND -> "The workspace is unavailable."
            InternalWorkspaceErrorCode.ENTRY_NOT_FOUND -> "The workspace entry was not found."
            InternalWorkspaceErrorCode.ENTRY_UNSUPPORTED -> "The workspace entry type is unsupported."
            InternalWorkspaceErrorCode.ENTRY_EXISTS -> "The workspace entry already exists."
            InternalWorkspaceErrorCode.ROOT_OPERATION_FORBIDDEN -> "The workspace root cannot be changed."
            InternalWorkspaceErrorCode.NON_EMPTY_DIRECTORY -> "Only empty directories can be deleted."
            InternalWorkspaceErrorCode.READ_ONLY -> "The workspace is read-only."
            InternalWorkspaceErrorCode.PERMISSION_DENIED -> "Workspace permission was denied."
            InternalWorkspaceErrorCode.GRANT_LOST -> "The workspace permission is no longer available."
            InternalWorkspaceErrorCode.FILE_TOO_LARGE -> "The workspace file is too large."
            InternalWorkspaceErrorCode.READ_LIMIT_EXCEEDED -> "The workspace read limit was exceeded."
            InternalWorkspaceErrorCode.QUOTA_EXCEEDED -> "The workspace quota was exceeded."
            InternalWorkspaceErrorCode.DEPTH_LIMIT_EXCEEDED -> "The workspace path is too deep."
            InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED -> "The workspace entry limit was exceeded."
            InternalWorkspaceErrorCode.INVALID_UTF8 -> "Workspace text is not valid UTF-8."
            InternalWorkspaceErrorCode.OFFSET_OUT_OF_RANGE -> "The requested file offset is outside the file."
            InternalWorkspaceErrorCode.INVALID_PATCH -> "The workspace patch is invalid."
            InternalWorkspaceErrorCode.CONFLICT -> "The workspace entry changed; the operation was not applied."
            InternalWorkspaceErrorCode.UNSUPPORTED -> "The workspace provider cannot perform this operation safely."
            InternalWorkspaceErrorCode.IO_ERROR -> "The workspace operation could not be completed."
            InternalWorkspaceErrorCode.UNKNOWN_OUTCOME -> "The workspace operation outcome is unknown."
        }

    override fun toString(): String = code.name
}

internal sealed interface InternalWorkspaceResult<out T> {
    data class Success<T>(val value: T) : InternalWorkspaceResult<T>
    data class Failure(val error: InternalWorkspaceError) : InternalWorkspaceResult<Nothing>
}

internal data class InternalWorkspaceLimits(
    val maxFileBytes: Long = 256L * 1024L,
    val quotaBytes: Long = 4L * 1024L * 1024L,
    val maxPathBytes: Int = 512,
    val maxSegmentBytes: Int = 120,
    val maxPathDepth: Int = 16,
    val maxEntries: Int = 512,
    val maxDirectoryEntries: Int = 256,
    /** Read requests are bounded to the same 256 KiB envelope as text writes/files. */
    val maxReadBytes: Long = 256L * 1024L,
    /**
     * Work budget, separate from the output budget: the maximum directory
     * nodes one operation may *scan* (including fingerprint recursion into
     * subtrees whose entries are never returned).
     */
    val maxScannedEntries: Int = 8192,
    /** Maximum filesystem metadata reads (attributes, sizes) per operation. */
    val maxMetadataReads: Int = 8192,
    /** Wall-clock bound per operation in milliseconds. */
    val maxWallTimeMs: Long = 15_000L,
) {
    init {
        require(maxFileBytes > 0)
        require(maxFileBytes <= Int.MAX_VALUE.toLong())
        require(quotaBytes >= maxFileBytes)
        require(maxPathBytes > 0)
        require(maxSegmentBytes > 0)
        require(maxPathDepth > 0)
        require(maxEntries > 0)
        require(maxDirectoryEntries > 0)
        require(maxReadBytes > 0 && maxReadBytes <= maxFileBytes)
        require(maxScannedEntries > 0)
        require(maxMetadataReads > 0)
        require(maxWallTimeMs > 0)
    }
}

/**
 * Per-operation execution budget.  Output limits ([InternalWorkspaceLimits.maxDirectoryEntries]
 * for returned entries) do not bound execution cost: a shallow list fingerprint
 * recurses into subtrees, and quota checks walk the tree.  This budget caps
 * the work itself.  Exhaustion reports ENTRY_LIMIT_EXCEEDED — a bounded,
 * retryable-as-smaller-scope limit, never an internal error.
 */
internal class ScanBudget(
    private val limits: InternalWorkspaceLimits,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val deadlineMs = clock() + limits.maxWallTimeMs
    var scannedEntries: Int = 0
        private set
    var metadataReads: Int = 0
        private set

    fun visitNodes(count: Int = 1) {
        scannedEntries += count
        if (scannedEntries > limits.maxScannedEntries) InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED.error()
        checkTime()
    }

    fun metadataRead() {
        metadataReads += 1
        if (metadataReads > limits.maxMetadataReads) InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED.error()
        checkTime()
    }

    private fun checkTime() {
        if (clock() > deadlineMs) InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED.error()
    }
}

internal data class InternalWorkspaceDescriptor(
    val id: String,
    val displayName: String,
    val backendType: InternalWorkspaceBackendType,
    val readable: Boolean,
    val writable: Boolean,
    val quotaBytes: Long?,
    val maxFileBytes: Long,
    val maxReadBytes: Long = maxFileBytes,
    val maxEntries: Int = 512,
    val maxDirectoryEntries: Int = 256,
    val enabled: Boolean,
    /** Whether replacing an existing entry has a backend-proven atomic guarantee. */
    val supportsAtomicReplace: Boolean = false,
    /**
     * The operations this backend can actually dispatch.  This stays inside the Android
     * adapter; the shared descriptor intentionally has no operation list, and the adapter
     * exposes this set through the canonical [WorkspaceBackend.capabilities] property.
     */
    val operationCapabilities: Set<CapabilityId> = emptySet(),
    /**
     * Honest mutation semantics.  These names are deliberately distinct: not every
     * backend that can publish a file atomically can also create-if-absent without
     * a time-of-check/time-of-use race, and a metadata version is not a content
     * version.  Tool schema and UI copy must reflect this set, never the generic
     * phrase "atomic write".
     */
    val mutationCapabilities: Set<WorkspaceMutationCapability> = setOf(
        WorkspaceMutationCapability.BEST_EFFORT_CONFLICT_DETECTION,
    ),
    /**
     * Work budget for a single operation, separate from output limits:
     * scanned nodes, metadata reads, and wall-clock milliseconds.  Listing a
     * parent may scan more metadata than it returns; these bounds keep that
     * cost explicit instead of hiding it behind the returned-page size.
     */
    val maxScannedEntries: Int = 8192,
    val maxMetadataReads: Int = 8192,
    val maxWallTimeMs: Long = 15_000L,
)

/**
 * Backend mutation capabilities, from strongest to weakest.  A backend
 * advertises only the properties its commit primitive can prove:
 *
 * - [ATOMIC_PUBLISH]: the final rename is one atomic filesystem operation; a
 *   crash can leave the old or the new content, never a torn mix.  Applies to
 *   replace-publishes of a fully written temporary file — not to streaming
 *   create-only writes, whose claim is visible before its content is complete
 *   (see [WorkspaceAtomicCommit.writeExclusive]).
 * - [CREATE_IF_ABSENT]: a create-only commit fails with ENTRY_EXISTS/TARGET_EXISTS
 *   when the target appears before the commit, and never overwrites it.  The
 *   commit must not silently degrade to REPLACE_EXISTING.  This is
 *   create-atomicity, not full-content atomic visibility.
 * - [COMPARE_AND_REPLACE]: a strict atomic compare-and-swap primitive: the
 *   expected-version check and the replace commit cannot be separated by an
 *   external writer.  A read-compare-rewrite sequence under a process lock
 *   (even with a content hash and a last-moment re-read) does NOT qualify
 *   across processes; backends without a true CAS primitive must advertise
 *   only [BEST_EFFORT_CONFLICT_DETECTION.
 * - [BEST_EFFORT_CONFLICT_DETECTION]: conflicts are detected from metadata or
 *   re-reads only; no content-hash guarantee is made (large files, directory
 *   trees, DocumentsProvider backends).
 * - [RECOVERABLE_EDIT]: the backend retains the pre-edit content/version so a
 *   failed or unwanted edit can be restored.  No current backend claims this;
 *   recovery is provided by version tokens plus read-before-write at the tool
 *   layer.
 */
internal enum class WorkspaceMutationCapability {
    ATOMIC_PUBLISH,
    CREATE_IF_ABSENT,
    COMPARE_AND_REPLACE,
    BEST_EFFORT_CONFLICT_DETECTION,
    RECOVERABLE_EDIT,
}

/**
 * The commit primitives for file publishes and create-only commits.
 * The temporary file must already be fully written and synced by the caller.
 *
 * Platform fact (verified by probe on Linux/JDK and Windows/NTFS,
 * 2026-09-05): a plain `Files.move(tmp, target, ATOMIC_MOVE)` *without*
 * `REPLACE_EXISTING` silently replaces the target instead of failing — on
 * Linux/Android as well as Windows.  The rename therefore cannot prove
 * create-only semantics on any platform.  Earlier comments claiming Linux
 * rename fails when the target exists were wrong and are withdrawn.
 *
 * Consequently:
 * - create-only *file content* goes through [writeExclusive], whose
 *   kernel-atomic exclusive create (`O_CREAT|O_EXCL` / `CREATE_NEW`) either
 *   creates the file with our content or fails with
 *   [FileAlreadyExistsException] on every platform and filesystem, with no
 *   rename and no `REPLACE` flag anywhere on that path.  There is no silent
 *   downgrade to replace semantics by construction.  Note this is
 *   create-atomicity only: the claim becomes visible (0 bytes, then partial
 *   content) while the bytes stream in, so it is NOT a full-content atomic
 *   publish and must never be described as one.
 * - no-clobber *publishes* of a complete temporary file go through
 *   [publishNew], which links (atomic, fails when the target exists) or
 *   falls back to an exclusive-create stream copy — never a bare rename.
 * - no-replace *moves* have no safe primitive and are UNSUPPORTED for every
 *   node kind (scheme A, 3f75 finding B): a non-atomic copy+delete cannot
 *   prove "the deleted source is the copied source", so it could return
 *   success while deleting uncopied concurrent content.  Copy + delete stay
 *   available as two explicit steps.
 */
internal object WorkspaceAtomicCommit {
    fun publish(temporary: Path, target: Path, replaceExisting: Boolean) {
        if (replaceExisting) {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            publishNew(temporary, target)
        }
    }

    /**
     * Publish a fully written [temporary] file at [target] without ever
     * overwriting an existing target.  Prefers a single atomic hard link
     * (fails with [FileAlreadyExistsException] when the target exists);
     * filesystems without link support fall back to a kernel-atomic
     * exclusive-create stream copy.  A racing target always fails the commit
     * with [FileAlreadyExistsException]; the existing target is untouched.
     */
    fun publishNew(temporary: Path, target: Path) {
        try {
            Files.createLink(target, temporary)
            runCatching { Files.deleteIfExists(temporary) }
            return
        } catch (already: java.nio.file.FileAlreadyExistsException) {
            runCatching { Files.deleteIfExists(temporary) }
            throw already
        } catch (_: UnsupportedOperationException) {
            // Fall through to the portable exclusive-create copy below.
        } catch (_: java.io.IOException) {
            // Fall through: the copy path re-attempts with the same
            // no-overwrite guarantee and surfaces residual failures.
        }
        val content = try {
            Files.readAllBytes(temporary)
        } catch (failure: java.io.IOException) {
            runCatching { Files.deleteIfExists(temporary) }
            throw failure
        }
        runCatching { Files.deleteIfExists(temporary) }
        writeExclusive(target, content)
    }

    /**
     * Create [target] with exactly [content] via kernel-atomic exclusive
     * create, or throw [FileAlreadyExistsException] leaving any existing
     * target untouched.  Portable across platforms and filesystems (including
     * ones without hard-link or atomic-rename-if-absent support).  A
     * successfully returned call has fsynced the complete content; a failed
     * call removes the partial claim when it can still prove the claim is
     * its own.
     *
     * Visibility note: this is create-atomicity, not full-content atomic
     * publish.  The claim is visible at 0 bytes while the content streams in,
     * so concurrent readers may observe empty/partial content and a crash may
     * leave a partial claim.  Only the temp-complete → one-step publish path
     * ([publish] with `replaceExisting=true`, [publishNew]) owns
     * ATOMIC_PUBLISH semantics.
     */
    fun writeExclusive(target: Path, content: ByteArray) {
        val channel = try {
            Files.newByteChannel(
                target,
                java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.LinkOption.NOFOLLOW_LINKS,
            )
        } catch (already: java.nio.file.FileAlreadyExistsException) {
            throw already
        }
        val claimKey = runCatching {
            Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey()
        }.getOrNull()
        try {
            channel.use { open ->
                var buffer = java.nio.ByteBuffer.wrap(content)
                while (buffer.hasRemaining()) open.write(buffer)
                (open as? java.nio.channels.FileChannel)?.force(true)
                    ?: throw java.io.IOException("fsync unavailable")
            }
            if (Files.size(target) != content.size.toLong()) throw java.io.IOException("short write")
        } catch (failure: java.io.IOException) {
            removeClaim(target, claimKey)
            throw failure
        }
    }

    private fun removeClaim(target: Path, claimKey: Any?) {
        if (claimKey == null) return // Cannot prove ownership; leave the entry for diagnosis.
        val current = runCatching {
            Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey()
        }.getOrNull() ?: return
        if (current == claimKey) runCatching { Files.deleteIfExists(target) }
    }
}

internal data class InternalWorkspaceEntry(
    val path: String,
    val type: InternalWorkspaceEntryType,
    val sizeBytes: Long?,
    val version: String,
)

internal data class InternalWorkspaceList(
    val path: String,
    val entries: List<InternalWorkspaceEntry>,
    val version: String,
    val nextCursor: String? = null,
    /** Safe category/count metadata for children omitted during best-effort enumeration. */
    val skippedEntries: Int = 0,
    val warnings: List<WorkspaceListingWarning> = emptyList(),
)

/** Bounded, path-free warning accumulator shared by Internal and SAF directory listings. */
internal class InternalWorkspaceListingWarnings {
    private val counts = linkedMapOf<WorkspaceListingWarningCode, Int>()

    fun add(code: WorkspaceListingWarningCode) {
        if (skippedEntries >= runtime.mobileagent.skills.tooling.WorkspaceListing.MAX_SKIPPED_ENTRIES) return
        counts[code] = (counts[code] ?: 0) + 1
    }

    val skippedEntries: Int
        get() = counts.values.sum()

    fun snapshot(): List<WorkspaceListingWarning> = counts.map { (code, count) ->
        WorkspaceListingWarning(code, count)
    }
}

internal data class InternalWorkspaceStat(
    val path: String,
    val type: InternalWorkspaceEntryType,
    val sizeBytes: Long?,
    val version: String,
)

internal data class InternalWorkspaceContent(
    val path: String,
    val bytes: ByteArray,
    val version: String,
    val offsetBytes: Long = 0L,
    val totalBytes: Long? = null,
    val eof: Boolean = true,
) {
    override fun equals(other: Any?): Boolean = other is InternalWorkspaceContent &&
        path == other.path && bytes.contentEquals(other.bytes) && version == other.version &&
        offsetBytes == other.offsetBytes && totalBytes == other.totalBytes && eof == other.eof

    override fun hashCode(): Int {
        var result = 31 * (31 * path.hashCode() + bytes.contentHashCode()) + version.hashCode()
        result = 31 * result + offsetBytes.hashCode()
        result = 31 * result + (totalBytes?.hashCode() ?: 0)
        return 31 * result + eof.hashCode()
    }
}

internal data class InternalWorkspaceWrite(
    val path: String,
    val bytes: Long,
    val created: Boolean,
    val version: String,
)

internal data class InternalWorkspaceDirectoryChange(
    val path: String,
    val created: Boolean,
    val version: String,
)

internal data class InternalWorkspaceDelete(
    val path: String,
    val type: InternalWorkspaceEntryType,
    val deleted: Boolean,
)

internal data class InternalWorkspaceTransfer(
    val sourcePath: String,
    val destinationPath: String,
    val type: InternalWorkspaceEntryType,
    val bytes: Long?,
    val version: String,
)

/**
 * Backend-neutral typed file operations.  Methods are blocking by design; callers must invoke
 * them from their IO dispatcher.  No method accepts an Android URI or a host filesystem path.
 */
internal interface InternalWorkspaceBackendApi {
    val descriptor: InternalWorkspaceDescriptor

    fun list(
        relativePath: String = "",
        maxEntries: Int = 256,
        cursor: String? = null,
    ): InternalWorkspaceResult<InternalWorkspaceList>

    fun stat(relativePath: String): InternalWorkspaceResult<InternalWorkspaceStat>

    fun read(
        relativePath: String,
        maxBytes: Long = 256L * 1024L,
        offsetBytes: Long = 0L,
    ): InternalWorkspaceResult<InternalWorkspaceContent>

    fun applyPatch(
        relativePath: String,
        patch: String,
        expectedVersion: String?,
        format: InternalWorkspacePatchFormat = InternalWorkspacePatchFormat.UNIFIED_DIFF,
    ): InternalWorkspaceResult<InternalWorkspaceWrite>

    fun write(
        relativePath: String,
        content: ByteArray,
        expectedVersion: String? = null,
        replaceExisting: Boolean = true,
    ): InternalWorkspaceResult<InternalWorkspaceWrite>

    fun createDirectory(
        relativePath: String,
        expectedVersion: String? = null,
    ): InternalWorkspaceResult<InternalWorkspaceDirectoryChange>

    fun delete(relativePath: String, expectedVersion: String? = null): InternalWorkspaceResult<InternalWorkspaceDelete>

    fun move(
        sourcePath: String,
        destinationPath: String,
        expectedVersion: String? = null,
        replaceExisting: Boolean = false,
    ): InternalWorkspaceResult<InternalWorkspaceTransfer>

    fun copy(
        sourcePath: String,
        destinationPath: String,
        expectedVersion: String? = null,
        replaceExisting: Boolean = false,
    ): InternalWorkspaceResult<InternalWorkspaceTransfer>
}

internal enum class InternalWorkspacePatchFormat {
    UNIFIED_DIFF,
    REPLACE,
}

/**
 * Process-local opaque list cursors.  The token is random and maps only to
 * backend-owned state; no path, URI, document id, or version is serialized in
 * the model-facing cursor.  State is bounded so an untrusted caller cannot
 * grow this map without limit.
 */
internal class InternalWorkspaceCursorStore(
    private val maxTokens: Int = 512,
) {
    private data class State(val path: String, val fingerprint: String, val offset: Int)

    private val random = SecureRandom()
    private val states = LinkedHashMap<String, State>(maxTokens, 0.75f, true)

    fun issue(path: String, fingerprint: String, offset: Int): String {
        val bytes = ByteArray(24)
        var token: String
        synchronized(states) {
            do {
                random.nextBytes(bytes)
                token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            } while (states.containsKey(token))
            states[token] = State(path, fingerprint, offset)
            while (states.size > maxTokens) states.entries.iterator().apply { next(); remove() }
        }
        return token
    }

    fun resolve(token: String, path: String, fingerprint: String): Int? = synchronized(states) {
        val state = states[token] ?: return@synchronized null
        if (state.path != path || state.fingerprint != fingerprint || state.offset < 0) null else state.offset
    }
}

internal object InternalWorkspaceVersions {
    /** Expected version sentinel for a create-if-absent operation. */
    const val MISSING = "missing"

    fun bytes(bytes: ByteArray): String = digest().digest(bytes).toHex()

    fun text(text: String): InternalWorkspaceResult<ByteArray> = try {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = encoder.encode(CharBuffer.wrap(text))
        InternalWorkspaceResult.Success(ByteArray(encoded.remaining()).also(encoded::get))
    } catch (_: CharacterCodingException) {
        InternalWorkspaceResult.Failure(InternalWorkspaceError(InternalWorkspaceErrorCode.INVALID_UTF8))
    }

    fun decode(bytes: ByteArray): InternalWorkspaceResult<String> = try {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        InternalWorkspaceResult.Success(decoder.decode(ByteBuffer.wrap(bytes)).toString())
    } catch (_: CharacterCodingException) {
        InternalWorkspaceResult.Failure(InternalWorkspaceError(InternalWorkspaceErrorCode.INVALID_UTF8))
    }

    internal fun digest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}

internal fun InternalWorkspaceBackendApi.readText(relativePath: String, maxBytes: Long = 256L * 1024L): InternalWorkspaceResult<String> =
    when (val result = read(relativePath, maxBytes)) {
        is InternalWorkspaceResult.Failure -> result
        is InternalWorkspaceResult.Success -> when (val decoded = InternalWorkspaceVersions.decode(result.value.bytes)) {
            is InternalWorkspaceResult.Failure -> decoded
            is InternalWorkspaceResult.Success -> decoded
        }
    }

internal fun InternalWorkspaceErrorCode.error(): Nothing = throw InternalWorkspaceFailure(InternalWorkspaceError(this))

internal class InternalWorkspaceFailure(val error: InternalWorkspaceError) : RuntimeException()
