// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.security

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.util.Locale

/**
 * Supplies the stable, app-scoped identity used when binding encrypted
 * runtime state.  The identity is not a secret and must never be used as a
 * substitute for an authority grant.
 */
fun interface AppInstanceIdStore {
    fun loadOrCreateAppInstanceId(): String
}

/**
 * SharedPreferences-backed app identity for the Android application scope.
 *
 * This store intentionally fails closed when an existing value is malformed;
 * silently replacing it would make previously encrypted bindings
 * unrecoverable while presenting them as new state.
 */
class AndroidAppInstanceIdStore(
    private val preferences: SharedPreferences,
    private val random: SecureRandom = SecureRandom(),
) : AppInstanceIdStore {
    @Synchronized
    override fun loadOrCreateAppInstanceId(): String {
        val existing = preferences.getString(APP_INSTANCE_ID_KEY, null)
        if (existing != null) {
            validateAppInstanceId(existing)
            return existing
        }

        val bytes = ByteArray(APP_INSTANCE_ID_BYTES)
        return try {
            random.nextBytes(bytes)
            val generated = bytes.toHex()
            check(
                preferences.edit()
                    .putString(APP_INSTANCE_ID_KEY, generated)
                    .commit(),
            ) { "App identity persistence failed" }
            generated
        } finally {
            bytes.fill(0)
        }
    }

    private fun validateAppInstanceId(value: String) {
        check(value.length == APP_INSTANCE_ID_HEX_CHARS) { "App identity is invalid" }
        check(value == value.lowercase(Locale.ROOT)) { "App identity is not canonical" }
        check(value.all { it in HEX_DIGITS }) { "App identity is invalid" }
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) {
            append(HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f])
            append(HEX_DIGITS[byte.toInt() and 0x0f])
        }
    }

    companion object {
        private const val APP_INSTANCE_ID_BYTES = 32
        private const val APP_INSTANCE_ID_HEX_CHARS = APP_INSTANCE_ID_BYTES * 2
        private const val APP_INSTANCE_ID_KEY = "app_instance_id_v1"
        private const val HEX_DIGITS = "0123456789abcdef"
        internal const val PREFERENCES_NAME = "runtime.mobileagent.identity.v1"

        @JvmStatic
        fun create(context: Context): AndroidAppInstanceIdStore {
            val appContext = context.applicationContext ?: context
            return AndroidAppInstanceIdStore(
                appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )
        }
    }
}

/** Production construction seam for the one canonical app identity store. */
object AndroidAppInstanceIdStoreFactory {
    @JvmStatic
    fun create(context: Context): AppInstanceIdStore = AndroidAppInstanceIdStore.create(context)
}
