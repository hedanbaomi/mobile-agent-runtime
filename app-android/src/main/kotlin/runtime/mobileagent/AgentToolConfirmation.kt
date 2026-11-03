// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import runtime.mobileagent.diagnostics.DiagnosticAuthority
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.skills.RunControlTools

/** User-owned per-agent preference. Missing or malformed data always requires confirmation. */
internal object AgentToolConfirmation {
    private const val KEY = "skipToolConfirmations"
    private val ROOT_AUTHORITY_NAMES = setOf(Authority.ROOT_DIRECT.name, Authority.SHIZUKU_ROOT.name)
    private val ROOT_ONLY_TOOLS = setOf("file_get_metadata", "file_set_metadata", "file_apply_patch")
    private val ROOT_ELIGIBLE_TOOLS = setOf(
        "file_list",
        "file_stat",
        "file_read_text",
        "file_write_text",
        "file_create_directory",
        "file_move",
        "file_delete",
        "file_glob",
        "file_grep",
        "file_get_metadata",
        "file_set_metadata",
        "file_apply_patch",
        "shell_exec",
        RunControlTools.READ_BATCH,
        RunControlTools.JOB_START,
    )

    /**
     * Starting a background operation or executing an actual Root tool/workspace/shell operation
     * always requires an explicit foreground approval decision (`ROOT05/06/08`), without forcing
     * unrelated non-Root tools or ordinary conversations to prompt merely because a Root channel
     * is selected in Settings.
     */
    fun requiresExplicitApproval(
        toolName: String,
        selectedAuthority: String = Authority.NONE.name,
        workspaceAuthority: DiagnosticAuthority = DiagnosticAuthority.NONE,
        rootRun: Boolean = false,
    ): Boolean =
        toolName == RunControlTools.JOB_START ||
            toolName in ROOT_ONLY_TOOLS ||
            (toolName == "shell_exec" && selectedAuthority in ROOT_AUTHORITY_NAMES) ||
            workspaceAuthority == DiagnosticAuthority.ROOT_DIRECT ||
            workspaceAuthority == DiagnosticAuthority.SHIZUKU_ROOT ||
            (rootRun && toolName in ROOT_ELIGIBLE_TOOLS)

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
