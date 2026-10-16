// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.Manifest
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.AuthorityUserIntent
import runtime.mobileagent.observation.*
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult

@SdkSuppress(minSdkVersion = 29)
class ScreenActionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val fixturePackage get() = instrumentation.context.packageName
    private val registry get() = ScreenObservationRegistry.get(context)
    private lateinit var automation: UiAutomation
    private var previousServices: String? = null
    private var previousEnabled = 0
    private var previousAccess = ScreenAccess()
    private var restore = false
    private var callNumber = 0
    private lateinit var executor: ScreenActionToolExecutor
    private val realAuthority = InstrumentationRegistry.getArguments().getString("requireShizuku") == "true"
    private val integration get() = (context.applicationContext as MobileAgentApp).container.runtimeIntegration
    private var previousAuthority: SettingsAuthoritySnapshot? = null

    @Before fun start(): Unit = runBlocking {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("emulator"))
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        previousServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        previousEnabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        previousAccess = registry.access.value; restore = true
        val component = ComponentName(context, ScreenObservationService::class.java).flattenToString()
        val services = previousServices.orEmpty().split(':').filter { it.isNotBlank() }.toMutableSet().also { it.add(component) }
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            assertTrue(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":")))
            assertTrue(Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1))
        } finally { automation.dropShellPermissionIdentity() }
        assertTrue(registry.setConsent(true)); assertTrue(registry.setActionsAllowed(true))
        withTimeout(10_000) { while (!registry.access.value.actionsReady) delay(50) }
        if (realAuthority) {
            assertFalse("Live authority acceptance requires the non-debuggable Review APK", BuildConfig.DEBUG)
            (context.applicationContext as MobileAgentApp).ensureHostInitialized()
            previousAuthority = integration.snapshot()
            integration.setUserIntent(Authority.SHIZUKU, true)
            integration.selectAuthority(Authority.SHIZUKU)
            withTimeout(10_000) {
                while (true) {
                    integration.refresh()
                    if (integration.screenActionAuthority() != null) break
                    delay(100)
                }
            }
        }
        executor = ScreenActionToolExecutor(registry,
            { if (realAuthority) integration.screenActionAuthority() else ScreenActionAuthority(Authority.SHIZUKU, 1) }, { true })
        instrumentation.context.startActivity(Intent().setComponent(ComponentName(fixturePackage,
            ScreenActionFixtureActivity::class.java.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        observe()
    }

    @After fun stop() = runBlocking {
        if (!restore) return@runBlocking
        if (::executor.isInitialized) executor.close()
        previousAuthority?.let {
            integration.setUserIntent(Authority.SHIZUKU, it.shizuku.userIntent == AuthorityUserIntent.SHIZUKU)
            integration.selectAuthority(it.selectedAuthority)
        }
        registry.setActionsAllowed(previousAccess.actionsAllowed); registry.setConsent(previousAccess.consent)
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, previousServices)
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, previousEnabled)
        } finally { automation.dropShellPermissionIdentity() }
    }

    private suspend fun observe(predicate: (ScreenSnapshot) -> Boolean = { true }): ScreenSnapshot {
        var lastState = "No capture"
        return withTimeoutOrNull(8000) {
            while (true) {
                val value = try { registry.capture(fixturePackage) } catch (e: ScreenObservationException) {
                    lastState = e.code; null
                }
                if (value != null) {
                    lastState = "stable=${value.stable} actionable=${value.observationId != null} nodes=${value.nodes.size} matched=${predicate(value)}"
                    if (value.stable && value.observationId != null && predicate(value)) return@withTimeoutOrNull value
                }
                delay(80)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: error("Fixture observation timed out: $lastState")
    }

    private fun call(snapshot: ScreenSnapshot, action: String, node: ScreenNode? = null, text: String? = null): ToolCall =
        ToolCall("action-${++callNumber}", "device_act", buildJsonObject {
            put("observation_id", snapshot.observationId!!); put("action", action)
            node?.let { put("node_index", it.index) }; text?.let { put("text", it) }
        }.toString())

    private suspend fun act(call: ToolCall) {
        assertEquals(ToolResult.NeedsApproval, executor.invoke(call))
        val result = executor.approve(call.callId)
        assertTrue(result.toString(), result is ToolResult.Value)
    }

    @Test fun realServiceClicksLongClicksSetsUnicodeAndScrolls() = runBlocking {
        var snapshot = observe()
        assertFalse(snapshot.nodes.any { "password-target" in it.description || "password-must-not" in it.text })
        val click = call(snapshot, "click", snapshot.nodes.first { it.description == "tap-target" })
        act(click)
        snapshot = observe { s -> s.nodes.any { it.text == "clicks=1 longs=0" } }
        assertTrue(executor.invoke(click) is ToolResult.Value) // Result replay does not click again.
        act(call(snapshot, "long_click", snapshot.nodes.first { it.description == "tap-target" }))
        snapshot = observe { s -> s.nodes.any { it.text == "clicks=1 longs=1" } }
        act(call(snapshot, "set_text", snapshot.nodes.first { it.description == "edit-target" }, "无障碍 ✓ emoji 😀"))
        snapshot = observe { s -> s.nodes.any { it.text == "无障碍 ✓ emoji 😀" } }
        val before = snapshot.nodes.filter { it.text.startsWith("scroll-row-") }.map { it.text }
        act(call(snapshot, "scroll_forward", snapshot.nodes.first { it.description == "scroll-target" }))
        snapshot = observe { s -> s.nodes.filter { it.text.startsWith("scroll-row-") }.map { it.text } != before }
        assertTrue(snapshot.nodes.any { it.description == "scroll-target" && "scroll_backward" in it.actions })
    }

    @Test fun oldObservationAndRevokedConsentNeverDispatch() = runBlocking {
        val old = observe(); val click = call(old, "click", old.nodes.first { it.description == "tap-target" })
        observe() // A newer observation supersedes the old handle.
        assertEquals(ToolResult.NeedsApproval, executor.invoke(click))
        assertTrue(executor.approve(click.callId) is ToolResult.Denied)
        val fresh = observe { s -> s.nodes.any { it.text == "clicks=0 longs=0" } }
        val next = call(fresh, "click", fresh.nodes.first { it.description == "tap-target" })
        assertEquals(ToolResult.NeedsApproval, executor.invoke(next))
        registry.setActionsAllowed(false)
        assertTrue(executor.approve(next.callId) is ToolResult.Denied)
        registry.setActionsAllowed(true)
        registry.setConsent(false)
        assertFalse(registry.access.value.actionsAllowed)
        registry.setConsent(true)
        assertTrue(registry.access.value.ready)
        assertFalse(registry.access.value.actionsAllowed)
        assertTrue(ScreenActionToolExecutor(registry, { ScreenActionAuthority(Authority.SHIZUKU, 1) }, { true }).specs.isEmpty())
        if (realAuthority) {
            registry.setActionsAllowed(true)
            assertNotNull(integration.screenActionAuthority())
            integration.selectAuthority(Authority.NONE)
            assertNull(integration.screenActionAuthority())
            assertTrue(ScreenActionToolExecutor(registry, integration::screenActionAuthority, { true }).specs.isEmpty())
            integration.selectAuthority(Authority.WIRED_ADB)
            assertNull("A ready unselected Shizuku must not substitute for Wired ADB", integration.screenActionAuthority())
        }
    }

    @Test fun navigationConsumesObservationAndDoesNotRepeat() = runBlocking {
        val snapshot = observe()
        val home = call(snapshot, "home")
        act(home)
        withTimeout(5000) {
            while (true) {
                val current = try { registry.capture(null) } catch (_: ScreenObservationException) { null }
                if (current != null && current.packageName != fixturePackage) break
                delay(80)
            }
        }
        assertTrue(executor.invoke(home) is ToolResult.Value)
        assertTrue(executor.approve(home.callId) is ToolResult.Invalid)
    }

    @Test fun compareAcknowledgedClicksWithLocalShellInput() = runBlocking {
        val accessibilityMs = mutableListOf<Long>(); val inputMs = mutableListOf<Long>()
        var expectedClicks = 0
        repeat(12) { iteration ->
            var snapshot = observe()
            var node = snapshot.nodes.first { it.description == "tap-target" }
            val request = call(snapshot, "click", node)
            assertEquals(ToolResult.NeedsApproval, executor.invoke(request))
            var started = SystemClock.elapsedRealtimeNanos()
            assertTrue(executor.approve(request.callId) is ToolResult.Value)
            val accessibilityDispatchMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
            expectedClicks++
            snapshot = observe { s -> s.nodes.any { it.text == "clicks=$expectedClicks longs=0" } }
            node = snapshot.nodes.first { it.description == "tap-target" }
            val x = (node.bounds[0] + node.bounds[2]) / 2; val y = (node.bounds[1] + node.bounds[3]) / 2
            started = SystemClock.elapsedRealtimeNanos()
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input tap $x $y")).use { it.readBytes() }
            val inputDispatchMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
            expectedClicks++
            observe { s -> s.nodes.any { it.text == "clicks=$expectedClicks longs=0" } }
            if (iteration >= 2) { accessibilityMs += accessibilityDispatchMs; inputMs += inputDispatchMs }
        }
        val json = buildJsonObject {
            put("api", Build.VERSION.SDK_INT); put("samples", accessibilityMs.size)
            put("authority_mode", if (realAuthority) "production_shizuku" else "fixture")
            put("accessibility_median_ms", accessibilityMs.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2.0 })
            put("local_shell_input_median_ms", inputMs.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2.0 })
            putJsonArray("accessibility_ms") { accessibilityMs.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("local_shell_input_ms") { inputMs.forEach { add(JsonPrimitive(it)) } }
            put("verified_clicks", expectedClicks)
        }.toString()
        Log.i("ScreenActionBenchmark", json)
        assertEquals(24, expectedClicks)
    }
}
