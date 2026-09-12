// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProfilesTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun apiFormatKeepsLegacyAndResponsesNamesStable() {
        assertEquals("OPENAI_COMPATIBLE", ApiFormat.OPENAI_COMPATIBLE.name)
        assertEquals("OPENAI_RESPONSES", ApiFormat.OPENAI_RESPONSES.name)
        assertEquals(ApiFormat.OPENAI_COMPATIBLE, json.decodeFromString<ApiFormat>("\"OPENAI_COMPATIBLE\""))
        assertEquals(ApiFormat.OPENAI_RESPONSES, json.decodeFromString<ApiFormat>("\"OPENAI_RESPONSES\""))
        assertEquals(
            ApiFormat.OPENAI_COMPATIBLE,
            json.decodeFromString<ApiFormat>(json.encodeToString(ApiFormat.serializer(), ApiFormat.OPENAI_COMPATIBLE)),
        )
    }
}

class ProviderDestinationBindingTest {
    private val previous = ProviderProfile(
        id = "provider.one",
        name = "One",
        apiFormat = ApiFormat.OPENAI_COMPATIBLE,
        baseUrl = "https://provider-a.invalid/v1",
        headerSecretRefs = mapOf("X-Org-Token" to "aux-a"),
        secretRef = "primary-a",
        revision = 1,
    )

    @Test
    fun sameDestinationKeepsAuxiliaryHeaderRefs() {
        assertEquals(
            previous.headerSecretRefs,
            ProviderDestinationBinding.headerSecretRefsForSave(previous, previous.baseUrl),
        )
    }

    @Test
    fun destinationChangeDropsInheritedAuxiliaryHeaderRefs() {
        assertEquals(
            emptyMap<String, String>(),
            ProviderDestinationBinding.headerSecretRefsForSave(
                previous,
                "https://provider-b.invalid/v1",
                previous.headerSecretRefs,
            ),
        )
    }

    @Test
    fun destinationChangeKeepsNewlyConfirmedHeaderRefs() {
        val confirmed = mapOf("X-Org-Token" to "aux-b")
        assertEquals(
            confirmed,
            ProviderDestinationBinding.headerSecretRefsForSave(
                previous,
                "https://provider-b.invalid/v1",
                confirmed,
            ),
        )
    }
}
