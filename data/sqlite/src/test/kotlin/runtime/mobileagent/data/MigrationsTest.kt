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
        assertEquals(1, db.query("SELECT version FROM schema_version").single().long("version"))
    }
}
