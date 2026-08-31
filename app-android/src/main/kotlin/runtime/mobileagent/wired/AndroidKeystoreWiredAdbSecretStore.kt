// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.wired

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import runtime.mobileagent.bridge.BridgeEncoding
import runtime.mobileagent.bridge.BridgeProtocol

/**
 * Android Keystore-backed persistence for one paired desktop trust.
 *
 * The trust bytes are never put in the Runtime database or a plaintext file.
 * Android Keystore owns an independent AES key for every secretRef; the
 * app-private preferences entry contains only the AES-GCM ciphertext and IV.
 * The ciphertext is authenticated with an unambiguous binding containing the
 * app, desktop, challenge-delivered serial fingerprint, protocol version,
 * and pairing transcript hash. A missing or mismatched binding is
 * indistinguishable from an unavailable secret to callers.
 */
class AndroidKeystoreWiredAdbSecretStore(
    private val preferences: SharedPreferences,
) : WiredAdbBoundSecretStore {
    private val keyStoreProvider: () -> KeyStore = ::openKeyStore

    override suspend fun put(secretRef: String, secret: ByteArray) {
        throw IllegalArgumentException("wired trust requires a complete binding")
    }

    override suspend fun resolve(secretRef: String): ByteArray? {
        throw IllegalArgumentException("wired trust requires a complete binding")
    }

    override suspend fun putBound(secretRef: String, secret: ByteArray, binding: WiredAdbSecretBinding) {
        withContext(Dispatchers.IO) {
            requireIdentityPart(secretRef, "secretRef")
            require(secret.size == BridgeProtocol.GCM_KEY_BYTES) { "persistent trust has invalid size" }
            val aad = bindingAad(binding)
            var blob = ByteArray(0)
            var ciphertext = ByteArray(0)
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, keyFor(secretRef, create = true))
                cipher.updateAAD(aad)
                ciphertext = cipher.doFinal(secret)
                require(cipher.iv.size == GCM_IV_BYTES) { "unexpected GCM IV size" }
                blob = ByteArray(1 + cipher.iv.size + ciphertext.size)
                blob[0] = FORMAT_VERSION
                cipher.iv.copyInto(blob, 1)
                ciphertext.copyInto(blob, 1 + cipher.iv.size)
                check(
                    preferences.edit()
                        .putString(preferenceKey(secretRef), Base64.encodeToString(blob, Base64.NO_WRAP))
                        .commit(),
                ) { "wired trust persistence failed" }
            } finally {
                aad.fill(0)
                blob.fill(0)
                ciphertext.fill(0)
            }
        }
    }

    override suspend fun resolveBound(secretRef: String, binding: WiredAdbSecretBinding): ByteArray? =
        withContext(Dispatchers.IO) {
            requireIdentityPart(secretRef, "secretRef")
            val encoded = preferences.getString(preferenceKey(secretRef), null) ?: return@withContext null
            val blob = try {
                Base64.decode(encoded, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                return@withContext null
            }
            val aad = bindingAad(binding)
            var iv = ByteArray(0)
            var ciphertext = ByteArray(0)
            try {
                if (blob.size < 1 + GCM_IV_BYTES + BridgeProtocol.GCM_TAG_BYTES || blob[0] != FORMAT_VERSION) {
                    return@withContext null
                }
                iv = blob.copyOfRange(1, 1 + GCM_IV_BYTES)
                ciphertext = blob.copyOfRange(1 + GCM_IV_BYTES, blob.size)
                val key = keyFor(secretRef, create = false) ?: return@withContext null
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(BridgeProtocol.GCM_TAG_BYTES * 8, iv))
                cipher.updateAAD(aad)
                cipher.doFinal(ciphertext).also {
                    if (it.size != BridgeProtocol.GCM_KEY_BYTES) {
                        it.fill(0)
                        return@withContext null
                    }
                }
            } catch (_: GeneralSecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } finally {
                aad.fill(0)
                blob.fill(0)
                iv.fill(0)
                ciphertext.fill(0)
            }
        }

    override suspend fun remove(secretRef: String) {
        withContext(Dispatchers.IO) {
            requireIdentityPart(secretRef, "secretRef")
            check(
                preferences.edit().remove(preferenceKey(secretRef)).commit(),
            ) { "wired trust persistence failed" }
            val keyStore = keyStoreProvider()
            val alias = keyAlias(secretRef)
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    private fun keyFor(secretRef: String, create: Boolean): SecretKey? {
        val keyStore = keyStoreProvider()
        val alias = keyAlias(secretRef)
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null || !create) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(BridgeProtocol.GCM_KEY_BYTES * 8)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun bindingAad(binding: WiredAdbSecretBinding): ByteArray {
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)
        data.write(SECRET_AAD_DOMAIN)
        data.writeInt(binding.protocolVersion)
        writeUtf8(data, binding.appInstanceId)
        writeUtf8(data, binding.desktopId)
        val serial = BridgeEncoding.unhex(binding.serialFingerprint.lowercase(Locale.ROOT))
        val transcript = BridgeEncoding.unhex(binding.transcriptHash.lowercase(Locale.ROOT))
        try {
            data.write(serial)
            data.write(transcript)
        } finally {
            serial.fill(0)
            transcript.fill(0)
        }
        return output.toByteArray()
    }

    private fun writeUtf8(data: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        data.writeInt(bytes.size)
        data.write(bytes)
        bytes.fill(0)
    }

    private fun preferenceKey(secretRef: String): String = "trust." + digestHex(secretRef)

    private fun keyAlias(secretRef: String): String = KEY_ALIAS_PREFIX + digestHex(secretRef)

    private fun digestHex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .let { bytes ->
                try {
                    bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
                } finally {
                    bytes.fill(0)
                }
            }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 1
        private const val GCM_IV_BYTES = 12
        private const val KEY_ALIAS_PREFIX = "runtime.mobileagent.wired.trust."
        private val SECRET_AAD_DOMAIN = "MAR-WIRED-TRUST-AAD-V1".toByteArray(StandardCharsets.US_ASCII)
        internal const val PREFERENCES_NAME = "runtime.mobileagent.wired.adb.trust.v1"

        private fun openKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }
}

/** Production construction seam; no in-memory/debug implementation is used. */
object AndroidKeystoreWiredAdbSecretStoreFactory {
    @JvmStatic
    fun create(context: Context): WiredAdbBoundSecretStore =
        AndroidKeystoreWiredAdbSecretStore(
            context.applicationContext.getSharedPreferences(
                AndroidKeystoreWiredAdbSecretStore.PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            ),
        )
}
