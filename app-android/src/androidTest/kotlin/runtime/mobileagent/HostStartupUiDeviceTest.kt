// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.ui.HostInitializationScreen

@RunWith(AndroidJUnit4::class)
class HostStartupUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    @Test fun failureShowsBoundedCodeAndForegroundRecoveryActions() {
        var retries = 0
        var exports = 0
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            HostInitializationScreen(true, HostStartupFailure(HostStartupStage.DATABASE, HostStartupError.STORAGE),
                onRetry = { retries++ }, onExport = { exports++ })
        }
        compose.onNodeWithText(context.getString(R.string.host_failure_details, "DATABASE", "STORAGE")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.host_retry)).performClick()
        compose.onNodeWithText(context.getString(R.string.host_export)).performClick()
        assertEquals(1, retries)
        assertEquals(1, exports)
    }

    @Test fun databaseOpenFailureIsClassifiedAndExplicitRetryInitializesTheSameHost() {
        val base = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val root = java.io.File(base.cacheDir, "startup-${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
        var blocked = true
        val context = object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir() = java.io.File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = java.io.File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = java.io.File(root, "no-backup").apply { mkdirs() }
            override fun getDatabasePath(name: String): java.io.File =
                if (blocked) java.io.File(root, "blocked").apply { mkdirs() } else java.io.File(root, name)
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("${root.name}-$name", mode)
        }
        val app = MobileAgentApp()
        android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", android.content.Context::class.java).apply {
            isAccessible = true
            invoke(app, context)
        }
        val deferred = MobileAgentApp.deferHostInitializationForInstrumentation
        try {
            MobileAgentApp.deferHostInitializationForInstrumentation = true
            app.onCreate()
            org.junit.Assert.assertThrows(Exception::class.java) { app.ensureHostInitialized() }
            assertEquals(HostInitialization.FAILED, app.hostInitialization.value)
            assertEquals(HostStartupStage.DATABASE, app.hostStartupFailure.value?.stage)
            blocked = false
            app.ensureHostInitialized()
            assertEquals(HostInitialization.READY, app.hostInitialization.value)
            org.junit.Assert.assertNull(app.hostStartupFailure.value)
            val container = app.container
            app.ensureHostInitialized()
            org.junit.Assert.assertSame(container, app.container)
        } finally {
            MobileAgentApp.deferHostInitializationForInstrumentation = deferred
            if (app.isHostInitialized) { app.container.close(); app.database.close() }
            root.deleteRecursively()
        }
    }
}
