// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.worldbook

import kotlinx.serialization.json.*

/** JSON Pointer paths address a single immutable original tree, including arbitrary unknown fields. */
internal object WorldBookNativeReferences {
    fun index(root: JsonObject, wanted: Set<JsonElement>): Map<JsonElement, String> {
        val paths = hashMapOf<JsonElement, String>()
        fun visit(node: JsonElement, path: String) {
            if (node in wanted && path.length <= (1 shl 20)) paths.putIfAbsent(node, path)
            when (node) {
                is JsonObject -> node.forEach { (key, value) -> visit(value, "$path/${escape(key)}") }
                is JsonArray -> node.forEachIndexed { index, value -> visit(value, "$path/$index") }
                else -> Unit
            }
        }
        visit(root, "")
        return paths
    }

    fun resolve(root: JsonObject, reference: JsonElement, limits: WorldBookImportLimits): JsonElement {
        val pointer = reference as? JsonPrimitive ?: WorldBookNativeCompact.invalid("Reference must be a JSON Pointer string.")
        WorldBookNativeCompact.requireNative(pointer.isString, "Reference must be a string.")
        val path = pointer.content
        if (path.isEmpty()) return root
        WorldBookNativeCompact.requireNative(path.startsWith('/'), "Reference must be an absolute JSON Pointer.")
        val segments = path.substring(1).split('/')
        WorldBookNativeCompact.requireNative(segments.size <= limits.maxDepth, "Reference path exceeds source depth.")
        var node: JsonElement = root
        for (part in segments) {
            val key = unescape(part)
            node = when (val parent = node) {
                is JsonObject -> parent[key] ?: WorldBookNativeCompact.invalid("Missing reference object key.")
                is JsonArray -> {
                    val index = key.toIntOrNull()
                    WorldBookNativeCompact.requireNative(index != null && index.toString() == key, "Invalid reference array index.")
                    parent.getOrNull(index!!) ?: WorldBookNativeCompact.invalid("Reference array index is out of bounds.")
                }
                else -> WorldBookNativeCompact.invalid("Reference traverses a scalar.")
            }
        }
        return node
    }

    private fun escape(key: String): String = key.replace("~", "~0").replace("/", "~1")

    private fun unescape(key: String): String {
        var index = 0
        while (index < key.length) {
            if (key[index] == '~') {
                WorldBookNativeCompact.requireNative(index + 1 < key.length && key[index + 1] in "01", "Invalid JSON Pointer escape.")
                index++
            }
            index++
        }
        return key.replace("~1", "/").replace("~0", "~")
    }
}
