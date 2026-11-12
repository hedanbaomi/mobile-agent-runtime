// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import runtime.mobileagent.QuickStartStep
import runtime.mobileagent.R

/**
 * Immutable UI state representing the onboarding quick-start card.
 */
@Immutable
internal data class QuickStartUiState(
    val step: QuickStartStep? = null,
    val collapsed: Boolean = false,
    val visible: Boolean = false,
) {
    val stepId: String? get() = step?.name
}

/**
 * Decoupled presentation card for quick-start onboarding.
 * Pure stateless Composable taking immutable state and callbacks.
 */
@Composable
internal fun QuickStartCard(
    state: QuickStartUiState,
    onStepClick: (stepId: String) -> Unit,
    onDismiss: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.visible) return
    val current = state.step ?: return
    if (current == QuickStartStep.COMPLETE) return

    if (state.collapsed) {
        TextButton(
            onClick = onResume,
            modifier = modifier,
        ) {
            Text(stringResource(R.string.quick_start_resume))
        }
        return
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.quick_start_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(
                    when (current) {
                        QuickStartStep.MODEL -> R.string.quick_start_model
                        QuickStartStep.AGENT -> R.string.quick_start_agent
                        else -> R.string.quick_start_message
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                TextButton(
                    onClick = { onStepClick(current.name) },
                ) {
                    Text(stringResource(R.string.quick_start_continue))
                }
                TextButton(
                    onClick = onDismiss,
                ) {
                    Text(stringResource(R.string.quick_start_later))
                }
            }
        }
    }
}

/**
 * Backwards-compatible content adapter preserving existing device test signatures.
 */
@Composable
internal fun QuickStartContent(
    current: QuickStartStep,
    collapsed: Boolean,
    onCollapsed: (Boolean) -> Unit,
    onChooseAgent: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    QuickStartCard(
        state = QuickStartUiState(
            step = current,
            collapsed = collapsed,
            visible = current != QuickStartStep.COMPLETE,
        ),
        onStepClick = { stepId ->
            when (stepId) {
                QuickStartStep.MESSAGE.name -> onChooseAgent()
                QuickStartStep.MODEL.name -> onNavigate(AppRoutes.PROVIDERS)
                QuickStartStep.AGENT.name -> onNavigate(AppRoutes.AGENTS)
                else -> onNavigate(AppRoutes.CHAT)
            }
        },
        onDismiss = { onCollapsed(true) },
        onResume = { onCollapsed(false) },
    )
}
