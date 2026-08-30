// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.SecretStatus

class SecretInventoryTest {
    @Test
    fun unreferencedSecretIsRetiredThenDeleted() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val inventory = SecretInventory(db)
        val profiles = ProfileRepository(db)
        profiles.upsertProvider(
            ProviderProfile(
                id = "p1",
                name = "Local",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "ref-1",
                revision = 1,
            ),
        )
        inventory.putActive("ref-1", byteArrayOf(1, 2, 3))
        assertEquals(SecretStatus.ACTIVE, inventory.status("ref-1"))
        assertNotNull(inventory.ciphertext("ref-1"))
        assertEquals(true, profiles.deleteProvider("p1"))
        assertEquals(SecretStatus.DELETED, inventory.status("ref-1"))
        assertNull(inventory.ciphertext("ref-1"))
    }

    @Test
    fun primaryAndHeaderReferencesKeepSharedSecretsAlive() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val inventory = SecretInventory(db)
            val profiles = ProfileRepository(db)
            profiles.createProvider(
                ProviderProfile(
                    id = "p1",
                    name = "One",
                    apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                    baseUrl = "https://one.example.invalid/v1",
                    headerSecretRefs = mapOf("X-Shared" to "ref-shared-header"),
                    secretRef = "ref-primary-one",
                    revision = 1,
                ),
            )
            profiles.createProvider(
                ProviderProfile(
                    id = "p2",
                    name = "Two",
                    apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                    baseUrl = "https://two.example.invalid/v1",
                    headerSecretRefs = mapOf("X-Shared" to "ref-shared-header"),
                    secretRef = "ref-primary-two",
                    revision = 1,
                ),
            )
            listOf("ref-primary-one", "ref-primary-two", "ref-shared-header").forEach {
                inventory.putActive(it, byteArrayOf(1, 2, 3))
            }

            assertTrue("ref-shared-header" in inventory.referencedSecretRefs())
            assertTrue(profiles.deleteProvider("p1"))
            assertEquals(SecretStatus.DELETED, inventory.status("ref-primary-one"))
            assertEquals(SecretStatus.ACTIVE, inventory.status("ref-shared-header"))
            assertEquals(SecretStatus.ACTIVE, inventory.status("ref-primary-two"))

            assertTrue(profiles.deleteProvider("p2"))
            assertEquals(SecretStatus.DELETED, inventory.status("ref-shared-header"))
            assertEquals(SecretStatus.DELETED, inventory.status("ref-primary-two"))
        }
    }

    @Test
    fun updatingProviderRetiresOnlyReferencesNoLongerUsed() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val inventory = SecretInventory(db)
            val profiles = ProfileRepository(db)
            profiles.createProvider(
                ProviderProfile(
                    id = "p1",
                    name = "One",
                    apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                    baseUrl = "https://one.example.invalid/v1",
                    headerSecretRefs = mapOf("X-Old" to "ref-old-header"),
                    secretRef = "ref-old-primary",
                    revision = 1,
                ),
            )
            listOf("ref-old-primary", "ref-old-header").forEach { inventory.putActive(it, byteArrayOf(4)) }

            profiles.updateProvider(
                profiles.getProvider("p1")!!.copy(
                    headerSecretRefs = mapOf("X-New" to "ref-new-header"),
                    secretRef = "ref-new-primary",
                    revision = 2,
                ),
            )
            assertEquals(SecretStatus.DELETED, inventory.status("ref-old-primary"))
            assertEquals(SecretStatus.DELETED, inventory.status("ref-old-header"))
            assertFalse("ref-old-primary" in inventory.referencedSecretRefs())
            assertFalse("ref-old-header" in inventory.referencedSecretRefs())
        }
    }

    @Test
    fun immutableSnapshotReferencesKeepSecretsAliveAfterProfileChanges() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val inventory = SecretInventory(db)
            inventory.putActive("ref-snapshot-primary", byteArrayOf(5))
            inventory.putActive("ref-snapshot-header", byteArrayOf(6))
            db.execute(
                "INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at,provider_id,chat_model_revision,vision_model_id,vision_model_revision,embedding_model_id,embedding_model_revision,reranker_model_id,reranker_model_revision,parameter_overrides_json,context_policy_json,permission_settings_json,binding_manifest_json,expanded_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                listOf(
                    "snapshot.secret", 11, "agent.secret", "prompt.secret", "model.secret", 1, "[]", "[]",
                    "2026-08-29T00:00:00Z", "deleted-provider", 1, null, null, null, null, null, null,
                    "{}", "{}", "{}",
                    "{\"snapshotId\":\"snapshot.secret\",\"provider\":{\"secretRef\":\"ref-snapshot-primary\",\"headerSecretRefs\":{\"X-Snapshot\":\"ref-snapshot-header\"}}}",
                    "{}",
                ),
            )

            assertEquals(
                setOf("ref-snapshot-primary", "ref-snapshot-header"),
                inventory.referencedSecretRefs(),
            )
            inventory.collectOrphans()
            assertEquals(SecretStatus.ACTIVE, inventory.status("ref-snapshot-primary"))
            assertEquals(SecretStatus.ACTIVE, inventory.status("ref-snapshot-header"))

            db.execute("UPDATE agent_snapshots SET binding_manifest_json = ? WHERE id = ?", listOf("{}", "snapshot.secret"))
            inventory.collectOrphans()
            assertEquals(SecretStatus.ORPHANED, inventory.status("ref-snapshot-primary"))
            assertEquals(SecretStatus.ORPHANED, inventory.status("ref-snapshot-header"))
            inventory.collectOrphans()
            assertEquals(SecretStatus.DELETED, inventory.status("ref-snapshot-primary"))
            assertEquals(SecretStatus.DELETED, inventory.status("ref-snapshot-header"))
        }
    }

    @Test
    fun malformedSnapshotReferenceFailsClosedWithoutGarbageCollecting() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val inventory = SecretInventory(db)
            inventory.putActive("ref-malformed-snapshot", byteArrayOf(7))
            db.execute(
                "INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at,provider_id,chat_model_revision,vision_model_id,vision_model_revision,embedding_model_id,embedding_model_revision,reranker_model_id,reranker_model_revision,parameter_overrides_json,context_policy_json,permission_settings_json,binding_manifest_json,expanded_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                listOf(
                    "snapshot.bad", 11, "agent.bad", "prompt.bad", "model.bad", 1, "[]", "[]",
                    "2026-08-29T00:00:00Z", "provider.bad", 1, null, null, null, null, null, null,
                    "{}", "{}", "{}", "{not-json", "{}",
                ),
            )

            assertThrows(AppException::class.java) { inventory.collectOrphans() }
            assertEquals(SecretStatus.ACTIVE, inventory.status("ref-malformed-snapshot"))
        }
    }

    @Test
    fun configuredWebSearchKeyIsAFirstClassSecretReference() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val settings = SettingsRepository(db)
            val inventory = SecretInventory(db)
            inventory.putActive("search:brave", byteArrayOf(8, 9))

            settings.setWebSearch(secretRef = "search:brave", enabled = true)
            assertEquals("search:brave", settings.webSearchSecretRef())
            assertTrue(settings.webSearchEnabled())
            assertTrue("search:brave" in inventory.referencedSecretRefs())
            inventory.collectOrphans()
            assertEquals(SecretStatus.ACTIVE, inventory.status("search:brave"))

            settings.setWebSearch(secretRef = null, enabled = false)
            inventory.collectOrphans()
            assertEquals(SecretStatus.ORPHANED, inventory.status("search:brave"))
        }
    }
}
