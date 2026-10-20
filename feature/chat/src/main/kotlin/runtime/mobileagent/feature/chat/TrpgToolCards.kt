// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import runtime.mobileagent.domain.trpg.TrpgSession

sealed interface ChatTrpgToolCardUi {
    data class StandardDice(
        val expression: String,
        val total: Long,
        val dc: Int?,
        val success: Boolean?,
        val dice: List<Pair<Int, Boolean>>,
    ) : ChatTrpgToolCardUi

    data class CocCheck(
        val skill: Int,
        val roll: Int,
        val outcome: String,
        val hardAt: Int,
        val extremeAt: Int,
        val fumbleAt: Int,
        val tens: List<Int>,
        val ones: Int,
    ) : ChatTrpgToolCardUi

    data class CampaignState(
        val session: TrpgSession,
    ) : ChatTrpgToolCardUi
}

@Composable
internal fun TrpgInlineToolCard(message: ChatMessageUi, modifier: Modifier = Modifier) {
    val parsed = message.trpgToolCard ?: return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .testTag("conversation.trpgTool.${message.id}"),
    ) {
        when (parsed) {
            is ChatTrpgToolCardUi.StandardDice -> StandardDiceCardBody(parsed)
            is ChatTrpgToolCardUi.CocCheck -> CocCheckCardBody(parsed)
            is ChatTrpgToolCardUi.CampaignState -> CampaignStateCardBody(parsed.session)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StandardDiceCardBody(model: ChatTrpgToolCardUi.StandardDice) {
    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "🎲 " + stringResource(R.string.trpg_tool_dice_title, model.expression),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "= ${model.total}",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
        if (model.dc != null && model.success != null) {
            val pass = model.success
            Surface(
                color = if (pass) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer,
                contentColor = if (pass) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = if (pass) {
                        stringResource(R.string.trpg_tool_dc_pass, model.dc.toString())
                    } else {
                        stringResource(R.string.trpg_tool_dc_fail, model.dc.toString())
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        if (model.dice.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                model.dice.take(24).forEach { (value, kept) ->
                    Surface(
                        color = if (kept) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (kept) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = value.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            textDecoration = if (kept) TextDecoration.None else TextDecoration.LineThrough,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CocCheckCardBody(model: ChatTrpgToolCardUi.CocCheck) {
    val pass = model.outcome in setOf("critical_success", "extreme_success", "hard_success", "success")
    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "🐙 " + stringResource(R.string.trpg_tool_coc_title, model.skill.toString()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${model.roll} / ${model.skill}",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
        Surface(
            color = if (pass) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer,
            contentColor = if (pass) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = cocOutcomeLabel(model.outcome),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Text(
            text = stringResource(
                R.string.trpg_tool_coc_thresholds,
                model.extremeAt.toString(),
                model.hardAt.toString(),
                model.skill.toString(),
                model.fumbleAt.toString(),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CampaignStateCardBody(session: TrpgSession) {
    val state = session.state
    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "📜 " + stringResource(R.string.trpg_tool_state_title, session.revision.toString()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = state.ruleset.name,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        val subtitle = listOfNotNull(
            state.playerName.takeIf { it.isNotBlank() },
            state.scene.takeIf { it.isNotBlank() }?.let { "📍 $it" },
        ).joinToString(" · ")
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
        if (state.resources.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.resources.take(12).forEach { res ->
                    val maxSuffix = res.maximum?.let { "/$it" } ?: ""
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = "${res.name} ${res.current}$maxSuffix",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun cocOutcomeLabel(outcome: String): String = when (outcome) {
    "critical_success" -> stringResource(R.string.trpg_tool_coc_critical)
    "extreme_success" -> stringResource(R.string.trpg_tool_coc_extreme)
    "hard_success" -> stringResource(R.string.trpg_tool_coc_hard)
    "success" -> stringResource(R.string.trpg_tool_coc_regular)
    "fumble" -> stringResource(R.string.trpg_tool_coc_fumble)
    else -> stringResource(R.string.trpg_tool_coc_failure)
}
