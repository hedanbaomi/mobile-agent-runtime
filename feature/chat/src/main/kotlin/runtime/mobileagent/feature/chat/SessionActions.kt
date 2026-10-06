// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.chat

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** Host supplied action list is extensible without changing either session-list renderer. */
enum class SessionAction { ARCHIVE }

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun SessionActionItem(
    sessionId: String,
    actions: List<SessionAction>,
    zh: Boolean,
    onClick: () -> Unit,
    onAction: (String, SessionAction) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var menuOpen by remember(sessionId) { mutableStateOf(false) }
    Box(modifier.combinedClickable(onClick = onClick, onLongClick = { menuOpen = actions.isNotEmpty() })) {
        content()
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(when (action) { SessionAction.ARCHIVE -> if (zh) "归档" else "Archive" }) },
                    modifier = Modifier.testTag("session.action.${action.name}.$sessionId"),
                    onClick = { menuOpen = false; onAction(sessionId, action) },
                )
            }
        }
    }
}
