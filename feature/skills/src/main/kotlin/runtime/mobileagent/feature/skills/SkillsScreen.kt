// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.skills

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SkillsScreen() {
    Column(Modifier.padding(16.dp)) {
        Text("Skills are installed from local packages you choose. Python runs only in an isolated process.")
        Text("Announcements cannot grant Skill permissions or execute code.")
    }
}
