// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent.worldbook

import runtime.mobileagent.domain.worldbook.WorldBookContext
import runtime.mobileagent.domain.worldbook.WorldBookDecisionStatus
import runtime.mobileagent.domain.worldbook.WorldBookPosition
import runtime.mobileagent.domain.worldbook.WorldBookScanRequest
import runtime.mobileagent.domain.worldbook.WorldBookScanState
import runtime.mobileagent.domain.worldbook.WorldBookSelectiveLogic
import runtime.mobileagent.domain.worldbook.WorldBookSettings
import runtime.mobileagent.domain.worldbook.WorldBookTimedEffectType

/**
 * 单条目裁决（ST `checkWorldInfo` 内层循环的判定顺序）。
 *
 * 判定顺序与 ST 完全一致：禁用 → 生成类型触发器 → 角色名/标签过滤 → timed effects →
 * 递归延迟/排除递归 → 装饰器 → constant → sticky → 关键词/次要关键词逻辑。
 *
 * 本产品差异（见 [WorldBookEngine] KDoc）：
 *  - 不支持的装饰器 → `NEEDS_EXTERNAL`（阻止激活）；
 *  - `vectorized` 且**无关键词** → `NEEDS_EXTERNAL`（向量路径缺失），有关键词仍走关键词路径；
 *  - `automationId` 只告警，不影响关键词命中。
 */
internal object WorldBookEngineDecision {

    fun evaluate(
        entry: ScanEntry,
        scanState: WorldBookScanState,
        currentDelayLevel: Int,
        request: WorldBookScanRequest,
        settings: WorldBookSettings,
        effects: WorldBookEffects,
        buffer: WorldBookBuffer,
        budget: StepBudget,
        macroBudget: MacroBudget,
        random: DeterministicRandom,
        warnings: MutableList<String>,
    ): Verdict {
        val typed = entry.entry
        val context = request.context

        if (!typed.enabled) return Verdict(WorldBookDecisionStatus.INELIGIBLE, "entry-disabled")
        if (macroBudget.exhausted) return Verdict(WorldBookDecisionStatus.DENIED, WorldBookMacros.REJECT_RUN_LIMIT)
        if (typed.triggers.isNotEmpty() && request.generationType !in typed.triggers) {
            return Verdict(WorldBookDecisionStatus.INELIGIBLE, "trigger-mismatch")
        }
        characterFilterVerdict(entry, context, warnings)?.let { return it }

        val isSticky = effects.isActive(WorldBookTimedEffectType.STICKY, entry)
        val isCooldown = effects.isActive(WorldBookTimedEffectType.COOLDOWN, entry)
        if (effects.isActive(WorldBookTimedEffectType.DELAY, entry)) {
            return Verdict(WorldBookDecisionStatus.DENIED, "delayed")
        }
        if (isCooldown && !isSticky) return Verdict(WorldBookDecisionStatus.DENIED, "cooldown")
        if (scanState != WorldBookScanState.RECURSION && typed.delayUntilRecursion > 0 && !isSticky) {
            return Verdict(WorldBookDecisionStatus.DENIED, "delay-until-recursion")
        }
        if (scanState == WorldBookScanState.RECURSION && typed.delayUntilRecursion > currentDelayLevel && !isSticky) {
            return Verdict(WorldBookDecisionStatus.DENIED, "delay-until-recursion-level")
        }
        if (scanState == WorldBookScanState.RECURSION && settings.recursive && typed.excludeRecursion && !isSticky) {
            return Verdict(WorldBookDecisionStatus.DENIED, "exclude-recursion")
        }

        if (entry.unsupportedDecorators.isNotEmpty()) {
            val name = entry.unsupportedDecorators.first()
            addWarning(warnings, "decorator.unsupported:${entry.key}:$name")
            return Verdict(WorldBookDecisionStatus.NEEDS_EXTERNAL, "decorator-unsupported:$name")
        }
        if ("@@activate" in entry.decorators) return activation(entry, context, warnings, "decorator-activate")
        if ("@@dont_activate" in entry.decorators) {
            return Verdict(WorldBookDecisionStatus.DENIED, "decorator-dont-activate")
        }
        // Character Card V3：`use_regex` 为真时 `constant` 应被忽略，必须由正则键命中。
        if (typed.constant && !typed.useRegex) return activation(entry, context, warnings, "constant")
        if (isSticky) return activation(entry, context, warnings, "sticky-active")

        if (typed.keys.isEmpty()) {
            if (typed.vectorized) {
                addWarning(warnings, "env.vector-unavailable:${entry.key}")
                return Verdict(WorldBookDecisionStatus.NEEDS_EXTERNAL, "vector-no-keywords")
            }
            return Verdict(WorldBookDecisionStatus.SKIPPED, "no-keys")
        }

        val textToScan = buffer.text(entry, scanState)
        var foundPrimary: String? = null
        for (key in typed.keys) {
            val resolved = resolveKey(key, context, random, request.seed, macroBudget)
            resolved.rejected?.let { addWarning(warnings, "$it:${entry.key}"); return Verdict(WorldBookDecisionStatus.DENIED, it) }
            if (resolved.text.isNotBlank() && matchWithBudget(buffer, textToScan, resolved.text.trim(), entry, budget, warnings)) {
                foundPrimary = key; break
            }
        }
        val primaryHit = foundPrimary ?: return Verdict(WorldBookDecisionStatus.SKIPPED, "primary-no-match")

        if (typed.useRegex || !typed.selective || typed.secondaryKeys.isEmpty()) {
            return activation(entry, context, warnings, "primary-match", listOf(primaryHit))
        }

        var anyMatch = false
        var allMatch = true
        for (key in typed.secondaryKeys) {
            val resolved = resolveKey(key, context, random, request.seed, macroBudget)
            resolved.rejected?.let { addWarning(warnings, "$it:${entry.key}"); return Verdict(WorldBookDecisionStatus.DENIED, it) }
            val hit = resolved.text.isNotBlank() && matchWithBudget(buffer, textToScan, resolved.text.trim(), entry, budget, warnings)
            if (hit) anyMatch = true else allMatch = false
            val matched = when (typed.selectiveLogic) {
                WorldBookSelectiveLogic.AND_ANY -> hit
                WorldBookSelectiveLogic.NOT_ALL -> !hit
                else -> false
            }
            if (matched) {
                return activation(entry, context, warnings, "secondary-match", listOf(primaryHit, key))
            }
        }
        val matched = when (typed.selectiveLogic) {
            WorldBookSelectiveLogic.NOT_ANY -> !anyMatch
            WorldBookSelectiveLogic.AND_ALL -> allMatch
            else -> false
        }
        return if (matched) {
            activation(entry, context, warnings, "secondary-match", listOf(primaryHit))
        } else {
            Verdict(WorldBookDecisionStatus.SKIPPED, "secondary-unmatched", listOf(primaryHit))
        }
    }

    /**
     * 激活闸门：位置锚点不存在时判 `NEEDS_EXTERNAL`，不得静默激活、也不得推进 timed effects。
     *
     *  - `AN_TOP` / `AN_BOTTOM` 需要 `context.authorNote`；
     *  - `EM_TOP` / `EM_BOTTOM` 需要 `context.exampleMessages`；
     *  - `OUTLET` 需要 `context.availableOutlets` 中存在该 `outletName`（离线预览默认为空）。
     */
    private fun activation(
        entry: ScanEntry,
        context: WorldBookContext,
        warnings: MutableList<String>,
        reason: String,
        matchedKeys: List<String> = emptyList(),
    ): Verdict {
        val missing = missingAnchor(entry, context) ?: return Verdict(WorldBookDecisionStatus.ACTIVATED, reason, matchedKeys)
        addWarning(warnings, "$missing:${entry.key}")
        return Verdict(WorldBookDecisionStatus.NEEDS_EXTERNAL, missing)
    }

    private fun missingAnchor(entry: ScanEntry, context: WorldBookContext): String? = when (entry.effectivePosition) {
        WorldBookPosition.AN_TOP, WorldBookPosition.AN_BOTTOM ->
            if (context.authorNote.isBlank()) "missing-anchor:author-note" else null

        WorldBookPosition.EM_TOP, WorldBookPosition.EM_BOTTOM ->
            if (context.exampleMessages.isEmpty()) "missing-anchor:example-messages" else null

        WorldBookPosition.OUTLET -> when {
            entry.entry.outletName.isBlank() -> "missing-anchor:outlet"
            entry.entry.outletName !in context.availableOutlets -> "missing-anchor:outlet:${entry.entry.outletName}"
            else -> null
        }

        else -> null
    }

    /** 角色名/标签过滤（ST `characterFilter`）。缺失环境时同样保守处理并告警。 */
    private fun characterFilterVerdict(
        entry: ScanEntry,
        context: WorldBookContext,
        warnings: MutableList<String>,
    ): Verdict? {
        val filter = entry.entry.characterFilter
        if (filter.names.isNotEmpty()) {
            if (context.characterFileName.isBlank()) {
                addWarning(warnings, "env.character-file-name-missing:${entry.key}")
            }
            val nameIncluded = filter.names.contains(context.characterFileName)
            val filtered = if (filter.isExclude) nameIncluded else !nameIncluded
            if (filtered) return Verdict(WorldBookDecisionStatus.INELIGIBLE, "character-filter-names")
        }
        if (filter.tags.isNotEmpty()) {
            if (context.characterTags.isEmpty()) addWarning(warnings, "env.tag-map-unavailable:${entry.key}")
            val includesTag = context.characterTags.any { it in filter.tags }
            val filtered = if (filter.isExclude) includesTag else !includesTag
            if (filtered) return Verdict(WorldBookDecisionStatus.INELIGIBLE, "character-filter-tags")
        }
        return null
    }

    private fun matchWithBudget(
        buffer: WorldBookBuffer,
        haystack: String,
        needle: String,
        entry: ScanEntry,
        budget: StepBudget,
        warnings: MutableList<String>,
    ): Boolean = try {
        buffer.matchKeys(haystack, needle, entry, budget)
    } catch (e: RegexAbortedException) {
        addWarning(warnings, "${e.code}:${entry.key}")
        false
    }

    private fun resolveKey(
        key: String,
        context: WorldBookContext,
        random: DeterministicRandom,
        seed: Long,
        macroBudget: MacroBudget,
    ): WorldBookMacros.Result = WorldBookMacros.substitute(key, context, random, seed, budget = macroBudget)

    /** 概率判定：sticky 不重掷；`probability >= 100` 恒真；`probability <= 0` 恒假。 */
    fun probabilityPasses(entry: ScanEntry, effects: WorldBookEffects, random: DeterministicRandom): Boolean {
        val typed = entry.entry
        if (!typed.useProbability || typed.probability >= 100) return true
        if (effects.isActive(WorldBookTimedEffectType.STICKY, entry)) return true
        if (typed.probability <= 0) return false
        return random.nextDouble() * 100 <= typed.probability
    }
}
