// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import runtime.mobileagent.domain.LocalePreference

/** Resolve Android string resources against the same effective app language as UI state. */
@Composable
internal fun AppLocalizedResources(language: String, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    val deviceConfiguration = LocalConfiguration.current
    val configuration = remember(deviceConfiguration, language) {
        Configuration(deviceConfiguration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(language)))
        }
    }
    val context = remember(baseContext, configuration) {
        val localized = baseContext.createConfigurationContext(configuration)
        // Retain the Activity wrapper chain for components that need a window or launcher.
        object : ContextWrapper(baseContext) {
            override fun getResources() = localized.resources
            override fun getAssets() = localized.assets
        }
    }
    CompositionLocalProvider(
        LocalContext provides context,
        LocalConfiguration provides configuration,
        content = content,
    )
}


/**
 * Locale configuration for Activity-owned windows, including Compose dialogs. The small
 * preference mirror is readable before Activity attachment without opening the database.
 * SQLite remains authoritative; sync is called only after the accepted settings are loaded.
 */
internal class ActivityLocaleConfiguration {
    private var attachedPreference = "system"

    fun attach(base: Context): Context {
        attachedPreference = base.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY, "system") ?: "system"
        val configuration = Configuration(base.resources.configuration).apply {
            setLocales(if (attachedPreference == "system") Resources.getSystem().configuration.locales
                else LocaleList(Locale.forLanguageTag(attachedPreference)))
        }
        return base.createConfigurationContext(configuration)
    }

    fun sync(activity: Activity, preference: LocalePreference) {
        val requested = when (preference) {
            LocalePreference.SYSTEM -> "system"
            LocalePreference.EN_US -> "en-US"
            LocalePreference.ZH_CN -> "zh-CN"
        }
        if (requested == attachedPreference) return
        activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(KEY, requested).apply()
        attachedPreference = requested
        // Rebuild Activity-owned resource contexts rather than mutating shared Resources.
        // Android retains the ViewModelStore and saved instance/navigation state.
        activity.recreate()
    }

    companion object {
        internal const val PREFERENCES = "app-ui-locale"
        internal const val KEY = "preference"
    }
}
