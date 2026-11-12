// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data.migrations

import runtime.mobileagent.data.ProfileRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.domain.GrantLifetime

internal object MigrationProfileBackfills {
    /**
     * Keep the explicit AgentSnapshot column in sync with the historical
     * snapshot_id column. The latter remains the compatibility source for
     * existing repositories and transfers, while the former is consumed by
     * the thread/workspace integration. Blank legacy values are safe to
     * backfill; a conflicting non-blank value is treated as damaged data and
     * aborts migration instead of silently selecting a different snapshot.
     */
    fun backfillConversationSnapshotIds(connection: SqlConnection) {
        connection.execute(
            "UPDATE conversations SET agent_snapshot_id = snapshot_id " +
                "WHERE agent_snapshot_id IS NULL OR trim(agent_snapshot_id) = ''",
        )
        val mismatch = connection.query(
            "SELECT id FROM conversations WHERE agent_snapshot_id <> snapshot_id LIMIT 1",
        ).singleOrNull()
        if (mismatch != null) {
            migrationInvalid("Conversation ${mismatch.string("id")} has conflicting snapshot ids")
        }
    }

    /**
     * Validate every existing model before marking v11 complete and only derive endpoint_json
     * where the old schema genuinely had no endpoint column/value. Earlier code used
     * getOrDefault(CHAT), getOrDefault(emptyList()) and a catch-all endpoint fallback, which
     * silently changed damaged data into a different model binding.
     */
    fun backfillV11(connection: SqlConnection) {
        connection.query("SELECT id, role, capabilities, endpoint_json FROM model_profiles").forEach { row ->
            val modelId = row.string("id")
            val role = ProfileRepository.decodeRole(
                ProfileRepository.requirePersistedString(row.columns["role"], "role", modelId),
                modelId,
            )
            val caps = ProfileRepository.decodeCapabilities(
                ProfileRepository.requirePersistedString(row.columns["capabilities"], "capabilities", modelId),
                modelId,
            )
            val stored = ProfileRepository.requirePersistedString(row.columns["endpoint_json"], "endpoint_json", modelId)
            val endpoint = ProfileRepository.decodePersistedEndpoint(stored, role, caps, modelId)
            if (stored.isBlank() || stored.trim() == "{}") {
                val encoded = kotlinx.serialization.json.Json.encodeToString(
                    runtime.mobileagent.domain.ModelEndpoint.serializer(),
                    endpoint,
                )
                connection.execute("UPDATE model_profiles SET endpoint_json = ? WHERE id = ?", listOf(encoded, modelId))
            }
        }
    }

    /**
     * v12 only adds metadata to the legacy Skill grant rows. Every pre-v12 grant is a
     * persistent user grant because the old schema had no expiry concept; capability sets,
     * revoked flags and revisions are intentionally left untouched.
     */
    fun backfillV12(connection: SqlConnection) {
        connection.execute(
            "UPDATE permission_grants SET lifetime = ? WHERE lifetime IS NULL OR lifetime = ''",
            listOf(GrantLifetime.PERSISTENT.name),
        )
        connection.query("SELECT grant_id, install_id, created_at FROM permission_grants").forEach { row ->
            val grantId = row.string("grant_id")
            val createdAt = row.string("created_at")
            if (createdAt.isNotBlank()) return@forEach
            val fromInstall = connection.query(
                "SELECT created_at FROM skill_installs WHERE install_id = ?",
                listOf(row.string("install_id")),
            ).singleOrNull()?.string("created_at").orEmpty()
            connection.execute(
                "UPDATE permission_grants SET created_at = ? WHERE grant_id = ?",
                listOf(fromInstall, grantId),
            )
        }
        connection.execute(
            "UPDATE permission_grants SET revoked_at = COALESCE(revoked_at, created_at) WHERE revoked <> 0 AND (revoked_at IS NULL OR revoked_at = '')",
        )
    }
}
