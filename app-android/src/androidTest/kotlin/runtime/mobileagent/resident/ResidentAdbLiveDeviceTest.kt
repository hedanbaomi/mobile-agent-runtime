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
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.skills.tooling.*
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.wired.*

/** Opt-in disposable-device test. It explicitly revokes this app's resident test activation. */
@RunWith(AndroidJUnit4::class)
class ResidentAdbLiveDeviceTest {
    @Test fun detachedShellActivationSurvivesClientCloseAndReattaches() = runBlocking {
        assumeTrue("Explicit disposable-device resident activation required",
            InstrumentationRegistry.getArguments().getString("requireResident") == "true")
        assumeTrue("Binary shell stdin requires API 31", Build.VERSION.SDK_INT >= 31)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val uid = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("id -u")).use {
            it.readBytes().toString(Charsets.US_ASCII).trim()
        }
        assertEquals("Test activation must use shell, never root", "2000", uid)
        var bridge = ResidentAdbAuthorityBridge.create(context, shellPermission = { true })
        var originalFailure: Throwable? = null
        try {
            bridge.forget()
            val prompt = bridge.requestPairingFromForeground(true) as WiredAdbResult.Success
            val token = prompt.value.tokenDisplay().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            try { ResidentAdbDeviceActivationFixture.launch(automation, context, token, secret) }
            finally { token.fill(0); secret.fill(0) }
            assertTrue("Detached service did not authenticate", bridge.connect() is WiredAdbResult.Success)
            val shell = bridge.executeShell(bridge.newShellRequest("id -u")) as WiredAdbResult.Success
            assertEquals(0, shell.value.exitCode)
            assertEquals("2000", shell.value.stdout.toString(Charsets.US_ASCII).trim())

            val guarded = ResidentAdbAuthorityBridge.create(context, shellPermission = { false })
            try {
                val request = ShellExecRequest.fromRuntime(
                    callId = "resident-permission-gate", command = "id -u", agentId = "resident-test-agent",
                    snapshotId = "resident-test-snapshot", selectedAuthority = Authority.WIRED_ADB,
                    dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK, policyVersion = 1L,
                    configSnapshotHash = "resident-test-config", sessionIdentity = "resident-test-session",
                )
                val denied = guarded.createShellExecutor().execute(request)
                assertEquals(ShellExecutionStatus.FAILED, denied.status)
                assertEquals(Authority.WIRED_ADB, denied.authority)
                assertEquals(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE, denied.error?.code)
                assertTrue(guarded.executeShell(guarded.newShellRequest("id -u")) is WiredAdbResult.Failure)
            } finally { guarded.close() }

            val backend = bridge.createWorkspaceBackend()
            val directory = "resident-test-${UUID.randomUUID()}"
            val file = "$directory/proof.txt"
            assertTrue(backend.createDirectory(WorkspaceCreateDirectoryRequest(backend.descriptor.id, directory)) is WorkspaceResult.Success)
            assertTrue(backend.writeText(WorkspaceWriteTextRequest(backend.descriptor.id, file, "resident proof", false)) is WorkspaceResult.Success)
            val read = backend.readText(WorkspaceReadTextRequest(backend.descriptor.id, file, 1024)) as WorkspaceResult.Success
            assertEquals("resident proof", read.value.text)

            bridge.disconnect()
            assertEquals("USB/client detach keeps the live grant", WiredAdbLifecycleState.READY, bridge.refresh().state)
            bridge.close()
            bridge = ResidentAdbAuthorityBridge.create(context, shellPermission = { true })
            assertTrue("Client recreation must reauthenticate without activation", bridge.connect() is WiredAdbResult.Success)
            val restoredRead = bridge.createWorkspaceBackend().readText(WorkspaceReadTextRequest("wired-adb", file, 1024)) as WorkspaceResult.Success
            assertEquals("resident proof", restoredRead.value.text)
            assertTrue(bridge.createWorkspaceBackend().delete(WorkspaceDeleteRequest("wired-adb", file)) is WorkspaceResult.Success)
            assertTrue(bridge.createWorkspaceBackend().delete(WorkspaceDeleteRequest("wired-adb", directory)) is WorkspaceResult.Success)
            bridge.forget()
            assertFalse(bridge.refresh().trusted)
            assertTrue(bridge.executeShell(bridge.newShellRequest("id -u")) is WiredAdbResult.Failure)
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            try { bridge.forget() }
            catch (cleanup: Throwable) { originalFailure?.addSuppressed(cleanup) ?: throw cleanup }
            finally { bridge.close() }
        }
    }
}
