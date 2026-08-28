// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.providers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import runtime.mobileagent.domain.ProviderProfile

@Composable
fun ProvidersScreen(
    providers: List<ProviderProfile>,
    status: String,
    onSave: (name: String, baseUrl: String, modelId: String, apiKey: String, vision: Boolean, tools: Boolean) -> Boolean,
) {
    val name = remember { mutableStateOf("") }
    val baseUrl = remember { mutableStateOf("https://api.openai.com/v1") }
    val modelId = remember { mutableStateOf("") }
    val apiKey = remember { mutableStateOf("") }
    val vision = remember { mutableStateOf(false) }
    val tools = remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Providers (BYOK). Keys are encrypted with Android Keystore. This app does not proxy model traffic.")
        providers.forEach { Text("• ${it.name} (${it.baseUrl})") }
        if (status.isNotBlank()) Text(status, modifier = Modifier.padding(vertical = 8.dp))
        OutlinedTextField(name.value, { name.value = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(baseUrl.value, { baseUrl.value = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(modelId.value, { modelId.value = it }, label = { Text("Chat model id") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            apiKey.value,
            { apiKey.value = it },
            label = { Text("API key") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = vision.value, onCheckedChange = { vision.value = it })
            Text("This model accepts images (Vision). Required before image knowledge can leave WAITING.")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = tools.value, onCheckedChange = { tools.value = it })
            Text("This model can call tools. HTTP still requires confirmation.")
        }
        Button(
            onClick = {
                val ok = onSave(name.value, baseUrl.value, modelId.value, apiKey.value, vision.value, tools.value)
                if (ok) apiKey.value = ""
            },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Save provider") }
        Text("Capability tests may bill your provider. They are never run automatically for every model.")
    }
}
