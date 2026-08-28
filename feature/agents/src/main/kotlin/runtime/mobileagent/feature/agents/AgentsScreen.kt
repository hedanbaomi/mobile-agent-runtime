// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.agents

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AgentsScreen() {
    val prompt = remember { mutableStateOf("You are a careful assistant. Cite knowledge IDs only when provided.") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Agent prompt is versioned. Changing it creates a new revision and a new chat snapshot boundary.")
        OutlinedTextField(prompt.value, { prompt.value = it }, modifier = Modifier.fillMaxWidth(), minLines = 6)
    }
}
