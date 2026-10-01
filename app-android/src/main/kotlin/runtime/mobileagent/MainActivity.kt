// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.graphics.Color
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import runtime.mobileagent.background.ImportWorkScheduler
import runtime.mobileagent.ui.MainApp

class MainActivity : ComponentActivity() {
    private var importRecovery: Job? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as? MobileAgentApp)?.ensureHostInitialized()
        configureSystemBars()
        setContent { MainApp(onMoveTaskToBack = { moveTaskToBack(true) }) }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val preferences = getSharedPreferences("notification-permission", MODE_PRIVATE)
            if (!preferences.getBoolean("requested", false)) {
                preferences.edit().putBoolean("requested", true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Foreground refresh belongs to the Activity/process lifecycle, not to construction of
        // the announcements screen ViewModel. The coordinator handles single-flight and backoff.
        (application as? MobileAgentApp)?.container?.announcementRefreshCoordinator?.foreground()
        val app = application as? MobileAgentApp ?: return
        if (importRecovery?.isActive == true) return
        importRecovery = lifecycleScope.launch {
            try {
                val (batches, configured) = runInterruptible(Dispatchers.IO) {
                    app.container.knowledge.recoverableBatchIds() to app.container.profiles.visionConfigured()
                }
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    // KEEP checks unique work atomically: an existing worker is
                    // never replaced, while a durable orphan can run again.
                    batches.forEach { ImportWorkScheduler.enqueueBatch(app, it, configured) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Foreground recovery is best effort; storage failures must
                // not crash navigation or erase any persisted import state.
            }
        }
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
