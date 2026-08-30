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

    private class MemoryPreferences : DiagnosticPreferenceStore {
        private var enabled = false
        override fun isEnabled(): Boolean = enabled
        override fun setEnabled(enabled: Boolean) { this.enabled = enabled }
    }
}
