// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.resident

import android.content.Context
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
import runtime.mobileagent.wired.WiredAdbResult

/** Real selected-directory locator recovery across an explicitly replaced shell daemon. */
@RunWith(AndroidJUnit4::class)
class ResidentAdbDirectoryReattachDeviceTest {
    @Test fun selectedDirectoryLocatorReopensAfterDaemonReplacementAndOldHandleFails() = runBlocking {
        assumeTrue("Explicit disposable-device activation required", InstrumentationRegistry.getArguments().getString("requireResident") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = ResidentAdbAuthorityBridge.create(context)
        var failure: Throwable? = null
        var locator: WorkspaceRecoveryLocator? = null
        try {
            bridge.forget()
            activate(bridge, context)
            val generation = bridge.refresh().serviceSessionId
            val provider = bridge.createWorkspaceProvider()
            val directory = ResidentAdbDirectoryFixture.downloads(context, provider.directoryBrowser)
            val id = "resident-directory-${UUID.randomUUID()}"
            val attachment = (provider.attachDirectory(WorkspaceAttachRequest(id, "Resident locator fixture", directory))
                as WorkspaceResult.Success).value
            locator = requireNotNull(attachment.recoveryLocator)
            val path = "resident-locator-${UUID.randomUUID()}.txt"
            assertTrue(attachment.backend.writeText(WorkspaceWriteTextRequest(id, path, "resident locator proof", false)) is WorkspaceResult.Success)
            bridge.forget()
            assertTrue("Destroyed daemon must reject the existing token", attachment.backend.readText(WorkspaceReadTextRequest(id, path, 1024)) is WorkspaceResult.Failure)
            activate(bridge, context)
            assertNotEquals("Replacement must use a new authenticated generation", generation, bridge.refresh().serviceSessionId)
            assertTrue("Old daemon token must stay rejected", attachment.backend.readText(WorkspaceReadTextRequest(id, path, 1024)) is WorkspaceResult.Failure)
            val nextProvider = bridge.createWorkspaceProvider()
            val reopened = (nextProvider.reattachDirectory(WorkspaceReattachRequest(id, "Resident locator fixture", requireNotNull(locator)))
                as WorkspaceResult.Success).value
            try {
                val read = reopened.backend.readText(WorkspaceReadTextRequest(id, path, 1024)) as WorkspaceResult.Success
                assertEquals("resident locator proof", read.value.text)
                assertTrue(reopened.backend.delete(WorkspaceDeleteRequest(id, path)) is WorkspaceResult.Success)
            } finally { reopened.recoveryLocator?.clear(); nextProvider.close(); provider.close() }
        } catch (original: Throwable) { failure = original; throw original }
        finally {
            locator?.clear()
            try { bridge.forget() } catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: throw cleanup }
            finally { bridge.close() }
        }
    }

    private suspend fun activate(bridge: ResidentAdbAuthorityBridge, context: Context) {
        val prompt = (bridge.requestPairingFromForeground(true) as WiredAdbResult.Success).value
        val token = prompt.tokenDisplay().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val secret = ByteArray(ResidentAdbProtocol.SECRET_BYTES).also(SecureRandom()::nextBytes)
        try { ResidentAdbDeviceActivationFixture.launch(InstrumentationRegistry.getInstrumentation().uiAutomation, context, token, secret) }
        finally { token.fill(0); secret.fill(0) }
        assertTrue("Replacement daemon must authenticate", bridge.connect() is WiredAdbResult.Success)
    }
}
