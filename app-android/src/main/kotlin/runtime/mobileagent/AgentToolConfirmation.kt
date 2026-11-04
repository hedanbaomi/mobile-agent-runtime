// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import runtime.mobileagent.diagnostics.DiagnosticAuthority
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.skills.RunControlTools

/** User-owned per-agent preference. Missing or malformed data always requires confirmation. */
internal object AgentToolConfirmation {
    private const val KEY = "skipToolConfirmations"

    /**
     * Root read-only opt-in, deliberately separate from [KEY].
     *
     * Reading through a Root channel is a real Root capability, so the ordinary
     * "skip tool confirmations" switch must never authorize it on its own. The value is read from
     * both the frozen snapshot and the live Agent settings, like every other opt-in here.
     */
    private const val ROOT_READ_KEY = "skipRootReadConfirmations"
    private val ROOT_AUTHORITY_NAMES = setOf(Authority.ROOT_DIRECT.name, Authority.SHIZUKU_ROOT.name)
    private val ROOT_ONLY_TOOLS = setOf("file_get_metadata", "file_set_metadata", "file_apply_patch")
    private val ROOT_READ_ONLY_TOOLS = setOf(
        "workspace_list", "file_list", "file_stat", "file_read_text", "file_glob", "file_grep",
        "file_get_metadata", RunControlTools.READ_BATCH,
    )
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
    private val WORKSPACE_TOOL_NAMES = setOf(
        "workspace_list", "file_list", "file_stat", "file_read_text", "file_write_text",
        "file_create_directory", "file_move", "file_delete", "file_glob", "file_grep",
        "file_get_metadata", "file_set_metadata", "file_apply_patch", "apply_patch",
    )
    /** Mirrors the side-effect-free batch allowlist; unknown children fail closed. */
    private val READ_BATCH_CHILD_TOOLS = (ROOT_READ_ONLY_TOOLS - RunControlTools.READ_BATCH) + "calculator"

    fun isRootReadOnlyTool(toolName: String): Boolean = toolName in ROOT_READ_ONLY_TOOLS

    /**
     * The workspace ids this exact call names. A mixed-session batch may contain several
     * children; Root approval must follow the child actually being executed, not the Run stem.
     */
    fun invocationWorkspaceIds(toolName: String, argumentsJson: String): List<String> {
        return runCatching {
            if (toolName == RunControlTools.READ_BATCH) {
                readBatchChildren(argumentsJson).mapNotNull { child ->
                    (child["arguments"] as? JsonObject)?.let(::workspaceIdFrom)
                }
            } else {
                val root = Json.parseToJsonElement(argumentsJson) as? JsonObject ?: return emptyList()
                listOfNotNull(workspaceIdFrom(root))
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Project the authority of the workspace named by this call. An explicit workspace wins over
     * the conversation binding; the binding is only a fallback for tools that do not name one.
     */
    fun invocationWorkspaceAuthority(
        toolName: String,
        argumentsJson: String,
        fallbackWorkspaceId: String?,
        authorityForWorkspace: (String) -> DiagnosticAuthority,
    ): DiagnosticAuthority {
        val ids = invocationWorkspaceIds(toolName, argumentsJson).distinct()
        if (ids.isEmpty()) {
            return fallbackWorkspaceId
                ?.let(authorityForWorkspace)
                ?: DiagnosticAuthority.NONE
        }
        // Lookup failure aborts the approval decision. NONE means a successfully classified
        // ordinary workspace, never a failed authority lookup that may hide a Root route.
        val authorities = ids.map(authorityForWorkspace)
        return authorities.firstOrNull(::isRootDiagnosticAuthority)
            ?: authorities.firstOrNull { it != DiagnosticAuthority.NONE }
            ?: DiagnosticAuthority.NONE
    }

    /** True only when the Root stamp is the sole available route evidence for this call. */
    fun isRunScopedRootTool(toolName: String, argumentsJson: String): Boolean {
        if (toolName == "workspace_list") return true
        if (invocationWorkspaceIds(toolName, argumentsJson).isNotEmpty()) return false
        if (toolName == RunControlTools.READ_BATCH) {
            val tools = readBatchChildren(argumentsJson).mapNotNull { child ->
                (child["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            }
            // A batch of purely non-workspace read tools does not become a Root operation just
            // because the Run also carries a Root stamp.
            if (tools.isNotEmpty() && tools.none { it in WORKSPACE_TOOL_NAMES }) return false
        }
        return true
    }

    /**
     * One production decision for the mixed-workspace approval branch. Root reads require their
     * own opt-in and can never be auto-approved by the ordinary switch; Root writes stay explicit
     * because [requiresExplicitApproval] remains authoritative for them.
     */
    fun autoApproves(
        toolName: String,
        selectedAuthority: String,
        workspaceAuthority: DiagnosticAuthority,
        rootRun: Boolean,
        rootReadConfirmed: Boolean,
        ordinarySkip: Boolean,
    ): Boolean {
        if (isRootContext(workspaceAuthority, rootRun) && isRootReadOnlyTool(toolName)) {
            return rootReadConfirmed
        }
        return !requiresExplicitApproval(
            toolName = toolName,
            selectedAuthority = selectedAuthority,
            workspaceAuthority = workspaceAuthority,
            rootRun = rootRun,
        ) && ordinarySkip
    }

    /**
     * Single production entry for both ordinary tool calls and read_batch. A batch is approved
     * only after every child is validated and approved against its own route.
     */
    fun autoApprovesInvocation(
        toolName: String,
        argumentsJson: String,
        selectedAuthority: String,
        workspaceAuthority: DiagnosticAuthority,
        fallbackWorkspaceId: String?,
        rootRun: Boolean,
        runScopedRoot: Boolean,
        rootReadConfirmed: Boolean,
        ordinarySkip: Boolean,
        authorityForWorkspace: (String) -> DiagnosticAuthority,
    ): Boolean {
        if (toolName == RunControlTools.READ_BATCH) {
            return autoApprovesReadBatch(
                argumentsJson = argumentsJson,
                selectedAuthority = selectedAuthority,
                fallbackWorkspaceId = fallbackWorkspaceId,
                rootRun = rootRun,
                rootReadConfirmed = rootReadConfirmed,
                ordinarySkip = ordinarySkip,
                authorityForWorkspace = authorityForWorkspace,
            )
        }
        return autoApproves(
            toolName = toolName,
            selectedAuthority = selectedAuthority,
            workspaceAuthority = workspaceAuthority,
            rootRun = runScopedRoot,
            rootReadConfirmed = rootReadConfirmed,
            ordinarySkip = ordinarySkip,
        )
    }

    /**
     * read_batch is atomic for approval: the outer call is auto-approved only when its children
     * are non-empty, well-formed, known read-only tools, and every child independently passes its
     * Root-read or ordinary-skip condition. Any malformed child or authority lookup failure
     * returns false so ReadBatchToolExecutor asks for the outer approval.
     */
    private fun autoApprovesReadBatch(
        argumentsJson: String,
        selectedAuthority: String,
        fallbackWorkspaceId: String?,
        rootRun: Boolean,
        rootReadConfirmed: Boolean,
        ordinarySkip: Boolean,
        authorityForWorkspace: (String) -> DiagnosticAuthority,
    ): Boolean {
        val children = strictBatchChildren(argumentsJson) ?: return false
        val needsFallbackRoute = children.any { it.tool == "workspace_list" }
        val fallbackAuthority = if (!rootRun && needsFallbackRoute && fallbackWorkspaceId != null) {
            try {
                authorityForWorkspace(fallbackWorkspaceId)
            } catch (_: Exception) {
                return false
            }
        } else {
            null
        }
        return children.all { child ->
            if (child.tool !in READ_BATCH_CHILD_TOOLS) return@all false
            when (child.tool) {
                "workspace_list" -> {
                    if (child.arguments.isNotEmpty()) return@all false
                    val root = rootRun || fallbackAuthority?.let(::isRootDiagnosticAuthority) == true
                    if (root) rootReadConfirmed
                    else ordinaryChildAutoApproves(child.tool, selectedAuthority, DiagnosticAuthority.NONE, ordinarySkip)
                }
                "calculator" -> {
                    if (child.arguments.keys != setOf("expression")) return@all false
                    val expression = (child.arguments["expression"] as? JsonPrimitive)
                        ?.takeIf { it.isString }?.contentOrNull
                    if (expression.isNullOrBlank()) return@all false
                    ordinaryChildAutoApproves(child.tool, selectedAuthority, DiagnosticAuthority.NONE, ordinarySkip)
                }
                else -> {
                    val workspaceId = strictWorkspaceId(child.arguments) ?: return@all false
                    val authority = try {
                        authorityForWorkspace(workspaceId)
                    } catch (_: Exception) {
                        return@all false
                    }
                    if (isRootDiagnosticAuthority(authority)) rootReadConfirmed
                    else ordinaryChildAutoApproves(child.tool, selectedAuthority, authority, ordinarySkip)
                }
            }
        }
    }

    private fun ordinaryChildAutoApproves(
        toolName: String,
        selectedAuthority: String,
        workspaceAuthority: DiagnosticAuthority,
        ordinarySkip: Boolean,
    ): Boolean = !requiresExplicitApproval(
        toolName = toolName,
        selectedAuthority = selectedAuthority,
        workspaceAuthority = workspaceAuthority,
        rootRun = false,
    ) && ordinarySkip

    private fun strictBatchChildren(argumentsJson: String): List<StrictBatchChild>? {
        val root = runCatching { Json.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull()
            ?: return null
        if (root.keys != setOf("calls")) return null
        val calls = root["calls"] as? JsonArray ?: return null
        if (calls.isEmpty()) return null
        val children = ArrayList<StrictBatchChild>(calls.size)
        for (element in calls) {
            val child = element as? JsonObject ?: return null
            if (child.keys != setOf("call_id", "tool", "arguments")) return null
            val callId = child.stringValue("call_id") ?: return null
            val tool = child.stringValue("tool") ?: return null
            val arguments = child["arguments"] as? JsonObject ?: return null
            children += StrictBatchChild(callId, tool, arguments)
        }
        if (children.map { it.callId }.distinct().size != children.size) return null
        return children
    }

    private fun strictWorkspaceId(value: JsonObject): String? {
        val raw = listOfNotNull(value["workspace_id"], value["workspaceId"])
        if (raw.isEmpty()) return null
        val ids = ArrayList<String>(raw.size)
        for (element in raw) {
            val id = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: return null
            ids += id
        }
        return ids.distinct().singleOrNull()
    }

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    private data class StrictBatchChild(
        val callId: String,
        val tool: String,
        val arguments: JsonObject,
    )

    private fun isRootDiagnosticAuthority(authority: DiagnosticAuthority): Boolean =
        authority == DiagnosticAuthority.ROOT_DIRECT || authority == DiagnosticAuthority.SHIZUKU_ROOT

    private fun readBatchChildren(argumentsJson: String): List<JsonObject> = runCatching {
        val root = Json.parseToJsonElement(argumentsJson) as? JsonObject ?: return@runCatching emptyList()
        (root["calls"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    }.getOrDefault(emptyList())

    private fun workspaceIdFrom(value: JsonObject): String? {
        val ids = listOfNotNull(value["workspace_id"], value["workspaceId"]).mapNotNull { raw ->
            (raw as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
        }
        return ids.distinct().singleOrNull()
    }

    /**
     * True when this invocation actually runs through a Root workspace or a Root-stamped Run.
     * Merely having a Root channel selected is not a Root call: an ordinary SAF/internal workspace
     * keeps its own semantics even while a Root channel is the selected authority.
     */
    fun isRootContext(
        workspaceAuthority: DiagnosticAuthority,
        rootRun: Boolean,
    ): Boolean =
        workspaceAuthority == DiagnosticAuthority.ROOT_DIRECT ||
            workspaceAuthority == DiagnosticAuthority.SHIZUKU_ROOT ||
            rootRun

    /**
     * Root mutations, Shell and background jobs always require explicit approval. A Root read-only
     * call requires the dedicated [ROOT_READ_KEY] opt-in ([rootReadConfirmed]) and is never covered
     * by the ordinary global switch; non-Root tools keep their previous semantics.
     */
    fun requiresExplicitApproval(
        toolName: String,
        selectedAuthority: String = Authority.NONE.name,
        workspaceAuthority: DiagnosticAuthority = DiagnosticAuthority.NONE,
        rootRun: Boolean = false,
        rootReadConfirmed: Boolean = false,
    ): Boolean {
        if (isRootContext(workspaceAuthority, rootRun) && isRootReadOnlyTool(toolName)) {
            return !rootReadConfirmed
        }
        return toolName == RunControlTools.JOB_START ||
            toolName in ROOT_ONLY_TOOLS ||
            (toolName == "shell_exec" && selectedAuthority in ROOT_AUTHORITY_NAMES) ||
            workspaceAuthority == DiagnosticAuthority.ROOT_DIRECT ||
            workspaceAuthority == DiagnosticAuthority.SHIZUKU_ROOT ||
            // No explicit workspace authority is available: a Root-stamped call must fail closed
            // for every Root-eligible mutation/read. An explicit ordinary workspace above keeps
            // the ordinary global skip switch effective even in a mixed Root+SAF Run.
            (rootRun && workspaceAuthority == DiagnosticAuthority.NONE && toolName in ROOT_ELIGIBLE_TOOLS)
    }

    /** Project the same shell disclosure for an explicit background invocation. */
    fun shellArguments(toolName: String, arguments: JsonObject?): JsonObject? = when (toolName) {
        "shell_exec" -> arguments
        RunControlTools.JOB_START -> arguments?.takeIf {
            (it["tool"] as? JsonPrimitive)?.let { tool -> tool.isString && tool.content == "shell_exec" } == true
        }?.get("arguments") as? JsonObject
        else -> null
    }

    fun skip(settingsJson: String?): Boolean = flag(settingsJson, KEY)

    /** Root read-only opt-in from one settings document (frozen snapshot or live Agent). */
    fun skipRootReadConfirmations(settingsJson: String?): Boolean = flag(settingsJson, ROOT_READ_KEY)

    /** An opt-in cannot retroactively relax an old session; a live off switch revokes it. */
    fun allows(snapshotSettingsJson: String?, liveSettingsJson: String?): Boolean =
        skip(snapshotSettingsJson) && skip(liveSettingsJson)

    /**
     * Root read-only confirmation can be skipped only when the frozen snapshot *and* the live
     * Agent settings both opted in; the ordinary [allows] switch is not consulted at all.
     */
    fun allowsRootRead(snapshotSettingsJson: String?, liveSettingsJson: String?): Boolean =
        skipRootReadConfirmations(snapshotSettingsJson) && skipRootReadConfirmations(liveSettingsJson)

    fun update(settingsJson: String?, skip: Boolean): String = write(settingsJson, KEY, skip)

    /** Agent-editor writer for the Root read-only opt-in (separate key, same document). */
    fun updateRootReadConfirmations(settingsJson: String?, skip: Boolean): String =
        write(settingsJson, ROOT_READ_KEY, skip)

    private fun flag(settingsJson: String?, key: String): Boolean = runCatching {
        ((Json.parseToJsonElement(settingsJson ?: "{}") as? JsonObject)
            ?.get(key) as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true
    }.getOrDefault(false)

    private fun write(settingsJson: String?, key: String, skip: Boolean): String {
        val previous = runCatching { Json.parseToJsonElement(settingsJson ?: "{}") as? JsonObject }
            .getOrNull()?.toMutableMap() ?: mutableMapOf()
        previous[key] = JsonPrimitive(skip)
        return JsonObject(previous).toString()
    }
}
