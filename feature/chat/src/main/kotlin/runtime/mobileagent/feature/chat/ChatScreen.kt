// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class ChatLine(val role: String, val text: String)

@Composable
fun ChatScreen(
    lines: List<ChatLine>,
    input: String,
    streaming: Boolean,
    status: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(status, modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(lines) { line ->
                Text("${line.role}: ${line.text}", modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = onInput,
            modifier = Modifier.fillMaxWidth(),
            enabled = !streaming,
            label = { Text("Message") },
        )
        Row(Modifier.padding(top = 8.dp)) {
            Button(onClick = onSend, enabled = !streaming && input.isNotBlank()) { Text("Send") }
            Button(
                onClick = onCancel,
                enabled = streaming,
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Cancel") }
        }
    }
}
