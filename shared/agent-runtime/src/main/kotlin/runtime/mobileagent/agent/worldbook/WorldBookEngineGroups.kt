// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent.worldbook

import runtime.mobileagent.domain.worldbook.WorldBookDecision
import runtime.mobileagent.domain.worldbook.WorldBookDecisionStatus
import runtime.mobileagent.domain.worldbook.WorldBookScanState
import runtime.mobileagent.domain.worldbook.WorldBookSettings
import runtime.mobileagent.domain.worldbook.WorldBookTimedEffectType

/**
 * 包含组过滤（ST `filterByInclusionGroups` / `filterGroupsByTimedEffects` / `filterGroupsByScoring`）。
 *
 * 顺序与 ST 一致：
 *  1. sticky 组成员独占（其余剔除）→ 冷却/延迟成员剔除；
 *  2. 组评分（全局 `useGroupScoring` 或组内任一条目开启时才评分；有 sticky 的组跳过）；
 *  3. 组已激活 → 全组剔除；组内只有一条 → 保留；
 *  4. `groupOverride` 优先，按 `order` 降序取第一条；
 *  5. 否则按 `groupWeight` 加权随机（由 seed 派生）。
 *
 * 与 ST 的差异：ST 的 `splice(indexOf(...))` 在 index 为 -1 时会误删末元素；
 * 本实现用显式集合记录被剔除条目，不复刻该缺陷。
 */
internal object WorldBookEngineGroups {

    fun filter(
        candidates: MutableList<ScanEntry>,
        activated: Map<String, ScanEntry>,
        buffer: WorldBookBuffer,
        scanState: WorldBookScanState,
        effects: WorldBookEffects,
        settings: WorldBookSettings,
        random: DeterministicRandom,
        budget: StepBudget,
        loop: Int,
        decisions: LinkedHashMap<String, WorldBookDecision>,
    ) {
        if (candidates.isEmpty()) return
        val grouped = LinkedHashMap<String, MutableList<ScanEntry>>()
        for (entry in candidates) {
            entry.entry.group.split(GROUP_SEPARATOR)
                .filter { it.isNotEmpty() }
                .forEach { grouped.getOrPut(it) { mutableListOf() } += entry }
        }
        if (grouped.isEmpty()) return

        val removed = HashSet<String>()

        fun removeEntry(entry: ScanEntry, group: String, reason: String) {
            if (!removed.add(entry.key)) return
            recordDecision(
                decisions, entry, WorldBookDecisionStatus.DENIED, "$reason:$group",
                emptyList(), 0, loop, scanState,
            )
        }

        val hasSticky = applyTimedEffects(grouped, effects, removed, ::removeEntry)
        applyScoring(grouped, removed, hasSticky, buffer, scanState, settings, budget, ::removeEntry)

        for ((group, members) in grouped) {
            if (hasSticky[group] == true) continue
            if (activated.values.any { group in it.entry.group.split(GROUP_SEPARATOR) }) {
                members.forEach { removeEntry(it, group, "group-already-activated") }
                continue
            }
            val live = members.filter { it.key !in removed }
            if (live.size <= 1) continue
            val priorities = live.filter { it.entry.groupOverride }
                .sortedWith(compareByDescending<ScanEntry> { it.entry.order }.thenBy { it.globalIndex })
            if (priorities.isNotEmpty()) {
                val winner = priorities.first()
                live.forEach { if (it.key != winner.key) removeEntry(it, group, "group-override-loser") }
                continue
            }
            val winner = weightedWinner(live, random)
            live.forEach { if (it.key != winner.key) removeEntry(it, group, "group-lost") }
        }

        candidates.removeAll { it.key in removed }
    }

    private fun applyTimedEffects(
        grouped: Map<String, MutableList<ScanEntry>>,
        effects: WorldBookEffects,
        removed: Set<String>,
        removeEntry: (ScanEntry, String, String) -> Unit,
    ): Map<String, Boolean> {
        val hasSticky = HashMap<String, Boolean>()
        for ((group, members) in grouped) {
            hasSticky[group] = false
            val sticky = members.filter { effects.isActive(WorldBookTimedEffectType.STICKY, it) }
            if (sticky.isNotEmpty()) {
                hasSticky[group] = true
                val stickyKeys = sticky.map { it.key }.toSet()
                members.filter { it.key !in stickyKeys }
                    .forEach { removeEntry(it, group, "group-sticky-loser") }
            }
            members.filter { effects.isActive(WorldBookTimedEffectType.COOLDOWN, it) &&
                !effects.isActive(WorldBookTimedEffectType.STICKY, it) }
                .forEach { removeEntry(it, group, "group-cooldown") }
            members.filter { effects.isActive(WorldBookTimedEffectType.DELAY, it) }
                .forEach { removeEntry(it, group, "group-delay") }
        }
        return hasSticky
    }

    private fun applyScoring(
        grouped: Map<String, MutableList<ScanEntry>>,
        removed: Set<String>,
        hasSticky: Map<String, Boolean>,
        buffer: WorldBookBuffer,
        scanState: WorldBookScanState,
        settings: WorldBookSettings,
        budget: StepBudget,
        removeEntry: (ScanEntry, String, String) -> Unit,
    ) {
        for ((group, members) in grouped) {
            if (!settings.useGroupScoring && members.none { it.entry.useGroupScoring == true }) continue
            if (hasSticky[group] == true) continue
            val live = members.filter { it.key !in removed }
            if (live.isEmpty()) continue
            val scores = live.map {
                try {
                    buffer.score(it, scanState, budget)
                } catch (e: RegexAbortedException) {
                    0
                }
            }
            val maxScore = scores.max()
            for (index in live.indices) {
                val entry = live[index]
                val scored = entry.entry.useGroupScoring ?: settings.useGroupScoring
                if (scored && scores[index] < maxScore) removeEntry(entry, group, "group-score-loser")
            }
        }
    }

    /** 加权随机：总权重 <= 0 时取第一条（与 ST 中 `rollValue <= currentWeight` 的退化行为一致）。 */
    private fun weightedWinner(live: List<ScanEntry>, random: DeterministicRandom): ScanEntry {
        val weights = live.map { it.entry.groupWeight.coerceAtLeast(0) }
        val total = weights.sum()
        if (total <= 0) return live.first()
        val roll = random.nextDouble() * total
        var cumulative = 0.0
        for (index in live.indices) {
            cumulative += weights[index]
            if (roll <= cumulative) return live[index]
        }
        return live.last()
    }

    private val GROUP_SEPARATOR = Regex(",\\s*")
}
