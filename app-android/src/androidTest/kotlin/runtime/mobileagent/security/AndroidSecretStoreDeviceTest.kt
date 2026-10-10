// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.storage.BundledSqliteConnection

/** Real Keystore and SQLite; every alias and database belongs only to this fixture. */
@RunWith(AndroidJUnit4::class)
class AndroidSecretStoreDeviceTest {
    @Test
    fun tamperedCiphertextFailsWithoutReplacingTheSavedCredential() = withFixture { store, db, _, ref ->
        val expected = "synthetic-secret-store-credential".toCharArray()
        val input = expected.copyOf()
        store.put(ref, input)
        assertTrue(input.all { it == '\u0000' })
        val restored = store.resolveForHost(ref)
        try {
            assertArrayEquals(expected, restored)
        } finally {
            restored.fill('\u0000')
            expected.fill('\u0000')
        }
        val ciphertext = store.inventory().ciphertext(ref)!!
        val tampered = ciphertext.copyOf()
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
        db.execute("UPDATE secrets SET ciphertext = ? WHERE ref = ?", listOf(tampered, ref))
        assertNotNull("Tampered AEAD bytes must never resolve to plaintext", runCatching { store.resolveForHost(ref) }.exceptionOrNull())
        assertArrayEquals(tampered, store.inventory().ciphertext(ref))
    }

    @Test
    fun lostKeystoreKeyCannotResolvePreviouslySavedCiphertext() = withFixture { store, db, alias, ref ->
        store.put(ref, "synthetic-key-loss-credential".toCharArray())
        val original = store.resolveForHost(ref)
        try {
            assertArrayEquals("synthetic-key-loss-credential".toCharArray(), original)
        } finally {
            original.fill('\u0000')
        }
        val ciphertext = store.inventory().ciphertext(ref)!!
        val keys = keyStore()
        assertTrue(keys.containsAlias(alias))
        keys.deleteEntry(alias)
        assertFalse(keyStore().containsAlias(alias))
        val reopened = AndroidSecretStore(InstrumentationRegistry.getInstrumentation().targetContext, db, alias)
        assertNotNull("Key loss must not return successful plaintext", runCatching { reopened.resolveForHost(ref) }.exceptionOrNull())
        assertArrayEquals(ciphertext, reopened.inventory().ciphertext(ref))
    }

    @Test
    fun missingCredentialReferenceReportsSecretUnavailableWithoutCreatingAKey() = withFixture { store, _, alias, ref ->
        val failure = runCatching { store.resolveForHost(ref) }.exceptionOrNull()
        assertTrue(failure is AppException)
        assertEquals(ErrorCode.SECRET_UNAVAILABLE, (failure as AppException).error.code)
        assertFalse(keyStore().containsAlias(alias))
    }

    private fun withFixture(body: suspend (AndroidSecretStore, BundledSqliteConnection, String, String) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val alias = "runtime.mobileagent.test.secret-store.$id"
        val cache = context.cacheDir.canonicalFile
        val root = File(cache, "secret-store-device-$id").canonicalFile
        check(root.parentFile == cache && root.mkdir())
        try {
            BundledSqliteConnection(File(root, "fixture.db").absolutePath).use { db ->
                Migrations.apply(db)
                body(AndroidSecretStore(context, db, alias), db, alias, "fixture-secret-$id")
            }
        } finally {
            // Never enumerate or delete the production runtime.mobileagent.secrets alias.
            keyStore().let { keys -> if (keys.containsAlias(alias)) keys.deleteEntry(alias) }
            check(root.parentFile == cache && root.name == "secret-store-device-$id")
            assertTrue("Closed fixture database must be removable", root.deleteRecursively())
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
