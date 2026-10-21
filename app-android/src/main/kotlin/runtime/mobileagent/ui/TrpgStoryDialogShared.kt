// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Shared chrome for the story dialog tabs, cards and 48dp actions. */

@Composable
internal fun StoryTab(index: Int, selected: Int, chinese: Boolean, zh: String, en: String, onSelect: (Int) -> Unit) {
    FilterChip(
        selected = selected == index,
        onClick = { onSelect(index) },
        label = { Text(if (chinese) zh else en) },
        modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.story.tab.$index"),
    )
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun StoryCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
internal fun <T> StoryAction(
    label: String,
    tag: String,
    enabled: Boolean,
    action: (T) -> Unit,
    value: T,
) {
    TextButton(onClick = { action(value) }, enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
