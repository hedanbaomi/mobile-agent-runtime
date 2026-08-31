// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebSearchDeviceTest {
    @Test
    fun braveResponseBecomesBoundedUntrustedSearchResults() {
        val secret = "search-test-secret-never-persist"
        val raw = """{"web":{"results":[
            {"title":"One","url":"https://one.example/path","description":"First $secret"},
            {"title":"Bad","url":"http://private.example/","description":"Rejected"},
            {"title":"Two","url":"https://two.example/","description":"Second"}
        ]}}"""
        val result = parseBraveSearchResponse(raw, 2, listOf(secret))
        assertTrue(result.contains("\"untrusted\":true"))
        assertTrue(result.contains("https://one.example/path"))
        assertTrue(result.contains("https://two.example/"))
        assertFalse(result.contains("http://private.example"))
        assertFalse(result.contains(secret))
        assertTrue(result.contains("***"))
    }

    @Test
    fun runtimeCapabilitySummaryReportsNoToolsAsUnavailable() {
        val summary = runtimeCapabilitySummary(emptyList())
        assertTrue(summary.contains("Active tools: none"))
        assertTrue(summary.contains("web_search=unavailable in this run"))
        assertTrue(summary.contains("isolated Python=unavailable in this run"))
        assertTrue(summary.contains("workspace/file operations=unavailable in this run"))
        assertTrue(summary.contains("Skill memory=unavailable in this run"))
        assertTrue(summary.contains("shell_exec=unavailable in this run"))
        assertTrue(summary.contains("Host PowerShell"))
        assertTrue(summary.contains("Root"))
    }

    @Test
    fun runtimeCapabilitySummaryReportsTypedWorkspaceAndSkillMemory() {
        val summary = runtimeCapabilitySummary(
            listOf("file_read_text", "memory_append", "workspace_list", "file_read_text"),
        )
        assertTrue(summary.contains("workspace/file operations=available through authorized typed tools"))
        assertTrue(summary.contains("Skill memory=available only in bound Skill memory namespaces"))
        assertTrue(summary.contains("web_search=unavailable in this run"))
        assertTrue(summary.contains("shell_exec=unavailable in this run"))
    }

    @Test
    fun runtimeCapabilitySummaryReportsShellOnlyAfterSelectedAuthorityAndDangerousModeGates() {
        val summary = runtimeCapabilitySummary(listOf("shell_exec"))
        assertTrue(summary.contains("shell_exec=available through the selected Android authority and Dangerous Mode"))
        assertTrue(summary.contains("Host PowerShell, host shell, host filesystem"))
        assertTrue(summary.contains("Root"))
    }

    @Test
    fun runtimeCapabilitySummaryReportsPythonWebKnowledgeAndKeepsOrderDeterministic() {
        val summary = runtimeCapabilitySummary(listOf("read_document", "py_fixture_run", "web_search"))
        assertTrue(summary.contains("web_search=available with per-call approval"))
        assertTrue(summary.contains("isolated Python=available only through enabled, granted Class B Skill tools"))
        assertTrue(summary.contains("workspace/file operations=unavailable in this run"))
        assertTrue(summary.contains("Skill memory=unavailable in this run"))
        assertTrue(summary.contains("knowledge_search/read_document"))
        assertTrue(summary.contains("do not claim"))
        assertEquals(summary, runtimeCapabilitySummary(listOf("web_search", "py_fixture_run", "read_document")))
    }
}
