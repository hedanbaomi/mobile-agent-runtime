// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Context
import android.content.res.Resources
import runtime.mobileagent.ui.ActivityLocaleConfiguration

private class CachedHostChatLocale(
    val appIdentity: Int,
    val preference: String,
    val systemLocales: String,
    val resources: Resources,
)

@Volatile
private var cachedHostChatLocale: CachedHostChatLocale? = null

/**
 * Resolve application-authored Chat notices and error descriptions in the selected app language.
 * Existing user/model payloads are untouched. Callers retain their existing choice of transient
 * UI state or durable error records; localization does not change that persistence boundary.
 */
internal fun Context.hostChatText(resource: Int, vararg args: Any): String {
    val root = applicationContext ?: return ActivityLocaleConfiguration().attach(this).resources.chatString(resource, args)
    val identity = System.identityHashCode(root)
    val preference = root.getSharedPreferences(ActivityLocaleConfiguration.PREFERENCES, Context.MODE_PRIVATE)
        .getString(ActivityLocaleConfiguration.KEY, "system") ?: "system"
    val systemLocales = if (preference == "system") {
        Resources.getSystem().configuration.locales.toLanguageTags()
    } else {
        ""
    }
    val existing = cachedHostChatLocale
    val localized = if (
        existing != null &&
        existing.appIdentity == identity &&
        existing.preference == preference &&
        existing.systemLocales == systemLocales
    ) {
        existing.resources
    } else {
        ActivityLocaleConfiguration().attach(root).resources.also {
            cachedHostChatLocale = CachedHostChatLocale(identity, preference, systemLocales, it)
        }
    }
    return localized.chatString(resource, args)
}

// An empty argument list requests a template, as with Resources.getString(id).
// Formatting that template here would fail before the projection supplies its value.
private fun Resources.chatString(resource: Int, args: Array<out Any>): String =
    if (args.isEmpty()) getString(resource) else getString(resource, *args)

/**
 * Stable ASCII budget marker. Recovery UI must not infer meaning from translated copy, so this
 * value is identical in every locale; the device contract test asserts it in both.
 */
internal const val CHAT_CONTEXT_OVERFLOW_CODE = "CONTEXT_OVERFLOW"
