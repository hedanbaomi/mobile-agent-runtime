// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.resident

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.skills.tooling.*
import runtime.mobileagent.wired.*

/** Run seed, host force-stop the target app, then reconnect or revoke in a new instrumentation. */
@RunWith(AndroidJUnit4::class)
class ResidentAdbProcessRestartDeviceTest {
    private val directory = "resident-process-restart-proof"
    private val file = "$directory/proof.txt"
    private val text = "own shell service survives app process death"

    private fun phase(required: String) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit disposable-device activation", args.getString("requireResident") == "true")
        assumeTrue("Other phase", args.getString("residentPhase") == required)
        assumeTrue("Binary stdin requires API31", Build.VERSION.SDK_INT >= 31)
    }

    private fun launch(context: Context, bridge: ResidentAdbAuthorityBridge) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val prompt = bridge.requestPairingFromForeground(true) as WiredAdbResult.Success<WiredAdbPairingPrompt>
        val token = prompt.value.tokenDisplay().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        try { ResidentAdbDeviceActivationFixture.launch(automation, context, token, secret) }
        finally { token.fill(0); secret.fill(0) }
    }

    @Test fun seedResidentBeforeHostKillsAppProcess() = runBlocking {
        phase("seed")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val uid = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("id -u")).use {
            it.readBytes().toString(Charsets.US_ASCII).trim()
        }
        assertEquals("2000", uid)
        val bridge = ResidentAdbAuthorityBridge.create(context, shellPermission = { true })
        bridge.forget()
        launch(context, bridge)
        assertTrue(bridge.connect() is WiredAdbResult.Success)
        val backend = bridge.createWorkspaceBackend()
        // This exact public fixture may remain from an interrupted previous dedicated-device run.
        backend.delete(WorkspaceDeleteRequest("wired-adb", file))
        backend.delete(WorkspaceDeleteRequest("wired-adb", directory))
        assertTrue(backend.createDirectory(WorkspaceCreateDirectoryRequest("wired-adb", directory)) is WorkspaceResult.Success)
        assertTrue(backend.writeText(WorkspaceWriteTextRequest("wired-adb", file, text, false)) is WorkspaceResult.Success)
        bridge.close()
        // Intentionally leave encrypted consent + resident daemon for the next host-separated phase.
    }

    @Test fun reconnectAfterHostKillsAppProcess() = runBlocking {
        phase("reconnect")
        val bridge = ResidentAdbAuthorityBridge.create(ApplicationProvider.getApplicationContext<Context>(), shellPermission = { true })
        try {
            assertTrue("Must reauthenticate solely from surviving daemon and Keystore credential", bridge.connect() is WiredAdbResult.Success)
            val shell = bridge.executeShell(bridge.newShellRequest("id -u")) as WiredAdbResult.Success<WiredAdbShellResult>
            assertEquals("2000", shell.value.stdout.toString(Charsets.US_ASCII).trim())
            val backend = bridge.createWorkspaceBackend()
            val read = backend.readText(WorkspaceReadTextRequest("wired-adb", file, 1024)) as WorkspaceResult.Success<WorkspaceText>
            assertEquals(text, read.value.text)
            assertTrue(backend.delete(WorkspaceDeleteRequest("wired-adb", file)) is WorkspaceResult.Success)
            assertTrue(backend.delete(WorkspaceDeleteRequest("wired-adb", directory)) is WorkspaceResult.Success)
        } finally { bridge.forget(); bridge.close() }
    }

    @Test fun revokeColdRegistryBeforeConnectingThenPrepareFreshActivation() = runBlocking {
        phase("revoke")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = ResidentAdbAuthorityBridge.create(context, shellPermission = { true })
        try {
            // No connect() call: revocation itself must recover the Binder before deleting consent.
            bridge.forget()
            assertFalse(bridge.refresh().trusted)
            launch(context, bridge)
            assertTrue("Cold revocation must release discovery so new activation succeeds", bridge.connect() is WiredAdbResult.Success)
            val backend = bridge.createWorkspaceBackend()
            backend.delete(WorkspaceDeleteRequest("wired-adb", file))
            backend.delete(WorkspaceDeleteRequest("wired-adb", directory))
            bridge.forget()
        } finally { bridge.close() }
    }

    @Test fun actualDaemonLossRetainsConsentFailsClosedAndAllowsFreshActivation() = runBlocking {
        phase("lost")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = ResidentAdbAuthorityBridge.create(context, shellPermission = { true })
        try {
            // Host kills only the identified own shell daemon between seed and this phase.
            assertTrue(bridge.connect() is WiredAdbResult.Failure)
            val state = bridge.refresh()
            assertTrue("Actual process loss must preserve durable user consent", state.trusted)
            assertEquals(WiredAdbUserIntent.ENABLED, state.userIntent)
            assertEquals(WiredAdbPlatformGrant.GRANTED, state.platformGrant)
            assertEquals(WiredAdbAvailability.TEMPORARILY_UNAVAILABLE, state.availability)
            assertEquals(WiredAdbConnectionState.DISCONNECTED, state.connection)
            assertNotEquals(WiredAdbLifecycleState.READY, state.state)
            assertTrue(bridge.executeShell(bridge.newShellRequest("id -u")) is WiredAdbResult.Failure)
            val typed = bridge.createWorkspaceBackend().readText(WorkspaceReadTextRequest("wired-adb", file, 1024))
            assertTrue(typed is WorkspaceResult.Failure)
            assertEquals(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE, (typed as WorkspaceResult.Failure).error.code)
            bridge.forget()
            assertFalse(bridge.refresh().trusted)
            launch(context, bridge)
            assertTrue("Real daemon loss permits a fresh authenticated activation", bridge.connect() is WiredAdbResult.Success)
            val backend = bridge.createWorkspaceBackend()
            backend.delete(WorkspaceDeleteRequest("wired-adb", file))
            backend.delete(WorkspaceDeleteRequest("wired-adb", directory))
            bridge.forget()
        } finally { bridge.close() }
    }
}
