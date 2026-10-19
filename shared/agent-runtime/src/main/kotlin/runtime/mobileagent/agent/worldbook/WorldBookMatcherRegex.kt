// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent.worldbook

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import runtime.mobileagent.domain.worldbook.WorldBookSettings

/**
 * 关键词解析与匹配（**RE2/J 线性引擎**，不使用 `java.util.regex`）。
 *
 * 为什么换成 RE2/J：Android 上 `java.util.regex.Matcher` 会先把输入 `toString()` 再交给 ICU，
 * 计数型 CharSequence 无法计步；RE2/J 的 `Pattern.matcher(CharSequence)` 走 `MatcherInput.utf16`，
 * 逐 `charAt` 读取，因此 [BudgetedCharSequence] 的步数预算在真机上有效；且 RE2 是线性时间引擎，
 * 不存在回溯爆炸（`(a+)+$`、`(a|aa)+$` 等模式也不会失控），无需再靠静态“回溯启发式”拒绝。
 *
 * 语义与差异（相对 JS/ST，均为显式拒绝而非静默回退）：
 *  - 支持 flags：`i`（Unicode simple case folding）、`m`、`s`；`g` 对无状态 `test` 无影响，忽略；
 *  - 明确拒绝：`u`/`y`/`d`/`v`（无等价语义）、回溯引用（`\1`…`\9`、`\k<name>`）、
 *    环视（`(?=` `(?!` `(?<=` `(?<!`）与其它 RE2 不支持的分组 → 稳定原因码 + 告警，字段原样保留；
 *  - `\d \w \s \b` 为 ASCII 语义（RE2 默认，与 JS/ST 一致）；Unicode 类用 `\p{...}`；
 *  - `use_regex = true`（CCv3）时 keys 即原始模式，`secondary_keys` 被忽略；无效模式按“不匹配”。
 *
 * 资源上界（不可由导入提升）：
 *  - 模式长度 ≤ [WorldBookSettings.maxRegexPatternLength]（硬上限 512）；
 *  - RE2 程序体积 ≤ [MAX_PROGRAM_SIZE]（`Pattern.programSize()`）；
 *  - 每轮匹配受 [StepBudget] 步数预算约束，扫描文本另受 `maxMessageScanChars` 截断。
 */
internal object WorldBookRegex {

    /** RE2 编译后程序体积上限。 */
    const val MAX_PROGRAM_SIZE = 10_000

    sealed interface ParsedKey {
        /** 已编译正则。 */
        data class RegexKey(val pattern: Pattern, val source: String) : ParsedKey

        /** 无效/被拒绝的关键词（不匹配）。 */
        data class Rejected(val reason: String) : ParsedKey
    }

    /** ST `parseRegexFromString`：`/pattern/flags`，内部未转义 `/` 拒绝（不是正则键）。 */
    fun parseSlashRegex(input: String, settings: WorldBookSettings): ParsedKey? {
        if (input.length < 2 || !input.startsWith('/')) return null
        val lastSlash = input.lastIndexOf('/')
        if (lastSlash == 0) return null
        val rawPattern = input.substring(1, lastSlash)
        val flags = input.substring(lastSlash + 1)
        if (rawPattern.contains(UNESCAPED_SLASH)) return null
        return compile(rawPattern.replace("\\/", "/"), flags, settings)
    }

    /** 编译 CCv3 的原始正则模式（无 flags 语法）。 */
    fun compileRaw(source: String, settings: WorldBookSettings): ParsedKey = compile(source, "", settings)

    private fun compile(pattern: String, flags: String, settings: WorldBookSettings): ParsedKey {
        if (pattern.length > settings.maxRegexPatternLength) return ParsedKey.Rejected("regex.pattern-too-long")
        var options = 0
        for (flag in flags) {
            options = options or when (flag) {
                'g' -> 0
                'i' -> Pattern.CASE_INSENSITIVE
                'm' -> Pattern.MULTILINE
                's' -> Pattern.DOTALL
                'u', 'y', 'd', 'v' -> return ParsedKey.Rejected("regex.unsupported-flag-$flag")
                else -> return ParsedKey.Rejected("regex.unsupported-flag")
            }
        }
        unsupportedSyntax(pattern)?.let { return ParsedKey.Rejected(it) }
        val compiled = try {
            if (options == 0) Pattern.compile(pattern) else Pattern.compile(pattern, options)
        } catch (e: PatternSyntaxException) {
            return ParsedKey.Rejected("regex.invalid")
        } catch (e: StackOverflowError) {
            return ParsedKey.Rejected("regex.aborted:stack-overflow")
        }
        if (compiled.programSize() > MAX_PROGRAM_SIZE) return ParsedKey.Rejected("regex.program-too-large")
        return ParsedKey.RegexKey(compiled, pattern)
    }

    /**
     * RE2 无法等价支持、且不能“偷偷回退”到其它引擎的语法：显式拒绝并给出稳定原因码。
     */
    fun unsupportedSyntax(pattern: String): String? {
        var index = 0
        while (index < pattern.length) {
            when (pattern[index]) {
                '\\' -> {
                    val next = pattern.getOrNull(index + 1)
                    if (next != null && next in '1'..'9') return "regex.unsupported-backreference"
                    if (next == 'k' && pattern.getOrNull(index + 2) == '<') return "regex.unsupported-backreference"
                    index++
                }

                '[' -> {
                    index++
                    while (index < pattern.length && pattern[index] != ']') {
                        if (pattern[index] == '\\') index++
                        index++
                    }
                }

                '(' -> {
                    if (pattern.startsWith("(?=", index) || pattern.startsWith("(?!", index) ||
                        pattern.startsWith("(?<=", index) || pattern.startsWith("(?<!", index)
                    ) {
                        return "regex.unsupported-lookaround"
                    }
                    if (pattern.startsWith("(?>", index) || pattern.startsWith("(?(", index) ||
                        pattern.startsWith("(?R", index)
                    ) {
                        return "regex.unsupported-group"
                    }
                }

                else -> Unit
            }
            index++
        }
        return null
    }

    /**
     * 在步数预算内执行匹配（RE2/J 逐 `charAt` 读取输入，预算可真正中断）。
     *
     * 中断只会有两种显式信号，均由调用方记录告警并判为不匹配：
     *  - 步数耗尽：[RegexAbortedException]（`regex.step-budget-exhausted`）；
     *  - 引擎栈溢出（防御性）：标记预算中止后抛 `regex.aborted:stack-overflow`。
     */
    fun test(pattern: Pattern, haystack: CharSequence, budget: StepBudget): Boolean {
        if (budget.exhausted) throw RegexAbortedException("regex.step-budget-exhausted")
        return try {
            pattern.matcher(BudgetedCharSequence(haystack, budget)).find()
        } catch (e: StackOverflowError) {
            budget.abort()
        }
    }

    private val UNESCAPED_SLASH = Regex("(^|[^\\\\])/")
}

/**
 * 无正则时的纯文本匹配。
 *
 * 整词边界**手工比较字符**（不使用 `\W`，因为 Android/ICU 的 `\W` 是 Unicode 语义）：
 * ASCII 模式只把 `[A-Za-z0-9_]` 视作单词字符（与 JS/ST 一致）；Unicode 模式使用
 * `Character.isLetterOrDigit`（设备无关）。多词关键词与 ST 一致退化为子串匹配。
 */
internal object WorldBookPlainMatch {
    fun matches(haystack: String, needle: String, wholeWords: Boolean, asciiBoundaries: Boolean): Boolean {
        if (needle.isEmpty()) return false
        if (!wholeWords) return haystack.contains(needle)
        if (needle.any { it.isWhitespace() }) return haystack.contains(needle)
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            if (hasBoundaries(haystack, index, needle.length, asciiBoundaries)) return true
            index = haystack.indexOf(needle, index + 1)
        }
        return false
    }

    private fun hasBoundaries(haystack: String, index: Int, length: Int, ascii: Boolean): Boolean {
        val beforeOk = index == 0 || !isWordChar(haystack[index - 1], ascii)
        val end = index + length
        val afterOk = end >= haystack.length || !isWordChar(haystack[end], ascii)
        return beforeOk && afterOk
    }

    private fun isWordChar(char: Char, ascii: Boolean): Boolean =
        if (ascii) {
            char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '_'
        } else {
            Character.isLetterOrDigit(char) || char == '_'
        }
}
