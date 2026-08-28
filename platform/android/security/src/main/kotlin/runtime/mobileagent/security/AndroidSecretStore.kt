// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.provider.SecretStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidSecretStore(
    private val context: Context,
    private val db: SqlConnection,
) : SecretStore {
    override suspend fun resolveForHost(ref: String): CharArray {
        val row = db.query("SELECT ciphertext FROM secrets WHERE ref = ?", listOf(ref)).singleOrNull()
            ?: error("SECRET_UNAVAILABLE")
        val blob = row.columns["ciphertext"] as ByteArray
        return decrypt(blob).toCharArray()
    }

    fun put(ref: String, secret: CharArray) {
        val cipher = encrypt(String(secret).toByteArray(Charsets.UTF_8))
        db.execute("INSERT OR REPLACE INTO secrets(ref, ciphertext, created_at) VALUES (?,?,?)", listOf(ref, cipher, java.time.Instant.now().toString()))
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encoded = cipher.doFinal(plain)
        return iv + encoded
    }

    private fun decrypt(blob: ByteArray): String {
        val iv = blob.copyOfRange(0, 12)
        val encoded = blob.copyOfRange(12, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encoded), Charsets.UTF_8)
    }

    companion object {
        private const val KEY_ALIAS = "runtime.mobileagent.secrets"
    }
}
