// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile

class ProvidersViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MobileAgentApp
    val providers = mutableStateListOf<ProviderProfile>()
    val status = mutableStateOf("")

    init {
        reload()
    }

    fun reload() {
        providers.clear()
        providers.addAll(app.container.profiles.listProviders())
    }

    fun save(name: String, baseUrl: String, modelId: String, apiKey: String, vision: Boolean, tools: Boolean = false): Boolean {
        if (name.isBlank() || baseUrl.isBlank() || modelId.isBlank()) {
            status.value = "Name, base URL, and model id are required."
            return false
        }
        if (apiKey.isBlank()) {
            status.value = "An API key is required. It is encrypted with Android Keystore and never written as plaintext."
            return false
        }
        val id = EntityId.random().value
        val secretRef = "provider:$id"
        val profile = ProviderProfile(
            id = id,
            name = name.trim(),
            apiFormat = ApiFormat.OPENAI_COMPATIBLE,
            baseUrl = baseUrl.trim().trimEnd('/'),
            secretRef = secretRef,
            revision = 1,
        )
        app.container.secrets.put(secretRef, apiKey.toCharArray())
        app.container.profiles.upsertProvider(profile)
        val capabilities = mutableSetOf("stream")
        if (vision) capabilities += "image"
        if (tools) capabilities += "tools"
        app.container.profiles.upsertModel(
            ModelProfile(
                id = EntityId.random().value,
                providerId = id,
                role = ModelRole.CHAT,
                modelId = modelId.trim(),
                capabilities = capabilities,
                contextLimit = 128_000,
                outputLimit = 8_192,
                revision = 1,
            ),
        )
        reload()
        status.value = "Saved ${profile.name}. The key is stored as ciphertext referenced by $secretRef."
        return true
    }
}
