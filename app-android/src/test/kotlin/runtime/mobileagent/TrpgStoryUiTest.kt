// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.trpg.*
import runtime.mobileagent.ui.StoryTrackDraft
import runtime.mobileagent.ui.buildStoryTrack
import runtime.mobileagent.ui.parseStoryInt
import runtime.mobileagent.ui.reconcileStory
import runtime.mobileagent.ui.storyEditorSubmitRevision
import runtime.mobileagent.ui.storyForRole
import runtime.mobileagent.ui.validateStoryTrackDraft

class TrpgStoryUiTest {
    private val chapter = TrpgStoryChapter("ch1", "Chapter One")
    private val session = TrpgStorySession("se1", "ch1", "Session One")
    private val clock = TrpgTrack("clock", TrpgTrackKind.CLOCK, "Doom", goal = 10, progress = 4)
    private val quest = TrpgTrack("quest", TrpgTrackKind.QUEST, "Find the key")
    private val secret = TrpgTrack("secret", TrpgTrackKind.CLUE, "Hidden clue", visibility = TrpgVisibility.GAME_MASTER)
    private val story = TrpgStory(
        campaignName = "The Long Road", chapters = listOf(chapter), sessions = listOf(session),
        tracks = listOf(clock, quest, secret), activeChapterId = "ch1", activeSessionId = "se1",
    )

    private fun draft(progress: String = "4", goal: String = "10") = StoryTrackDraft(
        id = clock.id, kind = clock.kind, title = "Doom", description = "The ritual advances",
        status = TrpgTrackStatus.ACTIVE, progress = progress, goal = goal, sourceMessageId = null,
        visibility = TrpgVisibility.PLAYER,
    )

    @Test fun playerRoleUsesTheFilteredProjectionAndGmRoleSeesEverything() {
        val player = storyForRole(story, gameMasterView = false)
        assertEquals(listOf("clock", "quest"), player.tracks.map { it.id })
        assertEquals("ch1", player.activeChapterId)
        assertEquals("se1", player.activeSessionId)
        val gm = storyForRole(story, gameMasterView = true)
        assertEquals(story, gm)
        assertTrue(gm.tracks.any { it.id == "secret" })
    }

    @Test fun externalRevisionNeverOverwritesAnOpenEditorDraft() {
        val incoming = story.copy(campaignName = "Changed", tracks = listOf(quest))
        val held = reconcileStory(story, 3, incoming, 4, editorOpen = true)
        assertEquals(story, held.story)
        assertEquals(3, held.baseRevision)
        assertTrue(held.conflict)

        val adopted = reconcileStory(story, 3, incoming, 4, editorOpen = false)
        assertEquals(incoming, adopted.story)
        assertEquals(4, adopted.baseRevision)
        assertFalse(adopted.conflict)

        // An equal or older host revision is never adopted, even without an editor.
        val stale = reconcileStory(story, 3, incoming, 3, editorOpen = false)
        assertEquals(story, stale.story)
        assertFalse(stale.conflict)
    }

    @Test fun editorSaveKeepsTheCapturedRevisionAndFlagsConflicts() {
        assertEquals(3L, storyEditorSubmitRevision(capturedRevision = 3, acceptedRevision = 3))
        assertNull(storyEditorSubmitRevision(capturedRevision = 3, acceptedRevision = 4))
        assertNull(storyEditorSubmitRevision(capturedRevision = 3, acceptedRevision = 2))
    }

    @Test fun numericDraftAllowsEmptyAndSignsButValidatesWithoutClamping() {
        assertNull(parseStoryInt(""))
        assertNull(parseStoryInt("  "))
        assertNull(parseStoryInt("-"))
        assertNull(parseStoryInt("+"))
        assertEquals(-2, parseStoryInt(" -2 "))
        assertEquals(999_999, parseStoryInt("999999"))
        assertNull(parseStoryInt("3.5"))
        assertNull(parseStoryInt("12x"))

        val empty = draft(progress = "")
        assertNotNull(validateStoryTrackDraft(empty, chinese = false))
        assertNull(buildStoryTrack(empty))
        assertEquals("", empty.progress)

        val negative = draft(progress = "-1")
        assertNotNull(validateStoryTrackDraft(negative, chinese = true))
        assertNull(buildStoryTrack(negative))
        assertEquals("-1", negative.progress)

        // Input is validated against 0..goal, never clamped to a different value.
        val overGoal = draft(progress = "11", goal = "10")
        assertNotNull(validateStoryTrackDraft(overGoal, chinese = false))
        assertNull(buildStoryTrack(overGoal))
        assertEquals("11", overGoal.progress)
    }

    @Test fun invalidGoalTitleAndSourceKeepTheDraftIntact() {
        val badGoal = draft(goal = "0")
        assertNotNull(validateStoryTrackDraft(badGoal, chinese = false))
        assertNull(buildStoryTrack(badGoal))

        val hugeGoal = draft(goal = "1001", progress = "0")
        assertNotNull(validateStoryTrackDraft(hugeGoal, chinese = false))
        assertNull(buildStoryTrack(hugeGoal))

        val blankTitle = draft().copy(title = "   ")
        assertNotNull(validateStoryTrackDraft(blankTitle, chinese = false))
        assertNull(buildStoryTrack(blankTitle))

        val longSource = draft().copy(sourceMessageId = "m".repeat(257))
        assertNotNull(validateStoryTrackDraft(longSource, chinese = false))
        assertNull(buildStoryTrack(longSource))

        val validSource = draft().copy(sourceMessageId = "message-1")
        assertNull(validateStoryTrackDraft(validSource, chinese = false))
        assertEquals("message-1", buildStoryTrack(validSource)?.sourceMessageId)
    }

    @Test fun validDraftBuildsTheExactTrack() {
        val built = buildStoryTrack(draft(progress = " 3 ", goal = " 10 ").copy(title = "  Doom  "))
        assertNotNull(built)
        assertEquals("Doom", built?.title)
        assertEquals(3, built?.progress)
        assertEquals(10, built?.goal)
        assertEquals("The ritual advances", built?.description)
        assertEquals(TrpgTrackStatus.ACTIVE, built?.status)
    }
}
