// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent.worldbook

import runtime.mobileagent.domain.worldbook.WorldBookEntry
import runtime.mobileagent.domain.worldbook.WorldBookPosition
import runtime.mobileagent.domain.worldbook.WorldBookRole

/**
 * 操作步数预算。每次 `charAt` 消耗一步；耗尽即抛 [RegexAbortedException]（code =
 * `regex.step-budget-exhausted`）。
 *
 * 这是“可实际中断”的正则安全机制：Java 正则引擎在回溯时也要逐字符读取输入，
 * 因此计数型 [CharSequence] 能把回溯中的匹配调用直接打断，不需要线程、不会留下失控任务。
 */
internal class StepBudget(private val limit: Long) {
    private var used = 0L
    var exhausted: Boolean = false
        private set

    fun consume(steps: Int = 1) {
        used += steps
        if (used > limit) abort()
    }

    /** 中止后续正则匹配（步数耗尽或引擎栈溢出后调用）。 */
    fun abort(): Nothing {
        exhausted = true
        throw RegexAbortedException("regex.step-budget-exhausted")
    }

    val usedSteps: Long get() = used
}

/**
 * 正则匹配被中断的显式信号（步数预算耗尽、或引擎回溯栈溢出）。
 * 调用方必须记录告警并视为“不匹配”，绝不静默吞掉。
 */
internal class RegexAbortedException(val code: String) : RuntimeException(code)

/** 计数型有界 CharSequence：把 Java 正则的字符访问转换为可中断的步数开销。 */
internal class BudgetedCharSequence(
    private val inner: CharSequence,
    private val budget: StepBudget,
) : CharSequence {
    override val length: Int get() = inner.length

    override fun get(index: Int): Char {
        budget.consume()
        return inner[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        BudgetedCharSequence(inner.subSequence(startIndex, endIndex), budget)

    override fun toString(): String = inner.toString()
}

/** 条目在扫描期的物化形态（含装饰器解析结果与派生 hash）。 */
internal class ScanEntry(
    val bookId: String,
    val revision: Int,
    val entry: WorldBookEntry,
    /** 该书内部顺序（用于稳定排序）。 */
    val indexInBook: Int,
    /** 在最终有序列表中的下标（ST `sortedEntries` 下标；排序完成后写入）。 */
    var globalIndex: Int,
) {
    /** 内容中剥离出的装饰器（已规范化）。 */
    var decorators: Set<String> = emptySet()

    /** 本产品不支持的装饰器（激活会被阻止并记录）。 */
    var unsupportedDecorators: Set<String> = emptySet()

    /** 剥离装饰器行之后的正文（注入提示用）。 */
    var scanContent: String = entry.content

    var hash: Long = 0L

    /** 装饰器覆盖（`@@scan_depth` / `@@depth` / `@@role` / `@@position`）。 */
    var decoratorScanDepth: Int? = null
    var decoratorDepth: Int? = null
    var decoratorRole: WorldBookRole? = null
    var decoratorPosition: WorldBookPosition? = null

    val key: String get() = "$bookId.${entry.uid}"
    val effectiveScanDepth: Int? get() = decoratorScanDepth ?: entry.scanDepth
    val effectivePosition: WorldBookPosition get() = decoratorPosition ?: entry.position
    val effectiveDepth: Int get() = decoratorDepth ?: entry.depth
    val effectiveRole: WorldBookRole get() = decoratorRole ?: entry.role
}

/** 装饰器解析结果。 */
internal class DecoratorParse(
    val decorators: Set<String>,
    val unsupported: Set<String>,
    val content: String,
)

/**
 * 装饰器解析：行为对齐 ST `parseDecorators`（`@@activate` / `@@dont_activate`，`@@@` 为转义形式），
 * 并识别 CCv3 中本产品实现的 `@@scan_depth` / `@@depth` / `@@role` / `@@position`。
 *
 * 与 ST 的差异（有意）：
 *  - ST 用 `startsWith` 判定，会把 `@@dont_activate_after_match` 误当成 `@@dont_activate`；这里改为整词匹配。
 *  - ST 会把无法识别的顶层 `@@` 行直接丢弃；这里记录为 unsupported 并阻止该条目激活
 *    （忽略条件型装饰器会静默扩大激活，违背“不能默默扩大激活”）。
 */
internal object WorldBookDecorators {

    private val KNOWN = setOf("@@activate", "@@dont_activate")

    /** 本引擎实现的 CCv3 装饰器（参数在 [WorldBookEnginePlan] 中校验）。 */
    val SUPPORTED = setOf("@@scan_depth", "@@depth", "@@role", "@@position")

    fun parse(content: String): DecoratorParse {
        if (!content.startsWith("@@")) return DecoratorParse(emptySet(), emptySet(), content)
        val lines = content.split('\n')
        val decorators = LinkedHashSet<String>()
        val unsupported = LinkedHashSet<String>()
        var newContent = content
        var fallbacked = false

        for (index in lines.indices) {
            val line = lines[index]
            if (!line.startsWith("@@")) {
                newContent = lines.subList(index, lines.size).joinToString("\n")
                break
            }
            if (line.startsWith("@@@") && !fallbacked) {
                continue
            }
            val normalized = if (line.startsWith("@@@")) line.substring(1) else line
            val name = normalized.trim().split(' ', '\t').first()
            if (name in KNOWN || name in SUPPORTED) {
                decorators += name
                fallbacked = false
            } else {
                unsupported += name
                fallbacked = true
            }
        }
        return DecoratorParse(decorators, unsupported, newContent)
    }

    /** 从原始内容中取出某个装饰器的参数（仅顶层连续装饰器行）。 */
    fun argumentOf(content: String, name: String): String? {
        if (!content.startsWith("@@")) return null
        for (line in content.split('\n')) {
            if (!line.startsWith("@@")) break
            val normalized = if (line.startsWith("@@@")) line.substring(1) else line
            val trimmed = normalized.trim()
            val token = trimmed.split(' ', '\t').first()
            if (token == name) return trimmed.substringAfter(' ', "").trim()
        }
        return null
    }
}

/**
 * FNV-1a 64 位稳定 hash。
 *
 * 覆盖**全部影响扫描语义的字段**（内容、键、四逻辑、概率、组/权重/覆盖、角色过滤器、位置/深度/角色、
 * sticky/cooldown/delay、递归开关、trigger、ignoreBudget、正则开关、extensions 文本等），
 * 因此修改概率/组/过滤器等设置同样会让旧 sticky/cooldown 失效（ST 哈希整个 entry）。
 * 仅排除纯 provenance：`originalJson`、`originalKey`、`displayIndex`。
 */
internal object WorldBookHash {
    private const val OFFSET = -3750763034362895579L // 14695981039346656037 (unsigned)
    private const val PRIME = 1099511628211L

    fun of(bookId: String, entry: WorldBookEntry): Long = fnv1a(canonical(bookId, entry))

    /**
     * 规范化文本：**长度前缀**编码，不使用可注入的分隔符。
     *
     * 每个字符串编码为 `长度:内容;`（`null` 编码为 `-1:;`，与空串 `0:;` 区分），
     * 每个列表编码为 `元素数[长度:内容,…]`。因此在 JSON 中合法的控制字符
     * （如 `\u0000` / `\u0002`）无法伪造字段或列表边界，`["a\u0002b"]` 与 `["a","b"]` 不会碰撞。
     */
    fun canonical(bookId: String, entry: WorldBookEntry): String = buildString {
        fun text(value: String?) {
            if (value == null) {
                append("-1:;")
                return
            }
            append(value.length).append(':').append(value).append(';')
        }

        fun list(values: List<String>) {
            append(values.size).append('[')
            for (value in values) {
                append(value.length).append(':').append(value).append(',')
            }
            append(']')
        }

        text(bookId)
        text(entry.uid)
        text(entry.content)
        text(entry.title)
        list(entry.keys)
        list(entry.secondaryKeys)
        list(entry.triggers)
        text(entry.constant.toString())
        text(entry.vectorized.toString())
        text(entry.selective.toString())
        text(entry.selectiveLogic.code.toString())
        text(entry.order.toString())
        text(entry.priority?.toString())
        text(entry.position.code.toString())
        text(entry.depth.toString())
        text(entry.role.code.toString())
        text(entry.outletName)
        text(entry.ignoreBudget.toString())
        text(entry.excludeRecursion.toString())
        text(entry.preventRecursion.toString())
        text(entry.delayUntilRecursion.toString())
        text(entry.probability.toString())
        text(entry.useProbability.toString())
        text(entry.group)
        text(entry.groupOverride.toString())
        text(entry.groupWeight.toString())
        text(entry.useGroupScoring?.toString())
        text(entry.scanDepth?.toString())
        text(entry.caseSensitive?.toString())
        text(entry.matchWholeWords?.toString())
        text(entry.useRegex.toString())
        text(entry.automationId)
        text(entry.sticky?.toString())
        text(entry.cooldown?.toString())
        text(entry.delay?.toString())
        text(entry.enabled.toString())
        text(entry.matchPersonaDescription.toString())
        text(entry.matchCharacterDescription.toString())
        text(entry.matchCharacterPersonality.toString())
        text(entry.matchCharacterDepthPrompt.toString())
        text(entry.matchScenario.toString())
        text(entry.matchCreatorNotes.toString())
        text(entry.characterFilter.isExclude.toString())
        list(entry.characterFilter.names)
        list(entry.characterFilter.tags)
        text(entry.extensions.toString())
    }

    private fun fnv1a(value: String): Long {
        var hash = OFFSET
        for (char in value) {
            hash = hash xor char.code.toLong()
            hash *= PRIME
        }
        hash = hash xor 0x1FL
        hash *= PRIME
        return hash
    }
}

/** 确定性伪随机（SplitMix64）：概率、组权重、可复现宏都从这里取值。 */
internal class DeterministicRandom(seed: Long) {
    private var state: Long = seed

    fun nextLong(): Long {
        state += -7046029254386353131L
        var z = state
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }

    fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

    fun nextInt(bound: Int): Int = if (bound <= 0) 0 else (nextDouble() * bound).toInt().coerceIn(0, bound - 1)
}
