// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.diagnostics

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsDeviceTest {
    @Test
    fun defaultDisabledDoesNotWriteAndOptInPersists() {
        withStore { directory, preferences ->
            val store = newStore(directory, preferences)
            assertFalse(store.isEnabled)
            assertFalse(store.recordCapabilityToggle("tools", true))
            assertFalse(store.recordDangerousModeChanged(true, DiagnosticDangerousModePolicy.AUTONOMOUS, "disabled-request"))
            assertFalse(
                store.recordShellExecutionState(
                    commandSha256 = "a".repeat(64),
                    terminalState = DiagnosticTerminalState.SUCCEEDED,
                    authority = DiagnosticAuthority.NONE,
                ),
            )
            assertEquals(0L, store.status().totalBytes)

            store.setEnabled(true)
            assertTrue(store.recordCapabilityToggle("tools", true))
            assertTrue(store.status().totalBytes > 0)
            store.setEnabled(false)
            val toggleLog = store.readFile(RollingDiagnosticLogStore.CURRENT_FILE_NAME).toString(Charsets.UTF_8)
            assertTrue(toggleLog.contains("\"event\":\"diagnostics_toggle\""))
            assertTrue(toggleLog.contains("\"enabled\":false"))
            assertFalse(store.recordCapabilityToggle("tools", false))
            store.setEnabled(true)

            val reopened = newStore(directory, preferences)
            assertTrue(reopened.isEnabled)
            assertTrue(reopened.status().totalBytes > 0)
        }
    }

    @Test
    fun rollingFilesAndExportStayBounded() {
        withStore { directory, preferences ->
            preferences.setEnabled(true)
            val store = newStore(directory, preferences)
            repeat(2_000) { index ->
                assertTrue(store.recordCapabilityToggle(if (index % 2 == 0) "tools" else "image", index % 3 == 0))
            }
            val status = store.status()
            assertTrue(status.currentBytes <= RollingDiagnosticLogStore.MAX_CURRENT_BYTES)
            assertTrue(status.previousBytes <= RollingDiagnosticLogStore.MAX_PREVIOUS_BYTES)
            assertTrue(status.lastCrashBytes <= RollingDiagnosticLogStore.MAX_LAST_CRASH_BYTES)
            assertTrue(status.totalBytes <= status.totalLimitBytes)
            assertTrue(store.exportBytes().size <= RollingDiagnosticLogStore.MAX_EXPORT_BYTES)
        }
    }

    @Test
    fun whitelistAndSanitizerRejectSensitiveValues() {
        withStore { directory, preferences ->
            preferences.setEnabled(true)
            val store = newStore(directory, preferences)
            val secret = "sk-testsecret123456"
            val stack = "RuntimeException $secret api_key=$secret https://example.test/path?token=$secret\n/data/user/0/com.example/files/knowledge.txt"
            assertTrue(store.record("uncaught_exception", mapOf("exceptionType" to "RuntimeException", "stack" to stack)))
            assertFalse(store.record("capability_toggle", mapOf("capability" to "tools", "enabled" to true, "modelId" to secret)))

            val log = store.readFile(RollingDiagnosticLogStore.CURRENT_FILE_NAME).toString(Charsets.UTF_8)
            assertFalse(log.contains(secret))
            assertFalse(log.contains("https://example.test"))
            assertFalse(log.contains("token=$secret"))
            assertFalse(log.contains("/data/user/0/com.example"))
            assertTrue(log.contains("[url-redacted]"))
            assertTrue(log.contains("[path-redacted]"))
            assertTrue(log.contains("\"level\":\"ERROR\""))
            assertFalse(log.substringBeforeLast('\n').contains('\n'))
        }
    }

    @Test
    fun exportContainsManifestAndOmitsCrashMessage() {
        withStore { directory, preferences ->
            preferences.setEnabled(true)
            val store = newStore(directory, preferences)
            val secret = "sk-exportsecret123456"
            store.recordCrash(Thread.currentThread(), RuntimeException("chat body $secret"))
            val entries = linkedMapOf<String, String>()
            ZipInputStream(ByteArrayInputStream(store.exportBytes())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            assertNotNull(entries["manifest.json"])
            assertTrue(entries["manifest.json"].orEmpty().contains("fingerprint"))
            assertTrue(entries.keys.contains(RollingDiagnosticLogStore.CURRENT_FILE_NAME))
            assertTrue(entries.keys.contains(RollingDiagnosticLogStore.LAST_CRASH_FILE_NAME))
            assertFalse(entries.values.any { it.contains(secret) })
            assertFalse(entries.values.any { it.contains("chat body") })
        }
    }

    @Test
    fun failedExportLeavesEvidenceAvailable() {
        withStore { directory, preferences ->
            preferences.setEnabled(true)
            val store = newStore(directory, preferences)
            store.recordCapabilityToggle("tools", true)
            val before = store.status().totalBytes
            try {
                store.exportTo(object : OutputStream() {
                    override fun write(value: Int) = throw IOException("destination closed")
                })
            } catch (_: IOException) {
                // Expected: the caller's destination failed.
            }
            assertEquals(before, store.status().totalBytes)
        }
    }

    @Test
    fun knowledgeSkillAndBatchEventsUseWhitelistedCountsAndThrottleProgress() {
        withStore { directory, preferences ->
            preferences.setEnabled(true)
            val store = newStore(directory, preferences)
            val gate = DiagnosticProgressGate(interval = 10)
            assertTrue(gate.shouldRecord("copying", 1, 25))
            assertFalse(gate.shouldRecord("copying", 2, 25))
            assertTrue(gate.shouldRecord("copying", 10, 25))
            assertFalse(gate.shouldRecord("copying", 10, 25))
            assertTrue(gate.shouldRecord("queued", 11, 25))
            assertTrue(gate.shouldRecord("queued", 25, 25))

            assertTrue(store.recordKnowledgeImportStart("FOLDER", 25))
            assertTrue(store.recordKnowledgeImportProgress("FOLDER", "copying", 10, 25))
            assertTrue(store.recordKnowledgeImportEnqueued("FOLDER", 10))
            assertTrue(store.recordKnowledgeImportStaged("FOLDER", 25))
            assertTrue(store.recordKnowledgeImportFailed("FOLDER", "copying", 2, IllegalArgumentException("secret path")))
            assertTrue(store.recordSkillInspectSuccess(2))
            assertTrue(store.recordSkillInspectFailed(1, IllegalStateException("skill name")))
            assertTrue(store.recordSkillInstallSuccess())
            assertTrue(store.recordSkillInstallFailed(errorCode = "rejected"))
            assertTrue(store.recordBatchWorkerStart())
            assertTrue(store.recordBatchWorkerComplete())
            assertTrue(store.recordBatchWorkerFailed(IllegalStateException("provider response")))
            assertFalse(store.record("knowledge_import_start", mapOf("kind" to "FOLDER", "stage" to "staging", "total" to 1, "uri" to "content://secret")))

            val log = (store.readFile(RollingDiagnosticLogStore.CURRENT_FILE_NAME) + store.readFile(RollingDiagnosticLogStore.PREVIOUS_FILE_NAME))
                .toString(Charsets.UTF_8)
            listOf(
                "knowledge_import_start", "knowledge_import_progress", "knowledge_import_enqueued",
                "knowledge_import_staged", "knowledge_import_failed", "skill_inspect_success",
                "skill_inspect_failed", "skill_install_success", "skill_install_failed",
                "batch_worker_start", "batch_worker_complete", "batch_worker_failed",
            ).forEach { event -> assertTrue("missing $event", log.contains("\"event\":\"$event\"")) }
            assertTrue(log.contains("\"errorCode\":\"validation\""))
            assertFalse(log.contains("content://secret"))
            assertFalse(log.contains("provider response"))
            assertFalse(log.contains("skill name"))
        }
    }

    @Test
    fun damagedRotationTargetFailsClosedWithoutCrashingCaller() {
        withStore { directory, preferences ->
            preferences.setEnabled(true)
            assertTrue(directory.mkdirs())
            File(directory, RollingDiagnosticLogStore.CURRENT_FILE_NAME).writeBytes(
                ByteArray(RollingDiagnosticLogStore.MAX_CURRENT_BYTES + 1) { 'x'.code.toByte() },
            )
            val blockedPrevious = File(directory, RollingDiagnosticLogStore.PREVIOUS_FILE_NAME)
            assertTrue(blockedPrevious.mkdirs())
            File(blockedPrevious, "marker").writeText("occupied")

            val store = newStore(directory, preferences)
            assertFalse(store.recordCapabilityToggle("tools", true))
            val status = store.status()
            assertTrue(status.currentBytes <= RollingDiagnosticLogStore.MAX_CURRENT_BYTES)
            assertTrue(status.totalBytes <= status.totalLimitBytes)
        }
    }

    @Test
    fun uncaughtHandlerDelegatesAndClearRemovesOwnedFiles() {
        withStore { directory, preferences ->
            preferences.setEnabled(true)
            val store = newStore(directory, preferences)
            var delegated = false
            val handler = DiagnosticUncaughtExceptionHandler(store) { _, _ -> delegated = true }
            handler.uncaughtException(Thread.currentThread(), IllegalStateException("not persisted"))
            assertTrue(delegated)
            assertTrue(store.status().lastCrashBytes > 0)

            store.clear()
            val status = store.status()
            assertEquals(0L, status.totalBytes)
            assertFalse(File(directory, RollingDiagnosticLogStore.CURRENT_FILE_NAME).exists())
            assertFalse(File(directory, RollingDiagnosticLogStore.LAST_CRASH_FILE_NAME).exists())
        }
    }

    @Test
    fun v2TypedEventsAreClosedAndHashUserControlledReferences() {
        withStore { directory, preferences ->
            preferences.setEnabled(true)
            val store = newStore(directory, preferences)
            val secret = "sk-diagnostic-v2-secret"
            val path = "C:\\Users\\private\\workspace\\secret.txt"
            val uri = "content://com.example.documents/tree/private"
            val command = "rm -rf $path"
            val stdout = "stdout-secret-$secret"
            val agentId = "agent-$secret"
            val skillId = "skill-$path"
            val workspaceId = "workspace-$uri"
            val callId = "call-$secret"
            val approvalId = "approval-$path"

            assertFalse(
                store.record(
                    "authority_selection_changed",
                    mapOf("selectedAuthority" to "shizuku", "unknownField" to secret),
                ),
            )
            assertTrue(
                store.record(
                    "runtime_tooling_unavailable",
                    mapOf("errorCode" to "TOOL_EXECUTION_CONTEXT_UNAVAILABLE"),
                ),
            )
            assertFalse(
                store.record(
                    "runtime_tooling_unavailable",
                    mapOf("errorCode" to "UNSAFE_UNKNOWN_CODE", "prompt" to secret),
                ),
            )
            assertTrue(
                store.recordRuntimeToolingUnavailable(
                    RuntimeToolingUnavailableCode.TOOL_EXECUTOR_FACTORY_UNAVAILABLE,
                    sessionRef = "runtime-session-$secret",
                    runRef = "runtime-run-$path",
                ),
            )
            assertTrue(
                store.recordAuthorityConfigurationState(
                    AuthorityConfigurationStateRecord(
                        authority = DiagnosticAuthority.SHIZUKU,
                        userIntentEnabled = true,
                        selected = true,
                        platformGrant = DiagnosticPlatformGrant.GRANTED,
                        availability = DiagnosticAvailability.READY,
                        connection = DiagnosticConnection.CONNECTED,
                        configured = true,
                        reason = DiagnosticAuthorityConfigurationReason.USER_ACTION,
                    ),
                ),
            )
            assertTrue(
                store.recordDangerousModeDecision(
                    DangerousModeDecisionRecord(
                        requestedPolicy = DiagnosticDangerousModePolicy.AUTONOMOUS,
                        accepted = false,
                        buildAllowed = false,
                        buildKnown = true,
                        authority = DiagnosticAuthority.SHIZUKU,
                        reason = DiagnosticDangerousModeDecisionReason.BUILD_DENIED,
                    ),
                ),
            )
            assertTrue(
                store.recordRuntimeToolExposure(
                    RuntimeToolExposureRecord(
                        agentId = agentId,
                        sessionRef = "tool-session-$secret",
                        runRef = "tool-run-$path",
                        effectiveGrantCount = 0,
                        snapshotBindingCount = 0,
                        exposedToolCount = 0,
                        workspaceToolCount = 4,
                        shellToolCount = 1,
                        registeredWorkspaceCount = 3,
                        grantedWorkspaceCount = 1,
                        boundWorkspaceCount = 1,
                        registeredGrantedWorkspaceCount = 1,
                        selectedAuthority = DiagnosticAuthority.SHIZUKU,
                        selectedAuthorityReady = true,
                        safGrantActive = true,
                        safBackendRegistered = true,
                        modelToolTransportEnabled = true,
                        reason = RuntimeToolExposureReason.EMPTY_EFFECTIVE_TOOL_SET,
                    ),
                ),
            )
            assertTrue(store.recordAuthoritySelectionChanged(DiagnosticAuthority.SHIZUKU, DiagnosticAuthority.NONE, "request-1"))
            assertTrue(store.recordAuthorityStateChanged(DiagnosticAuthority.SHIZUKU, DiagnosticAuthorityState.AVAILABLE))
            assertTrue(store.recordShizukuLifecycle(DiagnosticLifecycleState.READY, requestRef = "request-2"))
            assertTrue(store.recordWiredAdbLifecycle(DiagnosticLifecycleState.DISCONNECTED, "io", "request-3"))
            assertTrue(store.recordWorkspaceGrantChanged(workspaceId, DiagnosticGrantScope.READ_WRITE, true, "request-4"))
            assertTrue(store.recordWorkspaceOperationState(workspaceId, DiagnosticOperation.READ, DiagnosticOperationState.SUCCEEDED, 2, "request-5"))
            assertTrue(store.recordSkillMemoryOperationState(skillId, DiagnosticOperation.APPEND, DiagnosticOperationState.SUCCEEDED, 1, "request-6"))
            assertTrue(store.recordDangerousModeChanged(true, DiagnosticDangerousModePolicy.CONFIRM_HIGH_RISK, "request-7"))
            assertTrue(
                store.recordShellToolExposureChanged(
                    agentId,
                    DiagnosticExposureState.EXPOSED,
                    skillId,
                    DiagnosticAuthority.SHIZUKU,
                    "approved",
                    "request-8",
                ),
            )
            assertTrue(
                store.recordToolApprovalState(
                    callId = callId,
                    state = DiagnosticApprovalState.APPROVED,
                    approvalId = approvalId,
                    agentId = agentId,
                    skillId = skillId,
                    requestRef = "request-9",
                    reasonCode = "APPROVAL_REQUIRED",
                    capability = DiagnosticToolCapability.SHELL_EXECUTE,
                    authority = DiagnosticAuthority.SHIZUKU,
                    sessionRef = "approval-session-$secret",
                ),
            )
            assertTrue(
                store.recordShellExecutionState(
                    commandSha256 = command,
                    terminalState = DiagnosticTerminalState.SUCCEEDED,
                    authority = DiagnosticAuthority.SHIZUKU,
                    limitBucket = DiagnosticLimitBucket.SMALL,
                    stdoutBytes = 32,
                    stderrBytes = 4,
                    durationBucket = DiagnosticLimitBucket.TINY,
                    requestRef = "request-10",
                    callId = callId,
                    agentId = agentId,
                    skillId = skillId,
                ),
            )
            assertTrue(
                store.recordBridgeRequestState(
                    DiagnosticBridgeRequestState.COMPLETED,
                    DiagnosticAuthority.WIRED_ADB,
                    "request-11",
                    count = 1,
                ),
            )
            assertTrue(store.recordDiagnosticDropSummary(1, 2, 3, DiagnosticHealth.DEGRADED, "io"))

            val zipBytes = store.exportBytes()
            listOf(secret, path, uri, command, stdout, agentId, skillId, workspaceId, callId, approvalId).forEach { value ->
                assertFalse("sensitive marker persisted: $value", zipBytes.containsBytes(value.toByteArray(Charsets.UTF_8)))
            }
            val entries = zipEntries(zipBytes)
            val allText = entries.values.joinToString("\n")
            listOf(
                "authority_selection_changed", "authority_state_changed", "shizuku_lifecycle", "wired_adb_lifecycle",
                "workspace_grant_changed", "workspace_operation_state", "skill_memory_operation_state",
                "dangerous_mode_changed", "shell_tool_exposure_changed", "tool_approval_state", "shell_execution_state",
                "bridge_request_state", "diagnostic_drop_summary", "runtime_tooling_unavailable",
                "authority_configuration_state", "dangerous_mode_decision", "runtime_tool_exposure",
            ).forEach { event -> assertTrue("missing $event", allText.contains("\"event\":\"$event\"")) }
            assertTrue(allText.contains("\"level\":\"DEBUG\""))
            assertTrue(allText.contains("\"workspaceToolCount\":4"))
            assertTrue(allText.contains("\"shellToolCount\":1"))
            assertTrue(allText.contains("\"webToolCount\":0"))
            assertTrue(allText.contains("\"mcpToolCount\":0"))
            assertTrue(allText.contains("\"pythonToolCount\":0"))
            assertTrue(allText.contains("\"memoryToolCount\":0"))
            assertTrue(allText.contains("\"registeredWorkspaceCount\":3"))
            assertTrue(allText.contains("\"grantedWorkspaceCount\":1"))
            assertTrue(allText.contains("\"boundWorkspaceCount\":1"))
            assertTrue(allText.contains("\"registeredGrantedWorkspaceCount\":1"))
            assertTrue(allText.contains("\"selectedAuthority\":\"shizuku\""))
            assertTrue(allText.contains("\"selectedAuthorityReady\":true"))
            assertTrue(allText.contains("\"safGrantActive\":true"))
            assertTrue(allText.contains("\"safBackendRegistered\":true"))
            assertTrue(allText.contains("\"modelToolTransportEnabled\":true"))
            assertTrue(allText.contains("\"reasonCode\":\"approval_required\""))
            val references = Regex("\\\"(?:agentRef|skillRef|workspaceRef|callRef|approvalRef|requestRef)\\\":\\\"([0-9a-f]{32})\\\"")
                .findAll(allText)
                .map { it.groupValues[1] }
                .toList()
            assertTrue("expected hashed references", references.isNotEmpty())
            assertTrue(references.all { it.length == RollingDiagnosticLogStore.MAX_REFERENCE_LENGTH })
            assertFalse(allText.contains(agentId))
            assertFalse(allText.contains(workspaceId))
        }
    }

    @Test
    fun concurrentWritesRemainNdjsonAndRotationKeepsCompleteLines() {
        withStore { directory, preferences ->
            preferences.setEnabled(true)
            val store = newStore(directory, preferences)
            val executor = Executors.newFixedThreadPool(8)
            repeat(8) { worker ->
                executor.submit {
                    repeat(400) { index ->
                        store.recordCapabilityToggle(if ((worker + index) % 2 == 0) "tools" else "image", index % 3 == 0)
                    }
                }
            }
            executor.shutdown()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
            val status = store.status()
            assertTrue(status.currentBytes <= RollingDiagnosticLogStore.MAX_CURRENT_BYTES)
            assertTrue(status.previousBytes <= RollingDiagnosticLogStore.MAX_PREVIOUS_BYTES)
            listOf(RollingDiagnosticLogStore.CURRENT_FILE_NAME, RollingDiagnosticLogStore.PREVIOUS_FILE_NAME).forEach { name ->
                val text = store.readFile(name).toString(Charsets.UTF_8)
                text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    assertTrue("incomplete NDJSON line", line.startsWith("{") && line.endsWith("}"))
                    assertTrue(line.contains("\"schemaVersion\":2"))
                }
            }
        }
    }

    @Test
    fun ioFailureReturnsFalseAndDoesNotCrashCaller() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = File(context.cacheDir, "diagnostics-io-failure-${UUID.randomUUID()}")
        val preferences = MemoryPreferences().also { it.setEnabled(true) }
        try {
            path.writeText("not a directory")
            val store = newStore(path, preferences)
            assertFalse(store.recordCapabilityToggle("tools", true))
            val status = store.status()
            assertTrue(status.writeFailureCount > 0)
            assertTrue(status.health == DiagnosticHealth.DEGRADED)
        } finally {
            path.deleteRecursively()
        }
    }

    @Test
    fun androidPreferenceAdapterPersistsOptIn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "diagnostics-device-${UUID.randomUUID()}")
        val preferencesName = "diagnostics-device-${UUID.randomUUID()}"
        try {
            val first = AndroidDiagnosticLogger(context, directory, preferencesName)
            assertFalse(first.isEnabled)
            first.setEnabled(true)
            first.recordCapabilityToggle("image", true)
            val second = AndroidDiagnosticLogger(context, directory, preferencesName)
            assertTrue(second.isEnabled)
            assertTrue(second.status().sizeBytes > 0)
            second.clear()
        } finally {
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
            directory.deleteRecursively()
        }
    }

    private fun newStore(directory: File, preferences: MemoryPreferences): RollingDiagnosticLogStore =
        RollingDiagnosticLogStore(
            rootDirectory = directory,
            preferences = preferences,
            buildInfo = DiagnosticBuildInfo("test-revision", dirty = false, schemaVersion = 7, buildTimeUtc = "2026-01-01T00:00:00Z", fingerprint = "test-fingerprint"),
            sessionId = "test-session",
            nowUtc = { "2026-01-01T00:00:00.000Z" },
            processId = { 1234 },
            threadName = { "test-thread" },
        )

    private fun withStore(block: (File, MemoryPreferences) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "diagnostics-device-${UUID.randomUUID()}")
        val preferences = MemoryPreferences()
        try {
            block(directory, preferences)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun zipEntries(bytes: ByteArray): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return entries
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        return indices.any { start -> needle.indices.all { offset -> this[start + offset] == needle[offset] } }
    }

    private class MemoryPreferences : DiagnosticPreferenceStore {
        private var enabled = false
        override fun isEnabled(): Boolean = enabled
        override fun setEnabled(enabled: Boolean) { this.enabled = enabled }
    }
}
