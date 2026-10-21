// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import runtime.mobileagent.domain.trpg.TrpgStory
import runtime.mobileagent.domain.trpg.TrpgStoryProjection
import runtime.mobileagent.domain.trpg.TrpgStoryValidation
import runtime.mobileagent.domain.trpg.TrpgTrack
import runtime.mobileagent.domain.trpg.TrpgTrackKind
import runtime.mobileagent.domain.trpg.TrpgTrackStatus
import runtime.mobileagent.domain.trpg.TrpgVisibility

/* Pure story-dialog helpers kept free of Compose so unit tests can exercise them directly. */

/** Player view is a filtered projection; the GM view is the full authoritative story. */
internal fun storyForRole(story: TrpgStory, gameMasterView: Boolean): TrpgStory =
    if (gameMasterView) story else TrpgStoryProjection.player(story)

/** Numeric drafts keep empty/`-`/`+` while editing; parsing never clamps or rewrites the text. */
internal fun parseStoryInt(raw: String): Int? {
    val text = raw.trim()
    if (text.isEmpty() || text == "-" || text == "+") return null
    return text.toIntOrNull()
}

/**
 * A sub-editor may only save against the revision it captured when it opened. A newer accepted
 * revision means the host story moved on; the draft is kept and must be reloaded explicitly.
 */
internal fun storyEditorSubmitRevision(capturedRevision: Long, acceptedRevision: Long): Long? =
    capturedRevision.takeIf { it == acceptedRevision }

internal data class StorySync(val story: TrpgStory, val baseRevision: Long, val conflict: Boolean)

/**
 * Only a newer host revision is adopted. While an editor is open the accepted story and revision
 * are kept so the editor's draft is never overwritten by an external revision change.
 */
internal fun reconcileStory(
    accepted: TrpgStory,
    acceptedRevision: Long,
    incoming: TrpgStory,
    incomingRevision: Long,
    editorOpen: Boolean,
): StorySync = when {
    incomingRevision <= acceptedRevision -> StorySync(accepted, acceptedRevision, conflict = false)
    editorOpen -> StorySync(accepted, acceptedRevision, conflict = true)
    else -> StorySync(incoming, incomingRevision, conflict = false)
}

/** Raw draft for a tracked entity. Numeric fields stay strings until an explicit submit. */
internal data class StoryTrackDraft(
    val id: String,
    val kind: TrpgTrackKind,
    val title: String,
    val description: String,
    val status: TrpgTrackStatus,
    val progress: String,
    val goal: String,
    val sourceMessageId: String?,
    val visibility: TrpgVisibility,
)

internal fun validateStoryTrackDraft(draft: StoryTrackDraft, chinese: Boolean): String? {
    val title = draft.title.trim()
    if (title.isEmpty()) return if (chinese) "标题不能为空。" else "A title is required."
    if (title.length > TrpgStoryValidation.MAX_TITLE) {
        return if (chinese) "标题超过 128 字符。" else "The title exceeds 128 characters."
    }
    if (draft.description.length > TrpgStoryValidation.MAX_SUMMARY) {
        return if (chinese) "说明超过 2048 字符。" else "The description exceeds 2048 characters."
    }
    val goal = parseStoryInt(draft.goal)
    if (goal == null || goal !in 1..TrpgStoryValidation.MAX_GOAL) {
        return if (chinese) "目标必须是 1..1000 的整数。" else "The goal must be an integer in 1..1000."
    }
    val progress = parseStoryInt(draft.progress)
    if (progress == null || progress !in 0..goal) {
        return if (chinese) "进度必须是 0..目标 的整数（未做截断）。" else
            "Progress must be an integer in 0..goal (input is not clamped)."
    }
    draft.sourceMessageId?.let { source ->
        if (source.isBlank() || source.length > TrpgStoryValidation.MAX_SOURCE_ID) {
            return if (chinese) "来源消息 ID 无效。" else "The source message id is invalid."
        }
    }
    return null
}

internal fun buildStoryTrack(draft: StoryTrackDraft): TrpgTrack? {
    if (validateStoryTrackDraft(draft, chinese = false) != null) return null
    return TrpgTrack(
        id = draft.id,
        kind = draft.kind,
        title = draft.title.trim(),
        description = draft.description,
        status = draft.status,
        progress = checkNotNull(parseStoryInt(draft.progress)),
        goal = checkNotNull(parseStoryInt(draft.goal)),
        sourceMessageId = draft.sourceMessageId?.takeIf { it.isNotBlank() },
        visibility = draft.visibility,
    )
}

internal fun storyFailure(chinese: Boolean): String =
    if (chinese) "故事操作失败，草稿已保留。" else "Story operation failed; your draft was kept."

internal fun storyConflict(chinese: Boolean): String =
    if (chinese) "故事已被其他写入更新；草稿保留。请显式载入最新后再保存。" else
        "The story changed elsewhere; your draft is kept. Reload the latest explicitly before saving."

internal fun storyTrackKindLabel(kind: TrpgTrackKind, chinese: Boolean): String = when (kind) {
    TrpgTrackKind.QUEST -> if (chinese) "任务" else "Quest"
    TrpgTrackKind.CLUE -> if (chinese) "线索" else "Clue"
    TrpgTrackKind.CLOCK -> if (chinese) "时钟" else "Clock"
}

internal fun storyStatusLabel(status: TrpgTrackStatus, chinese: Boolean): String = when (status) {
    TrpgTrackStatus.OPEN -> if (chinese) "未开始" else "Open"
    TrpgTrackStatus.ACTIVE -> if (chinese) "进行中" else "Active"
    TrpgTrackStatus.DONE -> if (chinese) "完成" else "Done"
    TrpgTrackStatus.FAILED -> if (chinese) "失败" else "Failed"
}

internal fun storyVisibilityLabel(visibility: TrpgVisibility, chinese: Boolean): String =
    if (visibility == TrpgVisibility.GAME_MASTER) (if (chinese) "主持" else "GM")
    else (if (chinese) "玩家" else "Player")
