// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Explicit user permission; absent, malformed and string-valued flags fail closed. */
object AgentSearchPermission {
    private const val KEY = "webSearchEnabled"

    fun enabled(raw: String?): Boolean = runCatching {
        val value = (Json.parseToJsonElement(raw ?: "{}") as? JsonObject)?.get(KEY) as? JsonPrimitive
        value?.takeUnless { it.isString }?.booleanOrNull == true
    }.getOrDefault(false)

    fun update(raw: String?, enabled: Boolean): String {
        val root = Json.parseToJsonElement(raw ?: "{}") as? JsonObject
            ?: error("Agent permission settings must be an object")
        return JsonObject(root + (KEY to JsonPrimitive(enabled))).toString()
    }

    fun allows(snapshot: String?, current: String?): Boolean = enabled(snapshot) && enabled(current)
}
