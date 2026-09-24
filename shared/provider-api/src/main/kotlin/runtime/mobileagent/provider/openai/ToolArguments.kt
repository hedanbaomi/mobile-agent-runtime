// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Provider-supplied tool arguments, with a bounded, semantics-preserving repair.
 *
 * A tool call whose argument text is not usable JSON can never have been
 * dispatched: the failure is local and the runtime must classify it as an
 * unusable response, not as an outcome that may have reached the provider.
 * The one repair applied here is the narrowest one that keeps the provider's
 * intended values intact -- escaping C0 control characters that appear raw
 * inside a JSON string literal, which the JSON grammar forbids.  Truncated or
 * otherwise incomplete JSON is deliberately *not* completed: guessing the
 * missing structure would be exactly the kind of invention that could dispatch
 * an argument set the provider never sent.
 */
object ToolArguments {
    private val json = Json { ignoreUnknownKeys = true }
    private const val BYTE_ORDER_MARK = '\uFEFF'

    data class Parsed(val json: JsonObject, val text: String, val repaired: Boolean)

    /** Strict parse first, then a semantics-preserving repair; null when the arguments are NOT usable. */
    fun parse(raw: String): Parsed? {
        val text = normalized(raw)
        strict(text)?.let { return Parsed(it, text, false) }
        val repairedText = escapeRawControlCharacters(text) ?: return null
        val repaired = strict(repairedText) ?: return null
        return Parsed(repaired, repairedText, true)
    }

    /** Strict-only probe (no repair). */
    fun strict(raw: String): JsonObject? {
        val text = normalized(raw)
        if (text.isEmpty()) return null
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    }

    /**
     * Escape unescaped C0 control characters that appear INSIDE JSON string literals
     * (JSON forbids raw U+0000..U+001F there). Returns null when nothing changed.
     * MUST NOT alter anything outside string literals, MUST NOT drop/append structure,
     * MUST NOT try to complete truncated JSON (no auto-closing braces/brackets).
     */
    fun escapeRawControlCharacters(raw: String): String? {
        val escaped = StringBuilder(raw.length)
        var insideString = false
        var changed = false
        var index = 0
        while (index < raw.length) {
            val current = raw[index]
            when {
                !insideString -> {
                    escaped.append(current)
                    if (current == '"') insideString = true
                    index++
                }
                // A backslash escapes the next character, so a `\"` never ends the
                // literal and a `\\` never starts one.  The pair is copied verbatim:
                // an already-escaped sequence is never rewritten.
                current == '\\' -> {
                    escaped.append(current)
                    if (index + 1 < raw.length) escaped.append(raw[index + 1])
                    index += 2
                }
                current == '"' -> {
                    escaped.append(current)
                    insideString = false
                    index++
                }
                current.code < 0x20 -> {
                    escaped.append(controlEscape(current))
                    changed = true
                    index++
                }
                else -> {
                    escaped.append(current)
                    index++
                }
            }
        }
        return if (changed) escaped.toString() else null
    }

    /** Shortest legal escape for a C0 control character, matching the JSON grammar. */
    private fun controlEscape(character: Char): String = when (character) {
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        '\b' -> "\\b"
        '\u000C' -> "\\f"
        else -> "\\u" + character.code.toString(16).padStart(4, '0')
    }

    /**
     * Whitespace and a leading UTF-8 BOM are the only tolerated decorations; the
     * returned text is the exact string that the parser accepted, so a caller may
     * forward it to the runtime for its own re-parse.
     */
    private fun normalized(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith(BYTE_ORDER_MARK)) trimmed.removePrefix(BYTE_ORDER_MARK.toString()).trim() else trimmed
    }
}
