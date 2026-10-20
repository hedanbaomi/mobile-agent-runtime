// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent.worldbook

import runtime.mobileagent.domain.worldbook.WorldBookContext
import runtime.mobileagent.domain.worldbook.WorldBookScanMessage
import runtime.mobileagent.domain.worldbook.WorldBookScanState
import runtime.mobileagent.domain.worldbook.WorldBookSelectiveLogic
import runtime.mobileagent.domain.worldbook.WorldBookSettings

/**
 * ST `WorldInfoBuffer` 的等价实现：最近消息深度缓冲 + 递归缓冲 + 注入缓冲 + 最小激活深度偏移。
 *
 * 文本由 `\u0001` 分隔符拼接（ST 用同一个哨兵），并统一截断到 [WorldBookSettings.maxMessageScanChars]，
 * 保证超大输入不会拖垮线程。
 */
internal class WorldBookBuffer(
    messages: List<WorldBookScanMessage>,
    private val context: WorldBookContext,
    private val settings: WorldBookSettings,
    private val warnings: MutableList<String>,
) {
    /**
     * 深度 0 = 最近一条消息（ST 传入的是倒序 chat）。
     *
     * `world_info_include_names` 为真时把已提供的参与者名字拼进扫描文本（`name: text`），
     * 否则只用正文；消息对象与导出数据本身**不被改写**（只构造新的扫描字符串）。
     */
    private val depthBuffer: List<WorldBookScanMessage> = buildList {
        val max = minOf(messages.size, WorldBookSettings.MAX_SCAN_DEPTH)
        for (offset in 0 until max) {
            add(messages[messages.size - 1 - offset])
        }
    }

    private val recurseBuffer = ArrayList<String>()
    private val injectBuffer = ArrayList<String>()
    private var skew = 0
    private var truncated = false
    private val warned = HashSet<String>()

    init {
        for (prompt in context.injectedPrompts) {
            val trimmed = prompt.trim()
            if (trimmed.isNotEmpty()) injectBuffer += trimmed
        }
    }

    fun addRecurse(text: String) {
        if (text.isEmpty()) return
        recurseBuffer += text
    }

    fun hasRecurse(): Boolean = recurseBuffer.isNotEmpty()

    fun advanceScan() {
        skew++
    }

    fun depth(): Int = settings.scanDepth + skew

    /** ST `WorldInfoBuffer.get`。 */
    fun text(entry: ScanEntry, scanState: WorldBookScanState): String {
        val depth = entry.effectiveScanDepth ?: depth()
        if (depth < 0) return ""
        val end = minOf(depth, settings.maxScanDepth, depthBuffer.size)
        if (end <= 0) return ""
        val builder = StringBuilder(minOf(settings.maxMessageScanChars, 4096))
        fun appendBounded(value: String, start: Int = 0, end: Int = value.length): Boolean {
            val remaining = settings.maxMessageScanChars - builder.length
            if (end - start <= remaining) { builder.append(value, start, end); return true }
            builder.append(value, start, start + remaining)
            if (!truncated) {
                truncated = true
                warnOnce("scan.text-truncated:${settings.maxMessageScanChars}")
            }
            return false
        }
        fun appendExtra(value: String): Boolean = value.isEmpty() ||
            (appendBounded(JOINER) && appendBounded(value))
        fun appendMessage(message: WorldBookScanMessage): Boolean {
            if (settings.includeNames && message.name.isNotBlank() &&
                (!appendBounded(message.name) || !appendBounded(": "))) return false
            var start = 0
            var end = message.text.length
            while (start < end && message.text[start].isWhitespace()) start++
            while (end > start && message.text[end - 1].isWhitespace()) end--
            return appendBounded(message.text, start, end)
        }

        if (!appendBounded(MATCHER)) return builder.toString()
        for (index in 0 until end) {
            if (index > 0 && !appendBounded(JOINER)) return builder.toString()
            if (!appendMessage(depthBuffer[index])) return builder.toString()
        }
        for (value in extraText(entry, scanState)) if (!appendExtra(value)) return builder.toString()
        return builder.toString()
    }

    private fun extraText(entry: ScanEntry, scanState: WorldBookScanState): List<String> = buildList {
        if (entry.entry.matchPersonaDescription) add(context.personaDescription)
        if (entry.entry.matchCharacterDescription) add(context.characterDescription)
        if (entry.entry.matchCharacterPersonality) add(context.characterPersonality)
        if (entry.entry.matchCharacterDepthPrompt) add(context.characterDepthPrompt)
        if (entry.entry.matchScenario) add(context.scenario)
        if (entry.entry.matchCreatorNotes) add(context.creatorNotes)
        addAll(injectBuffer)
        // 最小激活扫描不应看到递归缓冲（ST 行为）
        if (scanState != WorldBookScanState.MIN_ACTIVATIONS) addAll(recurseBuffer)
    }

    fun matchKeys(haystack: String, needle: String, entry: ScanEntry, budget: StepBudget): Boolean {
        if (entry.entry.useRegex) {
            return when (val parsed = WorldBookRegex.compileRaw(needle, settings)) {
                is WorldBookRegex.ParsedKey.RegexKey -> WorldBookRegex.test(parsed.pattern, haystack, budget)
                is WorldBookRegex.ParsedKey.Rejected -> {
                    warnOnce("regex.rejected:${parsed.reason}")
                    false
                }
            }
        }
        val parsed = WorldBookRegex.parseSlashRegex(needle, settings)
        if (parsed is WorldBookRegex.ParsedKey.Rejected) {
            warnOnce("regex.rejected:${parsed.reason}")
            return false
        }
        if (parsed is WorldBookRegex.ParsedKey.RegexKey) {
            return WorldBookRegex.test(parsed.pattern, haystack, budget)
        }
        return WorldBookPlainMatch.matches(
            haystack = applyCase(haystack, entry),
            needle = applyCase(needle, entry),
            wholeWords = entry.entry.matchWholeWords ?: settings.matchWholeWords,
            asciiBoundaries = settings.wholeWordAsciiBoundaries,
        )
    }

    private fun applyCase(value: String, entry: ScanEntry): String {
        val caseSensitive = entry.entry.caseSensitive ?: settings.caseSensitive
        return if (caseSensitive) value else value.lowercase()
    }

    /** ST `WorldInfoBuffer.getScore`。 */
    fun score(entry: ScanEntry, scanState: WorldBookScanState, budget: StepBudget): Int {
        val keys = entry.entry.keys
        if (keys.isEmpty()) return 0
        val bufferState = text(entry, scanState)
        var primaryScore = 0
        for (key in keys) {
            if (key.isEmpty()) continue
            if (matchesKey(bufferState, key, entry, budget)) primaryScore++
        }
        // use_regex 为真时 secondary_keys 被忽略（CCv3）
        if (entry.entry.useRegex) return primaryScore
        val secondary = entry.entry.secondaryKeys
        if (secondary.isEmpty()) return primaryScore
        var secondaryScore = 0
        for (key in secondary) {
            if (key.isEmpty()) continue
            if (matchesKey(bufferState, key, entry, budget)) secondaryScore++
        }
        return when (entry.entry.selectiveLogic) {
            WorldBookSelectiveLogic.AND_ANY -> primaryScore + secondaryScore
            WorldBookSelectiveLogic.AND_ALL ->
                if (secondaryScore == secondary.size) primaryScore + secondaryScore else primaryScore

            else -> primaryScore
        }
    }

    private fun matchesKey(bufferState: String, key: String, entry: ScanEntry, budget: StepBudget): Boolean =
        try {
            matchKeys(bufferState, key, entry, budget)
        } catch (e: RegexAbortedException) {
            warnOnce(e.code)
            false
        }

    private fun warnOnce(message: String) {
        if (warned.add(message) && warnings.size < MAX_WARNINGS) warnings += message
    }

    private companion object {
        const val MATCHER = "\u0001"
        const val JOINER = "\n\u0001"
        const val MAX_WARNINGS = 256
    }
}
