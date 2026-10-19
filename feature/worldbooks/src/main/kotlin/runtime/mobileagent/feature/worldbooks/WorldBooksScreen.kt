// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.worldbooks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * Entry point of the world book feature.
 *
 * The screen is purely state-and-callback driven: it renders [state], reports intent
 * through [actions], and performs no side effect of its own. The host owns the editor
 * draft, the pending import and the offline test report.
 */
@Composable
fun WorldBooksScreen(
    state: WorldBooksUiState,
    actions: WorldBooksActions,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().testTag(WorldBookTestTags.SCREEN)) {
        val editor = state.editor
        if (editor == null) {
            WorldBookListPane(state, actions)
        } else {
            WorldBookEditorPane(editor, actions, status = state.status, error = state.error)
        }
        state.pendingImport?.let { pending -> WorldBookImportDialog(pending, actions) }
    }
}
