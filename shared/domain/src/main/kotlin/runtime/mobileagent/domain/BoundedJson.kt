// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.json.Json

/** Duplicate-key and resource guard before a JSON codec validates grammar and types. */
object BoundedJson {
    private data class Frame(val keys: MutableSet<String>?, var expectingKey: Boolean = true)

    fun requireStrictJsonObject(text: String, maxBytes: Int, maxDepth: Int, maxNodes: Int) {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        require(utf8.size in 2..maxBytes && !text.contains('\u0000')) { "JSON size or content is invalid" }
        val stack = mutableListOf<Frame>()
        var i = 0
        var nodes = 0
        var sawRootObject = false
        while (i < text.length) {
            when (val ch = text[i]) {
                '{', '[' -> {
                    if (stack.isEmpty()) sawRootObject = (ch == '{')
                    require(stack.size < maxDepth && ++nodes <= maxNodes) { "JSON exceeds structure budget" }
                    stack += Frame(if (ch == '{') hashSetOf() else null)
                }
                '}', ']' -> {
                    require(stack.isNotEmpty()) { "Unbalanced JSON closing token" }
                    stack.removeAt(stack.lastIndex)
                }
                ',' -> {
                    stack.lastOrNull()?.let { if (it.keys != null) it.expectingKey = true }
                    require(++nodes <= maxNodes) { "JSON exceeds node budget" }
                }
                '"' -> {
                    val start = i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') i++
                        i++
                    }
                    require(i < text.length) { "Unterminated JSON string" }
                    val frame = stack.lastOrNull()
                    if (frame?.keys != null && frame.expectingKey) {
                        val key = Json.decodeFromString(kotlinx.serialization.serializer<String>(), text.substring(start, i + 1))
                        require(frame.keys.add(key)) { "Duplicate JSON object key" }
                        frame.expectingKey = false
                    }
                }
            }
            i++
        }
        require(sawRootObject && stack.isEmpty()) { "JSON root must be a balanced object" }
    }
}
