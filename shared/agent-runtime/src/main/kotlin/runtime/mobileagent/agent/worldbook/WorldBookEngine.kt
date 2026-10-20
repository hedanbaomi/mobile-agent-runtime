// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent.worldbook

import runtime.mobileagent.domain.worldbook.WorldBookActivation
import runtime.mobileagent.domain.worldbook.WorldBookDecision
import runtime.mobileagent.domain.worldbook.WorldBookDecisionStatus
import runtime.mobileagent.domain.worldbook.WorldBookEvaluation
import runtime.mobileagent.domain.worldbook.WorldBookScanRequest
import runtime.mobileagent.domain.worldbook.WorldBookScanState
import runtime.mobileagent.domain.worldbook.WorldBookTimedEffectType

/**
 * 世界书选择引擎（ST `checkWorldInfo` 的等价实现，纯函数，无网络/数据库访问）。
 *
 * 语义对齐 SillyTavern 1.19.0：
 *  - 按“最近消息”扫描：深度 0 = 最后一条消息（当前用户消息最后）；
 *  - 四逻辑次要关键词（AND_ANY / NOT_ALL / NOT_ANY / AND_ALL）、常驻（constant）、
 *    粘滞（sticky）/冷却（cooldown）/延迟（delay）/递归延迟层（delayUntilRecursion）；
 *  - 递归扫描 + `excludeRecursion` / `preventRecursion` + `minActivations` 加深扫描；
 *  - 概率与组权重随机全部由 `seed` 决定，可复现；
 *  - 包含组过滤顺序见 [WorldBookEngineGroups]；
 *  - 预算：软预算 = settings.budgetCap>0 ? min(budgetUnits, cap) : budgetUnits，`ignoreBudget` 只能越过软预算；
 *    [hardBudgetUnits] 为请求硬预算，**任何条目（含 ignoreBudget / constant）都不可越过**；
 *    `hardBudgetUnits <= 0` 时不激活任何正文（交主链处理固定内容超窗）；
 *  - timed effects 以**消息数**为时间轴，重启后由 `previousEffects` 恢复。
 *
 * 与 ST 的差异（均为“不静默扩大激活”方向，且都有 decision/warning）：
 *  1. ST 会把**预算被拒**的条目正文送进递归缓冲（进而可能触发新条目）；本实现只递归**真正注入**的正文；
 *  2. ST 的 `newEntries.splice(indexOf(...))` 在 -1 时会误删末元素；本实现用显式集合，不复刻该缺陷；
 *  3. ST 装饰器用 `startsWith` 判定（`@@dont_activate_after_match` 被误判为 `@@dont_activate`）；本实现整词匹配；
 *  4. CCv3 中本产品未实现的装饰器（条件型）会**阻止**该条目激活（`NEEDS_EXTERNAL`），而不是忽略条件；
 *  5. 向量扩展缺失只关闭“无关键词的向量激活路径”（`vector-no-keywords`）；有关键词的 `vectorized` 条目
 *     仍按关键词激活；`automationId` 只是额外自动化行为，不执行脚本也不排除其正文的关键词命中（仅告警）；
 *  6. 概率/组权重使用 `seed` 派生的确定性随机（ST 用熵）；分布语义一致但可复现；
 *  7. 空正文（宏替换后为空白）按 `empty-content` 拒绝，不进入激活集合（ST 会激活但不渲染）。
 *
 * reason 稳定 code 表（decision.reason）：
 *  `entry-disabled`、`trigger-mismatch`、`character-filter-names`、`character-filter-tags`、
 *  `delayed`、`cooldown`、`delay-until-recursion`、`delay-until-recursion-level`、`exclude-recursion`、
 *  `decorator-activate`、`decorator-dont-activate`、`decorator-unsupported:<name>`、
 *  `constant`、`sticky-active`、`no-keys`、`vector-no-keywords`、`primary-no-match`、
 *  `primary-match`、`secondary-match`、`secondary-unmatched`、`probability-failed`、
 *  `budget-exhausted`、`hard-budget-exhausted`、`empty-content`、
 *  `group-sticky-loser:<g>`、`group-cooldown:<g>`、`group-delay:<g>`、`group-score-loser:<g>`、
 *  `group-already-activated:<g>`、`group-override-loser:<g>`、`group-lost:<g>`、`activated`。
 */
object WorldBookEngine {

    /** 递归轮次硬上限（即使设置不设限也不会失控）。 */
    private const val INTERNAL_MAX_LOOPS = 512

    fun evaluate(
        request: WorldBookScanRequest,
        budgetUnits: Int,
        hardBudgetUnits: Int = budgetUnits,
        estimateUnits: (String) -> Int,
    ): WorldBookEvaluation {
        val warnings = ArrayList<String>(0)
        // 引擎侧不可提升的硬上限（导入/构造值只能更严）+ 消息数非负。
        val request = request.copy(
            settings = WorldBookEnginePlan.normalizeSettings(request.settings, warnings),
            messageCount = request.messageCount.coerceAtLeast(0),
        )
        val settings = request.settings
        val random = DeterministicRandom(request.seed)
        val budget = StepBudget(settings.regexStepBudget)
        // 整轮共享的宏展开工作量预算（不是每条目各一份）。
        val macroBudget = MacroBudget(settings.maxRunExpansionChars)

        val entries = WorldBookEnginePlan.flatten(request)
        WorldBookEnginePlan.warnEnvironment(request, entries, warnings)

        val softBudget = if (settings.budgetCap > 0) minOf(budgetUnits, settings.budgetCap) else budgetUnits
        val hardBudget = maxOf(0, hardBudgetUnits)

        if (entries.isEmpty() || hardBudget <= 0) {
            return WorldBookEvaluation(
                decisions = if (entries.isEmpty()) emptyList() else blockedByHardBudget(entries),
                warnings = warnings,
                loops = 0,
                budgetUnits = softBudget,
                hardBudgetUnits = hardBudget,
            )
        }

        val effects = WorldBookEffects(entries, request.messageCount, request.previousEffects, warnings)
        val buffer = WorldBookBuffer(request.messages, request.context, settings, warnings)

        val decisions = LinkedHashMap<String, WorldBookDecision>()
        /** 候选条目的判决（reason / matchedKeys），在真正激活时写入 ACTIVATED 裁决。 */
        val pendingVerdicts = HashMap<String, Verdict>()
        val activated = LinkedHashMap<String, ScanEntry>()
        val substituted = HashMap<String, SubstitutedContent>()
        val failedProbability = HashSet<String>()
        // Budget every injected body, including entries excluded from recursive matching.
        val activatedText = StringBuilder()
        var estimatedTotal = 0
        var overflowed = false

        val delayLevels = ArrayDeque(
            entries.map { it.entry.delayUntilRecursion }.filter { it > 0 }.distinct().sorted(),
        )
        var currentDelayLevel = if (delayLevels.isEmpty()) 0 else delayLevels.removeFirst()

        var scanState = WorldBookScanState.INITIAL
        var loop = 0

        while (true) {
            if (settings.maxRecursionSteps > 0 && settings.maxRecursionSteps <= loop) {
                addWarning(warnings, "recursion.max-steps-reached:${settings.maxRecursionSteps}")
                break
            }
            if (loop >= INTERNAL_MAX_LOOPS) {
                addWarning(warnings, "recursion.internal-loop-cap:$INTERNAL_MAX_LOOPS")
                break
            }
            loop++

            val activatedNow = collectActivations(
                entries = entries,
                scanState = scanState,
                currentDelayLevel = currentDelayLevel,
                request = request,
                settings = settings,
                effects = effects,
                buffer = buffer,
                budget = budget,
                macroBudget = macroBudget,
                random = random,
                warnings = warnings,
                activated = activated,
                failedProbability = failedProbability,
                decisions = decisions,
                loop = loop,
                pendingVerdicts = pendingVerdicts,
            )

            var candidates = activatedNow.toMutableList()
            if (candidates.size > 1) {
                candidates = candidates.sortedWith(
                    compareByDescending<ScanEntry> { if (effects.isActive(WorldBookTimedEffectType.STICKY, it)) 1 else 0 }
                        .thenBy { it.globalIndex },
                ).toMutableList()
            }
            WorldBookEngineGroups.filter(
                candidates = candidates,
                activated = activated,
                buffer = buffer,
                scanState = scanState,
                effects = effects,
                settings = settings,
                random = random,
                budget = budget,
                loop = loop,
                decisions = decisions,
            )

            val baseUnits = estimateUnits(activatedText.toString())
            val newContent = StringBuilder()
            var ignoresBudgetRemaining = candidates.count { it.entry.ignoreBudget }
            val activatedThisLoop = ArrayList<ScanEntry>()

            for ((index, entry) in candidates.withIndex()) {
                if (entry.entry.ignoreBudget) ignoresBudgetRemaining--
                if (overflowed && !entry.entry.ignoreBudget) {
                    if (ignoresBudgetRemaining > 0) {
                        recordDecision(
                            decisions, entry, WorldBookDecisionStatus.DENIED, "budget-exhausted",
                            emptyList(), 0, loop, scanState,
                        )
                        continue
                    }
                    for (remaining in candidates.drop(index)) {
                        if (remaining.key in activated) continue
                        recordDecision(
                            decisions, remaining, WorldBookDecisionStatus.DENIED, "budget-exhausted",
                            emptyList(), 0, loop, scanState,
                        )
                    }
                    break
                }

                if (!WorldBookEngineDecision.probabilityPasses(entry, effects, random)) {
                    failedProbability += entry.key
                    recordDecision(
                        decisions, entry, WorldBookDecisionStatus.DENIED, "probability-failed",
                        emptyList(), 0, loop, scanState,
                    )
                    continue
                }

                val result = WorldBookMacros.substitute(
                    content = entry.scanContent,
                    context = request.context,
                    random = random,
                    seedSalt = request.seed xor entry.hash,
                    budget = macroBudget,
                )
                if (result.rejected != null) {
                    addWarning(warnings, "${result.rejected}:${entry.key}")
                    recordDecision(
                        decisions, entry, WorldBookDecisionStatus.DENIED, result.rejected,
                        emptyList(), 0, loop, scanState,
                    )
                    continue
                }
                result.preserved.forEach { macro ->
                    addWarning(warnings, "macro.external-preserved:${entry.key}:$macro")
                }
                if (result.text.isBlank()) {
                    recordDecision(
                        decisions, entry, WorldBookDecisionStatus.DENIED, "empty-content",
                        emptyList(), 0, loop, scanState,
                    )
                    continue
                }

                val previousContentLength = newContent.length
                newContent.append(result.text).append('\n')
                val units = estimateUnits(result.text).coerceAtLeast(0)
                if (!entry.entry.ignoreBudget && baseUnits + estimateUnits(newContent.toString()) >= softBudget) {
                    overflowed = true
                    if (settings.overflowAlert) addWarning(warnings, "budget.overflow:$softBudget")
                    recordDecision(
                        decisions, entry, WorldBookDecisionStatus.DENIED, "budget-exhausted",
                        emptyList(), units, loop, scanState,
                    )
                    continue
                }
                if (estimatedTotal + units > hardBudget) {
                    newContent.setLength(previousContentLength)
                    recordDecision(
                        decisions, entry, WorldBookDecisionStatus.DENIED, "hard-budget-exhausted",
                        emptyList(), units, loop, scanState,
                    )
                    continue
                }

                estimatedTotal += units
                substituted[entry.key] = SubstitutedContent(result.text, units, result.hiddenKeys)
                activated[entry.key] = entry
                activatedThisLoop += entry
                val verdict = pendingVerdicts[entry.key]
                recordDecision(
                    decisions, entry, WorldBookDecisionStatus.ACTIVATED,
                    verdict?.reason ?: "activated", verdict?.matchedKeys ?: emptyList(),
                    units, loop, scanState,
                )
            }

            if (activatedThisLoop.isNotEmpty()) effects.recordActivated(activatedThisLoop)

            // 递归只使用真正注入的正文（差异 1），并额外带上 CCv3 hidden_key 值。
            val recursionText = activatedThisLoop
                .filter { !it.entry.preventRecursion }
                .joinToString("\n") { entry ->
                    val content = substituted[entry.key]?.text ?: entry.scanContent
                    val hidden = substituted[entry.key]?.hiddenKeys.orEmpty()
                    if (hidden.isEmpty()) content else content + "\n" + hidden.joinToString("\n")
                }

            var nextScanState: WorldBookScanState? = null
            if (settings.recursive && !overflowed && activatedThisLoop.any { !it.entry.preventRecursion }) {
                nextScanState = WorldBookScanState.RECURSION
            }
            if (settings.recursive && !overflowed && scanState == WorldBookScanState.MIN_ACTIVATIONS && buffer.hasRecurse()) {
                nextScanState = WorldBookScanState.RECURSION
            }

            val minActivationsNotSatisfied = settings.minActivations > 0 && activated.size < settings.minActivations
            if (nextScanState == null && !overflowed && minActivationsNotSatisfied) {
                val overMax = (settings.minActivationsDepthMax > 0 && buffer.depth() > settings.minActivationsDepthMax) ||
                    buffer.depth() > request.messages.size
                if (overMax) {
                    addWarning(warnings, "min-activations.depth-exceeded:${activated.size}/${settings.minActivations}")
                } else {
                    nextScanState = WorldBookScanState.MIN_ACTIVATIONS
                    buffer.advanceScan()
                }
            }

            if (nextScanState == null && delayLevels.isNotEmpty()) {
                nextScanState = WorldBookScanState.RECURSION
                currentDelayLevel = delayLevels.removeFirst()
            }

            scanState = nextScanState ?: break
            if (recursionText.isNotEmpty()) {
                buffer.addRecurse(recursionText)
            }
            val injectedText = activatedThisLoop.joinToString("\n") { substituted.getValue(it.key).text }
            if (injectedText.isNotEmpty()) {
                if (activatedText.isNotEmpty()) activatedText.insert(0, "\n")
                activatedText.insert(0, injectedText)
            }
        }

        // 组装：ST 先按 order 降序稳定排序，再逐位置 unshift → 最终为 order 升序、同序按激活顺序倒序。
        val orderedActivated = activated.values
            .sortedWith(compareByDescending<ScanEntry> { it.entry.order }.thenBy { it.globalIndex })
            .reversed()

        val activations = orderedActivated.mapIndexed { index, entry ->
            WorldBookActivation(
                bookId = entry.bookId,
                revision = entry.revision,
                entryUid = entry.entry.uid,
                title = entry.entry.title,
                content = substituted[entry.key]?.text ?: entry.scanContent,
                position = entry.effectivePosition,
                depth = entry.effectiveDepth,
                role = entry.effectiveRole,
                outletName = entry.entry.outletName,
                order = entry.entry.order,
                reason = decisions[entry.key]?.reason ?: "activated",
                estimatedUnits = substituted[entry.key]?.units ?: 0,
                hash = entry.hash,
                index = index,
            )
        }

        return WorldBookEvaluation(
            activated = activations,
            decisions = decisions.values.toList(),
            nextEffects = effects.nextEffects(),
            warnings = warnings,
            estimatedUnits = estimatedTotal,
            loops = loop,
            budgetUnits = softBudget,
            hardBudgetUnits = hardBudget,
        )
    }

    private fun collectActivations(
        entries: List<ScanEntry>,
        scanState: WorldBookScanState,
        currentDelayLevel: Int,
        request: WorldBookScanRequest,
        settings: runtime.mobileagent.domain.worldbook.WorldBookSettings,
        effects: WorldBookEffects,
        buffer: WorldBookBuffer,
        budget: StepBudget,
        macroBudget: MacroBudget,
        random: DeterministicRandom,
        warnings: MutableList<String>,
        activated: Map<String, ScanEntry>,
        failedProbability: Set<String>,
        decisions: LinkedHashMap<String, WorldBookDecision>,
        loop: Int,
        pendingVerdicts: MutableMap<String, Verdict>,
    ): List<ScanEntry> {
        val result = ArrayList<ScanEntry>()
        for (entry in entries) {
            if (entry.key in failedProbability || entry.key in activated) continue
            val verdict = WorldBookEngineDecision.evaluate(
                entry = entry,
                scanState = scanState,
                currentDelayLevel = currentDelayLevel,
                request = request,
                settings = settings,
                effects = effects,
                buffer = buffer,
                budget = budget,
                macroBudget = macroBudget,
                random = random,
                warnings = warnings,
            )
            if (verdict.status == WorldBookDecisionStatus.ACTIVATED) {
                // 激活裁决推迟到真正进入 activated 集合时记录：组过滤/概率/预算仍可否决，
                // 若此刻先写入 ACTIVATED，后续否决就会被 recordDecision 的终态保护挡住。
                pendingVerdicts[entry.key] = verdict
                result += entry
            } else {
                recordDecision(
                    decisions, entry, verdict.status, verdict.reason, verdict.matchedKeys,
                    0, loop, scanState,
                )
            }
        }
        return result
    }

    /** 硬预算为 0（或负数）时：不激活任何正文，但保留可解释的裁决。 */
    private fun blockedByHardBudget(entries: List<ScanEntry>): List<WorldBookDecision> =
        entries.map {
            WorldBookDecision(
                bookId = it.bookId,
                entryUid = it.entry.uid,
                title = it.entry.title,
                status = WorldBookDecisionStatus.DENIED,
                reason = "hard-budget-exhausted",
                estimatedUnits = 0,
                loop = 0,
                scanState = WorldBookScanState.INITIAL,
            )
        }
}
