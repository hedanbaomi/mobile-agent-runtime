// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.Manifest
import android.app.UiAutomation
import android.content.ComponentName
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.observation.ScreenObservationRegistry
import runtime.mobileagent.observation.ScreenObservationService
import runtime.mobileagent.observation.ScreenObservationToolExecutor
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult

/** Real system-bound service on an emulator; never modifies a physical device's settings. */
@SdkSuppress(minSdkVersion = 29)
class ScreenObservationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var automation: UiAutomation
    private var previousServices: String? = null
    private var previousEnabled = 0
    private var previousConsent = false
    private var restore = false

    @Before fun connect() = runBlocking {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("emulator"))
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        previousServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        previousEnabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        val registry = ScreenObservationRegistry.get(context)
        previousConsent = registry.access.value.consent
        restore = true
        val component = ComponentName(context, ScreenObservationService::class.java).flattenToString()
        val services = previousServices.orEmpty().split(':').filter { it.isNotBlank() }.toMutableSet().also { it.add(component) }
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            assertTrue(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":")))
            assertTrue(Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1))
        } finally { automation.dropShellPermissionIdentity() }
        assertTrue(registry.setConsent(true))
        withTimeout(10_000) { while (!registry.access.value.ready) delay(50) }
    }

    @After fun disconnect() {
        if (!restore) return
        ScreenObservationRegistry.get(context).setConsent(previousConsent)
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, previousServices)
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, previousEnabled)
        } finally { automation.dropShellPermissionIdentity() }
    }

    @Test fun productionServiceReadsVisibleControlsAndOmitsPassword() = runBlocking {
        compose.setContent { Column {
            Text("screen-observation-fixture")
            BasicTextField("password-fixture-do-not-disclose", {}, visualTransformation = PasswordVisualTransformation())
        } }
        compose.waitForIdle()
        val registry = ScreenObservationRegistry.get(context)
        val executor = ScreenObservationToolExecutor(registry) { true }
        val call = ToolCall("read", "device_observe", "{\"package_name\":\"${context.packageName}\"}")
        assertEquals(ToolResult.NeedsApproval, executor.invoke(call))
        val result = executor.approve(call.callId)
        assertTrue(result.toString(), result is ToolResult.Value)
        val text = (result as ToolResult.Value).json
        assertTrue(text, text.contains("screen-observation-fixture"))
        assertFalse(text.contains("password-fixture-do-not-disclose"))
        registry.setConsent(false)
        assertFalse(executor.authorizeReplay(call))
        assertTrue(executor.invoke(call) is ToolResult.Denied)
    }

    @Test fun windowEventsWakeWaitAndReturnFreshContent() = runBlocking {
        val label = mutableStateOf("before-event")
        compose.setContent { Text(label.value) }
        compose.waitForIdle()
        val registry = ScreenObservationRegistry.get(context)
        val executor = ScreenObservationToolExecutor(registry) { true }
        val call = ToolCall("wait", "device_wait_for", "{\"package_name\":\"${context.packageName}\",\"text\":\"after-event\",\"timeout_ms\":5000}")
        assertEquals(ToolResult.NeedsApproval, executor.invoke(call))
        compose.runOnUiThread { label.value = "after-event" }
        compose.waitForIdle()
        val result = executor.approve(call.callId)
        assertTrue(result.toString(), result is ToolResult.Value && result.json.contains("after-event"))
    }

    @Test fun manifestRequiresSystemBindingAndScreenUsesExistingAgentConfirmationPolicy() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, ScreenObservationService::class.java), 0)
        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, info.permission)
        assertTrue(info.exported)
        assertFalse(AgentToolConfirmation.requiresExplicitApproval("device_observe"))
        assertFalse(AgentToolConfirmation.requiresExplicitApproval("device_wait_for"))
        assertTrue(AgentToolConfirmation.requiresExplicitApproval("job_start"))
    }
}
