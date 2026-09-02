// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.security

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import runtime.mobileagent.domain.Authority

/** Device coverage for the production Android Keystore locator envelope. */
class PrivilegedWorkspaceBindingCipherTest {
    @Test
    fun roundTripUsesPersistentKeyAndDoesNotStoreLocatorPlaintext() {
        val alias = testAlias()
        val cipher = PrivilegedWorkspaceBindingCipher(alias)
        val binding = binding()
        val locator = "storage/emulated/0/project/.runtime-locator".toByteArray(StandardCharsets.UTF_8)
        try {
            val sealed = cipher.seal(locator, binding)
            assertTrue(sealed is PrivilegedWorkspaceBindingSealResult.Success)
            val envelope = (sealed as PrivilegedWorkspaceBindingSealResult.Success).envelope
            val encoded = envelope.toByteArray()
            assertFalse(String(encoded, StandardCharsets.ISO_8859_1).contains(String(locator, StandardCharsets.ISO_8859_1)))

            val opened = cipher.open(encoded, binding)
            assertTrue(opened is PrivilegedWorkspaceBindingOpenResult.Success)
            assertArrayEquals(locator, (opened as PrivilegedWorkspaceBindingOpenResult.Success).locator)
            (opened as PrivilegedWorkspaceBindingOpenResult.Success).locator.fill(0)

            val recreated = PrivilegedWorkspaceBindingCipher(alias)
            val reopened = recreated.open(encoded, binding)
            assertTrue(reopened is PrivilegedWorkspaceBindingOpenResult.Success)
            assertArrayEquals(locator, (reopened as PrivilegedWorkspaceBindingOpenResult.Success).locator)
            (reopened as PrivilegedWorkspaceBindingOpenResult.Success).locator.fill(0)
        } finally {
            locator.fill(0)
            deleteKey(alias)
        }
    }

    @Test
    fun everySealGetsANewNonceAndEnvelopeRoundTripsThroughDecoder() {
        val alias = testAlias()
        val cipher = PrivilegedWorkspaceBindingCipher(alias)
        val binding = binding()
        val locator = "locator-value".toByteArray(StandardCharsets.UTF_8)
        try {
            val first = (cipher.seal(locator, binding) as PrivilegedWorkspaceBindingSealResult.Success).envelope
            val second = (cipher.seal(locator, binding) as PrivilegedWorkspaceBindingSealResult.Success).envelope
            assertFalse(first.nonce.contentEquals(second.nonce))
            assertFalse(first.toByteArray().contentEquals(second.toByteArray()))
            val decoded = PrivilegedWorkspaceBindingEnvelope.decode(first.toByteArray())
            assertTrue(decoded is PrivilegedWorkspaceBindingEnvelopeDecodeResult.Success)

            val openedFromSeparateColumns = cipher.open(first.encryptedLocator, first.locatorNonce, binding)
            assertTrue(openedFromSeparateColumns is PrivilegedWorkspaceBindingOpenResult.Success)
            (openedFromSeparateColumns as PrivilegedWorkspaceBindingOpenResult.Success).locator.fill(0)
        } finally {
            locator.fill(0)
            deleteKey(alias)
        }
    }

    @Test
    fun tamperAndAadMismatchAreTypedUnrecoverableFailures() {
        val alias = testAlias()
        val cipher = PrivilegedWorkspaceBindingCipher(alias)
        val binding = binding()
        val locator = "opaque-locator".toByteArray(StandardCharsets.UTF_8)
        try {
            val envelope = (cipher.seal(locator, binding) as PrivilegedWorkspaceBindingSealResult.Success).envelope
            val tampered = envelope.toByteArray()
            tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 1).toByte()
            val tamperedResult = cipher.open(tampered, binding)
            val tamperedFailure = tamperedResult as? PrivilegedWorkspaceBindingOpenResult.Failure
                ?: throw AssertionError("expected tamper failure")
            assertEquals(
                PrivilegedWorkspaceBindingErrorCode.AUTHENTICATION_FAILED,
                tamperedFailure.error.code,
            )
            assertTrue(tamperedFailure.error.unrecoverable)

            val aadMismatch = cipher.open(envelope, binding.copy(workspaceId = "other-workspace"))
            val aadFailure = aadMismatch as? PrivilegedWorkspaceBindingOpenResult.Failure
                ?: throw AssertionError("expected AAD mismatch failure")
            assertEquals(
                PrivilegedWorkspaceBindingErrorCode.AAD_MISMATCH,
                aadFailure.error.code,
            )
            assertTrue(aadFailure.error.unrecoverable)
        } finally {
            locator.fill(0)
            deleteKey(alias)
        }
    }

    @Test
    fun missingKeyAndMalformedEnvelopeFailClosedWithoutRawCryptoException() {
        val alias = testAlias()
        val cipher = PrivilegedWorkspaceBindingCipher(alias)
        val binding = binding()
        val locator = "opaque-locator".toByteArray(StandardCharsets.UTF_8)
        try {
            val envelope = (cipher.seal(locator, binding) as PrivilegedWorkspaceBindingSealResult.Success).envelope
            deleteKey(alias)
            val missing = cipher.open(envelope, binding)
            val missingFailure = missing as? PrivilegedWorkspaceBindingOpenResult.Failure
                ?: throw AssertionError("expected missing-key failure")
            assertEquals(
                PrivilegedWorkspaceBindingErrorCode.KEY_UNAVAILABLE,
                missingFailure.error.code,
            )
            assertTrue(missingFailure.error.unrecoverable)

            val malformed = PrivilegedWorkspaceBindingEnvelope.decode(byteArrayOf(1, 2, 3))
            assertTrue(malformed is PrivilegedWorkspaceBindingEnvelopeDecodeResult.Failure)
            assertEquals(
                PrivilegedWorkspaceBindingErrorCode.ENVELOPE_INVALID,
                (malformed as PrivilegedWorkspaceBindingEnvelopeDecodeResult.Failure).error.code,
            )
        } finally {
            locator.fill(0)
            deleteKey(alias)
        }
    }

    @Test
    fun appInstanceIdSurvivesStoreRecreationAndInvalidValueFailsClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(
            "runtime.mobileagent.identity.test.${UUID.randomUUID()}",
            Context.MODE_PRIVATE,
        )
        try {
            val first = AndroidAppInstanceIdStore(preferences)
            val id = first.loadOrCreateAppInstanceId()
            assertEquals(64, id.length)
            assertEquals(id, AndroidAppInstanceIdStore(preferences).loadOrCreateAppInstanceId())

            preferences.edit().putString("app_instance_id_v1", "bad").commit()
            try {
                AndroidAppInstanceIdStore(preferences).loadOrCreateAppInstanceId()
                throw AssertionError("expected invalid app identity to fail closed")
            } catch (_: IllegalStateException) {
                // Expected: identity replacement would strand encrypted bindings.
            }
        } finally {
            preferences.edit().clear().commit()
        }
    }

    private fun binding() = PrivilegedWorkspaceBindingAad(
        appInstanceId = "a".repeat(64),
        workspaceId = "workspace-test",
        authority = Authority.SHIZUKU,
        locatorVersion = 1,
    )

    private fun testAlias(): String = "runtime.mobileagent.test.pwb.${UUID.randomUUID()}"

    private fun deleteKey(alias: String) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }
}
