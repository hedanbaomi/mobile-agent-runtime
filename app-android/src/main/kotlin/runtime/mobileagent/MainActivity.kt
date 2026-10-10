// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.graphics.Color
import android.content.Context
import runtime.mobileagent.domain.LocalePreference
import runtime.mobileagent.ui.ActivityLocaleConfiguration
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.content.Intent
import android.content.ComponentName
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
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
import runtime.mobileagent.ui.DatabaseRecoveryScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private val localeConfiguration = ActivityLocaleConfiguration()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localeConfiguration.attach(newBase))
    }

    internal fun syncLocalePreference(preference: LocalePreference) {
        localeConfiguration.sync(this, preference)
    }

    private var importRecovery: Job? = null
    private var recoveryBusy by mutableStateOf(false)
    private var recoveryMessage by mutableStateOf<String?>(null)
    private val exportRecovery = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) lifecycleScope.launch {
            recoveryBusy = true
            recoveryMessage = try {
                runInterruptible(Dispatchers.IO) {
                    val recovery = checkNotNull((application as MobileAgentApp).databaseRecovery)
                    checkNotNull(contentResolver.openOutputStream(uri)).use { recovery.export(it) }
                }
                getString(R.string.database_recovery_exported)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { getString(R.string.database_recovery_failed) }
            finally { recoveryBusy = false }
        }
    }
    private var updateInstallRunning = false
    private var pendingUpdatePermission = false
    private var installingUpdates: runtime.mobileagent.updates.AppUpdateCoordinator? = null
    private fun currentUpdates() = installingUpdates ?: (application as MobileAgentApp).container.appUpdates
    private val updatePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingUpdatePermission) {
            pendingUpdatePermission = false
            if (packageManager.canRequestPackageInstalls()) lifecycleScope.launch { openVerifiedUpdateInstaller() }
            else currentUpdates().installationMessage("未允许安装；可在允许后重试。")
        }
    }
    private val updateInstaller = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        currentUpdates().installationMessage(if (result.resultCode == RESULT_OK) "安装已完成，请重新打开应用。" else "安装未完成，可重试安装。")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as? MobileAgentApp)?.ensureHostInitialized()
        if ((application as MobileAgentApp).databaseRecovery != null) {
            configureSystemBars()
            setContent {
                DatabaseRecoveryScreen(recoveryBusy, recoveryMessage,
                    onExport = { exportRecovery.launch("mobile-agent-database-recovery.zip") },
                    onStartNew = { lifecycleScope.launch {
                        recoveryBusy = true
                        try {
                            runInterruptible(Dispatchers.IO) { (application as MobileAgentApp).startNewDatabaseFromRecovery() }
                            recreate()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { recoveryMessage = getString(R.string.database_recovery_failed) }
                        finally { recoveryBusy = false }
                    } },
                )
            }
            return
        }
        pendingUpdatePermission = savedInstanceState?.getBoolean("pending-update-permission") ?: false
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
        if (!(application as MobileAgentApp).isHostInitialized) return
        // Foreground refresh belongs to the Activity/process lifecycle, not to construction of
        // the announcements screen ViewModel. The coordinator handles single-flight and backoff.
        (application as? MobileAgentApp)?.container?.announcementRefreshCoordinator?.foreground()
        val app = application as? MobileAgentApp ?: return
        // Foreground entry performs the due check (at most hourly) and keeps re-checking while visible.
        app.container.appUpdates.foreground()
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

    override fun onStop() {
        super.onStop()
        if (!(application as MobileAgentApp).isHostInitialized) return
        // Leaving the foreground stops the update ticker; no service, worker or timer keeps running.
        (application as? MobileAgentApp)?.container?.appUpdates?.background()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending-update-permission", pendingUpdatePermission)
        super.onSaveInstanceState(outState)
    }

    internal fun downloadAndInstallUpdate(updates: runtime.mobileagent.updates.AppUpdateCoordinator = (application as MobileAgentApp).container.appUpdates) {
        if (updateInstallRunning) return
        updateInstallRunning = true
        installingUpdates = updates
        lifecycleScope.launch {
            try {
                if (updates.downloadOrReady().await() == null) return@launch
                if (!packageManager.canRequestPackageInstalls()) {
                    pendingUpdatePermission = true
                    updates.installationMessage("请允许此应用安装更新，返回后继续安装。")
                    updatePermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                } else openVerifiedUpdateInstaller()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                pendingUpdatePermission = false
                updates.installationMessage("无法打开系统安装程序，请重试。")
            } finally { updateInstallRunning = false }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun openVerifiedUpdateInstaller() {
        val updates = currentUpdates()
        // Restores the persisted candidate after process recreation during the permission UI without
        // a new network check: downloading and installing must not wait for the hourly window.
        updates.restore().join()
        val file = updates.readyForInstall() ?: return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.app-updates", file)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            val systemInstaller = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .firstOrNull { it.activityInfo.applicationInfo.flags and
                    (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
                ?: error("No system package installer")
            intent.component = ComponentName(systemInstaller.activityInfo.packageName, systemInstaller.activityInfo.name)
            updates.dismiss()
            updateInstaller.launch(intent)
        } catch (_: Exception) { updates.installationMessage("无法打开系统安装程序，请重试。") }
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
