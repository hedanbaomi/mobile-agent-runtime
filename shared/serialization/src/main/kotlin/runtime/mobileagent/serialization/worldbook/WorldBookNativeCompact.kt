// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package runtime.mobileagent.serialization.worldbook

import kotlinx.serialization.json.*
import runtime.mobileagent.domain.worldbook.WorldBookDocument
import runtime.mobileagent.domain.worldbook.WorldBookEntry
import runtime.mobileagent.domain.worldbook.WorldBookSettings

/** Version 2 shares only exact source subtrees. References never target other entries/references. */
internal object WorldBookNativeCompact {
    private val json = Json { encodeDefaults = false; explicitNulls = true }
    private val fields = WorldBookEntry.serializer().descriptor.let { descriptor ->
        (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()
    }
    private val rootFields = setOf("spec", "version", "format", "name", "description", "settings", "originalJson", "entries")

    fun encode(document: WorldBookDocument): String {
        val entries = document.entries.map { entry ->
            // Tree encoding JsonElement through its serializer can rewrite numeric literals (1.00
            // becomes 1.0). Keep both arbitrary JSON fields as the actual source objects.
            val fields = json.encodeToJsonElement(entry).jsonObject.toMutableMap()
            if (entry.originalJson.isNotEmpty()) fields["originalJson"] = entry.originalJson
            if (entry.extensions.isNotEmpty()) fields["extensions"] = entry.extensions
            JsonObject(fields)
        }
        val paths = WorldBookNativeReferences.index(document.originalJson, entries.flatMap { it.values }.toSet())
        val root = buildJsonObject {
            put("spec", "mobileagent.worldbook")
            put("version", 2)
            put("format", document.format.name)
            put("name", document.name)
            put("description", document.description)
            put("settings", json.encodeToJsonElement(document.settings))
            put("originalJson", document.originalJson)
            put("entries", JsonArray(entries.map { pack(it, paths) }))
        }
        return root.toString()
    }

    private fun pack(entry: JsonObject, paths: Map<JsonElement, String>): JsonObject {
        val values = linkedMapOf<String, JsonElement>()
        val references = linkedMapOf<String, JsonElement>()
        entry.forEach { (key, value) ->
            val path = paths[value]
            if (path != null && path.length + 16 < value.toString().length) {
                references[key] = JsonPrimitive(path)
            } else {
                values[key] = value
            }
        }
        return buildJsonObject {
            put("values", JsonObject(values))
            if (references.isNotEmpty()) put("references", JsonObject(references))
        }
    }

    fun expand(root: JsonObject, limits: WorldBookImportLimits): JsonObject {
        val version = (root["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        if (version == 1) return root
        requireNative(version == 2, "Unsupported native world book version.")
        requireNative(root.keys.all { it in rootFields }, "Unknown native envelope field.")
        val original = root["originalJson"] as? JsonObject ?: invalid("Native originalJson must be an object.")
        WorldBookNativeBounds.validateSource(original, limits)
        root["settings"]?.let {
            try { json.decodeFromJsonElement<WorldBookSettings>(it) } catch (_: Exception) {
                invalid("Malformed native settings.")
            }
        }
        val entries = root["entries"] as? JsonArray ?: invalid("Native entries must be an array.")
        if (entries.size > limits.maxEntries) throw WorldBookImportException(
            WorldBookNoticeCodes.ENTRY_COUNT_LIMIT, "Native entry count exceeds ${limits.maxEntries}.",
        )
        val budget = WorldBookNativeBounds.Expansion(limits)
        val expanded = entries.map { unpack(it, original, budget, limits) }
        return JsonObject(root + ("entries" to JsonArray(expanded)))
    }

    private fun unpack(
        element: JsonElement,
        original: JsonObject,
        budget: WorldBookNativeBounds.Expansion,
        limits: WorldBookImportLimits,
    ): JsonObject {
        val wrapper = element as? JsonObject ?: invalid("Native entry must be an object.")
        requireNative(wrapper.keys.all { it == "values" || it == "references" }, "Unknown native entry field.")
        val values = wrapper["values"] as? JsonObject ?: invalid("Native entry values must be an object.")
        val references = wrapper["references"]?.let { it as? JsonObject ?: invalid("Invalid references object.") }
            ?: JsonObject(emptyMap())
        requireNative((values.keys + references.keys).all { it in fields }, "Unknown native typed field.")
        requireNative(values.keys.intersect(references.keys).isEmpty(), "Native field has a value and a reference.")
        val merged = values.toMutableMap()
        references.forEach { (field, reference) ->
            merged[field] = WorldBookNativeReferences.resolve(original, reference, limits)
        }
        val result = JsonObject(merged)
        budget.add(result)
        return result
    }

    internal fun requireNative(condition: Boolean, message: String) {
        if (!condition) invalid(message)
    }

    internal fun invalid(message: String): Nothing =
        throw WorldBookImportException(WorldBookNoticeCodes.ENTRY_FIELD_DROPPED, message)
}
