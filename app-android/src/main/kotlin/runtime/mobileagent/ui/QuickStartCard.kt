// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import runtime.mobileagent.AppContainer
import runtime.mobileagent.QuickStartStep
import runtime.mobileagent.quickStartStep
import runtime.mobileagent.R

/** Reads local readiness only; never probes a provider, creates an agent or grants a capability. */
@Composable
internal fun QuickStartCard(container: AppContainer, route: String, detailOpen: Boolean, streaming: Boolean, onChooseAgent: () -> Unit, onNavigate: (String) -> Unit) {
    if (detailOpen || route != AppRoutes.CHAT || streaming) return
    var step by remember { mutableStateOf<QuickStartStep?>(null) }
    var collapsed by remember { mutableStateOf(container.uiPreferences.getBoolean("quick-start-collapsed", false)) }
    LaunchedEffect(route, collapsed) {
        step = withContext(Dispatchers.IO) {
            runCatching {
                quickStartStep(
                    container.profiles.listModels().any { it.role == runtime.mobileagent.domain.ModelRole.CHAT },
                    container.agents.listOverviews().isNotEmpty(),
                    container.db.query("SELECT run_id FROM runs WHERE state='COMPLETED' LIMIT 1").isNotEmpty(),
                )
            }.getOrNull()
        }
    }
    val current = step ?: return
    QuickStartContent(current, collapsed, onCollapsed = { value ->
        collapsed = value
        container.uiPreferences.edit().putBoolean("quick-start-collapsed", value).apply()
    }, onChooseAgent, onNavigate)
}

@Composable
internal fun QuickStartContent(current: QuickStartStep, collapsed: Boolean, onCollapsed: (Boolean) -> Unit,
    onChooseAgent: () -> Unit, onNavigate: (String) -> Unit) {
    if (current == QuickStartStep.COMPLETE) return
    if (collapsed) {
        TextButton(onClick = { onCollapsed(false) }) { Text(stringResource(R.string.quick_start_resume)) }
        return
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.quick_start_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(when (current) {
                QuickStartStep.MODEL -> R.string.quick_start_model
                QuickStartStep.AGENT -> R.string.quick_start_agent
                else -> R.string.quick_start_message
            }), style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { if (current == QuickStartStep.MESSAGE) onChooseAgent() else onNavigate(when (current) {
                    QuickStartStep.MODEL -> AppRoutes.PROVIDERS
                    QuickStartStep.AGENT -> AppRoutes.AGENTS
                    else -> AppRoutes.CHAT
                }) }) { Text(stringResource(R.string.quick_start_continue)) }
                TextButton(onClick = { onCollapsed(true) }) { Text(stringResource(R.string.quick_start_later)) }
            }
        }
    }
}
