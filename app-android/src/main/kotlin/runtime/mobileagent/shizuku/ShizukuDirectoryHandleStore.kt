// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns the only absolute paths used by the Shizuku directory browser.
 *
 * The app receives random handles, never a path.  Every handle is bound to
 * this UserService instance (and therefore to its authenticated Binder
 * session) and is revalidated without following symlinks before use.
 */
internal class ShizukuDirectoryHandleStore {
    internal constructor(deviceRoot: Path = Paths.get("/")) {
        this.deviceRoot = deviceRoot.toAbsolutePath().normalize()
    }

    private val deviceRoot: Path
    private val lock = Any()
    private val random = SecureRandom()
    private val directoryHandles = LinkedHashMap<String, DirectoryHandle>()
    private val directoryTokensByPath = HashMap<Path, String>()
    private val workspaceHandles = LinkedHashMap<String, WorkspaceHandle>()
    private val pageStore = PageStore()

    fun openRoot(maxEntries: Int, continuation: String? = null): String = synchronized(lock) {
        runCatching {
            val root = deviceRoot
            validateDirectory(root)
            val token = directoryToken(root)
            renderDirectory("open_directory_root", token, root, parentToken = null, maxEntries, continuation)
        }.getOrElse { failureForThrowable("open_directory_root", it) }
    }

    fun browse(token: String?, maxEntries: Int, continuation: String? = null): String = synchronized(lock) {
        runCatching {
            val handle = directoryHandles[token]
                ?: return@synchronized failure("browse_directory", INVALID_HANDLE)
            validateDirectory(handle.path)
            val parentToken = handle.parentPath?.let { directoryToken(it) }
            renderDirectory("browse_directory", token.orEmpty(), handle.path, parentToken, maxEntries, continuation)
        }.getOrElse { failureForThrowable("browse_directory", it) }
    }

    fun attach(token: String?): String = synchronized(lock) {
        runCatching {
            val handle = directoryHandles[token]
                ?: return@synchronized failure("attach_directory", INVALID_HANDLE)
            validateDirectory(handle.path)
            attachWorkspace("attach_directory", handle.path)
        }.getOrElse { failureForThrowable("attach_directory", it) }
    }

    /**
     * Reopens a directory from a provider-owned locator.  The old workspace
     * token is intentionally not consulted; a new token is generated for this
     * UserService instance and the locator is validated against the fixed root
     * before any backend is created.
     */
    fun reattach(locator: ByteArray?): String = synchronized(lock) {
        runCatching {
            val decoded = ShizukuRecoveryLocatorCodec.decode(locator)
                ?: throw StoreFailure(RECOVERY_LOCATOR_INVALID)
            validateDirectory(decoded.path)
            if (!ShizukuRecoveryLocatorCodec.identityMatches(decoded.path, decoded.fileKey)) {
                throw StoreFailure(WORKSPACE_NOT_FOUND)
            }
            attachWorkspace("reattach_directory", decoded.path)
        }.getOrElse { failureForThrowable("reattach_directory", it) }
    }

    fun workspace(token: String?): WorkspaceHandle? = synchronized(lock) {
        val handle = workspaceHandles[token] ?: return@synchronized null
        runCatching { validateDirectory(handle.path) }.getOrNull() ?: return@synchronized null
        handle
    }

    /**
     * Renders one bounded picker page.  Directories sort before files so a
     * large file population can never crowd a directory out of reach, and
     * every page carries an opaque continuation when entries remain.
     *
     * The continuation is a process-local random capability bound to this
     * directory and its shallow fingerprint (immediate child names and
     * types).  It encodes no path or offset; a direct-child change, an
     * unknown token, or a service restart fails closed with
     * [INVALID_HANDLE] instead of a shifted page.
     */
    private fun renderDirectory(
        operation: String,
        token: String,
        directory: Path,
        parentToken: String?,
        maxEntries: Int,
        continuation: String?,
    ): String {
        val children = try {
            Files.newDirectoryStream(directory).use { stream -> stream.toList() }
        } catch (_: SecurityException) {
            return failure(operation, PERMISSION_DENIED)
        } catch (_: java.nio.file.AccessDeniedException) {
            return failure(operation, PERMISSION_DENIED)
        } catch (failure: StoreFailure) {
            return failure(operation, failure.code)
        } catch (_: Exception) {
            return failure(operation, DIRECTORY_UNAVAILABLE)
        }
        val fingerprint = pickerFingerprint(children)
        val start = continuation?.let {
            if (!isContinuationToken(it)) return failure(operation, INVALID_HANDLE)
            pageStore.resolve(token, fingerprint, it) ?: return failure(operation, INVALID_HANDLE)
        } ?: 0
        // Directories are the selectable picker subject; files never consume
        // a directory's reachability budget.
        val ordered = children.sortedWith(
            compareByDescending<Path> { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .thenBy { it.fileName.toString() },
        )
        if (start > ordered.size) return failure(operation, INVALID_HANDLE)
        val end = minOf(start + maxEntries, ordered.size)
        val entries = JSONArray()
        var unsafeSkipped = false
        for (index in start until end) {
            val child = ordered[index]
            // Symlinks are deliberately not offered as selectable entries. A
            // caller must choose the canonical directory reached without
            // crossing a symlink boundary.
            if (Files.isSymbolicLink(child)) {
                unsafeSkipped = true
                continue
            }
            val type = when {
                Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) -> "directory"
                Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) -> "file"
                else -> {
                    unsafeSkipped = true
                    continue
                }
            }
            val entry = JSONObject()
                .put("name", child.fileName.toString())
                .put("type", type)
                .put("readable", Files.isReadable(child))
                .put("writable", Files.isWritable(child))
            if (type == "file") {
                runCatching { Files.size(child) }
                    .getOrNull()
                    ?.takeIf { it >= 0L }
                    ?.let { entry.put("bytes", it) }
            } else {
                entry.put("handle", directoryToken(child))
            }
            entries.put(entry)
        }
        val next = if (end < ordered.size) pageStore.issue(token, fingerprint, end) else null
        return bounded(
            JSONObject()
                .put("ok", true)
                .put("operation", operation)
                .put("handle", token)
                .put("parentHandle", parentToken ?: JSONObject.NULL)
                .put("deviceRoot", directory == deviceRoot && deviceRoot == Paths.get("/"))
                .put("entries", entries)
                .put("truncated", next != null || unsafeSkipped)
                .put("continuation", next ?: JSONObject.NULL),
        )
    }

    /**
     * Shallow picker fingerprint: immediate child names and node kinds only.
     * Subdirectories are never descended into, so a huge child subtree cannot
     * poison the parent's pagination.
     */
    private fun pickerFingerprint(children: List<Path>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        children.sortedBy { it.fileName.toString() }.forEach { child ->
            val kind = when {
                Files.isSymbolicLink(child) -> "symlink"
                Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) -> "directory"
                Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) -> "file"
                else -> "unsupported"
            }
            digest.update(child.fileName.toString().toByteArray(StandardCharsets.UTF_8))
            digest.update(kind.toByteArray(StandardCharsets.UTF_8))
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest())
    }

    private fun isContinuationToken(value: String): Boolean =
        value.length in 1..MAX_CONTINUATION_BYTES && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private class PageStore {
        private val random = SecureRandom()
        private val entries = LinkedHashMap<String, PageState>()

        fun issue(directoryToken: String, fingerprint: String, offset: Int): String {
            val bytes = ByteArray(TOKEN_BYTES)
            random.nextBytes(bytes)
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            entries[token] = PageState(directoryToken, fingerprint, offset)
            while (entries.size > MAX_PAGE_CURSORS) entries.remove(entries.keys.first())
            bytes.fill(0)
            return token
        }

        fun resolve(directoryToken: String, fingerprint: String, token: String): Int? {
            val state = entries[token] ?: return null
            return state.takeIf { it.directoryToken == directoryToken && it.fingerprint == fingerprint }?.offset
        }

        private data class PageState(val directoryToken: String, val fingerprint: String, val offset: Int)
    }

    private fun directoryToken(path: Path): String {
        directoryTokensByPath[path]?.let { return it }
        if (directoryHandles.size >= MAX_DIRECTORY_HANDLES) {
            throw StoreFailure(LIMIT)
        }
        val token = newToken()
        val parent = path.parent?.takeUnless { path == Paths.get("/") }
        directoryHandles[token] = DirectoryHandle(path, parent)
        directoryTokensByPath[path] = token
        return token
    }

    private fun validateDirectory(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        if (!isWithinDeviceRoot(normalized)) throw StoreFailure(OUTSIDE_ROOT)
        // Walk only the segments below the device root.  Platform ancestors
        // above it (for example /data/user/0, which is a symlink on current
        // Android releases) are outside this store's threat model; the
        // per-segment symlink and existence checks below the root still
        // prevent escape from the browsed tree.  A device-wide root keeps the
        // previous from-filesystem-root walk unchanged.
        var current = deviceRoot
        val relative = runCatching { deviceRoot.relativize(normalized) }.getOrNull()
            ?: throw StoreFailure(OUTSIDE_ROOT)
        relative.iterator().forEach { segment ->
            current = current.resolve(segment.toString())
            if (Files.isSymbolicLink(current)) throw StoreFailure(SYMLINK_REJECTED)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) throw StoreFailure(WORKSPACE_NOT_FOUND)
        }
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) throw StoreFailure(WORKSPACE_NOT_FOUND)
    }

    private fun isWithinDeviceRoot(path: Path): Boolean = path.startsWith(deviceRoot)

    private fun attachWorkspace(operation: String, path: Path): String {
        if (workspaceHandles.size >= MAX_WORKSPACE_HANDLES) return failure(operation, LIMIT)
        val locator = ShizukuRecoveryLocatorCodec.encode(path)
            ?: return failure(operation, RECOVERY_LOCATOR_INVALID)
        return try {
            val workspaceToken = newToken()
            workspaceHandles[workspaceToken] = WorkspaceHandle(
                path = path,
                rootIsDeviceRoot = isDeviceRoot(path),
                store = ShizukuWorkspaceFileStore(
                    rootFile = path.toFile(),
                    enforceQuota = false,
                    skipSymlinksInList = isDeviceRoot(path),
                ),
            )
            bounded(
                JSONObject()
                    .put("ok", true)
                    .put("operation", operation)
                    .put("workspaceHandle", workspaceToken)
                    .put("rootKind", if (isDeviceRoot(path)) "device" else "directory")
                    .put(
                        "recoveryLocator",
                        Base64.getUrlEncoder().withoutPadding().encodeToString(locator),
                    ),
            )
        } finally {
            locator.fill(0)
        }
    }

    private fun isDeviceRoot(path: Path): Boolean = path == deviceRoot && deviceRoot == Paths.get("/")

    private fun failureForThrowable(operation: String, throwable: Throwable): String = failure(
        operation,
        (throwable as? StoreFailure)?.code
            ?: if (throwable is SecurityException || throwable is java.nio.file.AccessDeniedException) {
                PERMISSION_DENIED
            } else {
                DIRECTORY_UNAVAILABLE
            },
    )

    private class StoreFailure(val code: String) : Exception()

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun bounded(value: JSONObject): String {
        val encoded = value.toString()
        return if (encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_OUTPUT_BYTES) {
            encoded
        } else {
            failure(value.optString("operation", "result"), OUTPUT_LIMIT)
        }
    }

    private fun failure(operation: String, code: String): String = bounded(
        JSONObject()
            .put("ok", false)
            .put("operation", operation)
            .put("code", code),
    )

    internal data class WorkspaceHandle(
        val path: Path,
        val rootIsDeviceRoot: Boolean,
        val store: ShizukuWorkspaceFileStore,
    )

    private data class DirectoryHandle(val path: Path, val parentPath: Path?)

    companion object {
        const val INVALID_HANDLE = "DIRECTORY_HANDLE_INVALID"
        const val DIRECTORY_UNAVAILABLE = "DIRECTORY_UNAVAILABLE"
        const val RECOVERY_LOCATOR_INVALID = "RECOVERY_LOCATOR_INVALID"
        const val WORKSPACE_NOT_FOUND = "WORKSPACE_NOT_FOUND"
        const val OUTSIDE_ROOT = ShizukuWorkspaceFileStore.OUTSIDE_ROOT
        const val SYMLINK_REJECTED = ShizukuWorkspaceFileStore.SYMLINK_REJECTED
        const val PERMISSION_DENIED = ShizukuWorkspaceFileStore.PERMISSION_DENIED
        const val LIMIT = ShizukuWorkspaceFileStore.LIMIT
        const val OUTPUT_LIMIT = ShizukuWorkspaceFileStore.OUTPUT_LIMIT
        const val TOKEN_BYTES = 32
        const val MAX_DIRECTORY_ENTRIES = 256
        const val MAX_DIRECTORY_HANDLES = 4_096
        const val MAX_WORKSPACE_HANDLES = 512
        const val MAX_OUTPUT_BYTES = 32 * 1024
        /** Opaque picker continuation tokens share the cursor size envelope. */
        const val MAX_CONTINUATION_BYTES = 512
        const val MAX_PAGE_CURSORS = 1_024
    }
}
