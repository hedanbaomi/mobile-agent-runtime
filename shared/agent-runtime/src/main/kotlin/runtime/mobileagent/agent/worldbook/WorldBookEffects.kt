// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent.worldbook

import runtime.mobileagent.domain.worldbook.WorldBookTimedEffect
import runtime.mobileagent.domain.worldbook.WorldBookTimedEffectType

/**
 * 定时效果管理器（ST `WorldInfoTimedEffects` 等价实现）。
 *
 * 语义要点：
 *  - 时间轴单位是**聊天消息数**（[messageCount]），不是工具轮/生成轮；
 *  - sticky / cooldown 持久化（[nextEffects]）；delay 由 `messageCount < entry.delay` 派生，不持久化；
 *  - 条目被编辑后 hash 变化 → 旧效果立即失效（丢弃并告警）；
 *  - 消息数没有推进（`messageCount <= start`）且未受保护 → 效果丢弃（ST “chat not advanced”）；
 *  - sticky 到期时若条目配置了 cooldown，立刻转入一个**受保护**的 cooldown，并且覆盖同键的旧冷却
 *    （ST 通过 `chat_metadata.timedWorldInfo.cooldown[key] = effect` 覆盖，随后同一轮再处理冷却）；
 *  - 已禁用条目由引擎在扫描阶段排除，其残留效果会因“条目不存在/不再配置该效果”被清理。
 */
internal class WorldBookEffects(
    private val entries: List<ScanEntry>,
    private val messageCount: Int,
    previous: List<WorldBookTimedEffect>,
    private val warnings: MutableList<String>,
) {
    private val byKey: Map<String, ScanEntry> = entries.associateBy { it.key }

    /** 待处理/保留的 sticky 效果（键为 `bookId.uid`）。 */
    private val sticky = LinkedHashMap<String, WorldBookTimedEffect>()
    private val cooldown = LinkedHashMap<String, WorldBookTimedEffect>()

    private val activeSticky = HashSet<String>()
    private val activeCooldown = HashSet<String>()
    private val activeDelay = HashSet<String>()
    private val warned = HashSet<String>()

    init {
        prepare(previous)
    }

    private fun prepare(previous: List<WorldBookTimedEffect>) {
        for (effect in previous) {
            when (effect.type) {
                WorldBookTimedEffectType.DELAY -> warn("effect.delay-not-persisted:${effect.bookId}.${effect.entryUid}")
                WorldBookTimedEffectType.STICKY -> sticky[keyOf(effect)] = effect
                WorldBookTimedEffectType.COOLDOWN -> cooldown[keyOf(effect)] = effect
            }
        }
        prune(WorldBookTimedEffectType.STICKY, sticky, cooldown)
        prune(WorldBookTimedEffectType.COOLDOWN, cooldown, null)
        for (entry in entries) {
            val delay = entry.entry.delay ?: 0
            if (delay > 0 && messageCount < delay) activeDelay += entry.key
        }
    }

    /**
     * 逐个校验已存效果：失效则移除，仍有效则标记活跃；sticky 到期时按 ST 规则写入冷却。
     */
    private fun prune(
        type: WorldBookTimedEffectType,
        pending: LinkedHashMap<String, WorldBookTimedEffect>,
        cooldownSink: MutableMap<String, WorldBookTimedEffect>?,
    ) {
        for ((key, effect) in pending.entries.toList()) {
            val entry = byKey[key]
            if (entry == null) {
                if (messageCount >= effect.end) {
                    pending.remove(key)
                } else {
                    warn("effect.book-unavailable:$key")
                }
                continue
            }
            if (entry.hash != effect.hash) {
                warn("effect.hash-changed:$key")
                pending.remove(key)
                continue
            }
            if (configuredLength(type, entry) <= 0) {
                pending.remove(key)
                continue
            }
            if (messageCount <= effect.start && !effect.protected) {
                pending.remove(key)
                continue
            }
            if (messageCount >= effect.end) {
                pending.remove(key)
                if (type == WorldBookTimedEffectType.STICKY && cooldownSink != null) {
                    val length = entry.entry.cooldown ?: 0
                    if (length > 0) {
                        cooldownSink[key] = WorldBookTimedEffect(
                            type = WorldBookTimedEffectType.COOLDOWN,
                            bookId = entry.bookId,
                            entryUid = entry.entry.uid,
                            hash = effect.hash,
                            start = messageCount,
                            end = messageCount + length,
                            protected = true,
                        )
                        activeCooldown += key
                    }
                }
                continue
            }
            if (type == WorldBookTimedEffectType.STICKY) activeSticky += key else activeCooldown += key
        }
    }

    private fun configuredLength(type: WorldBookTimedEffectType, entry: ScanEntry): Int = when (type) {
        WorldBookTimedEffectType.STICKY -> entry.entry.sticky ?: 0
        WorldBookTimedEffectType.COOLDOWN -> entry.entry.cooldown ?: 0
        WorldBookTimedEffectType.DELAY -> 0
    }

    fun isActive(type: WorldBookTimedEffectType, entry: ScanEntry): Boolean = when (type) {
        WorldBookTimedEffectType.STICKY -> entry.key in activeSticky
        WorldBookTimedEffectType.COOLDOWN -> entry.key in activeCooldown
        WorldBookTimedEffectType.DELAY -> entry.key in activeDelay
    }

    /** 记录本轮激活条目的 sticky / cooldown 起点（已存在则不重置）。 */
    fun recordActivated(activated: List<ScanEntry>) {
        for (entry in activated) {
            val stickyLength = entry.entry.sticky ?: 0
            if (stickyLength > 0 && entry.key !in sticky) {
                sticky[entry.key] = effectFor(WorldBookTimedEffectType.STICKY, entry, stickyLength, protected = false)
                activeSticky += entry.key
            }
            val cooldownLength = entry.entry.cooldown ?: 0
            if (cooldownLength > 0 && entry.key !in cooldown) {
                cooldown[entry.key] = effectFor(WorldBookTimedEffectType.COOLDOWN, entry, cooldownLength, protected = false)
                activeCooldown += entry.key
            }
        }
    }

    private fun effectFor(
        type: WorldBookTimedEffectType,
        entry: ScanEntry,
        length: Int,
        protected: Boolean,
    ): WorldBookTimedEffect = WorldBookTimedEffect(
        type = type,
        bookId = entry.bookId,
        entryUid = entry.entry.uid,
        hash = entry.hash,
        start = messageCount,
        end = messageCount + length,
        protected = protected,
    )

    /** 需要持久化的效果（仅 STICKY / COOLDOWN），顺序稳定。 */
    fun nextEffects(): List<WorldBookTimedEffect> =
        sortEffects(sticky.values) + sortEffects(cooldown.values)

    private fun sortEffects(values: Collection<WorldBookTimedEffect>): List<WorldBookTimedEffect> =
        values.sortedWith(compareBy({ it.type.name }, { it.bookId }, { it.entryUid }))

    private fun keyOf(effect: WorldBookTimedEffect): String = "${effect.bookId}.${effect.entryUid}"

    private fun warn(message: String) {
        if (warned.add(message)) addWarning(warnings, message)
    }
}
