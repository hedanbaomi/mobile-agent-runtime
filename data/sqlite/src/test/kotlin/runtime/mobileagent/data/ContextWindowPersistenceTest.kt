// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ContextLimitMode
import runtime.mobileagent.domain.ContextLimitSource
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.contextWindowTarget

class ContextWindowPersistenceTest {
    @Test fun delayedCatalogResultCannotOverwriteANewerUserEdit() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val profiles = ProfileRepository(db)
            val provider = ProviderProfile("siliconflow", "SiliconFlow", ApiFormat.OPENAI_COMPATIBLE,
                "https://api.siliconflow.cn/v1", revision = 1)
            profiles.createProvider(provider)
            val model = ModelProfile("qwen", provider.id, ModelRole.CHAT, "Qwen/Qwen3.8-27B",
                emptySet(), contextLimit = 0, outputLimit = 4096, revision = 1,
                contextLimitMode = ContextLimitMode.AUTO)
            profiles.createModel(model)
            val target = contextWindowTarget(provider.id, provider.baseUrl, model.modelId)

            profiles.updateModel(model.copy(contextLimitMode = ContextLimitMode.MANUAL,
                contextLimit = 32768, revision = 2))
            assertNull(profiles.recordContextWindow(model.id, 262144, ContextLimitSource.PROVIDER_METADATA,
                target, "2026-09-25T00:00:00Z", expectedRevision = 1))
            assertNull(profiles.recordContextWindow(model.id, 262144, ContextLimitSource.PROVIDER_METADATA,
                target, "2026-09-25T00:00:00Z", expectedRevision = 2))
            assertEquals(32768, profiles.getModel(model.id)?.contextLimit)
            assertEquals(2, profiles.getModel(model.id)?.revision)
        }
    }

    @Test fun matchingAutoRevisionRecordsVerifiedWindowOnce() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val profiles = ProfileRepository(db)
            val provider = ProviderProfile("siliconflow", "SiliconFlow", ApiFormat.OPENAI_COMPATIBLE,
                "https://api.siliconflow.cn/v1", revision = 1)
            profiles.createProvider(provider)
            val model = ModelProfile("qwen", provider.id, ModelRole.CHAT, "Qwen/Qwen3.8-27B",
                emptySet(), contextLimit = 0, outputLimit = 4096, revision = 1,
                contextLimitMode = ContextLimitMode.AUTO)
            profiles.createModel(model)
            val target = contextWindowTarget(provider.id, provider.baseUrl, model.modelId)
            assertNull(profiles.recordContextWindow(model.id, 8192, ContextLimitSource.PROVIDER_METADATA,
                "$target-other", "2026-09-25T00:00:00Z", expectedRevision = 1))
            assertNotNull(profiles.recordContextWindow(model.id, 262144, ContextLimitSource.PROVIDER_METADATA,
                target, "2026-09-25T00:00:00Z", expectedRevision = 1))
            assertNull(profiles.recordContextWindow(model.id, 8192, ContextLimitSource.PROVIDER_METADATA,
                target, "2026-09-25T00:00:00Z", expectedRevision = 2))
            assertEquals(262144, profiles.getModel(model.id)?.contextWindowValue)
            assertEquals(2, profiles.getModel(model.id)?.revision)
        }
    }
}
