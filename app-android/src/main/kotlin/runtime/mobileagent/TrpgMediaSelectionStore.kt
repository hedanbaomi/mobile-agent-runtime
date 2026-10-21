// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

/**
 * Per-conversation local picks.
 *
 * Clearing a selection only forgets the URI preference. Nothing in this
 * contract deletes, moves or rewrites the user's audio or image file.
 */
interface TrpgMediaSelectionStore {
    fun load(conversationId: String): TrpgMediaSelection

    fun save(conversationId: String, selection: TrpgMediaSelection)

    fun clear(conversationId: String)
}

class TrpgMediaInMemoryStore : TrpgMediaSelectionStore {
    private val entries = linkedMapOf<String, TrpgMediaSelection>()

    override fun load(conversationId: String): TrpgMediaSelection =
        entries[conversationId]?.let { TrpgMediaSelections.sanitize(it) } ?: TrpgMediaSelection()

    override fun save(conversationId: String, selection: TrpgMediaSelection) {
        TrpgMediaSelections.requireValid(conversationId, selection)
        entries[conversationId] = TrpgMediaSelections.sanitize(selection)
    }

    override fun clear(conversationId: String) {
        entries.remove(conversationId)
    }

    fun stored(conversationId: String): TrpgMediaSelection? = entries[conversationId]
}
