// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.providers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.ProviderProfile

@Composable
fun ProvidersScreen(
    providers: List<ProviderProfile>,
    onSave: (ProviderProfile, String) -> Unit,
) {
    val name = remember { mutableStateOf("") }
    val baseUrl = remember { mutableStateOf("https://api.openai.com/v1") }
    val apiKey = remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Providers (BYOK). Keys are stored in Android Keystore ciphertext, never in source control.")
        providers.forEach { Text("• ${it.name} (${it.baseUrl})") }
        OutlinedTextField(name.value, { name.value = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(baseUrl.value, { baseUrl.value = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(apiKey.value, { apiKey.value = it }, label = { Text("API key") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            val profile = ProviderProfile(
                id = EntityId.random().value,
                name = name.value,
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = baseUrl.value,
                secretRef = "provider:${name.value}",
                revision = 1,
            )
            onSave(profile, apiKey.value)
        }, modifier = Modifier.padding(top = 8.dp)) { Text("Save provider") }
        Text("Capability tests may bill your provider. They are never run automatically for every model.")
    }
}
