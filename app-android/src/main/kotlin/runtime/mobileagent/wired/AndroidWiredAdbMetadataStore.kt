// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.wired

import android.content.Context
import android.content.SharedPreferences
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import runtime.mobileagent.bridge.BridgeEncoding

/**
 * App-private, synchronous metadata store for the wired authority.
 *
 * Trust metadata, user intent, and the dedicated wired app identity live in
 * one SharedPreferences namespace so there is one canonical source of truth.
 * No persistent trust bytes are stored here: those remain in the independent
 * Android Keystore-backed secret store.  A single encoded trust record also
 * avoids observing a partially written set of identity fields after a process
 * interruption.
 */
class AndroidWiredAdbMetadataStore internal constructor(
    private val preferences: SharedPreferences,
    private val random: SecureRandom = SecureRandom(),
) {

    /** Typed views share this exact preferences-backed canonical store. */
    val trustStore: WiredAdbTrustStore = TrustStoreView()
    val intentStore: WiredAdbIntentStore = IntentStoreView()

    /**
     * Return the stable identity for this app installation, creating it only
     * when the foreground/app container first initializes the wired authority.
     * The value is independent from announcement, telemetry, and Runtime IDs.
     */
    @Synchronized
    fun loadOrCreateAppInstanceId(): String {
        val stored = preferences.getString(APP_INSTANCE_ID_KEY, null)
        if (stored != null) {
            requireIdentityPart(stored, "appInstanceId")
            require(stored.length == APP_INSTANCE_ID_HEX_CHARS) {
                "wired appInstanceId is invalid"
            }
            require(stored.all { it in HEX_DIGITS }) { "wired appInstanceId is invalid" }
            require(stored == stored.lowercase(Locale.ROOT)) { "wired appInstanceId is not canonical" }
            return stored
        }

        val bytes = ByteArray(APP_INSTANCE_ID_BYTES)
        return try {
            random.nextBytes(bytes)
            val generated = BridgeEncoding.hex(bytes)
            check(
                preferences.edit()
                    .putString(APP_INSTANCE_ID_KEY, generated)
                    .commit(),
            ) { "wired appInstanceId persistence failed" }
            generated
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Initialize the canonical identity with an explicitly supplied value.
     * This is useful only for dependency-injected test/host construction; the
     * production context factory calls [loadOrCreateAppInstanceId].
     */
    @Synchronized
    internal fun ensureAppInstanceId(appInstanceId: String): String {
        requireIdentityPart(appInstanceId, "appInstanceId")
        require(appInstanceId.length == APP_INSTANCE_ID_HEX_CHARS) {
            "wired appInstanceId is invalid"
        }
        require(appInstanceId.all { it in HEX_DIGITS }) { "wired appInstanceId is invalid" }
        require(appInstanceId == appInstanceId.lowercase(Locale.ROOT)) {
            "wired appInstanceId is not canonical"
        }
        val existing = preferences.getString(APP_INSTANCE_ID_KEY, null)
        if (existing != null) {
            require(existing == existing.lowercase(Locale.ROOT)) { "wired appInstanceId is not canonical" }
            require(existing == appInstanceId) { "wired appInstanceId does not match canonical identity" }
            return existing
        }
        check(
            preferences.edit()
                .putString(APP_INSTANCE_ID_KEY, appInstanceId.lowercase(Locale.ROOT))
                .commit(),
        ) { "wired appInstanceId persistence failed" }
        return appInstanceId.lowercase(Locale.ROOT)
    }

    @Synchronized
    private fun loadTrust(): WiredAdbTrustRecord? {
        val encoded = preferences.getString(TRUST_RECORD_KEY, null) ?: return null
        require(encoded.length in 1..MAX_TRUST_RECORD_CHARS) { "wired trust metadata is invalid" }
        val record = decodeTrustRecord(encoded)
        val appInstanceId = preferences.getString(APP_INSTANCE_ID_KEY, null)
        if (appInstanceId != null) {
            require(record.appInstanceId == appInstanceId) {
                "wired trust metadata is bound to another app instance"
            }
        }
        return record
    }

    @Synchronized
    private fun saveTrust(record: WiredAdbTrustRecord) {
        val appInstanceId = preferences.getString(APP_INSTANCE_ID_KEY, null)
            ?: throw IllegalStateException("wired appInstanceId is not initialized")
        require(record.appInstanceId == appInstanceId) {
            "wired trust metadata is bound to another app instance"
        }
        val encoded = encodeTrustRecord(record)
        check(
            preferences.edit()
                .putString(TRUST_RECORD_KEY, encoded)
                .commit(),
        ) { "wired trust metadata persistence failed" }
    }

    @Synchronized
    private fun clearTrust() {
        check(
            preferences.edit()
                .remove(TRUST_RECORD_KEY)
                .commit(),
        ) { "wired trust metadata clear failed" }
    }

    @Synchronized
    private fun loadIntent(): WiredAdbUserIntent = when (
        val encoded = preferences.getString(USER_INTENT_KEY, null)
    ) {
        null -> WiredAdbUserIntent.DISABLED
        "ENABLED" -> WiredAdbUserIntent.ENABLED
        "DISABLED" -> WiredAdbUserIntent.DISABLED
        else -> throw IllegalStateException("wired user intent metadata is invalid")
    }

    @Synchronized
    private fun saveIntent(intent: WiredAdbUserIntent) {
        check(
            preferences.edit()
                .putString(USER_INTENT_KEY, intent.name)
                .commit(),
        ) { "wired user intent persistence failed" }
    }

    private fun encodeTrustRecord(record: WiredAdbTrustRecord): String {
        require(record.transcriptHash.isNotEmpty()) { "wired trust transcript is required" }
        val encoded = listOf(
            TRUST_FORMAT_VERSION,
            encodeText(record.desktopId),
            encodeText(record.appInstanceId),
            record.serialFingerprint.lowercase(Locale.ROOT),
            record.protocolVersion.toString(),
            encodeText(record.secretRef),
            record.transcriptHash.lowercase(Locale.ROOT),
        ).joinToString(RECORD_SEPARATOR)
        require(encoded.length <= MAX_TRUST_RECORD_CHARS) { "wired trust metadata is too large" }
        return encoded
    }

    private fun decodeTrustRecord(encoded: String): WiredAdbTrustRecord {
        val fields = encoded.split(RECORD_SEPARATOR)
        require(fields.size == TRUST_FIELD_COUNT) { "wired trust metadata is invalid" }
        require(fields[0] == TRUST_FORMAT_VERSION) { "wired trust metadata version is unsupported" }
        val protocolVersion = fields[4].toIntOrNull()
            ?: throw IllegalStateException("wired trust metadata is invalid")
        val record = WiredAdbTrustRecord(
            desktopId = decodeText(fields[1]),
            appInstanceId = decodeText(fields[2]),
            serialFingerprint = fields[3],
            protocolVersion = protocolVersion,
            secretRef = decodeText(fields[5]),
            transcriptHash = fields[6],
        )
        require(encodeTrustRecord(record) == encoded) { "wired trust metadata is not canonical" }
        return record
    }

    private fun encodeText(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeText(encoded: String): String {
        require(encoded.isNotEmpty() && encoded.length <= MAX_TEXT_FIELD_CHARS) {
            "wired trust metadata is invalid"
        }
        require(encoded.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "wired trust metadata is invalid"
        }
        val bytes = try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException("wired trust metadata is invalid")
        }
        val decoded = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw IllegalStateException("wired trust metadata is invalid", error)
        } finally {
            bytes.fill(0)
        }
        require(encodeText(decoded) == encoded) { "wired trust metadata is not canonical" }
        return decoded
    }

    private inner class TrustStoreView : WiredAdbTrustStore {
        override fun load(): WiredAdbTrustRecord? = loadTrust()
        override fun save(record: WiredAdbTrustRecord) = saveTrust(record)
        override fun clear() = clearTrust()
    }

    private inner class IntentStoreView : WiredAdbIntentStore {
        override fun load(): WiredAdbUserIntent = loadIntent()
        override fun save(intent: WiredAdbUserIntent) = saveIntent(intent)
    }

    companion object {
        private const val APP_INSTANCE_ID_BYTES = 32
        private const val APP_INSTANCE_ID_HEX_CHARS = APP_INSTANCE_ID_BYTES * 2
        private const val MAX_TEXT_FIELD_CHARS = 1_024
        private const val MAX_TRUST_RECORD_CHARS = 4_096
        private const val TRUST_FORMAT_VERSION = "1"
        private const val RECORD_SEPARATOR = "."
        private const val TRUST_FIELD_COUNT = 7
        private const val APP_INSTANCE_ID_KEY = "wired_app_instance_id_v1"
        private const val TRUST_RECORD_KEY = "wired_trust_record_v1"
        private const val USER_INTENT_KEY = "wired_user_intent_v1"
        private const val HEX_DIGITS = "0123456789abcdefABCDEF"
        internal const val PREFERENCES_NAME = "runtime.mobileagent.wired.metadata.v1"

        @JvmStatic
        fun create(context: Context): AndroidWiredAdbMetadataStore {
            val appContext = context.applicationContext ?: context
            return AndroidWiredAdbMetadataStore(
                appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )
        }
    }
}

/** Production constructor for the one canonical wired metadata namespace. */
object AndroidWiredAdbMetadataStoreFactory {
    @JvmStatic
    fun create(context: Context): AndroidWiredAdbMetadataStore =
        AndroidWiredAdbMetadataStore.create(context)
}
