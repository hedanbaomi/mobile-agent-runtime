// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/** Small pre-parse guard: bound depth/allocation and reject duplicate (also escaped) keys. */
object TrpgToolArguments {
    private data class Frame(val objectKeys: MutableSet<String>?, var expectingKey: Boolean = true)
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    fun forTool(name: String, text: String, fallback: (String) -> JsonElement): JsonElement =
        if (name in setOf("session_state_read", "session_state_update", "dice_roll")) parse(text) else fallback(text)

    internal fun builtin(name: String, text: String): JsonObject? =
        runCatching { forTool(name, text, Json::parseToJsonElement) as? JsonObject }.getOrNull()

    fun parse(text: String): JsonObject = boundedObject(text, 128 * 1024, 12, 1024)

    /** Display receipts can contain up to 100 detailed dice, but are never tool arguments. */
    fun parseResult(text: String): JsonObject = boundedObject(text, 256 * 1024, 16, 8192)

    private fun boundedObject(text: String, maxBytes: Int, maxDepth: Int, maxNodes: Int): JsonObject {
        require(text.length <= maxBytes && text.toByteArray(Charsets.UTF_8).size <= maxBytes) { "JSON exceeds limit" }
        val stack = mutableListOf<Frame>()
        var index = 0
        var nodes = 0
        while (index < text.length) {
            when (text[index]) {
                '{', '[' -> {
                    require(stack.size < maxDepth) { "JSON exceeds depth limit" }
                    stack += Frame(if (text[index] == '{') hashSetOf() else null)
                    require(++nodes <= maxNodes) { "JSON exceeds node limit" }
                }
                '}', ']' -> { require(stack.isNotEmpty()); stack.removeAt(stack.lastIndex) }
                ',' -> {
                    stack.lastOrNull()?.let { if (it.objectKeys != null) it.expectingKey = true }
                    require(++nodes <= maxNodes) { "JSON exceeds node limit" }
                }
                '"' -> {
                    val start = index++
                    while (index < text.length && text[index] != '"') {
                        if (text[index] == '\\') index++
                        index++
                    }
                    require(index < text.length) { "Unterminated string" }
                    val frame = stack.lastOrNull()
                    if (frame?.objectKeys != null && frame.expectingKey) {
                        val key = json.decodeFromString<String>(text.substring(start, index + 1))
                        require(frame.objectKeys.add(key)) { "Duplicate argument key" }
                        frame.expectingKey = false
                    }
                }
            }
            index++
        }
        require(stack.isEmpty()) { "Incomplete arguments" }
        return json.parseToJsonElement(text) as? JsonObject ?: error("Arguments must be an object")
    }
}
