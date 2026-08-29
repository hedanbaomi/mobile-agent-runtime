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
        configureSystemBars()
        setContent { MainApp() }
    }

    private fun configureSystemBars() {
        // The first frame uses the product's light 66ccff surface. MobileAgentTheme
        // reapplies the exact surface and icon contrast whenever the theme changes.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val surface = Color.rgb(242, 249, 253)
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
