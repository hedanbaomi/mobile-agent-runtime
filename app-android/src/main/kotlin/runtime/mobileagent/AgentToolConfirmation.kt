// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import runtime.mobileagent.skills.RunControlTools

/** User-owned per-agent preference. Missing or malformed data always requires confirmation. */
internal object AgentToolConfirmation {
    private const val KEY = "skipToolConfirmations"

    /** Starting a background operation requires an explicit foreground decision. */
    fun requiresExplicitApproval(toolName: String): Boolean =
        toolName == RunControlTools.JOB_START

    /** Project the same shell disclosure for an explicit background invocation. */
    fun shellArguments(toolName: String, arguments: JsonObject?): JsonObject? = when (toolName) {
        "shell_exec" -> arguments
        RunControlTools.JOB_START -> arguments?.takeIf {
            (it["tool"] as? JsonPrimitive)?.let { tool -> tool.isString && tool.content == "shell_exec" } == true
        }?.get("arguments") as? JsonObject
        else -> null
    }

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
