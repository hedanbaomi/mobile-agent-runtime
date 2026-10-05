// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.AppSettings
import runtime.mobileagent.domain.LocalePreference
import runtime.mobileagent.domain.ThemePreference
import runtime.mobileagent.domain.WebSearchProvider

/** Small, namespaced preference repository for theme and locale choices. */
class SettingsRepository(private val db: SqlConnection) {
    fun get(): AppSettings = AppSettings(
        theme = theme(),
        locale = locale(),
        globalRootPromptOverride = globalRootPromptOverride(),
        globalRootPromptRevision = readInt(KEY_ROOT_REV, 0),
        globalRootPromptHash = read(KEY_ROOT_HASH).orEmpty(),
        globalRootPromptUpdatedAt = read(KEY_ROOT_UPDATED).orEmpty(),
        globalRootPromptUnlocked = read(KEY_ROOT_UNLOCKED) == "1",
    )

    fun settings(): AppSettings = get()

    fun set(settings: AppSettings) {
        db.transaction {
            put(KEY_THEME, settings.theme.name)
            put(KEY_LOCALE, settings.locale.name)
            writeGlobalRoot(settings)
        }
    }

    fun effectiveGlobalRootPrompt(): String =
        globalRootPromptOverride() ?: DEFAULT_GLOBAL_ROOT_PROMPT

    fun setGlobalRootPrompt(override: String?, unlocked: Boolean) {
        val normalized = override?.trim()?.takeIf { it.isNotEmpty() || unlocked }
        val hash = normalized?.let { sha256(it) }.orEmpty()
        db.transaction {
            if (normalized == null) {
                db.execute("DELETE FROM app_prefs WHERE key IN (?,?,?)", listOf(KEY_ROOT_OVERRIDE, KEY_ROOT_HASH, KEY_ROOT_UPDATED))
            } else {
                put(KEY_ROOT_OVERRIDE, normalized)
                put(KEY_ROOT_HASH, hash)
                put(KEY_ROOT_UPDATED, runtime.mobileagent.domain.Utc.nowIso())
            }
            put(KEY_ROOT_UNLOCKED, if (unlocked) "1" else "0")
            val revision = readInt(KEY_ROOT_REV, 0) + 1
            put(KEY_ROOT_REV, revision.toString())
        }
    }

    fun restoreDefaultGlobalRootPrompt() = setGlobalRootPrompt(null, unlocked = true)

    fun webSearchProvider(): WebSearchProvider? {
        val id = read(KEY_WEB_SEARCH_PROVIDER) ?: return WebSearchProvider.BRAVE
        return WebSearchProvider.fromId(id)
    }

    fun webSearchRevision(): Long = read(KEY_WEB_SEARCH_REVISION)?.toLongOrNull() ?: 0L

    fun selectWebSearchProvider(provider: WebSearchProvider) = db.transaction {
        put(KEY_WEB_SEARCH_PROVIDER, provider.id)
        advanceWebSearchRevision()
    }

    fun webSearchSecretRef(provider: WebSearchProvider? = webSearchProvider()): String? =
        provider?.let { read(webSearchSecretKey(it)) }?.takeIf { it.isNotBlank() }

    fun webSearchEnabled(provider: WebSearchProvider? = webSearchProvider()): Boolean =
        provider != null && read(webSearchEnabledKey(provider)) == "1" && webSearchSecretRef(provider) != null

    /** Persist only an opaque encrypted-secret reference and the user's explicit enablement. */
    fun setWebSearch(secretRef: String?, enabled: Boolean, provider: WebSearchProvider = checkNotNull(webSearchProvider())) {
        val normalized = secretRef?.trim()?.takeIf { it.isNotEmpty() }
        require(normalized == null || normalized.matches(Regex("search:[A-Za-z0-9._:-]{1,128}"))) {
            "Invalid web-search secret reference"
        }
        db.transaction {
            if (normalized == null) {
                db.execute("DELETE FROM app_prefs WHERE key IN (?,?)", listOf(webSearchSecretKey(provider), webSearchEnabledKey(provider)))
            } else {
                put(webSearchSecretKey(provider), normalized)
                put(webSearchEnabledKey(provider), if (enabled) "1" else "0")
            }
            advanceWebSearchRevision()
        }
    }

    private fun advanceWebSearchRevision() = put(KEY_WEB_SEARCH_REVISION, Math.addExact(webSearchRevision(), 1L).toString())

    private fun writeGlobalRoot(settings: AppSettings) {
        put(KEY_ROOT_UNLOCKED, if (settings.globalRootPromptUnlocked) "1" else "0")
        put(KEY_ROOT_REV, settings.globalRootPromptRevision.toString())
        val override = settings.globalRootPromptOverride
        if (override == null) {
            db.execute("DELETE FROM app_prefs WHERE key = ?", listOf(KEY_ROOT_OVERRIDE))
        } else {
            put(KEY_ROOT_OVERRIDE, override)
        }
    }

    private fun globalRootPromptOverride(): String? = read(KEY_ROOT_OVERRIDE)

    private fun read(key: String): String? =
        db.query("SELECT value FROM app_prefs WHERE key=?", listOf(key)).firstOrNull()?.string("value")

    private fun readInt(key: String, default: Int): Int = read(key)?.toIntOrNull() ?: default

    private fun sha256(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    fun setTheme(value: ThemePreference) = put(KEY_THEME, value.name)

    fun setTheme(value: String) {
        val parsed = runCatching { ThemePreference.valueOf(value.trim().uppercase()) }
            .getOrElse { throw IllegalArgumentException("Unknown theme preference") }
        setTheme(parsed)
    }

    fun theme(): ThemePreference = db.query("SELECT value FROM app_prefs WHERE key=?", listOf(KEY_THEME))
        .firstOrNull()?.string("value")?.let { runCatching { ThemePreference.valueOf(it) }.getOrNull() }
        ?: ThemePreference.LIGHT

    fun themePreference(): ThemePreference = theme()

    fun setLocale(value: LocalePreference) = put(KEY_LOCALE, value.name)

    fun setLocale(value: String) {
        val parsed = runCatching { LocalePreference.valueOf(value.trim().uppercase().replace('-', '_')) }
            .getOrElse { throw IllegalArgumentException("Unknown locale preference") }
        setLocale(parsed)
    }

    fun locale(): LocalePreference = db.query("SELECT value FROM app_prefs WHERE key=?", listOf(KEY_LOCALE))
        .firstOrNull()?.string("value")?.let { runCatching { LocalePreference.valueOf(it) }.getOrNull() }
        ?: LocalePreference.SYSTEM

    fun localePreference(): LocalePreference = locale()

    private fun put(key: String, value: String) {
        db.execute("INSERT INTO app_prefs(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", listOf(key, value))
    }

    companion object {
        private const val KEY_THEME = "settings.theme"
        private const val KEY_LOCALE = "settings.locale"
        private const val KEY_ROOT_OVERRIDE = "settings.globalRootPrompt"
        private const val KEY_ROOT_REV = "settings.globalRootPrompt.revision"
        private const val KEY_ROOT_HASH = "settings.globalRootPrompt.hash"
        private const val KEY_ROOT_UPDATED = "settings.globalRootPrompt.updatedAt"
        private const val KEY_ROOT_UNLOCKED = "settings.globalRootPrompt.unlocked"
        internal const val KEY_WEB_SEARCH_SECRET_REF = "settings.webSearch.secretRef"
        private const val KEY_WEB_SEARCH_ENABLED = "settings.webSearch.enabled"
        private const val KEY_WEB_SEARCH_PROVIDER = "settings.webSearch.provider"
        private const val KEY_WEB_SEARCH_REVISION = "settings.webSearch.revision"
        internal fun webSearchSecretKey(provider: WebSearchProvider): String =
            if (provider == WebSearchProvider.BRAVE) KEY_WEB_SEARCH_SECRET_REF else "settings.webSearch.${provider.id}.secretRef"
        private fun webSearchEnabledKey(provider: WebSearchProvider): String =
            if (provider == WebSearchProvider.BRAVE) KEY_WEB_SEARCH_ENABLED else "settings.webSearch.${provider.id}.enabled"
        const val DEFAULT_GLOBAL_ROOT_PROMPT =
            "Follow the immutable runtime contract. Do not grant tools, network, files, or Python isolation from this prompt."
    }
}
