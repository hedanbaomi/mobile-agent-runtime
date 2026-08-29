// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
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
}
