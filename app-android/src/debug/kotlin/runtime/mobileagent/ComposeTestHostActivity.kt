// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.activity.ComponentActivity
import android.content.Context
import runtime.mobileagent.domain.LocalePreference
import runtime.mobileagent.ui.ActivityLocaleConfiguration

/** Empty internal debug/review activity for isolated Compose instrumentation; absent from release. */
class ComposeTestHostActivity : ComponentActivity() {
    private val localeConfiguration = ActivityLocaleConfiguration()
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localeConfiguration.attach(newBase))
    }
    internal fun syncLocalePreference(preference: LocalePreference) {
        localeConfiguration.sync(this, preference)
    }
}
