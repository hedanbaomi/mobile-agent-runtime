// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.knowledge

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun KnowledgeScreen() {
    Column(Modifier.padding(16.dp)) {
        Text("Knowledge bases stay on this device. Images without a Vision model wait; they are never silently dropped.")
        Text("Import uses the system file picker. Python and Skills cannot see real filesystem paths.")
    }
}
