// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.AppSettings
import runtime.mobileagent.domain.LocalePreference
import runtime.mobileagent.domain.ThemePreference

/** Small, namespaced preference repository for theme and locale choices. */
class SettingsRepository(private val db: SqlConnection) {
    fun get(): AppSettings = AppSettings(theme(), locale())

    fun settings(): AppSettings = get()

    fun set(settings: AppSettings) {
        db.transaction {
            put(KEY_THEME, settings.theme.name)
            put(KEY_LOCALE, settings.locale.name)
        }
    }

    fun setTheme(value: ThemePreference) = put(KEY_THEME, value.name)

    fun setTheme(value: String) {
        val parsed = runCatching { ThemePreference.valueOf(value.trim().uppercase()) }
            .getOrElse { throw IllegalArgumentException("Unknown theme preference") }
        setTheme(parsed)
    }

    fun theme(): ThemePreference = db.query("SELECT value FROM app_prefs WHERE key=?", listOf(KEY_THEME))
        .firstOrNull()?.string("value")?.let { runCatching { ThemePreference.valueOf(it) }.getOrNull() }
        ?: ThemePreference.SYSTEM

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
    }
}
