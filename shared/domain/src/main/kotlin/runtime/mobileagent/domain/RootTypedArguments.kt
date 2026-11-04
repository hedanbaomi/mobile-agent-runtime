// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RootExecutionBudget(
    val timeoutMs: Long = 30_000L,
    val maxOutputBytes: Int = 65_536,
    val maxEntries: Int = 256,
) {
    init {
        require(timeoutMs in 1L..MAX_TIMEOUT_MS) { "Invalid Root execution timeout" }
        require(maxOutputBytes in 1..MAX_OUTPUT_BYTES) { "Invalid Root output budget" }
        require(maxEntries in 1..MAX_ENTRIES) { "Invalid Root entry budget" }
    }

    companion object {
        const val MAX_TIMEOUT_MS = 120_000L
        const val MAX_OUTPUT_BYTES = 262_144
        const val MAX_ENTRIES = 1_000
    }
}

/**
 * Closed typed arguments for Root protocol v4. Raw model JSON never crosses into
 * the controlled Root service; the service independently re-normalizes this schema.
 */
@Serializable
sealed interface RootTypedArguments {
    val workspaceId: String
    val toolName: String
    val requiredCapability: CapabilityId

    fun validateScope(expectedWorkspaceId: String, relativePathScope: String?, execution: RootExecutionStamp)

    fun canonicalJson(): String = RootControlJson.encodeArguments(this)

    fun canonicalSha256(): String = RootControlJson.sha256Hex(canonicalJson())

    @Serializable
    @SerialName("file_list")
    data class FileList(
        override val workspaceId: String,
        val relativePath: String = "",
        val maxEntries: Int = 256,
        val cursor: String? = null,
    ) : RootTypedArguments {
        override val toolName: String get() = "file_list"
        override val requiredCapability: CapabilityId get() = CapabilityId(CapabilityId.FILE_LIST)

        init {
            require(RootPathGuards.isSafeId(workspaceId))
            require(RootPathGuards.isValidRelativePath(relativePath, allowRoot = true))
            require(maxEntries in 1..RootExecutionBudget.MAX_ENTRIES)
            require(cursor == null || cursor.length in 1..512)
        }

        override fun validateScope(expectedWorkspaceId: String, relativePathScope: String?, execution: RootExecutionStamp) {
            RootPathGuards.requirePathInScope(workspaceId, expectedWorkspaceId, relativePath, relativePathScope, allowRoot = true)
        }
    }

    @Serializable
    @SerialName("file_stat")
    data class FileStat(
        override val workspaceId: String,
        val relativePath: String,
    ) : RootTypedArguments {
        override val toolName: String get() = "file_stat"
        override val requiredCapability: CapabilityId get() = CapabilityId(CapabilityId.FILE_STAT)

        init {
            require(RootPathGuards.isSafeId(workspaceId))
            require(RootPathGuards.isValidRelativePath(relativePath, allowRoot = true))
        }

        override fun validateScope(expectedWorkspaceId: String, relativePathScope: String?, execution: RootExecutionStamp) {
            RootPathGuards.requirePathInScope(workspaceId, expectedWorkspaceId, relativePath, relativePathScope, allowRoot = true)
        }
    }

    @Serializable
    @SerialName("file_read_text")
    data class FileReadText(
        override val workspaceId: String,
        val relativePath: String,
        val offsetBytes: Long = 0L,
        val maxBytes: Int = 65_536,
    ) : RootTypedArguments {
        override val toolName: String get() = "file_read_text"
        override val requiredCapability: CapabilityId get() = CapabilityId(CapabilityId.FILE_READ_TEXT)

        init {
            require(RootPathGuards.isSafeId(workspaceId))
            require(RootPathGuards.isValidRelativePath(relativePath, allowRoot = false))
            require(offsetBytes >= 0L && maxBytes in 1..RootExecutionBudget.MAX_OUTPUT_BYTES)
        }

        override fun validateScope(expectedWorkspaceId: String, relativePathScope: String?, execution: RootExecutionStamp) {
            RootPathGuards.requirePathInScope(workspaceId, expectedWorkspaceId, relativePath, relativePathScope, allowRoot = false)
        }
    }

    @Serializable
    @SerialName("file_write_text")
    data class FileWriteText(
        override val workspaceId: String,
        val relativePath: String,
        val contentSha256: String,
        val contentByteLength: Int,
        val replaceExisting: Boolean = false,
        val expectedVersion: String? = null,
    ) : RootTypedArguments {
        override val toolName: String get() = "file_write_text"
        override val requiredCapability: CapabilityId get() = CapabilityId(CapabilityId.FILE_WRITE_TEXT)

        init {
            require(RootPathGuards.isSafeId(workspaceId))
            require(RootPathGuards.isValidRelativePath(relativePath, allowRoot = false))
            require(RootPathGuards.isSha256(contentSha256))
            require(contentByteLength in 0..RootExecutionBudget.MAX_OUTPUT_BYTES)
            require(expectedVersion == null || expectedVersion.length in 1..128)
        }

        override fun validateScope(expectedWorkspaceId: String, relativePathScope: String?, execution: RootExecutionStamp) {
            RootPathGuards.requirePathInScope(workspaceId, expectedWorkspaceId, relativePath, relativePathScope, allowRoot = false)
        }
    }

    @Serializable
    @SerialName("file_apply_patch")
    data class FileApplyPatch(
        override val workspaceId: String,
        val relativePath: String,
        val patchSha256: String,
        val patchByteLength: Int,
        val expectedVersion: String,
        val format: String = "unified",
    ) : RootTypedArguments {
        override val toolName: String get() = "file_apply_patch"
        override val requiredCapability: CapabilityId get() = CapabilityId(CapabilityId.FILE_WRITE_TEXT)

        init {
            require(RootPathGuards.isSafeId(workspaceId))
            require(RootPathGuards.isValidRelativePath(relativePath, allowRoot = false))
            require(RootPathGuards.isSha256(patchSha256))
            require(patchByteLength in 1..RootExecutionBudget.MAX_OUTPUT_BYTES)
            require(expectedVersion.length in 1..128)
            require(format in setOf("unified", "search_replace"))
        }

        override fun validateScope(expectedWorkspaceId: String, relativePathScope: String?, execution: RootExecutionStamp) {
            RootPathGuards.requirePathInScope(workspaceId, expectedWorkspaceId, relativePath, relativePathScope, allowRoot = false)
        }
    }

    @Serializable
    @SerialName("file_create_directory")
    data class FileCreateDirectory(
        override val workspaceId: String,
        val relativePath: String,
    ) : RootTypedArguments {
        override val toolName: String get() = "file_create_directory"
        override val requiredCapability: CapabilityId get() = CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY)

        init {
            require(RootPathGuards.isSafeId(workspaceId))
            require(RootPathGuards.isValidRelativePath(relativePath, allowRoot = false))
        }

        override fun validateScope(expectedWorkspaceId: String, relativePathScope: String?, execution: RootExecutionStamp) {
            RootPathGuards.requirePathInScope(workspaceId, expectedWorkspaceId, relativePath, relativePathScope, allowRoot = false)
        }
    }

    @Serializable
    @SerialName("file_move")
    data class FileMove(
        override val workspaceId: String,
        val sourceRelativePath: String,
        val destinationRelativePath: String,
        val replaceExisting: Boolean = false,
    ) : RootTypedArguments {
        override val toolName: String get() = "file_move"
        override val requiredCapability: CapabilityId get() = CapabilityId(CapabilityId.FILE_MOVE)

        init {
            require(RootPathGuards.isSafeId(workspaceId))
            require(RootPathGuards.isValidRelativePath(sourceRelativePath, allowRoot = false))
            require(RootPathGuards.isValidRelativePath(destinationRelativePath, allowRoot = false))
            require(sourceRelativePath != destinationRelativePath)
        }

        override fun validateScope(expectedWorkspaceId: String, relativePathScope: String?, execution: RootExecutionStamp) {
            RootPathGuards.requirePathInScope(workspaceId, expectedWorkspaceId, sourceRelativePath, relativePathScope, allowRoot = false)
            RootPathGuards.requirePathInScope(workspaceId, expectedWorkspaceId, destinationRelativePath, relativePathScope, allowRoot = false)
        }
    }

    @Serializable
    @SerialName("file_delete")
    data class FileDelete(
        override val workspaceId: String,
        val relativePath: String,
    ) : RootTypedArguments {
        override val toolName: String get() = "file_delete"
        override val requiredCapability: CapabilityId get() = CapabilityId(CapabilityId.FILE_DELETE)

        init {
            require(RootPathGuards.isSafeId(workspaceId))
            require(RootPathGuards.isValidRelativePath(relativePath, allowRoot = false))
        }

        override fun validateScope(expectedWorkspaceId: String, relativePathScope: String?, execution: RootExecutionStamp) {
            RootPathGuards.requirePathInScope(workspaceId, expectedWorkspaceId, relativePath, relativePathScope, allowRoot = false)
        }
    }

    @Serializable
    @SerialName("file_get_metadata")
    data class FileGetMetadata(
        override val workspaceId: String,
        val relativePath: String,
    ) : RootTypedArguments {
        override val toolName: String get() = "file_get_metadata"
        override val requiredCapability: CapabilityId get() = CapabilityId(CapabilityId.FILE_METADATA_READ)

        init {
            require(RootPathGuards.isSafeId(workspaceId))
            require(RootPathGuards.isValidRelativePath(relativePath, allowRoot = true))
        }

        override fun validateScope(expectedWorkspaceId: String, relativePathScope: String?, execution: RootExecutionStamp) {
            RootPathGuards.requirePathInScope(workspaceId, expectedWorkspaceId, relativePath, relativePathScope, allowRoot = true)
        }
    }

    @Serializable
    @SerialName("file_set_metadata")
    data class FileSetMetadata(
        override val workspaceId: String,
        val relativePath: String,
        val expectedVersion: String,
        val mode: Int? = null,
        val uid: Int? = null,
        val gid: Int? = null,
        val ownerTransferAuthorized: Boolean = false,
    ) : RootTypedArguments {
        override val toolName: String get() = "file_set_metadata"
        override val requiredCapability: CapabilityId get() = CapabilityId(CapabilityId.FILE_METADATA_WRITE)

        init {
            require(RootPathGuards.isSafeId(workspaceId))
            require(RootPathGuards.isValidRelativePath(relativePath, allowRoot = false))
            require(expectedVersion.length in 1..128)
            require(mode != null || uid != null)
            require(mode == null || mode in 0..511) { "Root metadata mode must be within 0..0777 without setuid/setgid/sticky" }
            require((uid == null) == (gid == null))
            require(uid == null || (uid >= 0 && gid!! >= 0))
            require(uid == null || (uid == 0 && gid == 0) || ownerTransferAuthorized) {
                "Non-root owner transfer requires explicit authorization"
            }
        }

        override fun validateScope(expectedWorkspaceId: String, relativePathScope: String?, execution: RootExecutionStamp) {
            RootPathGuards.requirePathInScope(workspaceId, expectedWorkspaceId, relativePath, relativePathScope, allowRoot = false)
        }
    }

    @Serializable
    @SerialName("file_glob")
    data class FileGlob(
        override val workspaceId: String,
        val relativePath: String = "",
        val pattern: String,
        val maxDepth: Int = 8,
        val maxScannedEntries: Int = 2_000,
        val maxScannedFiles: Int = 500,
        val maxResults: Int = 100,
        val maxMillis: Long = 5_000L,
    ) : RootTypedArguments {
        override val toolName: String get() = "file_glob"
        override val requiredCapability: CapabilityId get() = CapabilityId(CapabilityId.FILE_LIST)

        init {
            require(RootPathGuards.isSafeId(workspaceId))
            require(RootPathGuards.isValidRelativePath(relativePath, allowRoot = true))
            require(RootPathGuards.isValidGlobPattern(pattern))
            require(maxDepth in 0..16)
            require(maxScannedEntries in 1..20_000)
            require(maxScannedFiles in 1..5_000)
            require(maxResults in 1..1_000)
            require(maxMillis in 1L..30_000L)
        }

        override fun validateScope(expectedWorkspaceId: String, relativePathScope: String?, execution: RootExecutionStamp) {
            RootPathGuards.requirePathInScope(workspaceId, expectedWorkspaceId, relativePath, relativePathScope, allowRoot = true)
        }
    }

    @Serializable
    @SerialName("file_grep")
    data class FileGrep(
        override val workspaceId: String,
        val relativePath: String = "",
        val query: String,
        val caseSensitive: Boolean = false,
        val maxDepth: Int = 8,
        val maxScannedEntries: Int = 2_000,
        val maxScannedFiles: Int = 500,
        val maxScannedBytes: Long = 524_288L,
        val maxResults: Int = 100,
        val maxMillis: Long = 5_000L,
    ) : RootTypedArguments {
        override val toolName: String get() = "file_grep"
        override val requiredCapability: CapabilityId get() = CapabilityId(CapabilityId.FILE_READ_TEXT)

        init {
            require(RootPathGuards.isSafeId(workspaceId))
            require(RootPathGuards.isValidRelativePath(relativePath, allowRoot = true))
            require(RootPathGuards.isValidGrepQuery(query))
            require(maxDepth in 0..16)
            require(maxScannedEntries in 1..20_000)
            require(maxScannedFiles in 1..5_000)
            require(maxScannedBytes in 1L..4_194_304L)
            require(maxResults in 1..1_000)
            require(maxMillis in 1L..30_000L)
        }

        override fun validateScope(expectedWorkspaceId: String, relativePathScope: String?, execution: RootExecutionStamp) {
            RootPathGuards.requirePathInScope(workspaceId, expectedWorkspaceId, relativePath, relativePathScope, allowRoot = true)
        }
    }

    @Serializable
    @SerialName("shell_exec")
    data class ShellExec(
        override val workspaceId: String,
        val command: String,
        val cwdRelativePath: String? = null,
    ) : RootTypedArguments {
        override val toolName: String get() = "shell_exec"
        override val requiredCapability: CapabilityId get() = CapabilityId(CapabilityId.SHELL_EXECUTE)

        init {
            require(RootPathGuards.isSafeId(workspaceId))
            require(command.isNotBlank() && !command.contains('\u0000'))
            require(command.toByteArray(Charsets.UTF_8).size <= MAX_COMMAND_BYTES)
            require(cwdRelativePath == null || RootPathGuards.isValidRelativePath(cwdRelativePath, allowRoot = true))
        }

        override fun validateScope(expectedWorkspaceId: String, relativePathScope: String?, execution: RootExecutionStamp) {
            require(execution.shellConsentRevision > 0) { "Root Shell requires shellConsentRevision > 0" }
            RootPathGuards.requirePathInScope(
                workspaceId,
                expectedWorkspaceId,
                cwdRelativePath ?: "",
                relativePathScope,
                allowRoot = true,
            )
        }

        companion object {
            const val MAX_COMMAND_BYTES = 16_384
        }
    }
}

internal object RootPathGuards {
    private val safeIdRegex = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val opaqueIdRegex = Regex("[A-Za-z0-9_-]{16,128}")
    private val sha256Regex = Regex("[0-9a-f]{64}")

    fun isSafeId(value: String): Boolean = safeIdRegex.matches(value)
    fun isOpaqueId(value: String): Boolean = opaqueIdRegex.matches(value)
    fun isSha256(value: String): Boolean = sha256Regex.matches(value)

    fun isValidRelativePath(path: String, allowRoot: Boolean): Boolean {
        if (path.isEmpty()) return allowRoot
        if (path.length > 1024 || path.startsWith('/') || path.endsWith('/') || path.contains("//")) return false
        if (path.any { it == '\u0000' || it == '\\' || it == ':' || it.code < 32 || it.code == 127 }) return false
        return path.split('/').all { it.isNotBlank() && it != "." && it != ".." }
    }

    fun isValidGlobPattern(pattern: String): Boolean {
        if (pattern.isEmpty()) return false
        val normalized = java.text.Normalizer.normalize(pattern, java.text.Normalizer.Form.NFC)
        if (normalized.toByteArray(Charsets.UTF_8).size > 256) return false
        if (normalized.any { it == '\u0000' || it.isISOControl() || it == '\\' || it == ':' }) return false
        if (normalized.startsWith('/') || normalized.endsWith('/') || normalized.contains("//")) return false
        val parts = normalized.split('/')
        return parts.size <= 16 && parts.none { it.isEmpty() || it == "." || it == ".." }
    }

    fun isValidGrepQuery(query: String): Boolean =
        query.isNotEmpty() &&
            query.toByteArray(Charsets.UTF_8).size <= 256 &&
            query.none { it == '\u0000' || it.isISOControl() }

    fun requirePathInScope(
        actualWorkspaceId: String,
        expectedWorkspaceId: String,
        targetPath: String,
        relativePathScope: String?,
        allowRoot: Boolean,
    ) {
        require(actualWorkspaceId == expectedWorkspaceId) { "Workspace id mismatch" }
        require(isValidRelativePath(targetPath, allowRoot)) { "Relative path is invalid" }
        val scope = relativePathScope ?: return
        require(isValidRelativePath(scope, allowRoot = false)) { "Relative path scope is invalid" }
        require(targetPath == scope || targetPath.startsWith("$scope/")) {
            "Target relative path escapes the authorized scope"
        }
    }
}

internal object RootControlJson {
    val codec = kotlinx.serialization.json.Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        allowStructuredMapKeys = false
        isLenient = false
    }

    fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun encodeArguments(arguments: RootTypedArguments): String =
        codec.encodeToString(RootTypedArguments.serializer(), arguments)

    fun decodeArguments(rawJson: String): RootTypedArguments {
        requireStrictJsonObject(rawJson, 65_536, 8, 256)
        return codec.decodeFromString(RootTypedArguments.serializer(), rawJson)
    }

    fun requireStrictJsonObject(text: String, maxBytes: Int, maxDepth: Int, maxNodes: Int) =
        BoundedJson.requireStrictJsonObject(text, maxBytes, maxDepth, maxNodes)

}
