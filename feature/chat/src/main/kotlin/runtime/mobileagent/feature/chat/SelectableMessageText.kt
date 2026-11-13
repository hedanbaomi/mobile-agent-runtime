// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

@Composable
internal fun SelectableMessageText(
    role: String,
    text: String,
    userInk: Color,
    markers: Map<String, ReplyCitationMarker>,
    onCitation: (String) -> Unit,
) {
    // A primary-colored highlight disappears on the light theme's primary-colored bubble.
    val colors = if (role.equals("user", true) && userInk.luminance() > 0.5f) {
        val scrim = MaterialTheme.colorScheme.scrim
        TextSelectionColors(handleColor = scrim, backgroundColor = scrim.copy(alpha = 0.85f))
    } else LocalTextSelectionColors.current

    CompositionLocalProvider(LocalTextSelectionColors provides colors) {
        SelectionContainer {
            val modifier = Modifier.padding(top = 4.dp)
            if (role.equals("assistant", true)) MarkdownText(text, modifier, markers, onCitation)
            else Text(text, modifier)
        }
    }
}
