// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.announcements

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import runtime.mobileagent.announcements.AnnouncementItem

@Composable
fun AnnouncementsScreen(items: List<AnnouncementItem>) {
    Column(Modifier.padding(16.dp)) {
        Text("Announcements")
        if (items.isEmpty()) {
            Text("No cached announcements. Offline history appears here after a successful signed fetch.")
        } else {
            items.forEach { Text("${it.title} (rev ${it.revision})") }
        }
    }
}
