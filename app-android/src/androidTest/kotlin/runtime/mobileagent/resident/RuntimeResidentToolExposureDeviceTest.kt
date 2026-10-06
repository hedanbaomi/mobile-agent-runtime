// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.resident

import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.MobileAgentApp
import runtime.mobileagent.domain.*
import runtime.mobileagent.diagnostics.DiagnosticAuthority
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.Availability
import runtime.mobileagent.skills.tooling.Connection
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryBrowser
import runtime.mobileagent.skills.tooling.WorkspaceBrowseRequest
import runtime.mobileagent.skills.tooling.WorkspaceAttachRequest
import runtime.mobileagent.integration.WorkspaceAccessResult

/** Disposable review-device acceptance: no synthetic authority, backend, build policy or HTTP. */
@RunWith(AndroidJUnit4::class)
class RuntimeResidentToolExposureDeviceTest {
    @Test fun canonicalWiredActivationExposesDurableAgentToolsAndRevokesOldExecutors() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit disposable review device required", arguments.getString("requireReview") == "true")
        assertTrue("Resident test requires binary shell stdin", Build.VERSION.SDK_INT >= 31)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MobileAgentApp
        assertEquals("Production consent gate must run in a nondebuggable review APK", 0,
            app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        app.ensureHostInitialized()
        val container = app.container
        val runtime = container.runtimeIntegration
        assertTrue("Pre-existing activation must be explicitly revoked", runtime.revokeWiredAdb().accepted)
        runtime.selectAuthority(Authority.WIRED_ADB)
        runtime.setUserIntent(Authority.WIRED_ADB, true)
        val prompt = runtime.requestWiredAdbPairingToken(true)
        assertTrue("Canonical activation request failed", prompt.accepted)
        val token = requireNotNull(prompt.prompt).tokenDisplay().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val secret = ByteArray(ResidentAdbProtocol.SECRET_BYTES).also(SecureRandom()::nextBytes)
        try {
            try { ResidentAdbDeviceActivationFixture.launch(instrumentation.uiAutomation, app, token, secret) }
            finally { token.fill(0); secret.fill(0); prompt.prompt?.clear() }
            assertTrue("Canonical activation completion failed", runtime.completeWiredAdbPairing().accepted)
            var ready = false
            repeat(100) {
                val status = runtime.refresh()
                if (status.selectedAuthority == Authority.WIRED_ADB && status.wiredAdb.availability == Availability.READY &&
                    status.wiredAdb.connection == Connection.CONNECTED) ready = true
                if (!ready) delay(50)
            }
            assertTrue("Production container Wired backend did not become ready", ready)
            assertTrue("Review build must permit actual Dangerous Mode", runtime.refresh().dangerousModeBuildAllowed)
            val danger = runtime.setDangerousMode(DangerousMode.ENABLED_AUTONOMOUS, confirmed = true)
            assertTrue("Canonical confirmed enable failed: reason=${danger.reason}, selected=${danger.snapshot.selectedAuthority}, " +
                "mode=${danger.snapshot.dangerousMode}, durable=${danger.snapshot.durableDangerousMode}, " +
                "buildAllowed=${danger.snapshot.dangerousModeBuildAllowed}, buildKnown=${danger.snapshot.dangerousModeBuildKnown}", danger.accepted)
            val suffix = UUID.randomUUID().toString().replace("-", "")
            val workspaceId = "workspace.resident-e2e.$suffix"
            val browser = object : WorkspaceDirectoryBrowser {
                override suspend fun root(maxEntries: Int) = runtime.browsePrivilegedRoot(Authority.WIRED_ADB, maxEntries)
                override suspend fun browse(request: WorkspaceBrowseRequest) = runtime.browsePrivileged(Authority.WIRED_ADB, request)
            }
            val selectedDirectory = ResidentAdbDirectoryFixture.downloads(app, browser)
            val target = WorkspaceTarget()
            val attached = runtime.attachPrivileged(Authority.WIRED_ADB,
                WorkspaceAttachRequest(workspaceId, "Resident acceptance directory", selectedDirectory),
                WorkspaceIntent.ADD_TO_LIBRARY.plan(target), target)
            assertTrue("User-equivalent canonical directory attach failed: $attached", attached is WorkspaceAccessResult.Success)
            assertTrue("Explicitly selected resident workspace must be registered", runtime.grants.listWorkspaces()
                .any { it.id == workspaceId && it.enabled && it.readable && it.writable })
            val providerId = "provider.resident-e2e.$suffix"
            val modelId = "model.resident-e2e.$suffix"
            val agentId = "agent.resident-e2e.$suffix"
            container.profiles.createProvider(ProviderProfile(id = providerId, name = "Resident fixture",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE, baseUrl = "https://example.invalid/v1",
                secretRef = "fixture-resident-$suffix", revision = 1))
            container.profiles.createModel(ModelProfile(modelId, providerId, ModelRole.CHAT, "fixture-resident",
                setOf("stream", "tools"), contextLimit = 4096, outputLimit = 512, revision = 1))
            container.agents.saveWithPrompt(AgentProfile(agentId, "Resident fixture", "pending", modelId, revision = 0),
                "Use explicitly selected ADB authority.")
            val workspaceCapabilities = listOf(CapabilityId.WORKSPACE_ENUMERATE, CapabilityId.FILE_LIST,
                CapabilityId.FILE_STAT, CapabilityId.FILE_READ_TEXT, CapabilityId.FILE_WRITE_TEXT,
                CapabilityId.FILE_CREATE_DIRECTORY, CapabilityId.FILE_MOVE, CapabilityId.FILE_DELETE)
            val policyVersion = runtime.grants.currentPolicyVersion()
            (workspaceCapabilities + CapabilityId.SHELL_EXECUTE).forEach { capability ->
                runtime.grants.saveGrant(CapabilityGrant(grantId = EntityId.random().value, agentId = agentId,
                    capability = CapabilityId(capability), workspaceId = workspaceId.takeIf { capability != CapabilityId.SHELL_EXECUTE },
                    lifetime = GrantLifetime.PERSISTENT, policyVersion = policyVersion, createdAt = Utc.nowIso()))
            }
            val snapshot = runtime.createSnapshotWithCurrentGrants(agentId)
            val context = runtime.createToolExecutionContext(snapshot = snapshot, modelCallId = "model-resident-$suffix",
                sessionIdentity = "session-resident-$suffix", taskIdentity = "task-resident-$suffix",
                configSnapshotHash = "config-resident-$suffix")
            val factory = runtime.createToolExecutorFactory(context)
            val names = factory.toolingSpecs.map { it.name }.toSet()
            assertTrue("Real production factory must expose authorized shell", "shell_exec" in names)
            assertTrue("Real production factory must expose resident workspace tools", "file_write_text" in names && "file_read_text" in names)
            val exposure = runtime.toolExposureDiagnostics(context)
            assertEquals(DiagnosticAuthority.WIRED_ADB, exposure.selectedAuthority)
            assertTrue(exposure.selectedAuthorityReady)
            assertEquals(9, runtime.grants.listSnapshotBindings(snapshot.id).size)
            val shell = factory.invoke(ToolCall("resident-id-$suffix", "shell_exec", """{"command":"id -u"}"""))
            assertTrue("Canonical Agent shell failed: $shell", shell is ToolResult.Value)
            val result = JSONObject((shell as ToolResult.Value).json)
            assertEquals(0, result.getInt("exit_code"))
            assertEquals("2000", result.getString("stdout").trim())
            val path = "runtime-resident-e2e-$suffix.txt"
            val proof = "resident-production-$suffix"
            var created = false
            try {
                val write = factory.invoke(ToolCall("resident-write-$suffix", "file_write_text",
                    """{"workspaceId":"$workspaceId","relativePath":"$path","text":"$proof","replace":false}"""))
                assertTrue("Canonical resident file write failed: $write", write is ToolResult.Value)
                created = true
                val read = factory.invoke(ToolCall("resident-read-$suffix", "file_read_text",
                    """{"workspaceId":"$workspaceId","relativePath":"$path","maxBytes":4096}"""))
                assertTrue("Canonical resident file read failed: $read", read is ToolResult.Value)
                assertTrue((read as ToolResult.Value).json.contains(proof))
            } finally {
                if (created) assertTrue(factory.invoke(ToolCall("resident-delete-$suffix", "file_delete",
                    """{"workspaceId":"$workspaceId","relativePath":"$path"}""")) is ToolResult.Value)
            }
            runtime.setUserIntent(Authority.WIRED_ADB, false)
            assertTrue("Disabled authority must block existing executor", factory.invoke(ToolCall("resident-disabled-$suffix",
                "shell_exec", """{"command":"id -u"}""")) !is ToolResult.Value)
            runtime.setUserIntent(Authority.WIRED_ADB, true)
            assertTrue("Explicit revoke must terminate resident authority", runtime.revokeWiredAdb().accepted)
            assertTrue("Revoked authority must block existing executor", factory.invoke(ToolCall("resident-revoked-$suffix",
                "shell_exec", """{"command":"id -u"}""")) !is ToolResult.Value)
        } finally {
            runtime.setDangerousMode(DangerousMode.DISABLED, confirmed = true)
            assertTrue("Acceptance cleanup must revoke resident daemon", runtime.revokeWiredAdb().accepted)
        }
    }
}
