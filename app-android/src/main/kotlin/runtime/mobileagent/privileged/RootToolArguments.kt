// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.security.MessageDigest
import java.util.Collections
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.RootApprovalTools
import runtime.mobileagent.domain.RootTypedArguments
import runtime.mobileagent.skills.tooling.ToolSpec

/**
 * Provider-neutral Root tool catalog and strict argument normalization.
 *
 * The model only ever sees a workspace-relative path; absolute device paths,
 * inode facts and grant identifiers stay on the runtime side.  Raw model JSON
 * is bounded and parsed into [RootTypedArguments], which the controlled Root
 * service independently re-normalizes.  Nothing here reads a DB, creates a
 * grant/Run/approval, or dispatches an operation.
 */
object RootToolArguments {
    /**
     * Closed first-wave Root catalog.  A name that is absent here can never be
     * normalized, so a stale or hostile schema cannot borrow another tool's
     * capability or approval.  `file_stat` and `file_get_metadata` are
     * deliberately distinct tools with distinct capabilities (review F10).
     */
    val supportedToolNames: Set<String> = Collections.unmodifiableSet(CATALOG.mapTo(linkedSetOf()) { it.name })

    /** The frozen model-facing schema for exactly this tool, or null when unsupported. */
    fun spec(toolName: String): ToolSpec? = SPECS[toolName]

    /** The complete provider-neutral Root catalog in exposure order. */
    fun specs(): List<ToolSpec> = TOOLING_SPECS

    /** Catalog order, de-duplicated, unknown names dropped; never invents a schema. */
    fun specsFor(toolNames: Iterable<String>): List<ToolSpec> {
        val wanted = toolNames.toSet()
        return Collections.unmodifiableList(CATALOG.filter { it.name in wanted })
    }

    /**
     * Parse model JSON into the exact typed arguments for [toolName].
     *
     * Returns null for an unsupported tool, a wrong shape/type, an oversized
     * payload, duplicate JSON keys, or invalid workspace-relative paths.  The
     * failure value never carries argument text, a device path or an exception
     * message, so callers can safely map it to a static typed error.
     */
    fun normalize(toolName: String, argumentsJson: String): RootToolRequest? {
        val parser = PARSERS[toolName] ?: return null
        val root = strictJsonObject(argumentsJson) ?: return null
        return try {
            parser(root)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** Normalized request: typed arguments plus only the bounded payload the backend may need. */
class RootToolRequest internal constructor(
    val toolName: String,
    val capability: CapabilityId,
    val typed: RootTypedArguments,
    val payload: RootToolPayload,
    val canonicalJson: String,
    val canonicalSha256: String,
) {
    val workspaceId: String get() = typed.workspaceId

    /** Identity is the canonical typed digest; no raw text/path is retained here. */
    override fun equals(other: Any?): Boolean =
        other is RootToolRequest && other.toolName == toolName && other.canonicalSha256 == canonicalSha256

    override fun hashCode(): Int = 31 * toolName.hashCode() + canonicalSha256.hashCode()

    override fun toString(): String = "RootToolRequest(tool=$toolName)"
}

/**
 * Bounded write payload.  Only `file_write_text`/`file_apply_patch` carry text;
 * every other tool uses [None].  Text is never printed by `toString`, and the
 * digest/byte length are the canonical identity of the payload.
 */
sealed interface RootToolPayload {
    data object None : RootToolPayload

    class Text internal constructor(
        val text: String,
        val sha256: String,
        val byteLength: Int,
    ) : RootToolPayload {
        init {
            require(byteLength in 0..MAX_TEXT_BYTES) { "Root text payload exceeds its bound" }
            require(sha256.matches(SHA256_HEX)) { "Invalid Root text payload digest" }
        }

        override fun equals(other: Any?): Boolean =
            other is Text && other.sha256 == sha256 && other.byteLength == byteLength

        override fun hashCode(): Int = 31 * sha256.hashCode() + byteLength

        override fun toString(): String = "RootToolPayload.Text(byteLength=$byteLength)"
    }
}

private const val MAX_ARGUMENT_BYTES = 512 * 1024
private const val MAX_JSON_DEPTH = 8
private const val MAX_JSON_NODES = 512
private const val MAX_TEXT_BYTES = 262_144
private const val MAX_PATCH_BYTES = 262_144
private const val MAX_COMMAND_BYTES = 16_384
private const val DEFAULT_READ_BYTES = 65_536
private const val DEFAULT_LIST_ENTRIES = 256
private val SHA256_HEX = Regex("[0-9a-f]{64}")

private const val FILE_LIST_SCHEMA =
    """{"type":"object","additionalProperties":false,"required":["workspace_id"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","maxLength":1024},"max_entries":{"type":"integer","minimum":1,"maximum":1000},"cursor":{"type":"string","minLength":1,"maxLength":512}}}"""
private const val FILE_GLOB_SCHEMA =
    """{"type":"object","additionalProperties":false,"required":["workspace_id","pattern"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"pattern":{"type":"string","minLength":1,"maxLength":256},"relative_path":{"type":"string","maxLength":1024},"max_depth":{"type":"integer","minimum":0,"maximum":16},"max_scanned_entries":{"type":"integer","minimum":1,"maximum":20000},"max_scanned_files":{"type":"integer","minimum":1,"maximum":5000},"max_results":{"type":"integer","minimum":1,"maximum":1000},"max_millis":{"type":"integer","minimum":1,"maximum":30000}}}"""
private const val FILE_STAT_SCHEMA =
    """{"type":"object","additionalProperties":false,"required":["workspace_id","relative_path"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","maxLength":1024}}}"""
private const val FILE_READ_SCHEMA =
    """{"type":"object","additionalProperties":false,"required":["workspace_id","relative_path"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","maxLength":1024},"max_bytes":{"type":"integer","minimum":1,"maximum":262144},"offset_bytes":{"type":"integer","minimum":0}}}"""
private const val FILE_GREP_SCHEMA =
    """{"type":"object","additionalProperties":false,"required":["workspace_id","query"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"query":{"type":"string","minLength":1,"maxLength":256},"relative_path":{"type":"string","maxLength":1024},"case_sensitive":{"type":"boolean"},"max_depth":{"type":"integer","minimum":0,"maximum":16},"max_scanned_entries":{"type":"integer","minimum":1,"maximum":20000},"max_scanned_files":{"type":"integer","minimum":1,"maximum":5000},"max_scanned_bytes":{"type":"integer","minimum":1,"maximum":4194304},"max_results":{"type":"integer","minimum":1,"maximum":1000},"max_millis":{"type":"integer","minimum":1,"maximum":30000}}}"""
private const val FILE_WRITE_SCHEMA =
    """{"type":"object","additionalProperties":false,"required":["workspace_id","relative_path","text"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","maxLength":1024},"text":{"type":"string","maxLength":262144},"replace":{"type":"boolean","description":"Unconditional replacement is a last-writer-wins overwrite in an app-private directory."},"expected_version":{"type":"string","minLength":1,"maxLength":128,"description":"Enforceable only on UID0-exclusive paths. On an app-private directory the request is refused with WORKSPACE_VERSION_UNSUPPORTED before any write."}}}"""
private const val FILE_PATCH_SCHEMA =
    """{"type":"object","additionalProperties":false,"required":["workspace_id","relative_path","patch","expected_version"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","maxLength":1024},"patch":{"type":"string","minLength":1,"maxLength":262144},"expected_version":{"type":"string","minLength":1,"maxLength":128,"description":"Patching reads then replaces, so it requires a UID0-exclusive path; app-private directories are refused with WORKSPACE_VERSION_UNSUPPORTED."},"format":{"type":"string","enum":["unified","search_replace"]}}}"""
private const val FILE_CREATE_DIRECTORY_SCHEMA =
    """{"type":"object","additionalProperties":false,"required":["workspace_id","relative_path"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","maxLength":1024}}}"""
private const val FILE_MOVE_SCHEMA =
    """{"type":"object","additionalProperties":false,"required":["workspace_id","source_relative_path","destination_relative_path"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"source_relative_path":{"type":"string","maxLength":1024},"destination_relative_path":{"type":"string","maxLength":1024},"replace_existing":{"type":"boolean"}}}"""
private const val FILE_DELETE_SCHEMA =
    """{"type":"object","additionalProperties":false,"required":["workspace_id","relative_path"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","maxLength":1024}}}"""
private const val FILE_METADATA_READ_SCHEMA =
    """{"type":"object","additionalProperties":false,"required":["workspace_id","relative_path"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","maxLength":1024}}}"""
private const val FILE_METADATA_WRITE_SCHEMA =
    """{"type":"object","additionalProperties":false,"required":["workspace_id","relative_path","expected_version"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","maxLength":1024},"expected_version":{"type":"string","minLength":1,"maxLength":128},"mode":{"type":"integer","minimum":0,"maximum":511},"uid":{"type":"integer","minimum":0},"gid":{"type":"integer","minimum":0},"owner_transfer_authorized":{"type":"boolean"}}}"""
private const val SHELL_EXEC_SCHEMA =
    """{"type":"object","additionalProperties":false,"required":["workspace_id","command"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"command":{"type":"string","minLength":1,"maxLength":16384},"cwd_relative_path":{"type":"string","maxLength":1024}}}"""

private val CATALOG: List<ToolSpec> = listOf(
    ToolSpec("file_list", "List entries in an authorized Root workspace (bounded).",
        FILE_LIST_SCHEMA, CapabilityId(CapabilityId.FILE_LIST), false),
    ToolSpec("file_glob", "Find paths matching a glob pattern in an authorized Root workspace (bounded).",
        FILE_GLOB_SCHEMA, CapabilityId(CapabilityId.FILE_LIST), false),
    ToolSpec("file_stat", "Read metadata for an authorized Root workspace entry.",
        FILE_STAT_SCHEMA, CapabilityId(CapabilityId.FILE_STAT), false),
    ToolSpec("file_read_text", "Read a bounded UTF-8 chunk from an authorized Root workspace.",
        FILE_READ_SCHEMA, CapabilityId(CapabilityId.FILE_READ_TEXT), false),
    ToolSpec("file_grep", "Search UTF-8 text lines in an authorized Root workspace (bounded).",
        FILE_GREP_SCHEMA, CapabilityId(CapabilityId.FILE_READ_TEXT), false),
    ToolSpec("file_write_text", "Create or replace UTF-8 text in an authorized Root workspace. expected_version is enforceable only on UID0-exclusive paths; app-private directories accept unconditional writes only.",
        FILE_WRITE_SCHEMA, CapabilityId(CapabilityId.FILE_WRITE_TEXT), true),
    ToolSpec("file_apply_patch", "Apply a conditional text patch on a UID0-exclusive Root workspace path; app-private directories are refused with WORKSPACE_VERSION_UNSUPPORTED.",
        FILE_PATCH_SCHEMA, CapabilityId(CapabilityId.FILE_WRITE_TEXT), true),
    ToolSpec("file_create_directory", "Create a directory in an authorized Root workspace.",
        FILE_CREATE_DIRECTORY_SCHEMA, CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY), true),
    ToolSpec("file_move", "Move one entry inside an authorized Root workspace.",
        FILE_MOVE_SCHEMA, CapabilityId(CapabilityId.FILE_MOVE), true),
    ToolSpec("file_delete", "Delete one authorized Root workspace entry.",
        FILE_DELETE_SCHEMA, CapabilityId(CapabilityId.FILE_DELETE), true),
    ToolSpec("file_get_metadata", "Read the version token and metadata for an authorized Root workspace entry.",
        FILE_METADATA_READ_SCHEMA, CapabilityId(CapabilityId.FILE_METADATA_READ), false),
    ToolSpec("file_set_metadata", "Update mode or ownership for an authorized Root workspace entry.",
        FILE_METADATA_WRITE_SCHEMA, CapabilityId(CapabilityId.FILE_METADATA_WRITE), true),
    ToolSpec("shell_exec", "Execute one shell command through the selected Root authority.",
        SHELL_EXEC_SCHEMA, CapabilityId(CapabilityId.SHELL_EXECUTE), true),
)
private val SPECS: Map<String, ToolSpec> = CATALOG.associateBy { it.name }
private val TOOLING_SPECS: List<ToolSpec> = Collections.unmodifiableList(CATALOG.toList())
private val PARSERS: Map<String, (JsonObject) -> RootToolRequest> = mapOf(
    "file_list" to ::parseFileList,
    "file_glob" to ::parseFileGlob,
    "file_stat" to ::parseFileStat,
    "file_read_text" to ::parseFileReadText,
    "file_grep" to ::parseFileGrep,
    "file_write_text" to ::parseFileWriteText,
    "file_apply_patch" to ::parseFileApplyPatch,
    "file_create_directory" to ::parseFileCreateDirectory,
    "file_move" to ::parseFileMove,
    "file_delete" to ::parseFileDelete,
    "file_get_metadata" to ::parseFileGetMetadata,
    "file_set_metadata" to ::parseFileSetMetadata,
    "shell_exec" to ::parseShellExec,
)

private fun parseFileList(root: JsonObject): RootToolRequest {
    root.requireShape(setOf("workspace_id"), setOf("relative_path", "max_entries", "cursor"))
    return request(
        "file_list",
        RootTypedArguments.FileList(
            workspaceId = root.string("workspace_id"),
            relativePath = root.optionalString("relative_path") ?: "",
            maxEntries = root.optionalInt("max_entries") ?: DEFAULT_LIST_ENTRIES,
            cursor = root.optionalString("cursor"),
        ),
    )
}

private fun parseFileGlob(root: JsonObject): RootToolRequest {
    root.requireShape(
        setOf("workspace_id", "pattern"),
        setOf("relative_path", "max_depth", "max_scanned_entries", "max_scanned_files", "max_results", "max_millis"),
    )
    return request(
        "file_glob",
        RootTypedArguments.FileGlob(
            workspaceId = root.string("workspace_id"),
            relativePath = root.optionalString("relative_path") ?: "",
            pattern = root.string("pattern"),
            maxDepth = root.optionalInt("max_depth") ?: 8,
            maxScannedEntries = root.optionalInt("max_scanned_entries") ?: 2_000,
            maxScannedFiles = root.optionalInt("max_scanned_files") ?: 500,
            maxResults = root.optionalInt("max_results") ?: 100,
            maxMillis = root.optionalLong("max_millis") ?: 5_000L,
        ),
    )
}

private fun parseFileStat(root: JsonObject): RootToolRequest {
    root.requireShape(setOf("workspace_id", "relative_path"))
    return request(
        "file_stat",
        RootTypedArguments.FileStat(
            workspaceId = root.string("workspace_id"),
            relativePath = root.string("relative_path"),
        ),
    )
}

private fun parseFileReadText(root: JsonObject): RootToolRequest {
    root.requireShape(setOf("workspace_id", "relative_path"), setOf("max_bytes", "offset_bytes"))
    return request(
        "file_read_text",
        RootTypedArguments.FileReadText(
            workspaceId = root.string("workspace_id"),
            relativePath = root.string("relative_path"),
            offsetBytes = root.optionalLong("offset_bytes") ?: 0L,
            maxBytes = root.optionalInt("max_bytes") ?: DEFAULT_READ_BYTES,
        ),
    )
}

private fun parseFileGrep(root: JsonObject): RootToolRequest {
    root.requireShape(
        setOf("workspace_id", "query"),
        setOf(
            "relative_path",
            "case_sensitive",
            "max_depth",
            "max_scanned_entries",
            "max_scanned_files",
            "max_scanned_bytes",
            "max_results",
            "max_millis",
        ),
    )
    return request(
        "file_grep",
        RootTypedArguments.FileGrep(
            workspaceId = root.string("workspace_id"),
            relativePath = root.optionalString("relative_path") ?: "",
            query = root.string("query"),
            caseSensitive = root.optionalBoolean("case_sensitive") ?: false,
            maxDepth = root.optionalInt("max_depth") ?: 8,
            maxScannedEntries = root.optionalInt("max_scanned_entries") ?: 2_000,
            maxScannedFiles = root.optionalInt("max_scanned_files") ?: 500,
            maxScannedBytes = root.optionalLong("max_scanned_bytes") ?: 524_288L,
            maxResults = root.optionalInt("max_results") ?: 100,
            maxMillis = root.optionalLong("max_millis") ?: 5_000L,
        ),
    )
}

private fun parseFileWriteText(root: JsonObject): RootToolRequest {
    root.requireShape(setOf("workspace_id", "relative_path", "text"), setOf("replace", "expected_version"))
    val text = root.string("text")
    val bytes = boundedUtf8(text, MAX_TEXT_BYTES)
    val sha256 = sha256Hex(bytes)
    val typed = RootTypedArguments.FileWriteText(
        workspaceId = root.string("workspace_id"),
        relativePath = root.string("relative_path"),
        contentSha256 = sha256,
        contentByteLength = bytes.size,
        replaceExisting = root.optionalBoolean("replace") ?: false,
        expectedVersion = root.optionalString("expected_version"),
    )
    return request("file_write_text", typed, RootToolPayload.Text(text, sha256, bytes.size))
}

private fun parseFileApplyPatch(root: JsonObject): RootToolRequest {
    root.requireShape(
        setOf("workspace_id", "relative_path", "patch", "expected_version"),
        setOf("format"),
    )
    val patch = root.string("patch")
    val bytes = boundedUtf8(patch, MAX_PATCH_BYTES)
    val sha256 = sha256Hex(bytes)
    val typed = RootTypedArguments.FileApplyPatch(
        workspaceId = root.string("workspace_id"),
        relativePath = root.string("relative_path"),
        patchSha256 = sha256,
        patchByteLength = bytes.size,
        expectedVersion = root.string("expected_version"),
        format = root.optionalString("format") ?: "unified",
    )
    return request("file_apply_patch", typed, RootToolPayload.Text(patch, sha256, bytes.size))
}

private fun parseFileCreateDirectory(root: JsonObject): RootToolRequest {
    root.requireShape(setOf("workspace_id", "relative_path"))
    return request(
        "file_create_directory",
        RootTypedArguments.FileCreateDirectory(
            workspaceId = root.string("workspace_id"),
            relativePath = root.string("relative_path"),
        ),
    )
}

private fun parseFileMove(root: JsonObject): RootToolRequest {
    root.requireShape(
        setOf("workspace_id", "source_relative_path", "destination_relative_path"),
        setOf("replace_existing"),
    )
    return request(
        "file_move",
        RootTypedArguments.FileMove(
            workspaceId = root.string("workspace_id"),
            sourceRelativePath = root.string("source_relative_path"),
            destinationRelativePath = root.string("destination_relative_path"),
            replaceExisting = root.optionalBoolean("replace_existing") ?: false,
        ),
    )
}

private fun parseFileDelete(root: JsonObject): RootToolRequest {
    root.requireShape(setOf("workspace_id", "relative_path"))
    return request(
        "file_delete",
        RootTypedArguments.FileDelete(
            workspaceId = root.string("workspace_id"),
            relativePath = root.string("relative_path"),
        ),
    )
}

private fun parseFileGetMetadata(root: JsonObject): RootToolRequest {
    root.requireShape(setOf("workspace_id", "relative_path"))
    return request(
        "file_get_metadata",
        RootTypedArguments.FileGetMetadata(
            workspaceId = root.string("workspace_id"),
            relativePath = root.string("relative_path"),
        ),
    )
}

private fun parseFileSetMetadata(root: JsonObject): RootToolRequest {
    root.requireShape(
        setOf("workspace_id", "relative_path", "expected_version"),
        setOf("mode", "uid", "gid", "owner_transfer_authorized"),
    )
    return request(
        "file_set_metadata",
        RootTypedArguments.FileSetMetadata(
            workspaceId = root.string("workspace_id"),
            relativePath = root.string("relative_path"),
            expectedVersion = root.string("expected_version"),
            mode = root.optionalInt("mode"),
            uid = root.optionalInt("uid"),
            gid = root.optionalInt("gid"),
            ownerTransferAuthorized = root.optionalBoolean("owner_transfer_authorized") ?: false,
        ),
    )
}

private fun parseShellExec(root: JsonObject): RootToolRequest {
    root.requireShape(setOf("workspace_id", "command"), setOf("cwd_relative_path"))
    val command = root.string("command")
    boundedUtf8(command, MAX_COMMAND_BYTES)
    return request(
        "shell_exec",
        RootTypedArguments.ShellExec(
            workspaceId = root.string("workspace_id"),
            command = command,
            cwdRelativePath = root.optionalString("cwd_relative_path"),
        ),
    )
}

private fun request(
    toolName: String,
    typed: RootTypedArguments,
    payload: RootToolPayload = RootToolPayload.None,
): RootToolRequest {
    val capability = RootApprovalTools.capability(toolName) ?: throw IllegalArgumentException()
    if (typed.requiredCapability != capability) throw IllegalArgumentException()
    return RootToolRequest(
        toolName = toolName,
        capability = capability,
        typed = typed,
        payload = payload,
        canonicalJson = typed.canonicalJson(),
        canonicalSha256 = typed.canonicalSha256(),
    )
}

private fun JsonObject.requireShape(required: Set<String>, optional: Set<String> = emptySet()) {
    if (!keys.containsAll(required) || !keys.all { it in required || it in optional }) {
        throw IllegalArgumentException()
    }
}

private fun JsonObject.string(key: String): String {
    val value = this[key] as? JsonPrimitive
    if (value == null || !value.isString) throw IllegalArgumentException()
    return value.content
}

private fun JsonObject.optionalString(key: String): String? {
    val value = this[key]
    return if (value == null || value is JsonNull) null else string(key)
}

private fun JsonObject.nonStringPrimitive(key: String): JsonPrimitive? {
    val value = this[key]
    if (value == null || value is JsonNull) return null
    val primitive = value as? JsonPrimitive
    if (primitive == null || primitive.isString) throw IllegalArgumentException()
    return primitive
}

private fun JsonObject.optionalInt(key: String): Int? =
    nonStringPrimitive(key)?.let { it.intOrNull ?: throw IllegalArgumentException() }

private fun JsonObject.optionalLong(key: String): Long? =
    nonStringPrimitive(key)?.let { it.longOrNull ?: throw IllegalArgumentException() }

private fun JsonObject.optionalBoolean(key: String): Boolean? =
    nonStringPrimitive(key)?.let { it.booleanOrNull ?: throw IllegalArgumentException() }

private fun boundedUtf8(value: String, maxBytes: Int): ByteArray {
    if (value.indexOf('\u0000') >= 0) throw IllegalArgumentException()
    val bytes = value.toByteArray(Charsets.UTF_8)
    if (bytes.size > maxBytes) throw IllegalArgumentException()
    return bytes
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val hex = CharArray(digest.size * 2)
    val digits = "0123456789abcdef"
    for (index in digest.indices) {
        val value = digest[index].toInt() and 0xff
        hex[index * 2] = digits[value ushr 4]
        hex[index * 2 + 1] = digits[value and 0x0f]
    }
    return String(hex)
}

/**
 * Reject duplicate object keys and an oversized/deep payload before the codec
 * collapses them.  The subsequent codec parse re-validates JSON syntax and
 * escape sequences, so this scan only tracks structure and key uniqueness.
 */
private fun strictJsonObject(text: String): JsonObject? {
    if (text.length < 2 || text.length > MAX_ARGUMENT_BYTES) return null
    if (text.toByteArray(Charsets.UTF_8).size > MAX_ARGUMENT_BYTES || text.indexOf('\u0000') >= 0) return null
    val stack = ArrayList<JsonFrame>()
    var rootObject = false
    var nodes = 0
    var index = 0
    while (index < text.length) {
        when (text[index]) {
            '{', '[' -> {
                if (stack.isEmpty()) rootObject = text[index] == '{'
                if (stack.size >= MAX_JSON_DEPTH || ++nodes > MAX_JSON_NODES) return null
                stack.add(JsonFrame(if (text[index] == '{') HashSet() else null))
            }
            '}', ']' -> {
                if (stack.isEmpty()) return null
                stack.removeAt(stack.lastIndex)
            }
            ',' -> {
                if (++nodes > MAX_JSON_NODES) return null
                stack.lastOrNull()?.takeIf { it.keys != null }?.expectingKey = true
            }
            '"' -> {
                val start = index++
                while (index < text.length && text[index] != '"') {
                    if (text[index] == '\\') index++
                    index++
                }
                if (index >= text.length) return null
                val frame = stack.lastOrNull()
                if (frame?.keys != null && frame.expectingKey) {
                    val key = (runCatching {
                        Json.parseToJsonElement(text.substring(start, index + 1))
                    }.getOrNull() as? JsonPrimitive)?.content ?: return null
                    if (!frame.keys.add(key)) return null
                    frame.expectingKey = false
                }
            }
        }
        index++
    }
    if (!rootObject || stack.isNotEmpty()) return null
    return runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
}

private class JsonFrame(
    val keys: MutableSet<String>?,
    var expectingKey: Boolean = true,
)
