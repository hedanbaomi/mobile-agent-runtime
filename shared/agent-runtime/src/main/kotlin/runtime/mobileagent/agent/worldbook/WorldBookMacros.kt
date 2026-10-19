// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent.worldbook

import runtime.mobileagent.domain.worldbook.WorldBookContext

/**
 * 世界书文本宏替换（安全子集，**有界单遍追加**）。
 *
 * 已实现（对应 Character Card V3 “Curly Braced Syntaxes” 与 ST 内建）：
 *  - `{{char}}` / `<CHAR>` / `<BOT>` → 角色名；`{{user}}` / `<USER>` → 用户名；`<GROUP>` → 空串；
 *  - `{{description}}` `{{personality}}` `{{scenario}}` `{{persona}}` `{{charPrompt}}`
 *    `{{charInstruction}}` `{{charDepthPrompt}}` `{{creatorNotes}}` `{{mesExamples}}` → 上下文对应字段；
 *  - `{{random::a::b}}` / `{{random:a,b}}`、`{{pick::…}}` / `{{pick:…}}` → seed 派生的确定性选择；
 *  - `{{roll:N}}` / `{{roll N}}` / `{{roll:dN}}` → seed 派生的 1..N；
 *  - `{{// A}}`、`{{comment: A}}` → 空串；`{{hidden_key:A}}` → 空串但 A 进入递归匹配源；
 *  - `{{reverse:A}}`、`{{newline}}`、`{{noop}}`、`{{trim}}`。
 *
 * 明确**不**替换（保留原文，并记入 [Result.preserved] 与引擎 warnings）：
 *  `{{time}}` `{{date}}` `{{idle_duration}}` `{{lastMessage}}` `{{input}}` `{{maxPrompt}}`
 *  `{{outlet::x}}` `{{getvar::x}}` `{{banned "x"}}` `{{original}}` `{{timeDiff::a::b}}` 等
 *  依赖运行时环境/外部脚本/Slash 自动化的宏；外部 STscript、Quick Reply 自动化不执行。
 *
 * 安全边界（P1）：
 *  - **单遍**扫描 + 有界 [StringBuilder] 追加，不做“先全量展开再检查”；
 *  - 输出超过 [maxOutputChars]（默认 [DEFAULT_MAX_OUTPUT_CHARS] = 1 MiB，与单字段导入上限同阶）
 *    立即停止并返回 [Result.rejected] = `macro.output-limit-exceeded`，不再分配；
 *  - 输入超过 [MAX_INPUT_CHARS] → `macro.input-limit-exceeded`；
 *  - 因为不做链式再展开，`{{description}}` 里再出现 `{{persona}}` 不会被二次放大（与 ST 的有意差异，
 *    残留宏仍会被报告为 preserved）。
 *  - 正则常量对 Android/ICU 兼容：所有 `{` `}` 都转义（含字符类内部）。
 */
internal object WorldBookMacros {

    class Result(
        val text: String,
        /** CCv3 `{{hidden_key:A}}` 的值（仅参与递归匹配）。 */
        val hiddenKeys: List<String> = emptyList(),
        /** 保留未替换的外部宏名（去重、有界）。 */
        val preserved: List<String> = emptyList(),
        /** 非 null 表示因有界上限被拒绝（稳定原因码）。 */
        val rejected: String? = null,
    )

    const val DEFAULT_MAX_OUTPUT_CHARS = 1 shl 20
    const val MAX_INPUT_CHARS = 1 shl 20
    const val REJECT_INPUT_LIMIT = "macro.input-limit-exceeded"
    const val REJECT_OUTPUT_LIMIT = "macro.output-limit-exceeded"
    const val REJECT_RUN_LIMIT = "macro.run-output-limit-exceeded"

    private const val MAX_PRESERVED = 32
    private const val MAX_HIDDEN_KEYS = 64
    private const val MAX_LIST_ITEMS = 256

    /** 单遍令牌：`{{...}}`（不含嵌套花括号）或旧式 `<NAME>`。 */
    private val TOKEN = Regex(
        "\\{\\{([^\\{\\}]{1,120})\\}\\}|<(USER|BOT|CHAR|GROUP|CHARIFNOTGROUP)>",
        RegexOption.IGNORE_CASE,
    )

    /** 仅用于把“残留未替换宏”报告出来。 */
    private val ANY_MACRO = Regex("\\{\\{([^\\{\\}]{1,120})\\}\\}")

    private val PLAIN_NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")

    fun substitute(
        content: String,
        context: WorldBookContext,
        random: DeterministicRandom,
        seedSalt: Long,
        maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
        budget: MacroBudget? = null,
    ): Result {
        if (content.isEmpty()) return Result(content)
        if (content.length > MAX_INPUT_CHARS) return Result("", rejected = REJECT_INPUT_LIMIT)
        if (budget != null && !budget.tryConsume(content.length)) return Result("", rejected = REJECT_RUN_LIMIT)

        val limit = maxOutputChars.coerceIn(1, DEFAULT_MAX_OUTPUT_CHARS)
        val out = StringBuilder(minOf(content.length, limit))
        val hiddenKeys = ArrayList<String>()
        var rejected: String? = null
        var consumedUntil = 0

        fun appendBounded(value: String, start: Int = 0, end: Int = value.length): Boolean {
            val size = end - start
            if (size > limit - out.length) { rejected = REJECT_OUTPUT_LIMIT; return false }
            if (budget != null && !budget.tryConsume(size)) { rejected = REJECT_RUN_LIMIT; return false }
            out.append(value, start, end)
            return true
        }

        for (match in TOKEN.findAll(content)) {
            if (!appendBounded(content, consumedUntil, match.range.first)) break
            consumedUntil = match.range.last + 1
            val replacement = replacementFor(match, context, random, seedSalt, content.length, hiddenKeys)
            if (!appendBounded(replacement)) break
        }

        if (rejected == null) {
            appendBounded(content, consumedUntil, content.length)
        }
        if (rejected != null) {
            return Result("", hiddenKeys.toList(), emptyList(), rejected)
        }

        val text = out.toString()
        val preserved = LinkedHashSet<String>()
        for (match in ANY_MACRO.findAll(text)) {
            if (preserved.size >= MAX_PRESERVED) break
            val name = match.groupValues[1].substringBefore(':').substringBefore(' ').trim()
            if (name.isNotEmpty()) preserved += name
        }
        return Result(text, hiddenKeys.toList(), preserved.toList())
    }

    private fun replacementFor(
        match: MatchResult,
        context: WorldBookContext,
        random: DeterministicRandom,
        seedSalt: Long,
        contentLength: Int,
        hiddenKeys: MutableList<String>,
    ): String {
        val legacy = match.groups[2]?.value
        if (legacy != null) {
            return when (legacy.uppercase()) {
                "USER" -> context.userName
                "GROUP" -> ""
                else -> context.characterName
            }
        }
        val inner = match.groupValues[1]
        if (inner.startsWith("//")) return ""
        if (inner.startsWith("comment:", ignoreCase = true)) return ""
        if (inner.startsWith("hidden_key:", ignoreCase = true)) {
            if (hiddenKeys.size < MAX_HIDDEN_KEYS) hiddenKeys += inner.substringAfter(':').trim()
            return ""
        }
        if (inner.startsWith("reverse:", ignoreCase = true)) return inner.substringAfter(':').reversed()
        if (inner.startsWith("pick::", ignoreCase = true)) {
            return pick(inner.substring(6), random, seedSalt, match.range.first, contentLength)
        }
        if (inner.startsWith("pick:", ignoreCase = true)) {
            return pick(inner.substring(5), random, seedSalt, match.range.first, contentLength)
        }
        if (inner.startsWith("random::", ignoreCase = true)) return randomItem(inner.substring(8), random)
        if (inner.startsWith("random:", ignoreCase = true)) return randomItem(inner.substring(7), random)
        if (inner.startsWith("roll:", ignoreCase = true)) return roll(inner.substring(5), random)
        if (inner.startsWith("roll ", ignoreCase = true)) return roll(inner.substring(5), random)
        if (PLAIN_NAME.matches(inner)) return simple(inner.lowercase(), context) ?: match.value
        return match.value
    }

    private fun simple(name: String, context: WorldBookContext): String? = when (name) {
        "newline" -> "\n"
        "noop", "trim" -> ""
        "user" -> context.userName
        "char" -> context.characterName
        "description" -> context.characterDescription
        "personality" -> context.characterPersonality
        "scenario" -> context.scenario
        "persona" -> context.personaDescription
        "charprompt" -> context.characterDescription
        "charinstruction" -> context.characterPersonality
        "chardepthprompt" -> context.characterDepthPrompt
        "creatornotes" -> context.creatorNotes
        "mesexamples" -> context.exampleMessages.joinToString("\n")
        else -> null
    }

    private fun pick(raw: String, random: DeterministicRandom, seedSalt: Long, offset: Int, contentLength: Int): String {
        val items = splitList(raw)
        if (items.isEmpty()) return ""
        var hash = seedSalt
        hash = hash * 31 + offset
        hash = hash * 31 + contentLength
        return items[DeterministicRandom(hash).nextInt(items.size)]
    }

    private fun randomItem(raw: String, random: DeterministicRandom): String {
        val items = splitList(raw)
        return if (items.isEmpty()) "" else items[random.nextInt(items.size)]
    }

    private fun roll(formula: String, random: DeterministicRandom): String {
        val sides = formula.trim().lowercase().removePrefix("d").toIntOrNull() ?: return ""
        return if (sides <= 0) "" else (random.nextInt(sides) + 1).toString()
    }

    private fun splitList(raw: String): List<String> {
        val placeholder = "\u0000COMMA\u0000"
        val normalized = raw.replace("\\,", placeholder)
        val parts = if (normalized.contains("::")) normalized.split("::") else normalized.split(',')
        return parts
            .asSequence()
            .map { it.trim().replace(placeholder, ",") }
            .filter { it.isNotEmpty() }
            .take(MAX_LIST_ITEMS)
            .toList()
    }
}

/**
 * 整轮（一次 [WorldBookEngine.evaluate]）宏展开工作量预算：累计“输入字符 + 输出字符”。
 * 由引擎按 `WorldBookSettings.maxRunExpansionChars`（不可提升的硬上限）创建并共享给所有条目。
 */
internal class MacroBudget(private val totalChars: Int) {
    private var used = 0L

    val usedChars: Long get() = used
    val exhausted: Boolean get() = used > totalChars

    fun tryConsume(chars: Int): Boolean {
        require(chars >= 0)
        if (exhausted) return false
        used += chars
        return used <= totalChars
    }
}
