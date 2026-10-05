// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.SecretStatus
import runtime.mobileagent.domain.WebSearchProvider

class WebSearchSettingsTest {
    @Test fun legacyBraveAndSeparateProviderKeysSurviveSwitchesAndGarbageCollection() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            db.execute("INSERT INTO app_prefs(key,value) VALUES(?,?)", listOf("settings.webSearch.secretRef", "search:legacy"))
            db.execute("INSERT INTO app_prefs(key,value) VALUES(?,?)", listOf("settings.webSearch.enabled", "1"))
            val settings = SettingsRepository(db)
            val inventory = SecretInventory(db)
            inventory.putActive("search:legacy", byteArrayOf(1))
            assertEquals(WebSearchProvider.BRAVE, settings.webSearchProvider())
            assertTrue(settings.webSearchEnabled())
            for (provider in listOf(WebSearchProvider.TAVILY, WebSearchProvider.EXA)) {
                settings.selectWebSearchProvider(provider)
                assertFalse(settings.webSearchEnabled())
                assertNull(settings.webSearchSecretRef())
                val ref = "search:${provider.id}:fixture"
                inventory.putActive(ref, byteArrayOf(2))
                settings.setWebSearch(ref, true)
                inventory.collectOrphans()
                assertEquals(SecretStatus.ACTIVE, inventory.status("search:legacy"))
            }
            settings.selectWebSearchProvider(WebSearchProvider.TAVILY)
            assertEquals("search:tavily:fixture", SettingsRepository(db).webSearchSecretRef())
            settings.setWebSearch(null, false)
            inventory.retireIfUnreferenced("search:tavily:fixture")
            assertEquals(SecretStatus.DELETED, inventory.status("search:tavily:fixture"))
            assertEquals(SecretStatus.ACTIVE, inventory.status("search:exa:fixture"))
            settings.selectWebSearchProvider(WebSearchProvider.BRAVE)
            assertEquals("search:legacy", settings.webSearchSecretRef())
            assertTrue(settings.webSearchEnabled())
        }
    }

    @Test fun revocationRevisionChangesEvenWhenProviderIsRestored() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val settings = SettingsRepository(db)
            settings.setWebSearch("search:brave:fixture", true)
            val revision = settings.webSearchRevision()
            settings.selectWebSearchProvider(WebSearchProvider.EXA)
            settings.selectWebSearchProvider(WebSearchProvider.BRAVE)
            assertTrue(settings.webSearchRevision() > revision)
            settings.setWebSearch("search:brave:fixture", false)
            assertFalse(settings.webSearchEnabled())
            settings.setWebSearch("search:brave:fixture", true)
            assertTrue(settings.webSearchRevision() > revision + 2)
        }
    }

    @Test fun unknownPersistedProviderDoesNotUseBraveCredentials() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val settings = SettingsRepository(db)
            settings.setWebSearch("search:brave:fixture", true)
            db.execute("INSERT INTO app_prefs(key,value) VALUES(?,?)", listOf("settings.webSearch.provider", "unknown"))
            assertNull(settings.webSearchProvider())
            assertNull(settings.webSearchSecretRef())
            assertFalse(settings.webSearchEnabled())
        }
    }
}
