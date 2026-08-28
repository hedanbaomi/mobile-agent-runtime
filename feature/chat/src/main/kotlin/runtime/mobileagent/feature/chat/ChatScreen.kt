// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChatScreen() {
    val input = remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<String>() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        LazyColumn(Modifier.weight(1f)) {
            items(lines) { Text(it, modifier = Modifier.padding(bottom = 8.dp)) }
        }
        OutlinedTextField(
            value = input.value,
            onValueChange = { input.value = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Message") },
        )
        Button(
            onClick = {
                val text = input.value
                if (text.isNotBlank()) {
                    lines.add("You: $text")
                    input.value = ""
                    lines.add("Assistant: configure a Provider and Agent in Settings to stream a real reply.")
                }
            },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Send") }
    }
}
