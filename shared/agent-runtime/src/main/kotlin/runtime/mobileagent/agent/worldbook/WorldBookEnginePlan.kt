// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent.worldbook

import runtime.mobileagent.domain.worldbook.WorldBookInsertionStrategy
import runtime.mobileagent.domain.worldbook.WorldBookPosition
import runtime.mobileagent.domain.worldbook.WorldBookRole
import runtime.mobileagent.domain.worldbook.WorldBookScanRequest
import runtime.mobileagent.domain.worldbook.WorldBookSettings
import runtime.mobileagent.domain.worldbook.WorldBookSourceKind

/**
 * 扫描计划：来源展开、ST `getSortedEntries` 排序策略、装饰器解析与 hash 计算。
 *
 * 排序对齐 ST：chat → persona →（按 strategy 合并的 character/global），每组内部按 `order` 降序稳定排序。
 */
internal object WorldBookEnginePlan {

    fun flatten(request: WorldBookScanRequest): List<ScanEntry> {
        val chat = ArrayList<ScanEntry>()
        val persona = ArrayList<ScanEntry>()
        val character = ArrayList<ScanEntry>()
        val global = ArrayList<ScanEntry>()
        var sequence = 0

        for (source in request.sources) {
            source.document.entries.forEachIndexed { indexInBook, entry ->
                val scanEntry = ScanEntry(
                    bookId = source.id,
                    revision = source.revision,
                    entry = entry,
                    indexInBook = indexInBook,
                    globalIndex = sequence++,
                )
                val parsed = WorldBookDecorators.parse(entry.content)
                scanEntry.decorators = parsed.decorators
                scanEntry.unsupportedDecorators = parsed.unsupported
                scanEntry.scanContent = parsed.content
                scanEntry.hash = WorldBookHash.of(source.id, entry)
                applyImplementedDecorators(scanEntry, entry.content)
                when (source.kind) {
                    WorldBookSourceKind.CHAT -> chat += scanEntry
                    WorldBookSourceKind.PERSONA -> persona += scanEntry
                    WorldBookSourceKind.CHARACTER -> character += scanEntry
                    WorldBookSourceKind.GLOBAL -> global += scanEntry
                }
            }
        }

        val combined = when (request.settings.insertionStrategy) {
            WorldBookInsertionStrategy.CHARACTER_FIRST -> character.byOrder() + global.byOrder()
            WorldBookInsertionStrategy.GLOBAL_FIRST -> global.byOrder() + character.byOrder()
            WorldBookInsertionStrategy.EVENLY -> (global + character).byOrder()
        }
        val ordered = chat.byOrder() + persona.byOrder() + combined
        ordered.forEachIndexed { index, entry -> entry.globalIndex = index }
        return ordered
    }

    private fun List<ScanEntry>.byOrder(): List<ScanEntry> =
        sortedWith(compareByDescending<ScanEntry> { it.entry.order }.thenBy { it.indexInBook })

    /**
     * 引擎侧**不可提升**的限额执行：调用 [WorldBookSettings.normalized]，导入/构造值只能更严。
     * 越限时记录 `settings.clamped:<字段…>` 告警（不依赖 UI 校验）。
     */
    fun normalizeSettings(settings: WorldBookSettings, warnings: MutableList<String>): WorldBookSettings {
        val normalized = settings.normalized()
        if (normalized == settings) return settings
        val changed = listOfNotNull(
            "scanDepth".takeIf { normalized.scanDepth != settings.scanDepth },
            "minActivations".takeIf { normalized.minActivations != settings.minActivations },
            "minActivationsDepthMax".takeIf { normalized.minActivationsDepthMax != settings.minActivationsDepthMax },
            "budgetPercent".takeIf { normalized.budgetPercent != settings.budgetPercent },
            "budgetCap".takeIf { normalized.budgetCap != settings.budgetCap },
            "maxRecursionSteps".takeIf { normalized.maxRecursionSteps != settings.maxRecursionSteps },
            "maxScanDepth".takeIf { normalized.maxScanDepth != settings.maxScanDepth },
            "maxRegexPatternLength".takeIf { normalized.maxRegexPatternLength != settings.maxRegexPatternLength },
            "regexStepBudget".takeIf { normalized.regexStepBudget != settings.regexStepBudget },
            "maxMessageScanChars".takeIf { normalized.maxMessageScanChars != settings.maxMessageScanChars },
        )
        addWarning(warnings, "settings.clamped:" + changed.joinToString(","))
        return normalized
    }

    /**
     * 实现 CCv3 中可安全落地的装饰器；参数非法时降级为“不支持”（阻止激活并记录）。
     */
    private fun applyImplementedDecorators(scanEntry: ScanEntry, content: String) {
        fun reject(name: String) {
            scanEntry.decorators = scanEntry.decorators - name
            scanEntry.unsupportedDecorators = scanEntry.unsupportedDecorators + name
        }

        WorldBookDecorators.argumentOf(content, "@@scan_depth")?.let { argument ->
            val value = argument.toIntOrNull()
            if (value == null || value < 0) reject("@@scan_depth") else scanEntry.decoratorScanDepth = value
        }
        WorldBookDecorators.argumentOf(content, "@@depth")?.let { argument ->
            val value = argument.toIntOrNull()
            if (value == null || value < 0) {
                reject("@@depth")
            } else {
                scanEntry.decoratorDepth = value
                scanEntry.decoratorPosition = WorldBookPosition.AT_DEPTH
            }
        }
        WorldBookDecorators.argumentOf(content, "@@role")?.let { argument ->
            val role = when (argument.lowercase()) {
                "system" -> WorldBookRole.SYSTEM
                "user" -> WorldBookRole.USER
                "assistant" -> WorldBookRole.ASSISTANT
                else -> null
            }
            if (role == null) reject("@@role") else scanEntry.decoratorRole = role
        }
        WorldBookDecorators.argumentOf(content, "@@position")?.let { argument ->
            val position = when (argument.lowercase()) {
                "before_desc", "before_char" -> WorldBookPosition.BEFORE_CHAR
                "after_desc", "after_char" -> WorldBookPosition.AFTER_CHAR
                else -> null
            }
            if (position == null) reject("@@position") else scanEntry.decoratorPosition = position
        }
    }

    /**
     * 明确记录缺失的外部环境。向量扩展与自动化脚本不执行**不排除**条目的关键词命中：
     * 这里只告警，由扫描期决定是否还有可用的关键词路径。
     */
    fun warnEnvironment(request: WorldBookScanRequest, entries: List<ScanEntry>, warnings: MutableList<String>) {
        for (entry in entries) {
            if (entry.entry.vectorized) addWarning(warnings, "env.vector-unavailable:${entry.key}")
            if (entry.entry.automationId.isNotBlank()) {
                addWarning(warnings, "env.automation-not-executed:${entry.key}:${entry.entry.automationId}")
            }
        }
        val personaNeeded = entries.any { it.entry.matchPersonaDescription }
        if (personaNeeded && request.context.personaDescription.isBlank()) {
            addWarning(warnings, "env.persona-unavailable")
        }
    }
}
