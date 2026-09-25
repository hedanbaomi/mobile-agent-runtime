// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** User-owned per-agent preference. Missing or malformed data always requires confirmation. */
internal object AgentToolConfirmation {
    private const val KEY = "skipToolConfirmations"

    fun skip(settingsJson: String?): Boolean = runCatching {
        ((Json.parseToJsonElement(settingsJson ?: "{}") as? JsonObject)
            ?.get(KEY) as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true
    }.getOrDefault(false)

    /** An opt-in cannot retroactively relax an old session; a live off switch revokes it. */
    fun allows(snapshotSettingsJson: String?, liveSettingsJson: String?): Boolean =
        skip(snapshotSettingsJson) && skip(liveSettingsJson)

    fun update(settingsJson: String?, skip: Boolean): String {
        val previous = runCatching { Json.parseToJsonElement(settingsJson ?: "{}") as? JsonObject }
            .getOrNull()?.toMutableMap() ?: mutableMapOf()
        previous[KEY] = JsonPrimitive(skip)
        return JsonObject(previous).toString()
    }
}
