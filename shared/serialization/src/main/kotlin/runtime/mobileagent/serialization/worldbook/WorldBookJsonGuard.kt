// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.worldbook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/**
 * 导入前的有界 JSON 体检。
 *
 * 目的：
 *  1. 在真正建树之前拒绝超大输入、过深嵌套、过多节点与超长字符串；
 *  2. 探测**重复键**（kotlinx 会把重复键静默折叠为后者，属于歧义输入，必须拒绝）；
 *  3. 把语法错误统一映射为 [WorldBookNoticeCodes.JSON_SYNTAX]。
 *
 * 体检通过后再交给 kotlinx 解析：此时输入规模已被证明有界，因此解析不会失控。
 * 递归深度上限为 [WorldBookImportLimits.maxDepth]（默认 32），栈深安全。
 */
internal object WorldBookJsonGuard {

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    /** 体检结果：根对象 + 体检阶段产生的非致命说明（例如 BOM 剥离）。 */
    class Guarded(val root: JsonObject, val notices: List<WorldBookImportNotice>)

    /**
     * 校验并解析。
     *
     * @throws WorldBookImportException 输入越界、重复键或语法错误。
     */
    fun parse(text: String, limits: WorldBookImportLimits): Guarded {
        val notices = mutableListOf<WorldBookImportNotice>()
        var payload = text
        if (payload.startsWith('\uFEFF')) {
            payload = payload.substring(1)
            notices += WorldBookImportNotice(null, WorldBookNoticeCodes.JSON_BOM, "Stripped UTF-8 BOM before parsing.")
        }
        if (payload.length > limits.maxChars) {
            throw WorldBookImportException(
                WorldBookNoticeCodes.JSON_SIZE_LIMIT,
                "Input has ${payload.length} characters, limit is ${limits.maxChars}.",
                notices,
            )
        }
        Scanner(payload, limits, notices).scanDocument()
        val element: JsonElement = try {
            json.parseToJsonElement(payload)
        } catch (e: Exception) {
            throw WorldBookImportException(
                WorldBookNoticeCodes.JSON_SYNTAX,
                "Malformed JSON: ${e.message ?: e::class.simpleName}",
                notices,
            )
        }
        val root = element as? JsonObject ?: throw WorldBookImportException(
            WorldBookNoticeCodes.JSON_ROOT_NOT_OBJECT,
            "World book root must be a JSON object, was ${element::class.simpleName}.",
            notices,
        )
        return Guarded(root, notices)
    }

    /** 内部体检器状态（一次性）。 */
    private class Scanner(
        private val text: String,
        private val limits: WorldBookImportLimits,
        private val notices: MutableList<WorldBookImportNotice>,
    ) {
        private var index = 0
        private var nodes = 0

        fun scanDocument() {
            skipWhitespace()
            if (index >= text.length) {
                fail(WorldBookNoticeCodes.JSON_SYNTAX, "Input is empty or whitespace only.")
            }
            scanValue(0)
            skipWhitespace()
            if (index != text.length) {
                fail(WorldBookNoticeCodes.JSON_SYNTAX, "Trailing content at offset $index.")
            }
        }

        private fun scanValue(depth: Int) {
            if (depth > limits.maxDepth) {
                fail(
                    WorldBookNoticeCodes.JSON_DEPTH_LIMIT,
                    "Nesting deeper than ${limits.maxDepth} at offset $index.",
                )
            }
            nodes++
            if (nodes > limits.maxNodes) {
                fail(
                    WorldBookNoticeCodes.JSON_NODE_LIMIT,
                    "More than ${limits.maxNodes} JSON values.",
                )
            }
            skipWhitespace()
            if (index >= text.length) {
                fail(WorldBookNoticeCodes.JSON_SYNTAX, "Unexpected end of input at offset $index.")
            }
            when (text[index]) {
                '{' -> scanObject(depth)
                '[' -> scanArray(depth)
                '"' -> skipString()
                't' -> expectLiteral("true")
                'f' -> expectLiteral("false")
                'n' -> expectLiteral("null")
                else -> scanNumber()
            }
        }

        private fun scanObject(depth: Int) {
            index++ // '{'
            val keys = HashSet<String>()
            skipWhitespace()
            if (index < text.length && text[index] == '}') {
                index++
                return
            }
            while (true) {
                skipWhitespace()
                if (index >= text.length || text[index] != '"') {
                    fail(WorldBookNoticeCodes.JSON_SYNTAX, "Expected object key at offset $index.")
                }
                val key = readStringValue()
                if (!keys.add(key)) {
                    fail(
                        WorldBookNoticeCodes.JSON_DUPLICATE_KEY,
                        "Duplicate object key '${key.take(64)}' at offset $index.",
                    )
                }
                if (keys.size > limits.maxObjectKeys) {
                    fail(
                        WorldBookNoticeCodes.JSON_OBJECT_KEYS_LIMIT,
                        "Object with more than ${limits.maxObjectKeys} keys.",
                    )
                }
                skipWhitespace()
                if (index >= text.length || text[index] != ':') {
                    fail(WorldBookNoticeCodes.JSON_SYNTAX, "Expected ':' at offset $index.")
                }
                index++
                scanValue(depth + 1)
                skipWhitespace()
                if (index >= text.length) {
                    fail(WorldBookNoticeCodes.JSON_SYNTAX, "Unterminated object.")
                }
                when (text[index]) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return
                    }

                    else -> fail(WorldBookNoticeCodes.JSON_SYNTAX, "Expected ',' or '}' at offset $index.")
                }
            }
        }

        private fun scanArray(depth: Int) {
            index++ // '['
            skipWhitespace()
            if (index < text.length && text[index] == ']') {
                index++
                return
            }
            while (true) {
                scanValue(depth + 1)
                skipWhitespace()
                if (index >= text.length) {
                    fail(WorldBookNoticeCodes.JSON_SYNTAX, "Unterminated array.")
                }
                when (text[index]) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return
                    }

                    else -> fail(WorldBookNoticeCodes.JSON_SYNTAX, "Expected ',' or ']' at offset $index.")
                }
            }
        }

        private fun expectLiteral(literal: String) {
            if (!text.startsWith(literal, index)) {
                fail(WorldBookNoticeCodes.JSON_SYNTAX, "Invalid literal at offset $index.")
            }
            index += literal.length
        }

        private fun scanNumber() {
            val start = index
            if (index < text.length && (text[index] == '-' || text[index] == '+')) index++
            var digits = 0
            while (index < text.length && text[index].isDigit()) {
                index++
                digits++
            }
            if (index < text.length && text[index] == '.') {
                index++
                while (index < text.length && text[index].isDigit()) {
                    index++
                    digits++
                }
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (index < text.length && (text[index] == '-' || text[index] == '+')) index++
                while (index < text.length && text[index].isDigit()) index++
            }
            if (digits == 0) {
                fail(WorldBookNoticeCodes.JSON_SYNTAX, "Invalid number at offset $start.")
            }
        }

        /** 跳过字符串值，仅校验转义与长度（不分配解码结果）。 */
        private fun skipString() {
            index++ // '"'
            var length = 0
            while (index < text.length) {
                val c = text[index]
                when {
                    c == '"' -> {
                        index++
                        return
                    }

                    c == '\\' -> {
                        index++
                        if (index >= text.length) {
                            fail(WorldBookNoticeCodes.JSON_SYNTAX, "Dangling escape at end of input.")
                        }
                        if (text[index] == 'u') {
                            repeat(4) {
                                index++
                                if (index >= text.length || !text[index].isHexDigit()) {
                                    fail(WorldBookNoticeCodes.JSON_SYNTAX, "Invalid \\u escape at offset $index.")
                                }
                            }
                        }
                        index++
                        length++
                    }

                    c.code < 0x20 -> fail(
                        WorldBookNoticeCodes.JSON_SYNTAX,
                        "Raw control character in string at offset $index.",
                    )

                    else -> {
                        index++
                        length++
                    }
                }
                checkStringLength(length)
            }
            fail(WorldBookNoticeCodes.JSON_SYNTAX, "Unterminated string.")
        }

        /** 解码对象键（仅键需要逐字符比较，用于重复键探测）。 */
        private fun readStringValue(): String {
            index++ // '"'
            val sb = StringBuilder()
            while (index < text.length) {
                val c = text[index]
                when {
                    c == '"' -> {
                        index++
                        return sb.toString()
                    }

                    c == '\\' -> {
                        index++
                        if (index >= text.length) {
                            fail(WorldBookNoticeCodes.JSON_SYNTAX, "Dangling escape at end of input.")
                        }
                        when (val esc = text[index]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 >= text.length) {
                                    fail(WorldBookNoticeCodes.JSON_SYNTAX, "Invalid \\u escape at offset $index.")
                                }
                                val hex = text.substring(index + 1, index + 5)
                                val code = hex.toIntOrNull(16)
                                    ?: fail(WorldBookNoticeCodes.JSON_SYNTAX, "Invalid \\u escape '$hex'.")
                                sb.append(code.toChar())
                                index += 4
                            }

                            else -> fail(WorldBookNoticeCodes.JSON_SYNTAX, "Invalid escape '\\$esc' at offset $index.")
                        }
                        index++
                    }

                    c.code < 0x20 -> fail(
                        WorldBookNoticeCodes.JSON_SYNTAX,
                        "Raw control character in string at offset $index.",
                    )

                    else -> {
                        sb.append(c)
                        index++
                    }
                }
                checkStringLength(sb.length)
            }
            fail(WorldBookNoticeCodes.JSON_SYNTAX, "Unterminated string.")
        }

        private fun checkStringLength(length: Int) {
            if (length > limits.maxStringChars) {
                fail(
                    WorldBookNoticeCodes.JSON_STRING_LIMIT,
                    "String longer than ${limits.maxStringChars} characters.",
                )
            }
        }

        private fun skipWhitespace() {
            while (index < text.length) {
                when (text[index]) {
                    ' ', '\t', '\n', '\r' -> index++
                    else -> return
                }
            }
        }

        private fun fail(code: String, message: String): Nothing =
            throw WorldBookImportException(code, message, notices)
    }
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
