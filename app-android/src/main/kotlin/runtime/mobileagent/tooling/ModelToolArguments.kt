// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import kotlinx.serialization.json.*
import runtime.mobileagent.domain.BoundedJson
import runtime.mobileagent.skills.tooling.ToolSpec
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.skills.ToolSpec as LegacyToolSpec

/** One bounded model-JSON entrypoint for ordinary and controlled file/shell frontends. */
object ModelToolArguments {
    const val WORKSPACE_SCHEMA_VERSION = 4

    /** Result of reading the `workspace_id` / `workspaceId` alias pair exactly once. */
    sealed interface WorkspaceIdSelection {
        data object Absent : WorkspaceIdSelection
        data object Malformed : WorkspaceIdSelection
        data class Present(val id: String) : WorkspaceIdSelection
    }

    /**
     * Strict alias rule shared by routing, approval and typed normalization: the
     * camelCase alias is accepted only as an alternate spelling of one value.
     * Supplying both spellings with different values is a request error, not a
     * precedence choice.
     */
    fun workspaceIdSelection(text: String): WorkspaceIdSelection {
        val value = objectOrNull(text) ?: return WorkspaceIdSelection.Malformed
        val snake = value["workspace_id"]
        val camel = value["workspaceId"]
        if (snake == null && camel == null) return WorkspaceIdSelection.Absent
        val id = workspaceAliasValue(snake, camel) ?: return WorkspaceIdSelection.Malformed
        return WorkspaceIdSelection.Present(id)
    }

    fun workspaceId(text: String): String? =
        (workspaceIdSelection(text) as? WorkspaceIdSelection.Present)?.id

    /**
     * Canonical model spelling for one bounded object: `workspaceId` is rewritten
     * to `workspace_id` only when the alias pair carries one identical value.
     * Text without the alias is returned unchanged so existing identities and
     * replay keys keep their exact spelling.
     */
    fun canonicalWorkspaceObject(value: JsonObject): JsonObject? {
        val camel = value["workspaceId"] ?: return value
        val snake = value["workspace_id"]
        val id = workspaceAliasValue(snake, camel) ?: return null
        val result = linkedMapOf<String, JsonElement>()
        for ((key, element) in value) {
            when (key) {
                "workspaceId" -> Unit
                "workspace_id" -> result[key] = JsonPrimitive(id)
                else -> result[key] = element
            }
        }
        if ("workspace_id" !in result) result["workspace_id"] = JsonPrimitive(id)
        return JsonObject(result)
    }

    fun canonicalWorkspaceArguments(text: String): String? {
        val value = objectOrNull(text) ?: return null
        if (!value.containsKey("workspaceId")) return text
        return canonicalWorkspaceObject(value)?.toString()
    }

    private fun workspaceAliasValue(snake: JsonElement?, camel: JsonElement?): String? {
        fun scalar(element: JsonElement?): String? {
            if (element == null) return null
            val primitive = element as? JsonPrimitive ?: return null
            if (!primitive.isString) return null
            val content = primitive.content
            if (content.isBlank() || content.length > MAX_WORKSPACE_ID_LENGTH) return null
            return content
        }
        if (snake == null) return scalar(camel)
        if (camel == null) return scalar(snake)
        val first = scalar(snake) ?: return null
        val second = scalar(camel) ?: return null
        return if (first == second) first else null
    }

    /** Opaque model token to the ordinary backend's genuine numeric version, without inventing a CAS. */
    fun numericVersion(value: JsonObject): JsonObject? {
        val result = value.toMutableMap()
        for (key in listOf("expected_version", "expectedVersion")) {
            val raw = value[key] ?: continue
            val scalar = raw as? JsonPrimitive ?: return null
            val version = scalar.longOrNull?.takeIf { it >= 0 } ?: return null
            if (scalar.isString && scalar.content != version.toString()) return null
            result[key] = JsonPrimitive(version)
        }
        return JsonObject(result)
    }

    /** One model schema vocabulary; platform capability differences remain explicit inputs. */
    fun workspaceSchema(name: String, root: Boolean = false): String {
        if (name == "file_glob") return WorkspaceSearchProtocol.globSpec.inputSchema
        if (name == "file_grep") return WorkspaceSearchProtocol.grepSpec.inputSchema
        val properties = linkedMapOf<String, JsonElement>()
        fun string(name: String, max: Int, min: Int? = null) {
            properties[name] = buildJsonObject { put("type", "string"); put("maxLength", max); min?.let { put("minLength", it) } }
        }
        fun integer(name: String, min: Long, max: Long? = null) {
            properties[name] = buildJsonObject { put("type", "integer"); put("minimum", min); max?.let { put("maximum", it) } }
        }
        fun boolean(name: String) { properties[name] = buildJsonObject { put("type", "boolean") } }
        val required = mutableListOf("workspace_id")
        string("workspace_id", 128, 1)
        if (name == "shell_exec") {
            string("command", 16384, 1); required += "command"; string("cwd_relative_path", 1024)
        } else if (name == "file_move") {
            string("source_relative_path", 1024); string("destination_relative_path", 1024)
            required += listOf("source_relative_path", "destination_relative_path"); boolean("replace_existing")
        } else {
            string("relative_path", 512)
            if (name != "file_list") required += "relative_path"
            when (name) {
                "file_list" -> { integer("max_entries", 1, 1000); string("cursor", 512, 1) }
                "file_read_text" -> { integer("max_bytes", 1, 262144); integer("offset_bytes", 0) }
                "file_write_text" -> { string("text", 262144); required += "text"; boolean("replace") }
                "file_apply_patch" -> {
                    string("patch", 262144, 1); required += listOf("patch", "expected_version")
                    properties["format"] = buildJsonObject {
                        put("type", "string"); put("enum", JsonArray((if (root) listOf("unified_diff", "search_replace") else listOf("unified_diff", "replace")).map(::JsonPrimitive)))
                    }
                }
                "file_set_metadata" -> {
                    integer("mode", 0, 511); integer("uid", 0); integer("gid", 0); boolean("owner_transfer_authorized")
                    required += "expected_version"
                }
            }
            if (name in setOf("file_write_text", "file_apply_patch", "file_set_metadata") ||
                (!root && name in setOf("file_create_directory", "file_delete"))) {
                string("expected_version", 128, 1)
            }
        }
        return buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            put("required", JsonArray(required.map(::JsonPrimitive))); put("properties", JsonObject(properties))
        }.toString()
    }

    /**
     * Merge only genuinely shared model names; backend routing remains in the runtime.
     *
     * A merged schema may only advertise what every mounted backend can accept.
     * Properties that one side cannot parse (for example `expected_version` on a
     * Root delete) are omitted instead of being approved and then rejected by the
     * selected backend; enum-valued properties keep only their common values
     * (for example the two patch formats share only `unified_diff`).  Callers must
     * still consult `workspace_list` for the selected workspace's exact contract.
     */
    fun mergeSpecs(specs: List<LegacyToolSpec>): List<ToolSpec> = specs.groupBy { it.name }.map { (name, entries) ->
        val first = entries.first()
        if (entries.size == 1) {
            ToolSpec(name, first.description, first.parametersJson,
                entries.map { it.capability }.distinct().singleOrNull()?.takeIf(String::isNotBlank)?.let(::CapabilityId),
                first.sideEffect, first.schemaVersion)
        } else {
            val schemas = entries.map { Json.parseToJsonElement(it.parametersJson).jsonObject }
            val propertiesByKey = schemas.flatMap { (it["properties"] as? JsonObject).orEmpty().entries }
                .groupBy({ it.key }, { it.value })
            val properties = linkedMapOf<String, JsonElement>()
            val dropped = mutableListOf<String>()
            var narrowed = false
            for ((key, candidates) in propertiesByKey) {
                if (candidates.size != schemas.size) {
                    dropped += key
                    continue
                }
                val distinct = candidates.distinct()
                val enums = distinct.map { (it as? JsonObject)?.get("enum") as? JsonArray }
                if (enums.all { it != null }) {
                    var allowed = enums.first()!!.toList()
                    for (next in enums.drop(1)) {
                        val values = next!!.toSet()
                        allowed = allowed.filter { it in values }
                    }
                    if (allowed.isEmpty()) {
                        dropped += key
                        continue
                    }
                    if (allowed.size != enums.first()!!.size) narrowed = true
                    val base = (distinct.first() as JsonObject).toMutableMap()
                    base["enum"] = JsonArray(allowed)
                    properties[key] = JsonObject(base)
                } else if (distinct.size == 1) {
                    properties[key] = distinct.single()
                } else {
                    properties[key] = buildJsonObject { put("anyOf", JsonArray(distinct)) }
                }
            }
            val required = schemas.map { (it["required"] as? JsonArray).orEmpty().toSet() }
                .reduce { a, b -> a intersect b }
                .filter { it.jsonPrimitive.content in properties }
            val capabilityNote = if (dropped.isNotEmpty() || narrowed) {
                " Schema omits options that not every mounted backend accepts and keeps only shared patch formats; call workspace_list for the selected workspace's expected_version operations and patch_formats."
            } else ""
            val schema = buildJsonObject {
                put("type", "object"); put("additionalProperties", false)
                put("properties", JsonObject(properties)); put("required", JsonArray(required.toList()))
            }.toString()
            ToolSpec(name, "Execute $name in the identified authorized workspace.$capabilityNote",
                schema, entries.map { it.capability }.distinct().singleOrNull()?.takeIf(String::isNotBlank)?.let(::CapabilityId),
                entries.any { it.sideEffect }, entries.maxOf { it.schemaVersion })
        }
    }

    fun objectOrNull(text: String, maxBytes: Int = 512 * 1024, maxDepth: Int = 8, maxNodes: Int = 512): JsonObject? {
        if (text.length !in 2..maxBytes) return null
        return runCatching {
            BoundedJson.requireStrictJsonObject(text, maxBytes, maxDepth, maxNodes)
            Json.parseToJsonElement(text) as? JsonObject
        }.getOrNull()
    }

    /**
     * Validate the closed scalar property shape before a platform's typed codec translates it.
     * Per-operation bounds and path/version semantics remain in that typed request constructor.
     * Explicit null on an optional property has the same meaning as omission.
     */
    fun scalarObjectOrNull(spec: ToolSpec, text: String): JsonObject? =
        objectOrNull(text)?.let { scalarObjectOrNull(spec, it) }

    fun scalarObjectOrNull(spec: ToolSpec, value: JsonObject): JsonObject? = runCatching {
        val schema = Json.parseToJsonElement(spec.inputSchema).jsonObject
        val properties = schema.getValue("properties").jsonObject
        val required = (schema["required"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
        if (!value.keys.containsAll(required) || value.keys.any { it !in properties }) return null
        val normalized = value.filterValues { it != JsonNull }
        if (!normalized.keys.containsAll(required)) return null
        for ((key, element) in normalized) {
            val primitive = element as? JsonPrimitive ?: return null
            val type = properties.getValue(key).jsonObject.getValue("type").jsonPrimitive.content
            val valid = when (type) {
                "string" -> value.stringElement(key)
                "integer" -> !primitive.isString && primitive.longOrNull != null
                "boolean" -> value.booleanElement(key)
                else -> false
            }
            if (!valid) return null
        }
        JsonObject(normalized)
    }.getOrNull()

    // Scalar and alias checks shared by model-facing workspace frontends.
    internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
    internal fun JsonObject.stringAllowEmpty(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    internal fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.contentOrNull?.toBooleanStrictOrNull()
    internal fun JsonObject.stringElement(key: String): Boolean = (this[key] as? JsonPrimitive)?.isString == true
    internal fun JsonObject.booleanElement(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.let { !it.isString && it.contentOrNull?.toBooleanStrictOrNull() != null } == true

    internal fun JsonObject.containsAny(first: String, second: String): Boolean =
        containsKey(first) || containsKey(second)

    internal fun JsonObject.aliasElement(first: String, second: String): kotlinx.serialization.json.JsonElement? {
        val firstValue = this[first]
        val secondValue = this[second]
        require(firstValue == null || secondValue == null || firstValue == secondValue) {
            "Conflicting argument aliases"
        }
        return firstValue ?: secondValue
    }

    internal fun JsonObject.aliasString(first: String, second: String): String? =
        (aliasElement(first, second) as? JsonPrimitive)
            ?.takeIf { it.isString && it.content.isNotBlank() }
            ?.content

    internal fun JsonObject.aliasStringAllowEmpty(first: String, second: String): String? =
        (aliasElement(first, second) as? JsonPrimitive)?.takeIf { it.isString }?.content

    internal fun JsonObject.aliasStringElement(first: String, second: String): Boolean =
        !containsAny(first, second) || (aliasElement(first, second) as? JsonPrimitive)?.isString == true

    internal fun JsonObject.aliasLong(first: String, second: String): Long? =
        (aliasElement(first, second) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    internal fun JsonObject.aliasLongElement(first: String, second: String): Boolean =
        !containsAny(first, second) ||
            (aliasElement(first, second) as? JsonPrimitive)?.let { !it.isString && it.longOrNull != null } == true

    internal fun JsonObject.aliasBoolean(first: String, second: String): Boolean? =
        (aliasElement(first, second) as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.contentOrNull
            ?.toBooleanStrictOrNull()

    internal fun JsonObject.aliasBooleanElement(first: String, second: String): Boolean =
        !containsAny(first, second) ||
            (aliasElement(first, second) as? JsonPrimitive)
                ?.let { !it.isString && it.contentOrNull?.toBooleanStrictOrNull() != null } == true

    private const val MAX_WORKSPACE_ID_LENGTH = 128
}
