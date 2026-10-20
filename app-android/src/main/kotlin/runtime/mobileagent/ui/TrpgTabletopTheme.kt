// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import runtime.mobileagent.domain.trpg.TrpgResource
import runtime.mobileagent.domain.trpg.TrpgRuleset
import runtime.mobileagent.domain.trpg.TrpgState

internal data class TrpgTabletopPalette(
    val boardSurface: Color,
    val headerSurface: Color,
    val border: Color,
    val crestContainer: Color,
    val crestContent: Color,
    val hpAccent: Color,
    val mpAccent: Color,
    val sanAccent: Color,
    val luckAccent: Color,
    val defaultResourceAccent: Color,
    val criticalContainer: Color,
    val criticalContent: Color,
    val successContainer: Color,
    val successContent: Color,
    val failContainer: Color,
    val failContent: Color,
    val fumbleContainer: Color,
    val fumbleContent: Color,
)

internal data class TrpgResourceVisual(
    val icon: String,
    val accent: Color,
)

@Composable
internal fun rememberTrpgPalette(): TrpgTabletopPalette {
    val mode = LocalAppThemeMode.current
    val scheme = MaterialTheme.colorScheme
    val dark = mode == AppThemeMode.DARK || (mode == AppThemeMode.SYSTEM && isSystemInDarkTheme())
    val aqua = mode == AppThemeMode.CC66FF
    return when {
        aqua -> TrpgTabletopPalette(
            boardSurface = Color(0xFFEAF6FC),
            headerSurface = Color(0xFFD6EEF9),
            border = Color(0xFF7CC8F2),
            crestContainer = Color(0xFF66CCFF),
            crestContent = Color(0xFF003B52),
            hpAccent = Color(0xFFD92D20),
            mpAccent = Color(0xFF0284C7),
            sanAccent = Color(0xFF0E7490),
            luckAccent = Color(0xFFD97706),
            defaultResourceAccent = Color(0xFF0369A1),
            criticalContainer = Color(0xFFFEF3C7),
            criticalContent = Color(0xFF92400E),
            successContainer = Color(0xFFCFFAFE),
            successContent = Color(0xFF083344),
            failContainer = Color(0xFFE2E8F0),
            failContent = Color(0xFF334155),
            fumbleContainer = Color(0xFFFEE2E2),
            fumbleContent = Color(0xFF991B1B),
        )
        dark -> TrpgTabletopPalette(
            boardSurface = Color(0xFF182234),
            headerSurface = Color(0xFF1F2D44),
            border = Color(0xFF3B4F70),
            crestContainer = scheme.primaryContainer,
            crestContent = scheme.onPrimaryContainer,
            hpAccent = Color(0xFFF87171),
            mpAccent = Color(0xFF60A5FA),
            sanAccent = Color(0xFF34D399),
            luckAccent = Color(0xFFFBBF24),
            defaultResourceAccent = scheme.primary,
            criticalContainer = Color(0xFF78350F),
            criticalContent = Color(0xFFFDE68A),
            successContainer = scheme.tertiaryContainer,
            successContent = scheme.onTertiaryContainer,
            failContainer = scheme.secondaryContainer,
            failContent = scheme.onSecondaryContainer,
            fumbleContainer = scheme.errorContainer,
            fumbleContent = scheme.onErrorContainer,
        )
        else -> TrpgTabletopPalette(
            boardSurface = Color(0xFFF4F6FB),
            headerSurface = Color(0xFFE8EEF8),
            border = Color(0xFFC7D4E8),
            crestContainer = scheme.primaryContainer,
            crestContent = scheme.onPrimaryContainer,
            hpAccent = Color(0xFFDC2626),
            mpAccent = Color(0xFF2563EB),
            sanAccent = Color(0xFF0D9488),
            luckAccent = Color(0xFFD97706),
            defaultResourceAccent = scheme.primary,
            criticalContainer = Color(0xFFFEF3C7),
            criticalContent = Color(0xFF92400E),
            successContainer = scheme.tertiaryContainer,
            successContent = scheme.onTertiaryContainer,
            failContainer = scheme.secondaryContainer,
            failContent = scheme.onSecondaryContainer,
            fumbleContainer = scheme.errorContainer,
            fumbleContent = scheme.onErrorContainer,
        )
    }
}

private val HP_KEYS = listOf("HP", "生命", "体力")
private val SAN_KEYS = listOf("SAN", "理智", "心智", "污染", "侵蚀")
private val MP_KEYS = listOf("MP", "法力", "魔力", "法术", "SLOT", "能量")
private val LUCK_KEYS = listOf("LUCK", "幸运", "灵感", "激励", "INSP", "命运")
private val AC_KEYS = listOf("AC", "护甲", "防御")
private val STAT_EXACT = setOf("STR", "DEX", "CON", "INT", "WIS", "CHA", "SIZ", "APP", "POW", "EDU")
private val STAT_KEYS = listOf("力量", "敏捷", "体质", "智力", "感知", "魅力", "意志", "体型", "教育", "外貌")
private val SAVE_KEYS = listOf("豁免", "SAVE", "FORT", "REF", "WILL")
private val SPEED_KEYS = listOf("MOV", "SPEED", "INIT", "速度", "先攻", "移动", "AP")

internal fun trpgResourceVisual(name: String, palette: TrpgTabletopPalette): TrpgResourceVisual {
    val upper = name.trim().uppercase()
    return when {
        HP_KEYS.any(upper::contains) -> TrpgResourceVisual("♥", palette.hpAccent)
        SAN_KEYS.any(upper::contains) -> TrpgResourceVisual("👁", palette.sanAccent)
        MP_KEYS.any(upper::contains) -> TrpgResourceVisual("✦", palette.mpAccent)
        LUCK_KEYS.any(upper::contains) -> TrpgResourceVisual("★", palette.luckAccent)
        AC_KEYS.any(upper::contains) -> TrpgResourceVisual("🛡", palette.defaultResourceAccent)
        upper in STAT_EXACT || STAT_KEYS.any(upper::contains) -> TrpgResourceVisual("◈", palette.mpAccent)
        SAVE_KEYS.any(upper::contains) -> TrpgResourceVisual("⛨", palette.sanAccent)
        SPEED_KEYS.any(upper::contains) -> TrpgResourceVisual("⚡", palette.luckAccent)
        else -> TrpgResourceVisual("◆", palette.defaultResourceAccent)
    }
}

private fun i18n(chinese: Boolean, zh: String, en: String): String = if (chinese) zh else en

internal fun trpgRulesetTitle(ruleset: TrpgRuleset, chinese: Boolean): String = when (ruleset) {
    TrpgRuleset.DND_5E -> i18n(chinese, "⚔ D&D 通用框架", "⚔ D&D Universal")
    TrpgRuleset.DND_3R -> i18n(chinese, "⚔ D&D 3R/PF 框架", "⚔ D&D 3R/PF")
    TrpgRuleset.COC_7 -> i18n(chinese, "🐙 CoC 通用框架", "🐙 CoC Universal")
    TrpgRuleset.CUSTOM -> i18n(chinese, "📖 世界书自定义", "📖 Lorebook Custom")
}

internal fun trpgFrameworkCatalog(ruleset: TrpgRuleset, chinese: Boolean): List<TrpgResource> = when (ruleset) {
    TrpgRuleset.COC_7 -> listOf(
        TrpgResource("HP", 11, 11),
        TrpgResource("MP", 12, 12),
        TrpgResource("SAN", 60, 99),
        TrpgResource(i18n(chinese, "幸运", "Luck"), 50, 99),
        TrpgResource("STR", 50, 100),
        TrpgResource("CON", 55, 100),
        TrpgResource("SIZ", 60, 100),
        TrpgResource("DEX", 60, 100),
        TrpgResource("APP", 50, 100),
        TrpgResource("INT", 70, 100),
        TrpgResource("POW", 60, 100),
        TrpgResource("EDU", 70, 100),
        TrpgResource("MOV", 8, null),
        TrpgResource(i18n(chinese, "护甲", "Armor"), 0, null),
        TrpgResource(i18n(chinese, "体格", "Build"), 0, null),
        TrpgResource(i18n(chinese, "克苏鲁神话", "Mythos"), 0, 99),
        TrpgResource(i18n(chinese, "信用评级", "Credit"), 30, 99),
    )
    TrpgRuleset.DND_5E, TrpgRuleset.DND_3R -> listOf(
        TrpgResource("HP", 20, 20),
        TrpgResource(i18n(chinese, "临时HP", "Temp HP"), 0, null),
        TrpgResource("AC", 15, null),
        TrpgResource(i18n(chinese, "先攻", "Init"), 2, null),
        TrpgResource(i18n(chinese, "速度", "Speed"), 30, null),
        TrpgResource(i18n(chinese, "熟练/BAB", "Prof/BAB"), 2, null),
        TrpgResource(i18n(chinese, "一环法术位", "Lv1 Slots"), 2, 2),
        TrpgResource(i18n(chinese, "生命骰", "Hit Dice"), 2, 2),
        TrpgResource(i18n(chinese, "激励", "Inspiration"), 1, 1),
        TrpgResource("STR", 14, null),
        TrpgResource("DEX", 16, null),
        TrpgResource("CON", 14, null),
        TrpgResource("INT", 12, null),
        TrpgResource("WIS", 13, null),
        TrpgResource("CHA", 10, null),
        TrpgResource(i18n(chinese, "强韧豁免", "Fort Save"), 4, null),
        TrpgResource(i18n(chinese, "反射豁免", "Ref Save"), 5, null),
        TrpgResource(i18n(chinese, "意志豁免", "Will Save"), 3, null),
        TrpgResource(i18n(chinese, "被动察觉", "Pass.Perc"), 13, null),
    )
    TrpgRuleset.CUSTOM -> listOf(
        TrpgResource("HP", 20, 20),
        TrpgResource("MP", 10, 10),
        TrpgResource("SAN", 60, 100),
        TrpgResource(i18n(chinese, "行动点 AP", "AP"), 3, 3),
        TrpgResource(i18n(chinese, "护甲/防御", "Armor"), 10, null),
        TrpgResource(i18n(chinese, "命运点", "Fate"), 2, 2),
        TrpgResource(i18n(chinese, "污染度", "Corruption"), 0, 100),
        TrpgResource(i18n(chinese, "声望", "Reputation"), 0, null),
    )
}

internal fun trpgStarterState(ruleset: TrpgRuleset, base: TrpgState, chinese: Boolean): TrpgState {
    val starter = when (ruleset) {
        TrpgRuleset.COC_7 -> base.copy(
            ruleset = TrpgRuleset.COC_7,
            playerName = base.playerName.ifBlank { i18n(chinese, "调查员", "Investigator") },
            scene = base.scene.ifBlank { i18n(chinese, "调查起点（由世界书或守密人设定）", "Investigation Start") },
            resources = trpgFrameworkCatalog(TrpgRuleset.COC_7, chinese).take(4),
        )
        TrpgRuleset.CUSTOM -> base.copy(
            ruleset = TrpgRuleset.CUSTOM,
            playerName = base.playerName.ifBlank { i18n(chinese, "主角", "Protagonist") },
            scene = base.scene.ifBlank { i18n(chinese, "自定义世界书起始场景", "Lorebook Starting Scene") },
            resources = trpgFrameworkCatalog(TrpgRuleset.CUSTOM, chinese).take(4),
        )
        else -> base.copy(
            ruleset = ruleset,
            playerName = base.playerName.ifBlank { i18n(chinese, "冒险者", "Adventurer") },
            scene = base.scene.ifBlank { i18n(chinese, "冒险起点（由世界书或DM设定）", "Adventure Start") },
            resources = listOf(
                TrpgResource("HP", 20, 20),
                TrpgResource("AC", 15, null),
                TrpgResource(i18n(chinese, "一环法术位", "Lv1 Slots"), 2, 2),
                TrpgResource(i18n(chinese, "激励", "Inspiration"), 1, 1),
            ),
        )
    }
    return starter.copy(resources = base.resources + starter.resources.filter { preset ->
        base.resources.none { it.name == preset.name }
    }).also(runtime.mobileagent.domain.trpg.TrpgStateValidation::validate)
}

internal fun trpgSheetTemplate(ruleset: TrpgRuleset, chinese: Boolean): String = when (ruleset) {
    TrpgRuleset.COC_7 -> i18n(
        chinese,
        "【CoC 通用数值框架（兼容 6th/7th/Pulp/世界书变体，按需选用）】\n" +
            "【基础属性】STR 50 | CON 55 | SIZ 60 | DEX 60 | APP 50 | INT 70 | POW 60 | EDU 70\n" +
            "【衍生数值】MOV 8 | 体格 0 | 伤害加值 0 | 护甲 0\n" +
            "【核心技能】图书馆使用 70 | 侦查 65 | 聆听 50 | 心理学 60 | 说服 55 | 闪避 30 | 克苏鲁神话 0\n" +
            "【随身物品与背景】手电筒、调查笔记、钢笔 | 世界书特殊设定：可由KP/玩家自由补充",
        "[CoC Universal Framework (6th/7th/Pulp/Lorebook — use only needed fields)]\n" +
            "[Characteristics] STR 50 | CON 55 | SIZ 60 | DEX 60 | APP 50 | INT 70 | POW 60 | EDU 70\n" +
            "[Derived] MOV 8 | Build 0 | DB 0 | Armor 0\n" +
            "[Skills] Library Use 70 | Spot Hidden 65 | Listen 50 | Psychology 60 | Persuade 55 | Dodge 30\n" +
            "[Gear & Lorebook Rules] Flashlight, notebook | Custom lorebook traits may be added freely",
    )
    TrpgRuleset.CUSTOM -> i18n(
        chinese,
        "【世界书自定义规则角色卡（由挂载世界书与自定义数值栏驱动）】\n" +
            "【身份/阶位】待定\n" +
            "【自定义核心属性】可按世界书规则填写或放入上方数值栏目\n" +
            "【专长/技能/异能】按关联世界书条目结算\n" +
            "【装备与契约】待补充",
        "[Lorebook Custom Character Sheet]\n" +
            "[Identity / Rank] TBD\n" +
            "[Custom Attributes] Defined by active WorldBook or tracked in numeric resources\n" +
            "[Abilities & Gear] Resolved according to attached WorldBook entries",
    )
    else -> i18n(
        chinese,
        "【D&D / d20 通用数值框架（兼容 5E/2024/3.5R/PF/世界书变体，按需选用）】\n" +
            "【种族/职业/等级】1级 冒险者 | 熟练加值/BAB +2 | 先攻 +3 | 速度 30尺\n" +
            "【六维属性】STR 14(+2) | DEX 16(+3) | CON 14(+2) | INT 12(+1) | WIS 13(+1) | CHA 10(+0)\n" +
            "【豁免检定】强韧/体质 +4 | 反射/敏捷 +5 | 意志/感知 +3\n" +
            "【技能与特性】察觉 +3 | 隐匿 +5 | 调查 +3\n" +
            "【武器/法术/装备】长剑 (1d8+2)、短弓 (1d6+3)、探险家背包",
        "[D&D / d20 Universal Framework (5E/2024/3.5R/PF/Lorebook — use only needed fields)]\n" +
            "[Race/Class/Level] Lv.1 Adventurer | Prof/BAB +2 | Init +3 | Speed 30ft\n" +
            "[Abilities] STR 14(+2) | DEX 16(+3) | CON 14(+2) | INT 12(+1) | WIS 13(+1) | CHA 10(+0)\n" +
            "[Saves] Fort/CON +4 | Ref/DEX +5 | Will/WIS +3\n" +
            "[Skills & Features] Perception +3 | Stealth +5 | Investigation +3\n" +
            "[Weapons, Spells & Gear] Longsword (1d8+2), Shortbow (1d6+3), Explorer's Pack",
    )
}
