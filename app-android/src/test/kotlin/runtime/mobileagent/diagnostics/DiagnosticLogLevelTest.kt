// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.diagnostics

import java.io.File
import java.util.zip.ZipInputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DiagnosticLogLevelTest {
    private class Preferences(private var enabled: Boolean = true) : DiagnosticPreferenceStore {
        private var level = DiagnosticLevel.INFO
        override fun isEnabled(): Boolean = enabled
        override fun setEnabled(enabled: Boolean) { this.enabled = enabled }
        override fun logLevel(): DiagnosticLevel = level
        override fun setLogLevel(level: DiagnosticLevel) { this.level = level }
    }

    private fun store(root: File, preferences: DiagnosticPreferenceStore = Preferences(),
        minimumLevel: DiagnosticLevel? = null): RollingDiagnosticLogStore = RollingDiagnosticLogStore(
        rootDirectory = root,
        preferences = preferences,
        buildInfo = DiagnosticBuildInfo("test", false, 26, "2026-10-05T00:00:00Z", "test"),
        minimumLevel = minimumLevel,
    )

    private val vision = mapOf<String, Any?>(
        "requestRef" to "test-request", "kind" to "response_json", "chunk" to 0, "chunks" to 1,
        "originalChars" to 19, "capturedChars" to 19, "truncated" to false,
        "content" to "debug-body-sentinel",
    )
    private val progress = mapOf<String, Any?>(
        "kind" to "pdf", "stage" to "parsing", "completed" to 1, "total" to 2,
    )
    private fun log(root: File): String = File(root, RollingDiagnosticLogStore.CURRENT_FILE_NAME).readText()

    @Test
    fun infoDefaultKeepsLifecycleAndFailures(@TempDir root: File) {
        val logger = store(root)
        assertFalse(logger.isLevelEnabled(DiagnosticLevel.DEBUG))
        assertTrue(logger.recordProcessStarted())
        assertTrue(logger.record("runtime_tooling_unavailable", mapOf("errorCode" to "TOOL_EXECUTOR_FACTORY_UNAVAILABLE")))
        val lines = log(root).lines().filter(String::isNotBlank)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"level\":\"INFO\""))
        assertTrue(lines[1].contains("\"level\":\"ERROR\""))
    }

    @Test
    fun infoSkipsDebugBodiesAndProgressWithoutDegradingHealth(@TempDir root: File) {
        val logger = store(root)
        assertFalse(logger.record("vision_debug_content", vision))
        assertFalse(logger.record("knowledge_import_progress", progress))
        assertEquals(0L, logger.status().sizeBytes)
        assertEquals(0L, logger.status().droppedEventCount)
        assertEquals(DiagnosticHealth.HEALTHY, logger.status().health)
        assertTrue(logger.recordProcessStarted())
        assertFalse(log(root).contains("debug-body-sentinel"))
        assertFalse(log(root).contains("\"level\":\"DEBUG\""))
    }

    @Test
    fun explicitDebugFixturesStillUseValidClosedRecords(@TempDir root: File) {
        val logger = store(root, minimumLevel = DiagnosticLevel.DEBUG)
        assertTrue(logger.record("vision_debug_content", vision))
        assertTrue(logger.record("knowledge_import_progress", progress))
        assertTrue(log(root).contains("debug-body-sentinel"))
        assertTrue(log(root).contains("\"level\":\"DEBUG\""))
    }

    @Test
    fun disabledDiagnosticsStayEmptyAndToggleRecordsRemainInfo(@TempDir root: File) {
        val logger = store(root, Preferences(false))
        assertFalse(logger.recordProcessStarted())
        assertFalse(logger.recordCrash(Thread.currentThread(), IllegalStateException("not logged")))
        assertEquals(0L, logger.status().sizeBytes)
        logger.setEnabled(true)
        assertTrue(logger.recordProcessStarted())
        logger.setEnabled(false)
        val before = log(root)
        assertFalse(logger.recordProcessStarted())
        assertEquals(before, log(root))
        assertEquals(2, before.lines().count { it.contains("\"event\":\"diagnostics_toggle\"") })
        assertFalse(before.contains("\"level\":\"DEBUG\""))
    }

    @Test
    fun infoFilteringDoesNotBypassTheFieldWhitelist(@TempDir root: File) {
        val logger = store(root)
        assertFalse(logger.record("process_started", mapOf("password" to "never-persist-this")))
        assertEquals(1L, logger.status().droppedEventCount)
        assertTrue(logger.recordProcessStarted())
        assertFalse(log(root).contains("never-persist-this"))
    }

    @Test
    fun userDebugChoiceSurvivesRecreationAndCanReturnToInfo(@TempDir root: File) {
        val preferences = Preferences(false)
        val logger = store(root, preferences)
        assertEquals(DiagnosticLevel.INFO, logger.logLevel)
        logger.setLogLevel(DiagnosticLevel.DEBUG)
        assertFalse(logger.isEnabled)
        assertEquals(0L, logger.status().sizeBytes)
        logger.setEnabled(true)
        assertTrue(logger.record("vision_debug_content", vision))
        val recreated = store(root, preferences)
        assertEquals(DiagnosticLevel.DEBUG, recreated.logLevel)
        assertTrue(recreated.record("knowledge_import_progress", progress))
        recreated.setLogLevel(DiagnosticLevel.INFO)
        val before = log(root)
        assertFalse(logger.record("vision_debug_content", vision))
        assertEquals(before, log(root))
        assertEquals(DiagnosticLevel.INFO, store(root, preferences).logLevel)
        val manifest = ZipInputStream(recreated.exportBytes().inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && entry.name != "manifest.json") entry = zip.nextEntry
            assertTrue(entry != null)
            zip.readBytes().toString(Charsets.UTF_8)
        }
        assertTrue(manifest.contains("\"activeLogLevel\":\"INFO\""))
        assertTrue(manifest.contains("earlier DEBUG records"))
    }
}
