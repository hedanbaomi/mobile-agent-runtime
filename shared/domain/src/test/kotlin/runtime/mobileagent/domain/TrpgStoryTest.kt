// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.trpg.*

class TrpgStoryTest {
    private val chapter = TrpgStoryChapter("ch1", "Chapter One", "The road south")
    private val session = TrpgStorySession("se1", "ch1", "Session One", "We arrived")
    private val quest = TrpgTrack("q1", TrpgTrackKind.QUEST, "Find the key")
    private val story = TrpgStory(
        campaignName = "The Long Road",
        chapters = listOf(chapter),
        sessions = listOf(session),
        tracks = listOf(quest),
        activeChapterId = "ch1",
        activeSessionId = "se1",
    )
    private val strict = Json { ignoreUnknownKeys = false; explicitNulls = false }

    private fun apply(value: TrpgStory, command: TrpgStoryCommand) = TrpgStoryReducer.apply(value, command)

    @Test fun defaultStoryIsEmptyAndValid() {
        val empty = TrpgStory()
        TrpgStoryValidation.validate(empty)
        assertEquals("", empty.campaignName)
        assertTrue(empty.chapters.isEmpty() && empty.sessions.isEmpty() && empty.tracks.isEmpty())
        assertNull(empty.activeChapterId)
        assertNull(empty.activeSessionId)
    }

    @Test fun renameAndUpsertAreDeterministicAndDoNotMutateTheInput() {
        val renamed = apply(story, TrpgStoryCommand(TrpgStoryOperation.RENAME_CAMPAIGN, campaignName = "Ashes"))
        assertEquals("Ashes", renamed.campaignName)
        assertEquals("The Long Road", story.campaignName)

        val clock = TrpgTrack("c1", TrpgTrackKind.CLOCK, "Ritual", goal = 6, progress = 2)
        val withClock = apply(renamed, TrpgStoryCommand(TrpgStoryOperation.UPSERT_TRACK, track = clock))
        assertEquals(2, withClock.tracks.size)
        assertEquals(2, withClock.tracks.single { it.id == "c1" }.progress)

        val edited = apply(withClock, TrpgStoryCommand(TrpgStoryOperation.UPSERT_TRACK,
            track = clock.copy(goal = 8, status = TrpgTrackStatus.ACTIVE)))
        assertEquals(8, edited.tracks.single { it.id == "c1" }.goal)
        assertEquals(TrpgTrackStatus.ACTIVE, edited.tracks.single { it.id == "c1" }.status)

        val renamedChapter = apply(edited, TrpgStoryCommand(TrpgStoryOperation.UPSERT_CHAPTER,
            chapter = chapter.copy(title = "Chapter Zero", visibility = TrpgVisibility.GAME_MASTER)))
        assertEquals("Chapter Zero", renamedChapter.chapters.single().title)
        TrpgStoryValidation.validate(renamedChapter)
    }

    @Test fun duplicatesOrphansAndCountLimitsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TrpgStoryValidation.validate(story.copy(chapters = listOf(chapter, chapter.copy(title = "Duplicate"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrpgStoryValidation.validate(story.copy(sessions = listOf(session, session.copy(title = "Duplicate"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrpgStoryValidation.validate(story.copy(tracks = listOf(quest, quest.copy(title = "Duplicate"))))
        }
        // A session or current pointer must never name a missing parent.
        assertThrows(IllegalArgumentException::class.java) {
            TrpgStoryValidation.validate(story.copy(sessions = listOf(session.copy(chapterId = "missing"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrpgStoryValidation.validate(story.copy(activeChapterId = "missing"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrpgStoryValidation.validate(story.copy(activeSessionId = "missing"))
        }
        // Upserting a session with an unknown chapter is rejected before any write.
        assertThrows(IllegalArgumentException::class.java) {
            apply(story, TrpgStoryCommand(TrpgStoryOperation.UPSERT_SESSION,
                session = session.copy(id = "se2", chapterId = "missing")))
        }
        // Same ids in different collections are distinct namespaces.
        val crossType = story.copy(chapters = listOf(chapter), sessions = listOf(session.copy(id = chapter.id)),
            activeSessionId = chapter.id)
        TrpgStoryValidation.validate(crossType)
    }

    @Test fun addingPastCollectionLimitsFails() {
        val chapters = (1..TrpgStoryValidation.MAX_CHAPTERS).map { TrpgStoryChapter("ch$it", "Chapter $it") }
        val fullChapters = story.copy(chapters = chapters, sessions = emptyList(), activeChapterId = null, activeSessionId = null)
        assertThrows(IllegalArgumentException::class.java) {
            apply(fullChapters, TrpgStoryCommand(TrpgStoryOperation.UPSERT_CHAPTER,
                chapter = TrpgStoryChapter("extra", "Extra")))
        }
        // Editing an existing entry still fits at the limit.
        apply(fullChapters, TrpgStoryCommand(TrpgStoryOperation.UPSERT_CHAPTER,
            chapter = chapters.first().copy(title = "Renamed")))

        val sessions = (1..TrpgStoryValidation.MAX_SESSIONS).map { TrpgStorySession("se$it", null, "Session $it") }
        val fullSessions = story.copy(sessions = sessions, activeSessionId = null)
        assertThrows(IllegalArgumentException::class.java) {
            apply(fullSessions, TrpgStoryCommand(TrpgStoryOperation.UPSERT_SESSION,
                session = TrpgStorySession("extra", null, "Extra")))
        }

        val tracks = (1..TrpgStoryValidation.MAX_TRACKS).map { TrpgTrack("t$it", TrpgTrackKind.CLUE, "Clue $it") }
        val fullTracks = story.copy(tracks = tracks)
        assertThrows(IllegalArgumentException::class.java) {
            apply(fullTracks, TrpgStoryCommand(TrpgStoryOperation.UPSERT_TRACK,
                track = TrpgTrack("extra", TrpgTrackKind.QUEST, "Extra")))
        }
        TrpgStoryValidation.validate(fullTracks)
    }

    @Test fun clockAdvanceHonoursZeroToGoalWithoutOverflowOrClamping() {
        val clock = TrpgTrack("clock", TrpgTrackKind.CLOCK, "Doom", progress = 9, goal = 10)
        val base = story.copy(tracks = listOf(clock))
        val full = apply(base, TrpgStoryCommand(TrpgStoryOperation.ADVANCE_TRACK, id = "clock", delta = 1))
        assertEquals(10, full.tracks.single().progress)
        assertThrows(IllegalArgumentException::class.java) {
            apply(full, TrpgStoryCommand(TrpgStoryOperation.ADVANCE_TRACK, id = "clock", delta = 1))
        }
        val empty = apply(full, TrpgStoryCommand(TrpgStoryOperation.ADVANCE_TRACK, id = "clock", delta = -10))
        assertEquals(0, empty.tracks.single().progress)
        assertThrows(IllegalArgumentException::class.java) {
            apply(empty, TrpgStoryCommand(TrpgStoryOperation.ADVANCE_TRACK, id = "clock", delta = -1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            apply(base, TrpgStoryCommand(TrpgStoryOperation.ADVANCE_TRACK, id = "clock", delta = 1000))
        }
        assertThrows(IllegalArgumentException::class.java) {
            apply(base, TrpgStoryCommand(TrpgStoryOperation.ADVANCE_TRACK, id = "clock", delta = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            apply(base, TrpgStoryCommand(TrpgStoryOperation.ADVANCE_TRACK, id = "clock", delta = Int.MAX_VALUE))
        }
        // Bounds are validated, never silently clamped.
        assertEquals(9, base.tracks.single().progress)
    }

    @Test fun removingParentsClearsReferencesAndLeavesNoDanglingIds() {
        val withSession = story.copy(activeChapterId = "ch1", activeSessionId = "se1")
        val removed = apply(withSession, TrpgStoryCommand(TrpgStoryOperation.REMOVE_CHAPTER, id = "ch1"))
        assertTrue(removed.chapters.isEmpty())
        assertNull(removed.sessions.single().chapterId)
        assertNull(removed.activeChapterId)
        assertEquals("se1", removed.activeSessionId)
        TrpgStoryValidation.validate(removed)

        val noSession = apply(removed, TrpgStoryCommand(TrpgStoryOperation.REMOVE_SESSION, id = "se1"))
        assertTrue(noSession.sessions.isEmpty())
        assertNull(noSession.activeSessionId)
        TrpgStoryValidation.validate(noSession)

        val noTrack = apply(noSession, TrpgStoryCommand(TrpgStoryOperation.REMOVE_TRACK, id = "q1"))
        assertTrue(noTrack.tracks.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            apply(noTrack, TrpgStoryCommand(TrpgStoryOperation.REMOVE_TRACK, id = "q1"))
        }
    }

    @Test fun onlyTheFieldsForTheChosenOperationAreAccepted() {
        val invalid = listOf(
            TrpgStoryCommand(TrpgStoryOperation.RENAME_CAMPAIGN, campaignName = "x", id = "extra"),
            TrpgStoryCommand(TrpgStoryOperation.UPSERT_TRACK, track = quest, id = "extra"),
            TrpgStoryCommand(TrpgStoryOperation.UPSERT_TRACK, track = quest, delta = 1),
            TrpgStoryCommand(TrpgStoryOperation.REMOVE_CHAPTER, campaignName = "x"),
            TrpgStoryCommand(TrpgStoryOperation.REMOVE_CHAPTER),
            TrpgStoryCommand(TrpgStoryOperation.ADVANCE_TRACK, id = "q1"),
            TrpgStoryCommand(TrpgStoryOperation.SET_ACTIVE_CHAPTER, id = "ch1"),
            TrpgStoryCommand(TrpgStoryOperation.SET_ACTIVE_SESSION, sessionId = "se1", delta = 1),
            TrpgStoryCommand(TrpgStoryOperation.UPSERT_CHAPTER, chapter = chapter, session = session),
        )
        invalid.forEach { command ->
            assertThrows(IllegalArgumentException::class.java, { apply(story, command) }, command.toString())
        }
        // Undefined JSON fields/operations fail decoding before they can reach the reducer.
        assertThrows(SerializationException::class.java) {
            strict.decodeFromString<TrpgStoryCommand>("""{"operation":"RENAME_CAMPAIGN","campaignName":"x","bogus":1}""")
        }
        assertThrows(SerializationException::class.java) {
            strict.decodeFromString<TrpgStoryCommand>("""{"operation":"FLY_AWAY"}""")
        }
    }

    @Test fun settingCurrentChapterAndSessionKeepsPointersCoherent() {
        val chapter2 = TrpgStoryChapter("ch2", "Chapter Two")
        val session2 = TrpgStorySession("se2", "ch2", "Session Two")
        val base = story.copy(chapters = listOf(chapter, chapter2), sessions = listOf(session, session2))
        val moved = apply(base, TrpgStoryCommand(TrpgStoryOperation.SET_ACTIVE_SESSION, sessionId = "se2"))
        assertEquals("se2", moved.activeSessionId)
        assertEquals("ch2", moved.activeChapterId)
        val switched = apply(moved, TrpgStoryCommand(TrpgStoryOperation.SET_ACTIVE_CHAPTER, chapterId = "ch1"))
        assertEquals("ch1", switched.activeChapterId)
        assertNull(switched.activeSessionId)
        val unassigned = TrpgStorySession("free", null, "Unassigned")
        val free = apply(switched.copy(sessions = switched.sessions + unassigned),
            TrpgStoryCommand(TrpgStoryOperation.SET_ACTIVE_SESSION, sessionId = "free"))
        assertEquals("free", free.activeSessionId)
        assertEquals("ch1", free.activeChapterId)
        val cleared = apply(free, TrpgStoryCommand(TrpgStoryOperation.SET_ACTIVE_SESSION, sessionId = null))
        assertNull(cleared.activeSessionId)
        TrpgStoryValidation.validate(cleared)
    }

    @Test fun activeChapterMustAgreeWithAChapterBoundCurrentSession() {
        val chapter2 = TrpgStoryChapter("ch2", "Chapter Two")
        val session2 = TrpgStorySession("se2", "ch2", "Session Two")
        assertThrows(IllegalArgumentException::class.java) {
            TrpgStoryValidation.validate(story.copy(chapters = listOf(chapter, chapter2),
                sessions = listOf(session, session2), activeChapterId = "ch2", activeSessionId = "se1"))
        }
        // An unassigned current session places no constraint on the current chapter.
        val free = TrpgStorySession("free", null, "Unassigned")
        val unassignedSession = story.copy(sessions = listOf(session, free), activeChapterId = "ch1",
            activeSessionId = "free")
        TrpgStoryValidation.validate(unassignedSession)
        TrpgStoryValidation.validate(unassignedSession.copy(activeChapterId = null))
    }

    @Test fun clearingCurrentChapterClearsOnlyAChapterBoundCurrentSession() {
        val cleared = apply(story, TrpgStoryCommand(TrpgStoryOperation.SET_ACTIVE_CHAPTER, chapterId = null))
        assertNull(cleared.activeChapterId)
        assertNull(cleared.activeSessionId)
        TrpgStoryValidation.validate(cleared)

        val free = TrpgStorySession("free", null, "Unassigned")
        val withFree = story.copy(sessions = story.sessions + free, activeSessionId = "free", activeChapterId = "ch1")
        val kept = apply(withFree, TrpgStoryCommand(TrpgStoryOperation.SET_ACTIVE_CHAPTER, chapterId = null))
        assertNull(kept.activeChapterId)
        assertEquals("free", kept.activeSessionId)
        TrpgStoryValidation.validate(kept)
    }

    @Test fun changingTheCurrentSessionsChapterSyncsOrClearsTheCurrentChapter() {
        val chapter2 = TrpgStoryChapter("ch2", "Chapter Two")
        val session2 = TrpgStorySession("se2", "ch2", "Session Two")
        val base = story.copy(chapters = listOf(chapter, chapter2), sessions = listOf(session, session2),
            activeChapterId = "ch1", activeSessionId = "se1")
        TrpgStoryValidation.validate(base)

        val moved = apply(base, TrpgStoryCommand(TrpgStoryOperation.UPSERT_SESSION,
            session = session.copy(chapterId = "ch2")))
        assertEquals("ch2", moved.activeChapterId)
        assertEquals("se1", moved.activeSessionId)
        TrpgStoryValidation.validate(moved)

        val detached = apply(moved, TrpgStoryCommand(TrpgStoryOperation.UPSERT_SESSION,
            session = session.copy(chapterId = null)))
        assertNull(detached.activeChapterId)
        assertEquals("se1", detached.activeSessionId)
        TrpgStoryValidation.validate(detached)

        // A title-only edit of the current session must not clear a still-valid current chapter.
        val titleOnly = apply(base, TrpgStoryCommand(TrpgStoryOperation.UPSERT_SESSION,
            session = session.copy(title = "Renamed")))
        assertEquals("ch1", titleOnly.activeChapterId)
        assertEquals("se1", titleOnly.activeSessionId)

        // Editing another session never rewrites the current pointers.
        val other = apply(base, TrpgStoryCommand(TrpgStoryOperation.UPSERT_SESSION,
            session = session2.copy(title = "Renamed")))
        assertEquals("ch1", other.activeChapterId)
        assertEquals("se1", other.activeSessionId)
        TrpgStoryValidation.validate(other)
    }

    @Test fun playerProjectionKeepsTheActivePointersCoherent() {
        val free = TrpgStorySession("free", null, "Unassigned")
        val player = TrpgStoryProjection.player(story.copy(sessions = story.sessions + free,
            activeChapterId = "ch1", activeSessionId = "free"))
        assertEquals("ch1", player.activeChapterId)
        assertEquals("free", player.activeSessionId)
        TrpgStoryValidation.validate(player)

        val hiddenChapter = TrpgStoryChapter("gm-chapter", "Secret", visibility = TrpgVisibility.GAME_MASTER)
        val hiddenCurrent = story.copy(chapters = listOf(chapter, hiddenChapter),
            sessions = story.sessions + free, activeChapterId = "gm-chapter", activeSessionId = "free")
        TrpgStoryValidation.validate(hiddenCurrent)
        val hiddenProjected = TrpgStoryProjection.player(hiddenCurrent)
        assertNull(hiddenProjected.activeChapterId)
        assertEquals("free", hiddenProjected.activeSessionId)
        TrpgStoryValidation.validate(hiddenProjected)

        // Even an inconsistent host value cannot make the projection emit dangling pointers.
        val inconsistent = TrpgStoryProjection.player(story.copy(chapters = listOf(chapter, hiddenChapter),
            activeChapterId = "gm-chapter", activeSessionId = "se1"))
        assertNull(inconsistent.activeChapterId)
        assertNull(inconsistent.activeSessionId)
        TrpgStoryValidation.validate(inconsistent)
    }

    @Test fun playerProjectionHidesGameMasterObjectsAndDanglingPointers() {
        val gmChapter = TrpgStoryChapter("gm-chapter", "Secret Chapter", visibility = TrpgVisibility.GAME_MASTER)
        val visibleUnderGm = TrpgStorySession("under", "gm-chapter", "Under Hidden", visibility = TrpgVisibility.PLAYER)
        val gmSession = TrpgStorySession("gm-session", "ch1", "GM Session", visibility = TrpgVisibility.GAME_MASTER)
        val gmTrack = TrpgTrack("gm-clock", TrpgTrackKind.CLOCK, "Secret Clock", visibility = TrpgVisibility.GAME_MASTER)
        val visibleTrack = TrpgTrack("q2", TrpgTrackKind.CLUE, "Public Clue", visibility = TrpgVisibility.PLAYER)
        val mixed = story.copy(
            chapters = listOf(chapter, gmChapter),
            sessions = listOf(session, visibleUnderGm, gmSession),
            tracks = listOf(quest, gmTrack, visibleTrack),
            activeChapterId = "gm-chapter",
            activeSessionId = "gm-session",
        )
        val player = TrpgStoryProjection.player(mixed)
        assertEquals(listOf("ch1"), player.chapters.map { it.id })
        assertEquals(listOf("se1"), player.sessions.map { it.id })
        assertEquals(listOf("q1", "q2"), player.tracks.map { it.id })
        assertNull(player.activeChapterId)
        assertNull(player.activeSessionId)
        TrpgStoryValidation.validate(player)
        val encoded = Json.encodeToString(player)
        assertFalse(encoded.contains("gm-chapter"))
        assertFalse(encoded.contains("gm-session"))
        assertFalse(encoded.contains("Secret Chapter"))
        assertFalse(encoded.contains("Secret Clock"))
        // A visible current pointer survives the projection.
        val publicPointers = TrpgStoryProjection.player(story)
        assertEquals("ch1", publicPointers.activeChapterId)
        assertEquals("se1", publicPointers.activeSessionId)
    }

    @Test fun sourceMessageIdTravelsWithTheTrackAndIsValidatedButNeverResolvedHere() {
        val linked = quest.copy(sourceMessageId = "message-42")
        val withSource = apply(story, TrpgStoryCommand(TrpgStoryOperation.UPSERT_TRACK, track = linked))
        assertEquals("message-42", withSource.tracks.single().sourceMessageId)
        assertEquals("message-42", TrpgStoryCommand(TrpgStoryOperation.UPSERT_TRACK, track = linked).track?.sourceMessageId)
        assertThrows(IllegalArgumentException::class.java) {
            TrpgStoryValidation.validate(story.copy(tracks = listOf(quest.copy(sourceMessageId = " "))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrpgStoryValidation.validate(story.copy(tracks = listOf(quest.copy(sourceMessageId = "m".repeat(257)))))
        }
        // The domain cannot know whether the message exists; the repository checks the conversation.
        val unknown = apply(story, TrpgStoryCommand(TrpgStoryOperation.UPSERT_TRACK,
            track = quest.copy(sourceMessageId = "no-such-message")))
        assertEquals("no-such-message", unknown.tracks.single().sourceMessageId)
    }

    @Test fun textAndNumericBoundsAreEnforced() {
        assertThrows(IllegalArgumentException::class.java) {
            TrpgStoryValidation.validate(story.copy(campaignName = "c".repeat(129)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrpgStoryValidation.validate(story.copy(chapters = listOf(chapter.copy(summary = "s".repeat(2049)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrpgStoryValidation.validate(story.copy(sessions = listOf(session.copy(recap = "r".repeat(2049)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrpgStoryValidation.validate(story.copy(chapters = listOf(chapter.copy(title = " "))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrpgStoryValidation.validate(story.copy(tracks = listOf(quest.copy(goal = 0))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrpgStoryValidation.validate(story.copy(tracks = listOf(quest.copy(goal = 1001))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrpgStoryValidation.validate(story.copy(tracks = listOf(quest.copy(goal = 2, progress = 3))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrpgStoryValidation.validate(story.copy(tracks = listOf(quest.copy(description = "d".repeat(2049)))))
        }
        // Renaming cannot blank the campaign name even though the default empty story is valid.
        assertThrows(IllegalArgumentException::class.java) {
            apply(story, TrpgStoryCommand(TrpgStoryOperation.RENAME_CAMPAIGN, campaignName = " "))
        }
    }
}
