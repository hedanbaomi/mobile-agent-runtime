// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.LinkedHashMap
import runtime.mobileagent.domain.CapabilityId

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
)

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
)

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
