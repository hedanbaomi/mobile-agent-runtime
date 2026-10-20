// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import runtime.mobileagent.domain.trpg.TrpgRuleset
import runtime.mobileagent.skills.CocOutcome
import runtime.mobileagent.skills.CocRoll
import runtime.mobileagent.skills.DiceRoll
import runtime.mobileagent.skills.DiceRollResult
import runtime.mobileagent.skills.StandardRoll

internal data class TrpgManualRollEntry(
    val summary: String,
    val detail: String,
    val result: DiceRollResult,
)

private val STANDARD_PRESETS = listOf(
    "1d20" to "d20",
    "2d20kh1" to "优势 2d20kh1",
    "2d20kl1" to "劣势 2d20kl1",
    "1d100" to "d100",
    "4d6kh3" to "4d6kh3",
    "2d6" to "2d6",
    "1d12" to "d12",
    "1d10" to "d10",
    "1d8" to "d8",
    "1d6" to "d6",
    "1d4" to "d4",
)

private val COC_SKILL_PRESETS = listOf(25, 40, 50, 60, 70, 80)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrpgDiceRollerDialog(
    defaultRuleset: TrpgRuleset,
    writable: Boolean,
    canInsertDraft: Boolean,
    saveError: String?,
    chinese: Boolean,
    history: List<TrpgManualRollEntry>,
    onRollRecorded: (TrpgManualRollEntry) -> Unit,
    onInsertDraft: (String) -> Unit,
    onAppendNotes: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = rememberTrpgPalette()
    var cocMode by remember(defaultRuleset) { mutableStateOf(defaultRuleset == TrpgRuleset.COC_7) }
    var expression by remember { mutableStateOf("1d20") }
    var dcInput by remember { mutableStateOf("") }
    var skillInput by remember { mutableStateOf("60") }
    var bonusDice by remember { mutableIntStateOf(0) }
    var penaltyDice by remember { mutableIntStateOf(0) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val latest = history.firstOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("trpg.dice.dialog"),
        title = {
            Text(
                text = if (chinese) "🎲 跑团检定骰盘" else "🎲 Tabletop Dice Tray",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !cocMode,
                        onClick = { cocMode = false; errorText = null },
                        label = { Text(if (chinese) "标准复合骰 (D&D)" else "Standard / D&D") },
                        modifier = Modifier.testTag("trpg.dice.mode.standard"),
                    )
                    FilterChip(
                        selected = cocMode,
                        onClick = { cocMode = true; errorText = null },
                        label = { Text(if (chinese) "CoC 7th 百分检定" else "CoC 7e d100") },
                        modifier = Modifier.testTag("trpg.dice.mode.coc7"),
                    )
                }

                if (!cocMode) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        STANDARD_PRESETS.forEach { (formula, label) ->
                            FilterChip(
                                selected = expression.trim() == formula,
                                onClick = { expression = formula; errorText = null },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.testTag("trpg.dice.preset.$formula"),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = expression,
                            onValueChange = { expression = it; errorText = null },
                            label = { Text(if (chinese) "骰子表达式 (如 2d20kh1+5)" else "Expression (e.g. 2d20kh1+5)") },
                            singleLine = true,
                            modifier = Modifier.weight(0.68f).testTag("trpg.dice.expression"),
                        )
                        OutlinedTextField(
                            value = dcInput,
                            onValueChange = { dcInput = it; errorText = null },
                            label = { Text(if (chinese) "DC (可选)" else "DC (opt)") },
                            singleLine = true,
                            modifier = Modifier.weight(0.32f).testTag("trpg.dice.dc"),
                        )
                    }
                    Button(
                        onClick = {
                            runCatching {
                                val parsedDc = dcInput.trim().takeIf { it.isNotEmpty() }?.let {
                                    it.toIntOrNull() ?: throw IllegalArgumentException("Invalid DC")
                                }
                                val roll = DiceRoll.roll(expression.trim(), dc = parsedDc)
                                onRollRecorded(formatStandardRollEntry(roll, chinese))
                                errorText = null
                            }.onFailure { err ->
                                errorText = err.message ?: "Invalid dice expression"
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("trpg.dice.roll"),
                    ) {
                        Text(if (chinese) "🎲 立即掷骰" else "🎲 Roll Dice")
                    }
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        COC_SKILL_PRESETS.forEach { preset ->
                            FilterChip(
                                selected = skillInput.trim() == preset.toString(),
                                onClick = { skillInput = preset.toString(); errorText = null },
                                label = { Text("${if (chinese) "技能" else "Skill"} $preset") },
                                modifier = Modifier.testTag("trpg.dice.skill_preset.$preset"),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = skillInput,
                        onValueChange = { skillInput = it; errorText = null },
                        label = { Text(if (chinese) "技能成功率 (1-100)" else "Skill threshold (1-100)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("trpg.dice.coc_skill"),
                    )
                    CocModifierRow(
                        title = if (chinese) "奖励骰 (Bonus)" else "Bonus dice",
                        selected = bonusDice,
                        tagPrefix = "trpg.dice.coc_bonus",
                        onSelect = { bonusDice = it },
                    )
                    CocModifierRow(
                        title = if (chinese) "惩罚骰 (Penalty)" else "Penalty dice",
                        selected = penaltyDice,
                        tagPrefix = "trpg.dice.coc_penalty",
                        onSelect = { penaltyDice = it },
                    )
                    Button(
                        onClick = {
                            runCatching {
                                val skill = skillInput.trim().toIntOrNull()
                                    ?: throw IllegalArgumentException("Skill must be 1..100")
                                val roll = DiceRoll.cocRoll(skill, bonusDice, penaltyDice)
                                onRollRecorded(formatCocRollEntry(roll, chinese))
                                errorText = null
                            }.onFailure { err ->
                                errorText = err.message ?: "Invalid CoC check"
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("trpg.dice.roll_coc"),
                    ) {
                        Text(if (chinese) "🐙 进行百分检定 (d100)" else "🐙 Roll d100 Check")
                    }
                }

                errorText?.let { msg ->
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("trpg.dice.error"),
                    )
                }
                saveError?.let { msg ->
                    Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("trpg.dice.save_error"))
                }

                latest?.let { entry ->
                    TrpgDiceResultCard(
                        entry = entry,
                        palette = palette,
                        writable = writable,
                        canInsertDraft = canInsertDraft,
                        chinese = chinese,
                        onInsertDraft = { onInsertDraft(entry.summary) },
                        onAppendNotes = { onAppendNotes(entry.summary) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("trpg.dice.close")) {
                Text(if (chinese) "关闭" else "Close")
            }
        },
    )
}

@Composable
private fun CocModifierRow(
    title: String,
    selected: Int,
    tagPrefix: String,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        (0..2).forEach { count ->
            FilterChip(
                selected = selected == count,
                onClick = { onSelect(count) },
                label = { Text(count.toString()) },
                modifier = Modifier.testTag("$tagPrefix.$count"),
            )
        }
    }
}

@Composable
private fun TrpgDiceResultCard(
    entry: TrpgManualRollEntry,
    palette: TrpgTabletopPalette,
    writable: Boolean,
    canInsertDraft: Boolean,
    chinese: Boolean,
    onInsertDraft: () -> Unit,
    onAppendNotes: () -> Unit,
) {
    Surface(
        color = palette.boardSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, palette.border),
        modifier = Modifier.fillMaxWidth().testTag("trpg.dice.result"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = entry.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onInsertDraft,
                    enabled = canInsertDraft,
                    modifier = Modifier.weight(1f).testTag("trpg.dice.insert_draft"),
                ) {
                    Text(if (chinese) "填入聊天输入" else "To Chat Input", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onAppendNotes,
                    enabled = writable,
                    modifier = Modifier.weight(1f).testTag("trpg.dice.append_notes"),
                ) {
                    Text(if (chinese) "记入跑团笔记" else "To Notes", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun formatStandardRollEntry(roll: StandardRoll, chinese: Boolean): TrpgManualRollEntry {
    val dcText = roll.dc?.let { dc ->
        val verdict = if (roll.success == true) {
            if (chinese) "成功" else "SUCCESS"
        } else {
            if (chinese) "失败" else "FAILURE"
        }
        " vs DC $dc ($verdict)"
    }.orEmpty()
    val summary = "🎲 [${roll.expression}$dcText] = ${roll.total}"
    val diceFaces = roll.dice.joinToString(", ") { die ->
        if (die.kept) "d${die.sides}:${die.value}" else "~d${die.sides}:${die.value}~"
    }
    return TrpgManualRollEntry(summary, diceFaces, roll)
}

private fun formatCocRollEntry(roll: CocRoll, chinese: Boolean): TrpgManualRollEntry {
    val outcomeText = when (roll.outcome) {
        CocOutcome.CRITICAL_SUCCESS -> if (chinese) "大成功" else "CRITICAL"
        CocOutcome.EXTREME_SUCCESS -> if (chinese) "极难成功" else "EXTREME"
        CocOutcome.HARD_SUCCESS -> if (chinese) "困难成功" else "HARD"
        CocOutcome.SUCCESS -> if (chinese) "常规成功" else "SUCCESS"
        CocOutcome.FUMBLE -> if (chinese) "大失败" else "FUMBLE"
        CocOutcome.FAILURE -> if (chinese) "失败" else "FAILURE"
    }
    val summary = "🐙 [CoC7 ${roll.roll}/${roll.skill}] $outcomeText"
    val detail = "Tens=${roll.tens} (selected ${roll.selectedTens}), Ones=${roll.ones} | " +
        "≤${roll.extremeSuccessAt} / ≤${roll.hardSuccessAt} / ≤${roll.skill} (Fumble ≥${roll.fumbleAt})"
    return TrpgManualRollEntry(summary, detail, roll)
}
