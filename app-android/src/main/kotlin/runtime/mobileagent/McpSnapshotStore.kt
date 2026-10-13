// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import runtime.mobileagent.data.SqlConnection

/** Only immutable pins live here. Live endpoint configuration and approvals retain their store. */
internal object McpSnapshotStore {
    private const val PREFIX = "mcp.snapshot.v1:"
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    data class Read(val present: Boolean, val value: McpStoredSnapshot?)

    fun read(db: SqlConnection, snapshotId: String): Read {
        val raw = db.query("SELECT value FROM app_prefs WHERE key=?", listOf(key(snapshotId))).singleOrNull()
            ?.string("value") ?: return Read(false, null)
        return Read(true, raw.takeIf { it.length <= 1_048_576 }?.let {
            runCatching { json.decodeFromString<McpStoredSnapshot>(it) }.getOrNull()
        }?.takeIf { it.snapshotId == snapshotId })
    }

    fun write(db: SqlConnection, snapshot: McpStoredSnapshot) {
        val encoded = json.encodeToString(snapshot)
        require(encoded.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "MCP snapshot exceeds the local limit" }
        db.execute("INSERT INTO app_prefs(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            listOf(key(snapshot.snapshotId), encoded))
    }

    private fun key(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")))
        return PREFIX + id
    }
}
