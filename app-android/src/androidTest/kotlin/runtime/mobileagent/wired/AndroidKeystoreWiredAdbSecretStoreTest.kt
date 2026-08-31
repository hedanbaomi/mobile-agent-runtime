// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.wired

import android.content.Context
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import runtime.mobileagent.bridge.BridgeProtocol

/** Device coverage for the production Android Keystore-backed trust store. */
class AndroidKeystoreWiredAdbSecretStoreTest {
    @Test
    fun boundSecretRoundTripsWithAeadBindingAndSurvivesStoreRecreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(
            "runtime.mobileagent.wired.test.${UUID.randomUUID()}",
            Context.MODE_PRIVATE,
        )
        val store = AndroidKeystoreWiredAdbSecretStore(preferences)
        val secretRef = "wired-adb:test-${UUID.randomUUID()}"
        val secret = ByteArray(32) { (it + 1).toByte() }
        val binding = WiredAdbSecretBinding(
            appInstanceId = "app-instance",
            desktopId = "desktop-1",
            serialFingerprint = "11".repeat(32),
            protocolVersion = BridgeProtocol.VERSION,
            transcriptHash = "22".repeat(32),
        )

        try {
            store.putBound(secretRef, secret, binding)
            assertArrayEquals(secret, store.resolveBound(secretRef, binding))
            assertNull(
                store.resolveBound(
                    secretRef,
                    binding.copy(desktopId = "desktop-other"),
                ),
            )

            val persistedValue = preferences.all.values.filterIsInstance<String>().singleOrNull()
            assertNotNull(persistedValue)
            assertTrue(persistedValue!!.isNotEmpty())
            assertNotEquals(Base64.encodeToString(secret, Base64.NO_WRAP), persistedValue)

            val recreatedStore = AndroidKeystoreWiredAdbSecretStore(preferences)
            assertArrayEquals(secret, recreatedStore.resolveBound(secretRef, binding))
        } finally {
            store.remove(secretRef)
            preferences.edit().clear().commit()
            secret.fill(0)
        }
    }

    @Test
    fun unboundOperationsAreRejectedAndRemovalIsDurable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(
            "runtime.mobileagent.wired.test.${UUID.randomUUID()}",
            Context.MODE_PRIVATE,
        )
        val store = AndroidKeystoreWiredAdbSecretStore(preferences)
        val secretRef = "wired-adb:test-${UUID.randomUUID()}"
        val binding = WiredAdbSecretBinding(
            appInstanceId = "app-instance",
            desktopId = "desktop-1",
            serialFingerprint = "33".repeat(32),
            protocolVersion = BridgeProtocol.VERSION,
            transcriptHash = "44".repeat(32),
        )
        val secret = ByteArray(32) { 7 }

        try {
            assertIllegalArgument { store.put(secretRef, secret) }
            assertIllegalArgument { store.resolve(secretRef) }
            store.putBound(secretRef, secret, binding)
            assertArrayEquals(secret, store.resolveBound(secretRef, binding))
            store.remove(secretRef)
            assertNull(store.resolveBound(secretRef, binding))
            assertEquals(0, preferences.all.size)
        } finally {
            store.remove(secretRef)
            preferences.edit().clear().commit()
            secret.fill(0)
        }
    }

    @Test
    fun wiredMetadataUsesOneCanonicalAppIdentityTrustAndIntentStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(
            "runtime.mobileagent.wired.metadata.test.${UUID.randomUUID()}",
            Context.MODE_PRIVATE,
        )
        val store = AndroidWiredAdbMetadataStore(preferences)
        val record = WiredAdbTrustRecord(
            desktopId = "desktop-1",
            appInstanceId = store.loadOrCreateAppInstanceId(),
            serialFingerprint = "11".repeat(32),
            protocolVersion = BridgeProtocol.VERSION,
            secretRef = "wired-adb:test",
            transcriptHash = "22".repeat(32),
        )
        try {
            assertEquals(64, record.appInstanceId.length)
            assertEquals(record.appInstanceId, store.loadOrCreateAppInstanceId())
            store.intentStore.save(WiredAdbUserIntent.ENABLED)
            store.trustStore.save(record)

            val recreated = AndroidWiredAdbMetadataStore(preferences)
            assertEquals(record.appInstanceId, recreated.loadOrCreateAppInstanceId())
            assertEquals(WiredAdbUserIntent.ENABLED, recreated.intentStore.load())
            assertEquals(record, recreated.trustStore.load())

            recreated.trustStore.clear()
            assertNull(store.trustStore.load())
            assertEquals(WiredAdbUserIntent.ENABLED, store.intentStore.load())
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun malformedWiredTrustMetadataFailsClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(
            "runtime.mobileagent.wired.metadata.test.${UUID.randomUUID()}",
            Context.MODE_PRIVATE,
        )
        try {
            preferences.edit()
                .putString("wired_app_instance_id_v1", "0".repeat(64))
                .putString("wired_trust_record_v1", "1.invalid")
                .commit()
            assertIllegalState { AndroidWiredAdbMetadataStore(preferences).trustStore.load() }
        } finally {
            preferences.edit().clear().commit()
        }
    }

    private suspend fun assertIllegalArgument(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("expected unbound secret operation to fail")
        } catch (_: IllegalArgumentException) {
            // Expected: only the bound API is permitted for production trust.
        }
    }

    private fun assertIllegalState(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected malformed metadata to fail closed")
        } catch (_: IllegalStateException) {
            // Expected: malformed durable metadata must not become UNPAIRED.
        } catch (_: IllegalArgumentException) {
            // Validation errors are also fail-closed for the persistence seam.
        }
    }
}
