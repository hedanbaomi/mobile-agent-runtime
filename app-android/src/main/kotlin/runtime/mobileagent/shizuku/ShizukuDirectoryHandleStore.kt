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

    fun openRoot(maxEntries: Int): String = synchronized(lock) {
        runCatching {
            val root = deviceRoot
            validateDirectory(root)
            val token = directoryToken(root)
            renderDirectory("open_directory_root", token, root, parentToken = null, maxEntries)
        }.getOrElse { failureForThrowable("open_directory_root", it) }
    }

    fun browse(token: String?, maxEntries: Int): String = synchronized(lock) {
        runCatching {
            val handle = directoryHandles[token]
                ?: return@synchronized failure("browse_directory", INVALID_HANDLE)
            validateDirectory(handle.path)
            val parentToken = handle.parentPath?.let { directoryToken(it) }
            renderDirectory("browse_directory", token.orEmpty(), handle.path, parentToken, maxEntries)
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

    private fun renderDirectory(
        operation: String,
        token: String,
        directory: Path,
        parentToken: String?,
        maxEntries: Int,
    ): String {
        val entries = JSONArray()
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
        val sorted = children.sortedBy { it.fileName.toString() }
        var returned = 0
        var skipped = false
        for (child in sorted) {
            // Symlinks are deliberately not offered as selectable entries. A
            // caller must choose the canonical directory reached without
            // crossing a symlink boundary.
            if (Files.isSymbolicLink(child)) {
                skipped = true
                continue
            }
            val type = when {
                Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) -> "directory"
                Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) -> "file"
                else -> {
                    skipped = true
                    continue
                }
            }
            if (returned >= maxEntries) {
                skipped = true
                continue
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
            returned++
        }
        return bounded(
            JSONObject()
                .put("ok", true)
                .put("operation", operation)
                .put("handle", token)
                .put("parentHandle", parentToken ?: JSONObject.NULL)
                .put("deviceRoot", directory == deviceRoot && deviceRoot == Paths.get("/"))
                .put("entries", entries)
                .put("truncated", skipped),
        )
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
        var current = normalized.root ?: Paths.get("/")
        normalized.iterator().forEach { segment ->
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
    }
}
