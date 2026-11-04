// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.security.MessageDigest
import java.util.Collections
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.RootApprovalTools
import runtime.mobileagent.domain.RootTypedArguments
import runtime.mobileagent.skills.tooling.ToolSpec
import runtime.mobileagent.tooling.ModelToolArguments

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
     * Classify one bounded model payload before the typed codec runs.
     *
     * A malformed shape is [RootArgumentNormalization.Invalid].  A well-formed
     * conditional request that this backend cannot honour is reported as an
     * explicit unsupported capability instead, so callers do not approve it
     * first and then reject it with an opaque INVALID_REQUEST.
     */
    fun normalizeResult(toolName: String, argumentsJson: String): RootArgumentNormalization {
        val spec = SPECS[toolName] ?: return RootArgumentNormalization.Invalid
        val raw = ModelToolArguments.objectOrNull(argumentsJson) ?: return RootArgumentNormalization.Invalid
        val root = ModelToolArguments.canonicalWorkspaceObject(raw) ?: return RootArgumentNormalization.Invalid
        // Conditional fields are never ignored: a backend that cannot bind a
        // version must refuse before any approval or side effect.
        if (root.containsAny("expected_version", "expectedVersion") && toolName !in VERSION_CONDITIONAL_TOOLS) {
            return RootArgumentNormalization.UnsupportedVersionCondition
        }
        if (toolName == "file_apply_patch") {
            val format = root["format"]
            if (format != null) {
                val scalar = format as? JsonPrimitive
                if (scalar == null || !scalar.isString) return RootArgumentNormalization.Invalid
                if (scalar.content !in ROOT_PATCH_FORMATS) {
                    return RootArgumentNormalization.UnsupportedPatchFormat
                }
            }
        }
        val shape = ModelToolArguments.scalarObjectOrNull(spec, root) ?: return RootArgumentNormalization.Invalid
        return runCatching {
            val payloadKey = when (toolName) {
                "file_write_text" -> "text"
                "file_apply_patch" -> "patch"
                else -> null
            }
            val payloadText = payloadKey?.let { (shape.getValue(it) as JsonPrimitive).content }
            val bytes = payloadText?.let { boundedUtf8(it, MAX_TEXT_BYTES) }
            val digest = bytes?.let(::sha256Hex)
            val wire = buildJsonObject {
                put("type", toolName)
                shape.forEach { (key, value) ->
                    if (key != payloadKey) {
                        val wireKey = if (key == "replace") "replaceExisting" else snakeToCamel(key)
                        put(wireKey, if (key == "format" && (value as? JsonPrimitive)?.content == "unified_diff") JsonPrimitive("unified") else value)
                    }
                }
                if (bytes != null && digest != null) {
                    val prefix = if (payloadKey == "text") "content" else "patch"
                    put("${prefix}Sha256", digest)
                    put("${prefix}ByteLength", bytes.size)
                }
            }
            val typed = Json.decodeFromJsonElement(RootTypedArguments.serializer(), wire)
            RootArgumentNormalization.Success(request(toolName, typed, if (payloadText != null && bytes != null && digest != null) {
                RootToolPayload.Text(payloadText, digest, bytes.size)
            } else RootToolPayload.None))
        }.getOrElse { RootArgumentNormalization.Invalid }
    }

    /**
     * Parse model JSON into the exact typed arguments for [toolName].
     *
     * Returns null for an unsupported tool, a wrong shape/type, an oversized
     * payload, duplicate JSON keys, or invalid workspace-relative paths.  The
     * failure value never carries argument text, a device path or an exception
     * message, so callers can safely map it to a static typed error.
     */
    fun normalize(toolName: String, argumentsJson: String): RootToolRequest? =
        (normalizeResult(toolName, argumentsJson) as? RootArgumentNormalization.Success)?.request

    private fun JsonObject.containsAny(first: String, second: String): Boolean =
        containsKey(first) || containsKey(second)
}

/** Closed classification of one Root model payload. */
sealed interface RootArgumentNormalization {
    data class Success(val request: RootToolRequest) : RootArgumentNormalization

    data object Invalid : RootArgumentNormalization
    data object UnsupportedVersionCondition : RootArgumentNormalization
    data object UnsupportedPatchFormat : RootArgumentNormalization
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

private const val MAX_TEXT_BYTES = 262_144
private val SHA256_HEX = Regex("[0-9a-f]{64}")
/** The Root wire accepts these patch formats; `replace` belongs to the ordinary protocol only. */
private val ROOT_PATCH_FORMATS = setOf("unified_diff", "unified", "search_replace")
/** Tools whose typed contract can bind a target version; all others must refuse the field. */
private val VERSION_CONDITIONAL_TOOLS = setOf("file_write_text", "file_apply_patch", "file_set_metadata")

private val CATALOG: List<ToolSpec> = listOf(
    ToolSpec("file_list", "List entries in an authorized Root workspace (bounded).",
        ModelToolArguments.workspaceSchema("file_list", root = true), CapabilityId(CapabilityId.FILE_LIST), false, ModelToolArguments.WORKSPACE_SCHEMA_VERSION),
    ToolSpec("file_glob", "Find paths matching a glob pattern in an authorized Root workspace (bounded).",
        ModelToolArguments.workspaceSchema("file_glob", root = true), CapabilityId(CapabilityId.FILE_LIST), false, ModelToolArguments.WORKSPACE_SCHEMA_VERSION),
    ToolSpec("file_stat", "Read metadata for an authorized Root workspace entry.",
        ModelToolArguments.workspaceSchema("file_stat", root = true), CapabilityId(CapabilityId.FILE_STAT), false, ModelToolArguments.WORKSPACE_SCHEMA_VERSION),
    ToolSpec("file_read_text", "Read a bounded UTF-8 chunk from an authorized Root workspace.",
        ModelToolArguments.workspaceSchema("file_read_text", root = true), CapabilityId(CapabilityId.FILE_READ_TEXT), false, ModelToolArguments.WORKSPACE_SCHEMA_VERSION),
    ToolSpec("file_grep", "Search UTF-8 text lines in an authorized Root workspace (bounded).",
        ModelToolArguments.workspaceSchema("file_grep", root = true), CapabilityId(CapabilityId.FILE_READ_TEXT), false, ModelToolArguments.WORKSPACE_SCHEMA_VERSION),
    ToolSpec("file_write_text", "Create or replace UTF-8 text in an authorized Root workspace. expected_version is enforceable only on UID0-exclusive paths; app-private directories accept unconditional writes only.",
        ModelToolArguments.workspaceSchema("file_write_text", root = true), CapabilityId(CapabilityId.FILE_WRITE_TEXT), true, ModelToolArguments.WORKSPACE_SCHEMA_VERSION),
    ToolSpec("file_apply_patch", "Apply a conditional text patch on a UID0-exclusive Root workspace path; app-private directories are refused with WORKSPACE_VERSION_UNSUPPORTED. The replace patch format is an ordinary-backend capability and is refused explicitly here.",
        ModelToolArguments.workspaceSchema("file_apply_patch", root = true), CapabilityId(CapabilityId.FILE_WRITE_TEXT), true, ModelToolArguments.WORKSPACE_SCHEMA_VERSION),
    ToolSpec("file_create_directory", "Create a directory in an authorized Root workspace. Root does not accept expected_version for this operation.",
        ModelToolArguments.workspaceSchema("file_create_directory", root = true), CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY), true, ModelToolArguments.WORKSPACE_SCHEMA_VERSION),
    ToolSpec("file_move", "Move one entry inside an authorized Root workspace.",
        ModelToolArguments.workspaceSchema("file_move", root = true), CapabilityId(CapabilityId.FILE_MOVE), true, ModelToolArguments.WORKSPACE_SCHEMA_VERSION),
    ToolSpec("file_delete", "Delete one authorized Root workspace entry. Root does not accept expected_version for this operation.",
        ModelToolArguments.workspaceSchema("file_delete", root = true), CapabilityId(CapabilityId.FILE_DELETE), true, ModelToolArguments.WORKSPACE_SCHEMA_VERSION),
    ToolSpec("file_get_metadata", "Read the version token and metadata for an authorized Root workspace entry.",
        ModelToolArguments.workspaceSchema("file_get_metadata", root = true), CapabilityId(CapabilityId.FILE_METADATA_READ), false, ModelToolArguments.WORKSPACE_SCHEMA_VERSION),
    ToolSpec("file_set_metadata", "Update mode or ownership for an authorized Root workspace entry.",
        ModelToolArguments.workspaceSchema("file_set_metadata", root = true), CapabilityId(CapabilityId.FILE_METADATA_WRITE), true, ModelToolArguments.WORKSPACE_SCHEMA_VERSION),
    ToolSpec("shell_exec", "Execute one shell command through the selected Root authority.",
        ModelToolArguments.workspaceSchema("shell_exec", root = true), CapabilityId(CapabilityId.SHELL_EXECUTE), true, ModelToolArguments.WORKSPACE_SCHEMA_VERSION),
)
private val SPECS: Map<String, ToolSpec> = CATALOG.associateBy { it.name }
private val TOOLING_SPECS: List<ToolSpec> = Collections.unmodifiableList(CATALOG.toList())
/** Model keys have one canonical spelling; the service DTO keeps Kotlin field names. */
private fun snakeToCamel(key: String): String = key.split('_').let { pieces ->
    pieces.first() + pieces.drop(1).joinToString("") { it.replaceFirstChar { char -> char.uppercaseChar() } }
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
