// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.resident

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private AES-GCM ciphertext; its wrapping key never leaves Android Keystore. */
internal class ResidentAdbCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("resident-adb-v1", Context.MODE_PRIVATE)
    private val aad = "runtime.mobileagent.resident.credential.v1".toByteArray()
    private val alias = "runtime.mobileagent.resident.wrap.v1"

    data class Credential(val generation: String, val secret: ByteArray) : AutoCloseable {
        override fun close() { secret.fill(0) }
    }

    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }

    fun load(): Credential? {
        val generation = preferences.getString("generation", null) ?: return null
        require(generation.length == 36)
        val encoded = preferences.getString("ciphertext", null) ?: error("credential unavailable")
        require(encoded.length < 256)
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        try {
            require(bytes.size in 29..128)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false) ?: error("wrapping key unavailable"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(aad + generation.toByteArray(Charsets.US_ASCII))
            val secret = cipher.doFinal(bytes, 12, bytes.size - 12)
            require(secret.size == ResidentAdbProtocol.SECRET_BYTES)
            return Credential(generation, secret)
        } finally { bytes.fill(0) }
    }

    fun save(generation: String, secret: ByteArray) {
        require(generation.length == 36 && secret.size == ResidentAdbProtocol.SECRET_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(aad + generation.toByteArray(Charsets.US_ASCII))
        val encrypted = cipher.iv + cipher.doFinal(secret)
        try {
            check(preferences.edit().putString("generation", generation)
                .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
        } finally { encrypted.fill(0) }
    }

    fun clear() { check(preferences.edit().remove("generation").remove("ciphertext").commit()) }
    fun enabled(): Boolean = preferences.getBoolean("enabled", false)
    fun setEnabled(enabled: Boolean) { check(preferences.edit().putBoolean("enabled", enabled).commit()) }
}
