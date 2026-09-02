// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import runtime.mobileagent.ui.MainApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as? MobileAgentApp)?.ensureHostInitialized()
        configureSystemBars()
        setContent { MainApp() }
    }

    override fun onStart() {
        super.onStart()
        // Foreground refresh belongs to the Activity/process lifecycle, not to construction of
        // the announcements screen ViewModel. The coordinator handles single-flight and backoff.
        (application as? MobileAgentApp)?.container?.announcementRefreshCoordinator?.foreground()
    }

    private fun configureSystemBars() {
        // The first frame uses the product's light 66ccff surface. MobileAgentTheme
        // reapplies the exact surface and icon contrast whenever the theme changes.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val surface = Color.TRANSPARENT
        window.statusBarColor = surface
        window.navigationBarColor = surface
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }
}
