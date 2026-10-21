// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import runtime.mobileagent.domain.trpg.TrpgAtlas
import runtime.mobileagent.domain.trpg.TrpgAtlasValidation
import runtime.mobileagent.domain.trpg.TrpgEdge
import runtime.mobileagent.domain.trpg.TrpgLocation
import runtime.mobileagent.domain.trpg.TrpgVisibility

/** Numeric drafts keep empty/`-`/`+` while editing; parsing never clamps or rewrites the text. */
internal fun parseAtlasInt(raw: String): Int? {
    val text = raw.trim()
    if (text.isEmpty() || text == "-" || text == "+") return null
    return text.toIntOrNull()
}

/**
 * A sub-editor may only save against the revision it captured when it opened. A newer accepted
 * revision means the host atlas moved on; the draft is kept and must be reloaded explicitly.
 */
internal fun atlasEditorSubmitRevision(capturedRevision: Long, acceptedRevision: Long): Long? =
    capturedRevision.takeIf { it == acceptedRevision }

internal data class AtlasSync(val atlas: TrpgAtlas, val baseRevision: Long, val conflict: Boolean)

/**
 * Only a newer host revision is adopted. While an editor is open the accepted atlas and revision
 * are kept so the editor's draft is never overwritten by an external revision change.
 */
internal fun reconcileAtlas(
    accepted: TrpgAtlas,
    acceptedRevision: Long,
    incoming: TrpgAtlas,
    incomingRevision: Long,
    editorOpen: Boolean,
): AtlasSync = when {
    incomingRevision <= acceptedRevision -> AtlasSync(accepted, acceptedRevision, conflict = false)
    editorOpen -> AtlasSync(accepted, acceptedRevision, conflict = true)
    else -> AtlasSync(incoming, incomingRevision, conflict = false)
}

/** Raw draft for a location. Coordinates stay strings until an explicit submit. */
internal data class AtlasLocationDraft(
    val id: String,
    val title: String,
    val description: String,
    val x: String,
    val y: String,
    val visibility: TrpgVisibility,
)

internal fun validateAtlasLocationDraft(draft: AtlasLocationDraft, chinese: Boolean): String? {
    val title = draft.title.trim()
    if (title.isEmpty()) return if (chinese) "地点标题不能为空。" else "A location title is required."
    if (title.length > TrpgAtlasValidation.MAX_TITLE) {
        return if (chinese) "标题超过 128 字符。" else "The title exceeds 128 characters."
    }
    if (draft.description.length > TrpgAtlasValidation.MAX_DESCRIPTION) {
        return if (chinese) "说明超过 2048 字符。" else "The description exceeds 2048 characters."
    }
    val x = parseAtlasInt(draft.x)
    if (x == null || x !in 0..TrpgAtlasValidation.MAP_UNITS) {
        return if (chinese) "X 必须是 0..1000 的整数（输入不会被截断）。" else
            "X must be an integer in 0..1000 (input is not clamped)."
    }
    val y = parseAtlasInt(draft.y)
    if (y == null || y !in 0..TrpgAtlasValidation.MAP_UNITS) {
        return if (chinese) "Y 必须是 0..1000 的整数（输入不会被截断）。" else
            "Y must be an integer in 0..1000 (input is not clamped)."
    }
    return null
}

internal fun buildAtlasLocation(draft: AtlasLocationDraft): TrpgLocation? {
    if (validateAtlasLocationDraft(draft, chinese = false) != null) return null
    return TrpgLocation(
        id = draft.id,
        title = draft.title.trim(),
        description = draft.description,
        x = checkNotNull(parseAtlasInt(draft.x)),
        y = checkNotNull(parseAtlasInt(draft.y)),
        visibility = draft.visibility,
    )
}

internal data class AtlasEdgeDraft(
    val id: String,
    val fromId: String,
    val toId: String,
    val label: String,
    val visibility: TrpgVisibility,
)

internal fun validateAtlasEdgeDraft(draft: AtlasEdgeDraft, locationIds: Set<String>, chinese: Boolean): String? {
    if (draft.fromId !in locationIds || draft.toId !in locationIds) {
        return if (chinese) "请选择两个真实地点作为端点。" else "Pick two real locations as endpoints."
    }
    if (draft.fromId == draft.toId) {
        return if (chinese) "两个端点必须不同。" else "The two endpoints must differ."
    }
    if (draft.label.length > TrpgAtlasValidation.MAX_LABEL) {
        return if (chinese) "连线标签超过 128 字符。" else "The label exceeds 128 characters."
    }
    return null
}

internal fun buildAtlasEdge(draft: AtlasEdgeDraft, locationIds: Set<String>): TrpgEdge? {
    if (validateAtlasEdgeDraft(draft, locationIds, chinese = false) != null) return null
    return TrpgEdge(
        id = draft.id,
        fromId = draft.fromId,
        toId = draft.toId,
        label = draft.label.trim(),
        visibility = draft.visibility,
    )
}

internal fun atlasFailure(chinese: Boolean): String =
    if (chinese) "地图操作失败，草稿已保留。" else "Atlas operation failed; your draft was kept."

internal fun atlasConflict(chinese: Boolean): String =
    if (chinese) "地图已被其他写入更新；草稿保留。请显式载入最新后再保存。" else
        "The atlas changed elsewhere; your draft is kept. Reload the latest explicitly before saving."
