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
    fun runtimeCapabilitySummaryDoesNotInventShellOrArbitraryFiles() {
        val summary = runtimeCapabilitySummary(listOf("web_search", "py_fixture_run", "read_document"))
        assertTrue(summary.contains("web_search"))
        assertTrue(summary.contains("isolated Python"))
        assertTrue(summary.contains("PowerShell=unsupported"))
        assertTrue(summary.contains("arbitrary filesystem=unsupported"))
        assertTrue(summary.contains("books_kb.py"))
        assertTrue(summary.contains("do not claim"))
        assertEquals(summary, runtimeCapabilitySummary(listOf("read_document", "py_fixture_run", "web_search")))
    }
}
