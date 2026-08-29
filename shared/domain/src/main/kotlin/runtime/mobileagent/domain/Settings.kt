// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.Serializable

@Serializable
enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
    COLOR_66CCFF,
}

/** Alias retained for UI code that calls the setting a theme mode. */
typealias ThemeMode = ThemePreference

@Serializable
enum class LocalePreference {
    SYSTEM,
    ZH_CN,
    EN_US,
}

/** Alias retained for UI code that calls the setting a locale mode. */
typealias LocaleMode = LocalePreference

@Serializable
data class AppSettings(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val locale: LocalePreference = LocalePreference.SYSTEM,
)
