// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatSessionWorkspaceLabelsTest {
    @Test
    fun emptySessionsDoNotReadBindingsOrPresentations() {
        assertTrue(chatSessionWorkspaceLabels(
            emptyList(), { error("unexpected binding read") }, { error("unexpected presentation read") },
        ).isEmpty())
    }

    @Test
    fun largeSessionListReadsEachBindingButOnlyOneTitlePerWorkspace() {
        val ids = (0 until 1_201).map { "session.$it" }
        val bindingReads = mutableListOf<String>()
        val presentationReads = mutableListOf<String>()
        val labels = chatSessionWorkspaceLabels(
            ids,
            { id -> bindingReads += id; "workspace." + id.substringAfterLast('.').toInt() % 5 },
            { workspace -> presentationReads += workspace; "Title $workspace" },
        )
        assertEquals(ids, bindingReads)
        assertEquals(5, presentationReads.size)
        assertEquals(5, presentationReads.distinct().size)
        assertEquals(ids.associateWith { "Title workspace." + it.substringAfterLast('.').toInt() % 5 }, labels)
    }

    @Test
    fun unboundThreadsAndNullOrBlankTitlesKeepTheirMeaning() {
        val reads = mutableListOf<String>()
        val labels = chatSessionWorkspaceLabels(
            listOf("unbound", "missing-title", "blank-title"),
            { if (it == "unbound") null else it },
            { reads += it; if (it == "blank-title") "" else null },
        )
        assertEquals(mapOf("unbound" to "无工作区", "missing-title" to "已绑定工作区", "blank-title" to ""), labels)
        assertEquals(listOf("missing-title", "blank-title"), reads)
    }

    @Test
    fun bindingFailureIsLocalAndPresentationFailureIsCachedOnlyWithinThisRefresh() {
        val reads = mutableListOf<String>()
        val labels = chatSessionWorkspaceLabels(
            listOf("bad-binding", "bad-title.1", "good", "bad-title.2"),
            { if (it == "bad-binding") error("binding unavailable") else if (it.startsWith("bad-title")) "bad" else "good" },
            { reads += it; if (it == "bad") error("presentation unavailable") else "Good title" },
        )
        listOf("bad-binding", "bad-title.1", "bad-title.2").forEach {
            assertEquals("工作区状态不可用", labels.getValue(it))
        }
        assertEquals("Good title", labels.getValue("good"))
        assertEquals(listOf("bad", "good"), reads)
    }

    @Test
    fun newRefreshObservesRenameRemovalAndRecoveredPresentation() {
        var currentTitle: String? = "Original"
        var unavailable = false
        val binding: (String) -> String? = { "workspace" }
        val title: (String) -> String? = { if (unavailable) error("offline") else currentTitle }
        fun label() = chatSessionWorkspaceLabels(listOf("session"), binding, title).getValue("session")
        assertEquals("Original", label())
        currentTitle = "Renamed"
        assertEquals("Renamed", label())
        currentTitle = null
        assertEquals("已绑定工作区", label())
        unavailable = true
        assertEquals("工作区状态不可用", label())
        unavailable = false
        currentTitle = "Recovered"
        assertEquals("Recovered", label())
    }
}
