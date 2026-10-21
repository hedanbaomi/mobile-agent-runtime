// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Context
import android.content.SharedPreferences

/**
 * Private local preference adapter. Keys are scoped by conversation id, values
 * are only persisted `content://` URIs and no file content or path is stored.
 * [clear] removes the preference rows only; the picked files stay untouched.
 */
internal class TrpgMediaSharedPreferencesStore(context: Context) : TrpgMediaSelectionStore {
    private val preferences = context.applicationContext
        .getSharedPreferences("trpg-media-selection", Context.MODE_PRIVATE)

    override fun load(conversationId: String): TrpgMediaSelection {
        if (!TrpgMediaPolicy.conversationIdIsValid(conversationId)) return TrpgMediaSelection()
        return TrpgMediaSelection(
            audioUri = preferences.getString(audioKey(conversationId), null)
                ?.takeIf { TrpgMediaPolicy.isAllowedContentUri(it) },
            imageUri = preferences.getString(imageKey(conversationId), null)
                ?.takeIf { TrpgMediaPolicy.isAllowedContentUri(it) },
        )
    }

    override fun save(conversationId: String, selection: TrpgMediaSelection) {
        TrpgMediaSelections.requireValid(conversationId, selection)
        val editor = preferences.edit()
        putOrRemove(editor, audioKey(conversationId), selection.audioUri)
        putOrRemove(editor, imageKey(conversationId), selection.imageUri)
        editor.apply()
    }

    override fun clear(conversationId: String) {
        if (!TrpgMediaPolicy.conversationIdIsValid(conversationId)) return
        preferences.edit()
            .remove(audioKey(conversationId))
            .remove(imageKey(conversationId))
            .apply()
    }

    private fun putOrRemove(editor: SharedPreferences.Editor, key: String, uri: String?) {
        if (uri == null) editor.remove(key) else editor.putString(key, uri)
    }

    private fun audioKey(conversationId: String): String = "trpg.media.audio.$conversationId"

    private fun imageKey(conversationId: String): String = "trpg.media.image.$conversationId"
}
