// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderDestinationBinding
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.provider.ProviderConnectionResult
import runtime.mobileagent.provider.RequestHeaderValue
import java.net.URI

class ProviderDestinationAdapterTest {
    @Test
    fun destinationChangeDoesNotSendInheritedHeaderSecretsToTheNewHost() = runBlocking {
        val vault = mapOf(
            "primary-a" to "SYNTHETIC_PRIMARY_A",
            "aux-a" to "SYNTHETIC_A_AUXILIARY_NOT_A_REAL_KEY",
            "primary-b" to "SYNTHETIC_B_PRIMARY_NOT_A_REAL_KEY",
        )
        val old = ProviderProfile(
            id = "provider.dest",
            name = "Destination fixture",
            apiFormat = ApiFormat.OPENAI_COMPATIBLE,
            baseUrl = "https://provider-a.invalid/v1",
            headerSecretRefs = mapOf("X-Org-Token" to "aux-a"),
            secretRef = "primary-a",
            revision = 1,
        )
        val seen = mutableMapOf<String, String?>()
        val engine = MockEngine { request ->
            seen[request.url.host] = request.headers["X-Org-Token"]
            respond(
                content = """{"choices":[{"message":{"content":"ok"}}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        HttpClient(engine).use { http ->
            val sameHost = old.copy(
                headerSecretRefs = ProviderDestinationBinding.headerSecretRefsForSave(old, old.baseUrl, old.headerSecretRefs),
                revision = 2,
            )
            val sameResult = adapterFor(sameHost, http, vault).testConnection(chatModel(sameHost), vault.getValue(sameHost.secretRef).toCharArray())
            assertEquals(true, sameResult is ProviderConnectionResult.Success)
            assertEquals("SYNTHETIC_A_AUXILIARY_NOT_A_REAL_KEY", seen["provider-a.invalid"])

            val newUrl = "https://provider-b.invalid/v1"
            val changed = old.copy(
                baseUrl = newUrl,
                secretRef = "primary-b",
                headerSecretRefs = ProviderDestinationBinding.headerSecretRefsForSave(old, newUrl, old.headerSecretRefs),
                revision = 3,
            )
            assertEquals(emptyMap<String, String>(), changed.headerSecretRefs)
            val changedResult = adapterFor(changed, http, vault).testConnection(chatModel(changed), vault.getValue(changed.secretRef).toCharArray())
            assertEquals(true, changedResult is ProviderConnectionResult.Success)
            assertNull(seen["provider-b.invalid"])
        }
    }

    private fun chatModel(provider: ProviderProfile) = ModelProfile(
        id = "model.${provider.id}",
        providerId = provider.id,
        role = ModelRole.CHAT,
        modelId = "synthetic-dest-model",
        capabilities = setOf("stream"),
        contextLimit = 4_096,
        outputLimit = 256,
        revision = 1,
    )

    private fun adapterFor(
        provider: ProviderProfile,
        http: HttpClient,
        vault: Map<String, String>,
    ) = OpenAiAdapterFactory.create(
        format = provider.apiFormat,
        http = http,
        baseUrl = provider.baseUrl,
        headerSecretResolver = { host, ref ->
            require(host.equals(URI(provider.baseUrl).host, true) && ref in provider.headerSecretRefs.values) {
                "Header secret destination mismatch"
            }
            vault.getValue(ref).toCharArray()
        },
        defaultHeaders = provider.headerSecretRefs.mapValues { RequestHeaderValue.SecretRef(it.value) },
    )
}
