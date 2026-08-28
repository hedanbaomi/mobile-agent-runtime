// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ProviderProfile

class MigrationsTest {
    @Test
    fun schemaAndProviderRoundTrip() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = ProfileRepository(db)
        repo.upsertProvider(
            ProviderProfile(
                id = "p1",
                name = "Local",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "ref-1",
                revision = 1,
            ),
        )
        assertEquals("Local", repo.listProviders().single().name)
        assertEquals(7, db.query("SELECT version FROM schema_version").single().long("version"))
    }

    @Test
    fun chatBindingUsesTheModelProviderNotNameAndModelSort() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = ProfileRepository(db)
        repo.upsertProvider(
            ProviderProfile(
                id = "alpha",
                name = "Alpha endpoint",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://alpha.example.invalid/v1",
                secretRef = "ref-alpha",
                revision = 1,
            ),
        )
        repo.upsertProvider(
            ProviderProfile(
                id = "zulu",
                name = "Zulu endpoint",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://zulu.example.invalid/v1",
                secretRef = "ref-zulu",
                revision = 1,
            ),
        )
        repo.upsertModel(
            runtime.mobileagent.domain.ModelProfile(
                id = "m-z",
                providerId = "alpha",
                role = runtime.mobileagent.domain.ModelRole.CHAT,
                modelId = "zeta-chat",
                capabilities = setOf("stream"),
                contextLimit = 8_000,
                outputLimit = 1_024,
                revision = 1,
            ),
        )
        repo.upsertModel(
            runtime.mobileagent.domain.ModelProfile(
                id = "m-a",
                providerId = "zulu",
                role = runtime.mobileagent.domain.ModelRole.CHAT,
                modelId = "alpha-chat",
                capabilities = setOf("stream"),
                contextLimit = 8_000,
                outputLimit = 1_024,
                revision = 1,
            ),
        )
        val (provider, model) = repo.chatBinding()!!
        assertEquals(provider.id, model.providerId)
        assertEquals("alpha", provider.id)
        assertEquals("zeta-chat", model.modelId)
    }
}
