// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package runtime.mobileagent.serialization.worldbook

import java.util.IdentityHashMap
import kotlinx.serialization.json.*
import runtime.mobileagent.domain.worldbook.WorldBookEntry

/** Source limits remain unchanged. Native allowances account for three stored semantic views and
 * at most one sparse field/reference per entry field; expansion is charged before typed decoding. */
internal object WorldBookNativeBounds {
    private val fieldCount = WorldBookEntry.serializer().descriptor.elementsCount
    private fun bounded(value: Long): Int = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun nativeLimits(limits: WorldBookImportLimits): WorldBookImportLimits = limits.copy(
        maxChars = bounded(3L * limits.maxChars + limits.maxEntries.toLong() * fieldCount * 32),
        maxNodes = bounded(3L * limits.maxNodes + limits.maxEntries.toLong() * (fieldCount * 2 + 4) + 64),
        maxDepth = bounded(limits.maxDepth.toLong() + 4),
    )

    fun parse(text: String, limits: WorldBookImportLimits): WorldBookJsonGuard.Guarded {
        try {
            return WorldBookJsonGuard.parse(text, limits)
        } catch (failure: WorldBookImportException) {
            if (failure.code !in setOf(
                    WorldBookNoticeCodes.JSON_SIZE_LIMIT, WorldBookNoticeCodes.JSON_NODE_LIMIT,
                    WorldBookNoticeCodes.JSON_DEPTH_LIMIT,
                )) throw failure
            val expanded = WorldBookJsonGuard.parse(text, nativeLimits(limits))
            if ((expanded.root["spec"] as? JsonPrimitive)?.content != "mobileagent.worldbook") throw failure
            return expanded
        }
    }

    fun validateSource(source: JsonObject, limits: WorldBookImportLimits) {
        val metrics = Measure().of(source)
        verify(metrics, limits, limits.maxNodes.toLong(), limits.maxChars.toLong(), limits.maxDepth)
    }

    class Expansion(private val limits: WorldBookImportLimits) {
        private val measure = Measure()
        private var nodes = 0L
        private var chars = 0L

        fun add(entry: JsonObject) {
            val metrics = measure.of(entry)
            nodes += metrics.nodes
            chars += metrics.chars
            // Entry originals + normalized values each get one source-sized allowance; schema fields
            // contribute at most one default/sparse scalar and a <=32 character property name.
            verify(
                metrics.copy(nodes = nodes, chars = chars), limits,
                2L * limits.maxNodes + limits.maxEntries.toLong() * (fieldCount + 1),
                2L * limits.maxChars + limits.maxEntries.toLong() * fieldCount * 32,
                bounded(limits.maxDepth.toLong() + 2),
            )
        }
    }

    private data class Metrics(
        val nodes: Long, val chars: Long, val depth: Int, val objectKeys: Int, val stringChars: Int,
    )

    /** Identity memoization makes repeated references constant work without hiding their full cost. */
    private class Measure {
        private val cache = IdentityHashMap<JsonElement, Metrics>()

        fun of(node: JsonElement): Metrics {
            cache[node]?.let { return it }
            val children = when (node) {
                is JsonObject -> node.values
                is JsonArray -> node
                else -> emptyList()
            }
            var nodes = 1L
            var chars = when (node) {
                is JsonObject -> 2L + node.keys.sumOf { JsonPrimitive(it).toString().length.toLong() + 1 } + (node.size - 1).coerceAtLeast(0)
                is JsonArray -> 2L + (node.size - 1).coerceAtLeast(0)
                else -> node.toString().length.toLong()
            }
            var depth = 0
            var keys = (node as? JsonObject)?.size ?: 0
            var string = (node as? JsonPrimitive)?.takeIf { it.isString }?.content?.length ?: 0
            if (node is JsonObject) string = maxOf(string, node.keys.maxOfOrNull { it.length } ?: 0)
            children.forEach {
                val child = of(it)
                nodes += child.nodes
                chars += child.chars
                depth = maxOf(depth, child.depth + 1)
                keys = maxOf(keys, child.objectKeys)
                string = maxOf(string, child.stringChars)
            }
            return Metrics(nodes, chars, depth, keys, string).also { cache[node] = it }
        }
    }

    private fun verify(metrics: Metrics, limits: WorldBookImportLimits, nodes: Long, chars: Long, depth: Int) {
        val code = when {
            metrics.nodes > nodes -> WorldBookNoticeCodes.JSON_NODE_LIMIT
            metrics.chars > chars -> WorldBookNoticeCodes.JSON_SIZE_LIMIT
            metrics.depth > depth -> WorldBookNoticeCodes.JSON_DEPTH_LIMIT
            metrics.objectKeys > limits.maxObjectKeys -> WorldBookNoticeCodes.JSON_OBJECT_KEYS_LIMIT
            metrics.stringChars > limits.maxStringChars -> WorldBookNoticeCodes.JSON_STRING_LIMIT
            else -> return
        }
        throw WorldBookImportException(code, "Native source or expanded entries exceed their bounded JSON allowance.")
    }
}
