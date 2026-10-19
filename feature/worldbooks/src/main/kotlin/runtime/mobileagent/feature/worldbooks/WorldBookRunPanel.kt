// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.worldbooks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Reusable run report: matched and excluded entries with a reason each, the estimated
 * unit cost and the budget it is measured against.
 *
 * Only host-supplied text is shown. A failed entry is described by its own status and
 * reason, never by an exception or a call stack, and every state word is written out so
 * success and exclusion never differ by color alone.
 *
 * @param language host UI language. Visible copy resolves through the app-localized
 * resources, so the parameter is informational for hosts that render one language.
 */
@Composable
fun WorldBookRunPanel(
    preview: WorldBookPreviewUi,
    @Suppress("UNUSED_PARAMETER") language: String = "zh-CN",
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().testTag(WorldBookTestTags.RUN_PANEL),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(stringResource(R.string.wb_run_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.wb_preview_budget, preview.usedUnits, preview.budgetUnits),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp).testTag(WorldBookTestTags.RUN_BUDGET),
            )
            Text(
                stringResource(R.string.wb_preview_units, preview.totalEstimatedUnits),
                style = MaterialTheme.typography.labelSmall,
            )
            if (preview.isOverBudget()) {
                Text(
                    stringResource(R.string.wb_run_over_budget),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (preview.matches.isEmpty()) {
                Text(
                    stringResource(R.string.wb_preview_no_matches),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                preview.matches.forEachIndexed { index, match -> WorldBookMatchRow(match, index) }
            }
            if (preview.warnings.isNotEmpty()) {
                Text(
                    stringResource(R.string.wb_preview_warnings, preview.warnings.size),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
                preview.warnings.forEachIndexed { index, warning ->
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp).testTag(WorldBookTestTags.warning(index)),
                    )
                }
            }
        }
    }
}

@Composable
private fun WorldBookMatchRow(match: WorldBookMatchUi, index: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag(WorldBookTestTags.match(index)),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text(match.title, style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                WorldBookToneChip(worldBookStatusTone(match.status))
                if (match.status.isNotBlank()) {
                    Text(
                        match.status,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.wb_preview_units, match.safeUnits),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (match.reason.isNotBlank()) {
                Text(
                    stringResource(R.string.wb_match_reason),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(match.reason, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun WorldBookToneChip(tone: WorldBookStatusTone) {
    val container = when (tone) {
        WorldBookStatusTone.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
        WorldBookStatusTone.EXCLUDED -> MaterialTheme.colorScheme.surfaceVariant
        WorldBookStatusTone.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        WorldBookStatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        WorldBookStatusTone.NEUTRAL -> MaterialTheme.colorScheme.secondaryContainer
    }
    Surface(color = container, shape = MaterialTheme.shapes.small) {
        Text(
            stringResource(worldBookToneLabel(tone)),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

internal fun worldBookToneLabel(tone: WorldBookStatusTone): Int = when (tone) {
    WorldBookStatusTone.SUCCESS -> R.string.wb_tone_hit
    WorldBookStatusTone.EXCLUDED -> R.string.wb_tone_excluded
    WorldBookStatusTone.WARNING -> R.string.wb_tone_warning
    WorldBookStatusTone.ERROR -> R.string.wb_tone_error
    WorldBookStatusTone.NEUTRAL -> R.string.wb_tone_neutral
}
