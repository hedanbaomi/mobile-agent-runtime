// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain.trpg

import kotlinx.serialization.Serializable

@Serializable enum class TrpgTrackKind { QUEST, CLUE, CLOCK }
@Serializable enum class TrpgTrackStatus { OPEN, ACTIVE, DONE, FAILED }

/** A story chapter. Visibility is presentation only; it never grants or denies tool authority. */
@Serializable
data class TrpgStoryChapter(
    val id: String,
    val title: String,
    val summary: String = "",
    val visibility: TrpgVisibility = TrpgVisibility.PLAYER,
)

/** A play session. [chapterId] is null for an unassigned session; it must name a real chapter. */
@Serializable
data class TrpgStorySession(
    val id: String,
    val chapterId: String? = null,
    val title: String,
    val recap: String = "",
    val visibility: TrpgVisibility = TrpgVisibility.PLAYER,
)

/**
 * Quests, clues and clocks share one tracked entity. [progress] always stays in `0..goal`;
 * [goal] is 1..1000. [sourceMessageId] is a message id supplied by the caller; the domain never
 * checks the message store, and the host repository must verify it belongs to this conversation.
 */
@Serializable
data class TrpgTrack(
    val id: String,
    val kind: TrpgTrackKind,
    val title: String,
    val description: String = "",
    val status: TrpgTrackStatus = TrpgTrackStatus.OPEN,
    val progress: Int = 0,
    val goal: Int = 1,
    val sourceMessageId: String? = null,
    val visibility: TrpgVisibility = TrpgVisibility.PLAYER,
)

/** Default-empty, command-owned story state. Never mutated in place. */
@Serializable
data class TrpgStory(
    val campaignName: String = "",
    val chapters: List<TrpgStoryChapter> = emptyList(),
    val sessions: List<TrpgStorySession> = emptyList(),
    val tracks: List<TrpgTrack> = emptyList(),
    val activeChapterId: String? = null,
    val activeSessionId: String? = null,
)

@Serializable enum class TrpgStoryOperation {
    RENAME_CAMPAIGN,
    UPSERT_CHAPTER, REMOVE_CHAPTER, SET_ACTIVE_CHAPTER,
    UPSERT_SESSION, REMOVE_SESSION, SET_ACTIVE_SESSION,
    UPSERT_TRACK, REMOVE_TRACK, ADVANCE_TRACK,
}

/**
 * One explicit story operation. Only the fields belonging to the chosen operation are accepted;
 * any other non-null field is rejected instead of being silently ignored.
 *
 * `SET_ACTIVE_CHAPTER`/`SET_ACTIVE_SESSION` accept a null target to clear the current pointer.
 */
@Serializable
data class TrpgStoryCommand(
    val operation: TrpgStoryOperation,
    val campaignName: String? = null,
    val chapter: TrpgStoryChapter? = null,
    val session: TrpgStorySession? = null,
    val track: TrpgTrack? = null,
    val id: String? = null,
    val chapterId: String? = null,
    val sessionId: String? = null,
    val delta: Int? = null,
) {
    fun validate() {
        val present = buildSet {
            if (campaignName != null) add("campaignName")
            if (chapter != null) add("chapter")
            if (session != null) add("session")
            if (track != null) add("track")
            if (id != null) add("id")
            if (chapterId != null) add("chapterId")
            if (sessionId != null) add("sessionId")
            if (delta != null) add("delta")
        }
        val (allowed, required) = when (operation) {
            TrpgStoryOperation.RENAME_CAMPAIGN -> setOf("campaignName") to setOf("campaignName")
            TrpgStoryOperation.UPSERT_CHAPTER -> setOf("chapter") to setOf("chapter")
            TrpgStoryOperation.REMOVE_CHAPTER -> setOf("id") to setOf("id")
            TrpgStoryOperation.SET_ACTIVE_CHAPTER -> setOf("chapterId") to emptySet()
            TrpgStoryOperation.UPSERT_SESSION -> setOf("session") to setOf("session")
            TrpgStoryOperation.REMOVE_SESSION -> setOf("id") to setOf("id")
            TrpgStoryOperation.SET_ACTIVE_SESSION -> setOf("sessionId") to emptySet()
            TrpgStoryOperation.UPSERT_TRACK -> setOf("track") to setOf("track")
            TrpgStoryOperation.REMOVE_TRACK -> setOf("id") to setOf("id")
            TrpgStoryOperation.ADVANCE_TRACK -> setOf("id", "delta") to setOf("id", "delta")
        }
        require(present.all { it in allowed } && present.containsAll(required)) {
            "Unexpected or missing story operation fields"
        }
        when (operation) {
            TrpgStoryOperation.RENAME_CAMPAIGN -> {
                val name = requireNotNull(campaignName)
                require(name.isNotBlank() && name.length <= TrpgStoryValidation.MAX_CAMPAIGN_NAME) { "Invalid campaign name" }
            }
            TrpgStoryOperation.UPSERT_CHAPTER -> TrpgStoryValidation.validate(requireNotNull(chapter))
            TrpgStoryOperation.UPSERT_SESSION -> TrpgStoryValidation.validate(requireNotNull(session))
            TrpgStoryOperation.UPSERT_TRACK -> TrpgStoryValidation.validate(requireNotNull(track))
            TrpgStoryOperation.REMOVE_CHAPTER, TrpgStoryOperation.REMOVE_SESSION, TrpgStoryOperation.REMOVE_TRACK ->
                TrpgStoryValidation.id(requireNotNull(id))
            TrpgStoryOperation.SET_ACTIVE_CHAPTER -> chapterId?.let(TrpgStoryValidation::id)
            TrpgStoryOperation.SET_ACTIVE_SESSION -> sessionId?.let(TrpgStoryValidation::id)
            TrpgStoryOperation.ADVANCE_TRACK -> {
                TrpgStoryValidation.id(requireNotNull(id))
                val step = requireNotNull(delta)
                require(step != 0 && step in -TrpgStoryValidation.MAX_GOAL..TrpgStoryValidation.MAX_GOAL) { "Invalid track delta" }
            }
        }
    }
}

object TrpgStoryValidation {
    const val MAX_CAMPAIGN_NAME = 128
    const val MAX_TITLE = 128
    const val MAX_SUMMARY = 2048
    const val MAX_CHAPTERS = 16
    const val MAX_SESSIONS = 32
    const val MAX_TRACKS = 64
    const val MAX_GOAL = 1000
    const val MAX_SOURCE_ID = 256
    const val MAX_ID = 128

    fun id(value: String) {
        require(value.isNotBlank() && value.length <= MAX_ID) { "Invalid story entity id" }
    }

    private fun title(value: String) {
        require(value.isNotBlank() && value.length <= MAX_TITLE) { "Invalid story title" }
    }

    fun validate(chapter: TrpgStoryChapter) {
        id(chapter.id)
        title(chapter.title)
        require(chapter.summary.length <= MAX_SUMMARY) { "Chapter summary is too long" }
    }

    fun validate(session: TrpgStorySession) {
        id(session.id)
        title(session.title)
        session.chapterId?.let(::id)
        require(session.recap.length <= MAX_SUMMARY) { "Session recap is too long" }
    }

    fun validate(track: TrpgTrack) {
        id(track.id)
        title(track.title)
        require(track.description.length <= MAX_SUMMARY) { "Track description is too long" }
        require(track.goal in 1..MAX_GOAL) { "Track goal must be 1..$MAX_GOAL" }
        require(track.progress in 0..track.goal) { "Track progress must stay within 0..goal" }
        track.sourceMessageId?.let {
            require(it.isNotBlank() && it.length <= MAX_SOURCE_ID) { "Invalid source message id" }
        }
    }

    fun validate(story: TrpgStory) {
        require(story.campaignName.length <= MAX_CAMPAIGN_NAME) { "Campaign name is too long" }
        require(story.chapters.size <= MAX_CHAPTERS) { "Too many chapters" }
        require(story.sessions.size <= MAX_SESSIONS) { "Too many sessions" }
        require(story.tracks.size <= MAX_TRACKS) { "Too many tracks" }
        require(story.chapters.map { it.id }.distinct().size == story.chapters.size) { "Duplicate chapter id" }
        require(story.sessions.map { it.id }.distinct().size == story.sessions.size) { "Duplicate session id" }
        require(story.tracks.map { it.id }.distinct().size == story.tracks.size) { "Duplicate track id" }
        story.chapters.forEach(::validate)
        story.sessions.forEach(::validate)
        story.tracks.forEach(::validate)
        val chapterIds = story.chapters.mapTo(mutableSetOf()) { it.id }
        val sessionIds = story.sessions.mapTo(mutableSetOf()) { it.id }
        story.sessions.forEach { session ->
            require(session.chapterId == null || session.chapterId in chapterIds) { "Session chapter is missing" }
        }
        story.activeChapterId?.let { require(it in chapterIds) { "Active chapter is missing" } }
        story.activeSessionId?.let { require(it in sessionIds) { "Active session is missing" } }
        val activeSession = story.activeSessionId?.let { active -> story.sessions.single { it.id == active } }
        require(activeSession?.chapterId == null || activeSession.chapterId == story.activeChapterId) {
            "Active chapter must match the active session's chapter"
        }
    }
}

/**
 * Deterministic story transitions. Parent removal clears dependent pointers (sessions are detached
 * from a deleted chapter, current ids are cleared) so the result never holds a dangling reference.
 */
object TrpgStoryReducer {
    fun apply(story: TrpgStory, command: TrpgStoryCommand): TrpgStory {
        command.validate()
        val next = when (command.operation) {
            TrpgStoryOperation.RENAME_CAMPAIGN -> story.copy(campaignName = requireNotNull(command.campaignName))
            TrpgStoryOperation.UPSERT_CHAPTER -> {
                val value = requireNotNull(command.chapter)
                val exists = story.chapters.any { it.id == value.id }
                require(exists || story.chapters.size < TrpgStoryValidation.MAX_CHAPTERS) { "Too many chapters" }
                story.copy(chapters = upsert(story.chapters, value) { it.id })
            }
            TrpgStoryOperation.REMOVE_CHAPTER -> {
                val id = requireNotNull(command.id)
                require(story.chapters.any { it.id == id }) { "Chapter is missing" }
                story.copy(
                    chapters = story.chapters.filterNot { it.id == id },
                    sessions = story.sessions.map { if (it.chapterId == id) it.copy(chapterId = null) else it },
                    activeChapterId = story.activeChapterId?.takeIf { it != id },
                )
            }
            TrpgStoryOperation.SET_ACTIVE_CHAPTER -> {
                val id = command.chapterId
                val current = story.activeSessionId?.let { active -> story.sessions.singleOrNull { it.id == active } }
                if (id == null) {
                    // No current chapter: clear a chapter-bound current session; keep an unassigned one.
                    story.copy(
                        activeChapterId = null,
                        activeSessionId = story.activeSessionId.takeIf { current?.chapterId == null },
                    )
                } else {
                    require(story.chapters.any { it.id == id }) { "Chapter is missing" }
                    val sessionElsewhere = current?.chapterId != null && current.chapterId != id
                    story.copy(activeChapterId = id, activeSessionId = if (sessionElsewhere) null else story.activeSessionId)
                }
            }
            TrpgStoryOperation.UPSERT_SESSION -> {
                val value = requireNotNull(command.session)
                value.chapterId?.let { chapterId ->
                    require(story.chapters.any { it.id == chapterId }) { "Session chapter is missing" }
                }
                val exists = story.sessions.any { it.id == value.id }
                require(exists || story.sessions.size < TrpgStoryValidation.MAX_SESSIONS) { "Too many sessions" }
                val previous = story.sessions.singleOrNull { it.id == value.id }
                val chapterChanged = previous != null && previous.chapterId != value.chapterId
                val sessions = upsert(story.sessions, value) { it.id }
                // Moving the current session (including detaching it) moves/clears the current chapter.
                val activeChapter = if (story.activeSessionId == value.id && chapterChanged) value.chapterId
                    else story.activeChapterId
                story.copy(sessions = sessions, activeChapterId = activeChapter)
            }
            TrpgStoryOperation.REMOVE_SESSION -> {
                val id = requireNotNull(command.id)
                require(story.sessions.any { it.id == id }) { "Session is missing" }
                story.copy(
                    sessions = story.sessions.filterNot { it.id == id },
                    activeSessionId = story.activeSessionId?.takeIf { it != id },
                )
            }
            TrpgStoryOperation.SET_ACTIVE_SESSION -> {
                val id = command.sessionId
                if (id == null) story.copy(activeSessionId = null) else {
                    val session = story.sessions.singleOrNull { it.id == id } ?: error("Session is missing")
                    story.copy(
                        activeSessionId = id,
                        activeChapterId = session.chapterId ?: story.activeChapterId,
                    )
                }
            }
            TrpgStoryOperation.UPSERT_TRACK -> {
                val value = requireNotNull(command.track)
                val exists = story.tracks.any { it.id == value.id }
                require(exists || story.tracks.size < TrpgStoryValidation.MAX_TRACKS) { "Too many tracks" }
                story.copy(tracks = upsert(story.tracks, value) { it.id })
            }
            TrpgStoryOperation.REMOVE_TRACK -> {
                val id = requireNotNull(command.id)
                require(story.tracks.any { it.id == id }) { "Track is missing" }
                story.copy(tracks = story.tracks.filterNot { it.id == id })
            }
            TrpgStoryOperation.ADVANCE_TRACK -> {
                val track = story.tracks.singleOrNull { it.id == command.id } ?: error("Track is missing")
                val step = checkNotNull(command.delta)
                val result = track.progress.toLong() + step.toLong()
                require(result in 0..track.goal.toLong()) { "Track progress must stay within 0..goal" }
                story.copy(tracks = story.tracks.map { if (it.id == track.id) it.copy(progress = result.toInt()) else it })
            }
        }
        TrpgStoryValidation.validate(next)
        return next
    }

    private fun <T> upsert(items: List<T>, value: T, id: (T) -> String): List<T> =
        if (items.any { id(it) == id(value) }) items.map { if (id(it) == id(value)) value else it } else items + value
}

/**
 * Player/export projection: hides GM-only chapters, sessions and tracks. A player-visible session
 * under a hidden chapter is hidden too. Current ids that point at hidden objects are cleared, so
 * no hidden id, count or name crosses this boundary.
 */
object TrpgStoryProjection {
    fun player(story: TrpgStory): TrpgStory {
        val chapters = story.chapters.filter { it.visibility == TrpgVisibility.PLAYER }
        val chapterIds = chapters.mapTo(mutableSetOf()) { it.id }
        val sessions = story.sessions.filter {
            it.visibility == TrpgVisibility.PLAYER && (it.chapterId == null || it.chapterId in chapterIds)
        }
        val activeChapterId = story.activeChapterId?.takeIf { it in chapterIds }
        val activeSession = sessions.singleOrNull { it.id == story.activeSessionId }
        val activeSessionId = activeSession?.takeIf { it.chapterId == null || it.chapterId == activeChapterId }?.id
        return story.copy(
            chapters = chapters,
            sessions = sessions,
            tracks = story.tracks.filter { it.visibility == TrpgVisibility.PLAYER },
            activeChapterId = activeChapterId,
            activeSessionId = activeSessionId,
        )
    }
}
