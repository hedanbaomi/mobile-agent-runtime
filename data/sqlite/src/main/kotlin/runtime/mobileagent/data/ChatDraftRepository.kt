// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

/** Opaque draft identity; bodies belong to SQLite, never Android saved-state or diagnostics. */
data class ChatDraftOwner(val kind: String, val id: String) {
    init {
        require(kind in setOf("conversation", "agent", "unassigned"))
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")))
        require(kind != "unassigned" || id == "composer")
    }
    internal val key get() = "chat.draft.v1:$kind:$id"
}

/** Uses the existing namespaced local store; no schema migration or transcript change is needed. */
class ChatDraftRepository(private val db: SqlConnection) {
    fun load(owner: ChatDraftOwner): String = db.transaction {
        if (!exists(owner)) {
            db.execute("DELETE FROM app_prefs WHERE key=?", listOf(owner.key))
            return@transaction ""
        }
        db.query("SELECT value FROM app_prefs WHERE key=?", listOf(owner.key))
            .singleOrNull()?.string("value").orEmpty().also(::validate)
    }

    fun save(owner: ChatDraftOwner, body: String) = db.transaction {
        validate(body)
        if (body.isEmpty() && !exists(owner)) {
            db.execute("DELETE FROM app_prefs WHERE key=?", listOf(owner.key))
        } else {
            check(exists(owner)) { "草稿所属会话或智能体已不存在。" }
            db.execute("INSERT INTO app_prefs(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                listOf(owner.key, body))
        }
    }

    /** Restored legacy state cannot overwrite a newer durable edit or a persisted clear. */
    fun migrateLegacy(owner: ChatDraftOwner, body: String) = db.transaction {
        if (exists(owner) && db.query("SELECT key FROM app_prefs WHERE key=?", listOf(owner.key)).isEmpty()) save(owner, body)
    }

    private fun exists(owner: ChatDraftOwner): Boolean = when (owner.kind) {
        "conversation" -> db.query("SELECT id FROM conversations WHERE id=?", listOf(owner.id)).isNotEmpty()
        "agent" -> db.query("SELECT id FROM agent_profiles WHERE id=?", listOf(owner.id)).isNotEmpty()
        else -> true
    }

    private fun validate(body: String) {
        require(body.length <= MAX_CHARACTERS) { "草稿超过本地正文上限；当前输入仍在页面，请缩短后保存。" }
    }

    companion object { const val MAX_CHARACTERS = 1_000_000 }
}
