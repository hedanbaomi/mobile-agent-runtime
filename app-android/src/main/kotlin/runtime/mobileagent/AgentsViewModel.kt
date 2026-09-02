// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import runtime.mobileagent.data.InstalledSkill
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.AgentWorkspaceDefault
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.Conversation
import runtime.mobileagent.domain.ConversationWorkspaceBinding
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.SnapshotGrantBinding
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.domain.isRerankEndpoint
import runtime.mobileagent.feature.agents.*
import runtime.mobileagent.provider.SecretRedactor
import runtime.mobileagent.skills.PermissionGrant

/**
 * Narrow persistence seam for the Agent grant editor.  Implementations belong to AppContainer;
 * the view model never reaches into SQLite or exposes a workspace root/URI.  All grant writes
 * return the persisted revision or throw a typed repository conflict.
 */
interface AgentGrantPort {
    val available: Boolean
        get() = false
    val unavailableMessage: String
        get() = "授权存储未就绪。"

    fun listWorkspaces(): List<Workspace> = emptyList()
    fun listGrants(agentId: String, includeRevoked: Boolean = true): List<CapabilityGrant> = emptyList()
    fun saveGrant(grant: CapabilityGrant): CapabilityGrant = grantPortUnavailable(unavailableMessage)
    fun revokeGrant(grantId: String, expectedRevision: Long): CapabilityGrant = grantPortUnavailable(unavailableMessage)
    fun currentPolicyVersion(): Long = 0L
    fun listSnapshotBindings(snapshotId: String): List<SnapshotGrantBinding> = emptyList()
    fun bindSnapshot(binding: SnapshotGrantBinding): SnapshotGrantBinding = grantPortUnavailable(unavailableMessage)

    /** Existing Skill repository records are reused; no second Skill/package model is created. */
    fun listInstalledSkills(): List<InstalledSkill> = emptyList()
    fun listSkillGrants(installId: String): List<PermissionGrant> = emptyList()
    fun saveSkillGrant(grant: PermissionGrant): PermissionGrant = grantPortUnavailable(unavailableMessage)
    fun revokeSkillGrant(grantId: String, expectedRevision: Int): PermissionGrant = grantPortUnavailable(unavailableMessage)

    companion object {
        val EMPTY: AgentGrantPort = object : AgentGrantPort {
            override val available = false
            override val unavailableMessage = "授权存储未就绪；请稍后重试。"
        }
    }
}

/** AppContainer implements this provider so the VM remains decoupled from repository classes. */
interface AgentGrantPortProvider {
    val agentGrantPort: AgentGrantPort
}

/**
 * Narrow app-facing boundary for the durable workspace choice of a conversation and the
 * default used when creating a new one.  Implementations belong to the process-lifetime
 * RuntimeIntegration/container; UI code never reaches into SQLite and never receives a root or
 * URI.  A missing implementation is deliberately fail-closed: callers may still create an
 * unbound, no-workspace conversation, but they must not infer an Agent default or reuse another
 * conversation's binding.
 */
interface ThreadWorkspacePort {
    val available: Boolean
        get() = false
    val unavailableMessage: String
        get() = "线程工作区绑定存储未就绪。"

    fun conversationWorkspaceBinding(conversationId: String): ConversationWorkspaceBinding? = null
    fun bindConversationWorkspace(binding: ConversationWorkspaceBinding): ConversationWorkspaceBinding =
        error(unavailableMessage)

    fun agentWorkspaceDefault(agentId: String): AgentWorkspaceDefault? = null

    /** Resolve only a valid, active default for a *new* conversation; never fall back. */
    fun resolveNewThreadWorkspace(agentId: String): String? = null

    fun saveAgentWorkspaceDefault(default: AgentWorkspaceDefault): AgentWorkspaceDefault =
        error(unavailableMessage)
}

/** AppContainer exposes the one canonical conversation/default workspace adapter through this. */
interface ThreadWorkspacePortProvider {
    val threadWorkspacePort: ThreadWorkspacePort
}

/**
 * Runtime half of the thread-workspace boundary.  Snapshot creation and context freezing are
 * kept together so a caller cannot accidentally create a snapshot containing the union of all
 * Agent workspaces.  The selected workspace is nullable for an explicit unbound conversation;
 * the implementation must then expose no workspace backend/grants.
 */
interface ThreadWorkspaceRuntimePort {
    val available: Boolean
        get() = false
    val unavailableMessage: String
        get() = "线程工作区运行时未就绪。"

    fun createSnapshotWithWorkspace(
        agentId: String,
        workspaceId: String?,
        snapshotId: String = EntityId.random().value,
        at: String = Utc.nowIso(),
    ): AgentSnapshot = error(unavailableMessage)

    fun createToolExecutionContextForWorkspace(
        snapshot: AgentSnapshot,
        workspaceId: String?,
        modelCallId: String,
        sessionIdentity: String,
        configSnapshotHash: String,
        taskIdentity: String = "",
        skillId: String? = null,
        skillRevision: Long? = null,
        trustedSkillEnvelope: Boolean = skillId != null,
    ): runtime.mobileagent.tooling.ToolExecutionContext = error(unavailableMessage)
}

/** AppContainer exposes the same process-lifetime runtime adapter used by ChatViewModel. */
interface ThreadWorkspaceRuntimePortProvider {
    val threadWorkspaceRuntimePort: ThreadWorkspaceRuntimePort
}

private fun grantPortUnavailable(message: String): Nothing = error(message)

private data class GrantUiData(
    val workspaces: List<AgentWorkspaceUi> = emptyList(),
    val grants: List<AgentGrantUi> = emptyList(),
    val trustedSkills: List<AgentTrustedSkillUi> = emptyList(),
    val snapshotBindings: List<AgentSnapshotGrantUi> = emptyList(),
    val workspaceDefault: AgentWorkspaceDefault? = null,
)

/**
 * The generic Agent editor has no run or conversation identity.  Scoped grants therefore
 * cannot be manufactured here: doing so would either fail canonical validation later or, in a
 * permissive adapter, create a grant which can never match a real runtime identity.
 */
internal const val AGENT_EDITOR_LIFETIME_CONTEXT_ERROR =
    "TASK/SESSION 授权只能由具有真实任务或会话上下文的运行时流程创建；当前 Agent 编辑页没有该上下文，未保存任何授权。"

private fun isAgentEditorLifetimeAllowed(lifetime: GrantLifetime): Boolean =
    lifetime == GrantLifetime.ONCE || lifetime == GrantLifetime.PERSISTENT

/** Validate the complete draft before the profile or any grant mutation is attempted. */
internal fun validateAgentGrantDraftForContext(editor: AgentEditorUi) {
    val draft = editor.grantDraft ?: return
    require(isAgentEditorLifetimeAllowed(draft.lifetime)) { AGENT_EDITOR_LIFETIME_CONTEXT_ERROR }
    val workspace = draft.workspaceId?.let { workspaceId ->
        editor.workspaces.firstOrNull { it.id == workspaceId && it.enabled }
            ?: error("请选择可用工作区。")
    }
    val skill = draft.skillInstallId?.let { installId ->
        editor.trustedSkills.firstOrNull { it.installId == installId && it.enabled && it.trusted }
            ?: error("Skill 安装或包授权未验证，不能创建绑定。")
    }
    val pathScope = draft.pathScope?.trim()?.ifBlank { null }
    require(pathScope == null || workspace != null) { "填写相对范围前请选择工作区。" }
    require(draft.capability != CapabilityId(CapabilityId.SHELL_EXECUTE) || (workspace == null && pathScope == null)) {
        "shell.execute 授权不接受工作区或文件范围。"
    }
    // Keep the lookup in this validation path so stale Skill/workspace inventory is rejected
    // before AgentProfile persistence; the canonical constructor performs final validation.
    if (skill != null) require(skill.packageHash.isNotBlank()) { "Skill 包身份缺失。" }
}

/**
 * Build and persist one editor grant through the injected port.  This is intentionally a small
 * pure boundary around the port so the preflight/final gates are independently testable.  The
 * editor never supplies a task/session owner; scoped lifetimes are rejected before even reading
 * the policy version or invoking a write.
 */
internal fun saveAgentGrantDraft(
    editor: AgentEditorUi,
    agentId: String,
    grantPort: AgentGrantPort,
    createdAt: String = Utc.nowIso(),
): CapabilityGrant {
    require(grantPort.available) { grantPort.unavailableMessage }
    validateAgentGrantDraftForContext(editor)
    val draft = editor.grantDraft ?: error("没有待保存的能力授权。")
    val workspace = draft.workspaceId?.let { workspaceId ->
        editor.workspaces.firstOrNull { it.id == workspaceId && it.enabled }
            ?: error("请选择可用工作区。")
    }
    val skill = draft.skillInstallId?.let { installId ->
        editor.trustedSkills.firstOrNull { it.installId == installId && it.enabled && it.trusted }
            ?: error("Skill 安装或包授权未验证，不能创建绑定。")
    }
    val pathScope = draft.pathScope?.trim()?.ifBlank { null }

    // Final fail-closed gate immediately before constructing the canonical write payload.
    validateAgentGrantDraftForContext(editor)
    val grant = CapabilityGrant(
        grantId = EntityId.random().value,
        agentId = agentId,
        capability = draft.capability,
        skillInstallId = skill?.installId,
        packageHash = skill?.packageHash,
        workspaceId = workspace?.id,
        pathScope = pathScope,
        lifetime = draft.lifetime,
        policyVersion = grantPort.currentPolicyVersion(),
        createdAt = createdAt,
        // The generic editor has no valid identity for either scoped lifetime.
        taskId = null,
        sessionId = null,
    )
    require(isAgentEditorLifetimeAllowed(grant.lifetime) && grant.taskId == null && grant.sessionId == null) {
        AGENT_EDITOR_LIFETIME_CONTEXT_ERROR
    }
    val persisted = grantPort.saveGrant(grant)
    require(
        persisted.agentId == agentId &&
            persisted.capability == draft.capability &&
            persisted.lifetime == grant.lifetime &&
            persisted.taskId == null &&
            persisted.sessionId == null,
    ) { "Capability grant save returned an unexpected binding" }
    return persisted
}

private val READ_ONLY_WORKSPACE_CAPABILITIES = listOf(
    CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
    CapabilityId(CapabilityId.FILE_LIST),
    CapabilityId(CapabilityId.FILE_STAT),
    CapabilityId(CapabilityId.FILE_READ_TEXT),
)

private val READ_WRITE_WORKSPACE_CAPABILITIES = READ_ONLY_WORKSPACE_CAPABILITIES + listOf(
    CapabilityId(CapabilityId.FILE_WRITE_TEXT),
    CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY),
    CapabilityId(CapabilityId.FILE_MOVE),
    CapabilityId(CapabilityId.FILE_DELETE),
)

/**
 * Persist the simple workspace preset as ordinary canonical grants. Existing
 * matching active persistent grants are reused, so repeating the shortcut is
 * idempotent and never broadens a relative-path or Skill-bound grant.
 */
internal fun saveAgentWorkspaceGrantPreset(
    editor: AgentEditorUi,
    agentId: String,
    grantPort: AgentGrantPort,
    createdAt: String = Utc.nowIso(),
): List<CapabilityGrant> {
    require(grantPort.available) { grantPort.unavailableMessage }
    val preset = editor.workspaceGrantPreset ?: return emptyList()
    val workspace = editor.workspaces.firstOrNull { it.id == preset.workspaceId && it.enabled }
        ?: error("请选择可用工作区。")
    require(workspace.readable) { "该工作区没有读取权限。" }
    if (preset.access == AgentWorkspaceAccessPreset.READ_WRITE) {
        require(workspace.writable) { "该工作区仅有读取权限，不能授予读写工具。" }
    }
    val capabilities = when (preset.access) {
        AgentWorkspaceAccessPreset.READ_ONLY -> READ_ONLY_WORKSPACE_CAPABILITIES
        AgentWorkspaceAccessPreset.READ_WRITE -> READ_WRITE_WORKSPACE_CAPABILITIES
    }
    val existing = editor.grants.asSequence()
        .filter { it.enabled && !it.grant.revoked && !it.expired }
        .map { it.grant }
        .filter {
            it.workspaceId == workspace.id &&
                it.pathScope == null &&
                it.skillInstallId == null &&
                it.lifetime == GrantLifetime.PERSISTENT
        }
        .map { it.capability }
        .toSet()
    val missing = capabilities.filterNot(existing::contains)
    if (missing.isEmpty()) return emptyList()
    val policyVersion = grantPort.currentPolicyVersion()
    return missing.map { capability ->
        val grant = CapabilityGrant(
            grantId = EntityId.random().value,
            agentId = agentId,
            capability = capability,
            workspaceId = workspace.id,
            lifetime = GrantLifetime.PERSISTENT,
            policyVersion = policyVersion,
            createdAt = createdAt,
        )
        grantPort.saveGrant(grant).also { persisted ->
            require(
                persisted.agentId == agentId &&
                    persisted.capability == capability &&
                    persisted.workspaceId == workspace.id &&
                    persisted.pathScope == null &&
                    persisted.skillInstallId == null &&
                    persisted.lifetime == GrantLifetime.PERSISTENT,
            ) { "Workspace preset grant save returned an unexpected binding" }
        }
    }
}

/**
 * Validate the Agent default before saving the profile.  The persistence repository repeats
 * this check against canonical rows; this UI-side check prevents the common partial-save case
 * where a newly selected default has no persistent grant yet.  A pending preset/draft counts
 * because [save] writes grants before the default.
 */
internal fun validateAgentWorkspaceDefaultDraft(editor: AgentEditorUi) {
    val workspaceId = editor.defaultWorkspaceId ?: return
    val workspace = editor.workspaces.firstOrNull { it.id == workspaceId && it.enabled }
        ?: error("请选择可用的默认工作区。")
    require(workspace.readable) { "默认工作区必须具备读取权限。" }
    val existing = editor.grants.any { item ->
        val grant = item.grant
        item.enabled && !grant.revoked && !item.expired && item.skillTrusted &&
            grant.workspaceId == workspaceId && grant.lifetime == GrantLifetime.PERSISTENT
    }
    val preset = editor.workspaceGrantPreset?.workspaceId == workspaceId
    val draft = editor.grantDraft?.let { draft ->
        draft.workspaceId == workspaceId && draft.lifetime == GrantLifetime.PERSISTENT &&
            draft.capability != CapabilityId(CapabilityId.SHELL_EXECUTE)
    } == true
    require(existing || preset || draft) {
        "默认工作区需要先授予该工作区的长期能力；保存不会自动扩大授权。"
    }
}

class AgentsViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val app = application as MobileAgentApp
    private val grantPort: AgentGrantPort =
        (app.container as? AgentGrantPortProvider)?.agentGrantPort ?: AgentGrantPort.EMPTY
    /**
     * Conversation/default binding is a separate canonical store from capability grants.  Keep
     * the port optional during migrations so a missing adapter creates only an explicitly
     * unbound conversation; it must never fall back to the old all-grants snapshot path.
     */
    private val threadWorkspacePort: ThreadWorkspacePort? =
        (app.container as? ThreadWorkspacePortProvider)?.threadWorkspacePort
    private val threadWorkspaceRuntimePort: ThreadWorkspaceRuntimePort? =
        (app.container as? ThreadWorkspaceRuntimePortProvider)?.threadWorkspaceRuntimePort
    private val canonicalWorkspaceSink: runtime.mobileagent.workspace.CanonicalWorkspaceSink? =
        (app.container as? runtime.mobileagent.workspace.CanonicalWorkspaceSinkProvider)?.canonicalWorkspaceSink
    val state = mutableStateOf(AgentsUiState())
    private var editorBaseline: AgentEditorUi? = null
    private var grantPortError: String? = null
    /**
     * A workspace selection made before this Agent exists.  It is committed
     * together with the Agent save, so abandoning the editor never leaves an
     * orphan grant or default behind.
     */
    private var pendingWorkspaceDraft: runtime.mobileagent.domain.WorkspaceDraft? = null

    init {
        reload()
        savedStateHandle.get<String>(EDITOR_ID_KEY)?.let(::openEditor)
    }

    fun reload() {
        val profiles = app.container.profiles
        val agents = app.container.agents.list()
        val selected = state.value.selectedAgentId
            ?: savedStateHandle.get<String>(SELECTED_AGENT_KEY)
            ?: app.container.uiPreferences.getString("selected-agent", null)
        val selectedId = selected?.takeIf { id -> agents.any { it.id == id } }
        val summary = selectedId?.let { editorFrom(it) }
        state.value = state.value.copy(
            agents = agents.map { agent ->
                AgentCardUi(agent.id, agent.name, agent.revision,
                    profiles.getModel(agent.chatProfileId)?.modelId ?: "模型不可用",
                    "${agent.knowledgeBaseIds.size} 个知识库 · ${agent.skillIds.size} 个 Skill")
            },
            selectedAgentId = selectedId,
            summary = summary,
            hasRerankerModels = profiles.listModels().any { it.isRerankEndpoint() },
            grantStoreAvailable = grantPort.available,
            grantStoreError = grantPortError,
        )
    }

    fun select(id: String) {
        if (state.value.editorDirty) return
        if (app.container.agents.get(id) == null) return
        app.container.uiPreferences.edit().putString("selected-agent", id).apply()
        savedStateHandle[SELECTED_AGENT_KEY] = id
        savedStateHandle.remove<String>(EDITOR_ID_KEY)
        val summary = editorFrom(id)
        state.value = state.value.copy(
            selectedAgentId = id,
            summary = summary,
            editor = null,
            editorDirty = false,
            editorOpen = false,
            grantStoreAvailable = grantPort.available,
            grantStoreError = grantPortError,
        )
    }

    fun openEditor(id: String?) {
        val editor = editorFrom(id)
        editorBaseline = editor
        savedStateHandle[EDITOR_ID_KEY] = id
        state.value = state.value.copy(
            error = null,
            editor = editor,
            editorDirty = false,
            editorOpen = true,
            grantStoreAvailable = grantPort.available,
            grantStoreError = grantPortError,
        )
    }

    fun edit(editor: AgentEditorUi) {
        state.value = state.value.copy(editor = editor, editorDirty = editor != editorBaseline)
    }
    fun closeEditor() {
        editorBaseline = null
        pendingWorkspaceDraft = null
        savedStateHandle.remove<String>(EDITOR_ID_KEY)
        state.value = state.value.copy(editor = null, error = null, editorDirty = false, editorOpen = false)
    }

    /**
     * Stage a workspace selection made before the Agent exists.  Nothing is
     * authorized until [save] creates the Agent and commits the draft in the
     * same flow, so cancelling the editor leaves no orphan grant or default.
     *
     * The staged draft does NOT mutate [AgentEditorUi.defaultWorkspaceId]: the
     * default is committed by the canonical sink in [commitPendingWorkspaceDraft],
     * and mutating the editor default here would make [save] also run the
     * thread-workspace default path, double-writing the same default.
     */
    fun stageWorkspaceDraft(draft: runtime.mobileagent.domain.WorkspaceDraft) {
        pendingWorkspaceDraft = draft
        val editor = state.value.editor
        if (editor != null) {
            state.value = state.value.copy(editorDirty = true)
        }
    }

    fun pendingWorkspaceDraft(): runtime.mobileagent.domain.WorkspaceDraft? = pendingWorkspaceDraft

    /** Drop any staged draft without mutating persisted state. */
    fun clearWorkspaceDraft() {
        pendingWorkspaceDraft = null
    }
    fun query(value: String) { state.value = state.value.copy(query = value) }

    fun toggleResource(id: String, enabled: Boolean) {
        state.value.editor?.let { editor ->
            edit(editor.copy(resourceBindings = editor.resourceBindings.map {
                if (it.id == id && it.selectable && (!enabled || it.available)) {
                    // Once a stale disabled binding is removed, keep it unavailable so it
                    // cannot be accidentally re-added without enabling the Skill first.
                    it.copy(enabled = enabled, selectable = it.available || enabled)
                } else it
            }))
        }
    }

    fun save(): Boolean {
        val editor = state.value.editor ?: return false
        return try {
            require(editor.name.isNotBlank()) { "请填写 Agent 名称。" }
            val model = editor.chatModelId ?: error("请选择 Chat 模型。")
            require(editor.retrievalMode in setOf("explicit", "automatic")) { "检索模式必须是 explicit 或 automatic。" }
            require(editor.resourceBindings.none { it.type == "skill" && it.enabled && !it.available }) {
                "存在已绑定但当前未启用的技能，请先在技能页启用后再保存。"
            }
            val defaultChanged = editor.defaultWorkspaceId != editorBaseline?.defaultWorkspaceId
            require(!defaultChanged || threadWorkspacePort?.available == true) {
                threadWorkspacePort?.unavailableMessage ?: "线程工作区绑定存储未就绪。"
            }
            if (defaultChanged && editor.defaultWorkspaceId != null) {
                validateAgentWorkspaceDefaultDraft(editor)
            }
            val previous = editor.id?.let { app.container.agents.get(it) }
            val grantChanges = editor.grantDraft != null || editor.workspaceGrantPreset != null ||
                editor.grants.any { !it.enabled && !it.grant.revoked && !it.expired }
            require(!grantChanges || grantPort.available) {
                grantPort.unavailableMessage
            }
            if (editor.grantDraft != null) validateAgentGrantDraftForContext(editor)
            editor.workspaceGrantPreset?.let { preset ->
                val workspace = editor.workspaces.firstOrNull { it.id == preset.workspaceId && it.enabled }
                    ?: error("请选择可用工作区。")
                require(workspace.readable) { "该工作区没有读取权限。" }
                if (preset.access == AgentWorkspaceAccessPreset.READ_WRITE) {
                    require(workspace.writable) { "该工作区仅有读取权限，不能授予读写工具。" }
                }
            }
            val parameters = JsonObject(editor.parameters.filterValues { it.isNotBlank() }.mapValues { Json.parseToJsonElement(it.value) })
            val profile = AgentProfile(
                id = previous?.id ?: EntityId.random().value, name = editor.name.trim(),
                promptRevisionId = previous?.promptRevisionId ?: EntityId.random().value,
                chatProfileId = model, visionProfileId = editor.visionModelId,
                embeddingProfileId = previous?.embeddingProfileId, rerankerProfileId = editor.rerankerModelId,
                knowledgeBaseIds = editor.resourceBindings.filter { it.type == "knowledge" && it.enabled }.map { it.id },
                skillIds = editor.resourceBindings.filter { it.type == "skill" && it.enabled }.map { it.id },
                retrievalMode = editor.retrievalMode, revision = (previous?.revision ?: 0) + 1,
                parameterOverridesJson = parameters.toString(), contextPolicyJson = previous?.contextPolicyJson ?: "{}",
                permissionSettingsJson = previous?.permissionSettingsJson ?: "{}",
            )
            val createdNew = previous == null
            var savedId: String? = null
            try {
                val saved = app.container.agents.saveWithPrompt(profile, editor.prompt)
                savedId = saved.id
                persistGrantChanges(editor, saved.id)
                if (defaultChanged) persistWorkspaceDefault(editor, saved.id)
                val hadDraft = pendingWorkspaceDraft != null
                commitPendingWorkspaceDraft(saved.id)
                val workspaceUpdated = defaultChanged || hadDraft ||
                    editor.workspaceGrantPreset != null || editor.grantDraft != null
                app.container.uiPreferences.edit().putString("selected-agent", saved.id).apply()
                savedStateHandle[SELECTED_AGENT_KEY] = saved.id
                savedStateHandle.remove<String>(EDITOR_ID_KEY)
                editorBaseline = null
                pendingWorkspaceDraft = null
                state.value = state.value.copy(
                    selectedAgentId = saved.id,
                    editor = null,
                    editorOpen = false,
                    editorDirty = false,
                    error = null,
                    status = if (workspaceUpdated) {
                        "已保存 Agent、工作区默认值和能力授权；新会话将使用默认值，旧会话不变。"
                    } else {
                        "已保存 Agent；旧会话快照不变。"
                    },
                    grantStoreAvailable = grantPort.available,
                    grantStoreError = grantPortError,
                )
                reload()
                true
            } catch (failure: Exception) {
                if (createdNew && savedId != null) {
                    rollbackNewAgent(savedId)
                }
                throw failure
            }
        } catch (error: Exception) {
            state.value = state.value.copy(error = SecretRedactor.redact(error.message ?: "保存 Agent 失败。"))
            false
        }
    }

    fun restorePrompt(revisionId: String) {
        val revision = app.container.agents.getPromptRevision(revisionId) ?: return
        val editor = state.value.editor ?: return
        if (revision.agentId != editor.id) return
        edit(editor.copy(prompt = revision.template))
        state.value = state.value.copy(status = "已载入历史 Prompt。保存将创建新修订，不覆盖历史。")
    }

    fun createConversation(): String? = try {
        val agentId = state.value.selectedAgentId ?: error("请先选择已保存的 Agent。")
        val now = Utc.nowIso()
        val workspaceId = threadWorkspacePort
            ?.takeIf { it.available }
            ?.resolveNewThreadWorkspace(agentId)
        // RuntimeIntegration owns the exact workspace-filtered snapshot grant set.  During a
        // staged migration, a missing runtime adapter may still create a safe, unbound snapshot;
        // it must never use createSnapshotWithCurrentGrants(), which would union every workspace.
        val snapshot = threadWorkspaceRuntimePort
            ?.takeIf { it.available }
            ?.createSnapshotWithWorkspace(agentId, workspaceId, at = now)
            ?: app.container.agents.createSnapshot(agentId)
        require(snapshot.agentId == agentId) { "Workspace snapshot belongs to another Agent" }
        if (workspaceId != null) {
            val port = threadWorkspacePort ?: error("线程工作区绑定存储未就绪。")
            require(port.available) { port.unavailableMessage }
        }
        captureMcpSnapshot(app.container, snapshot.id, agentId)
        val conversation = Conversation(EntityId.random().value, snapshot.id, "新对话", now, now)
        app.container.conversations.create(conversation)
        if (workspaceId != null) {
            val port = threadWorkspacePort ?: error("线程工作区绑定存储未就绪。")
            val binding = ConversationWorkspaceBinding(
                sessionId = conversation.id,
                workspaceId = workspaceId,
                boundAt = now,
                revision = 1L,
            )
            val persisted = port.bindConversationWorkspace(binding)
            require(persisted == binding) { "会话工作区绑定保存返回了不一致的绑定。" }
        }
        reload()
        conversation.id
    } catch (error: Exception) {
        state.value = state.value.copy(error = SecretRedactor.redact(error.message ?: "创建会话失败。"))
        null
    }

    private fun persistGrantChanges(editor: AgentEditorUi, agentId: String) {
        // Validate before revoking existing grants as well as before saving the new one.  An old
        // TASK/SESSION draft must not cause any grant-side mutation from this context-free page.
        if (editor.grantDraft != null) validateAgentGrantDraftForContext(editor)
        editor.grants
            .filter { !it.grant.revoked && !it.expired && !it.enabled }
            .forEach { pending ->
                val revoked = grantPort.revokeGrant(pending.grant.grantId, pending.grant.revision)
                require(revoked.revoked) { "Capability grant revoke did not persist" }
            }

        if (editor.grantDraft != null) saveAgentGrantDraft(editor, agentId, grantPort)
        if (editor.workspaceGrantPreset != null) saveAgentWorkspaceGrantPreset(editor, agentId, grantPort)
    }

    /**
     * Commit a workspace selection staged before the Agent existed. Grant and
     * Agent default are written atomically by the canonical sink, so a failure
     * leaves no half-configured Agent. A staged draft only applies to a
     * newly-created Agent; editing an existing Agent never goes through the
     * draft path because the editor already supplies an agent id.
     */
    private fun commitPendingWorkspaceDraft(agentId: String) {
        val draft = pendingWorkspaceDraft ?: return
        val sink = canonicalWorkspaceSink ?: error("工作区写入通道未就绪。")
        // The draft commit is part of the Agent save and must complete before
        // save() reports success, so an abandoned editor never leaves a
        // half-configured Agent. It may reattach a privileged backend, hence
        // the blocking bridge over the suspend sink.
        val result = kotlinx.coroutines.runBlocking { sink.commitDraft(draft, agentId) }
        if (result is runtime.mobileagent.integration.WorkspaceAccessResult.Failure) {
            error("保存工作区授权失败：${result.code.name}")
        }
        pendingWorkspaceDraft = null
    }

    /**
     * A newly created Agent that failed its workspace commit must not remain
     * as a half-configured profile with orphan grants.
     */
    private fun rollbackNewAgent(agentId: String) {
        runCatching {
            grantPort.listGrants(agentId, includeRevoked = true).forEach { pending ->
                if (!pending.revoked) {
                    runCatching { grantPort.revokeGrant(pending.grantId, pending.revision) }
                }
            }
        }
        runCatching { app.container.agents.delete(agentId) }
    }

    private fun persistWorkspaceDefault(editor: AgentEditorUi, agentId: String) {
        val port = threadWorkspacePort ?: error("线程工作区绑定存储未就绪。")
        require(port.available) { port.unavailableMessage }
        val current = port.agentWorkspaceDefault(agentId)
        val currentRevision = current?.revision ?: 0L
        val expectedRevision = editorBaseline?.defaultWorkspaceRevision ?: 0L
        require(currentRevision == expectedRevision) { "默认工作区已被其他操作修改，请重新打开 Agent 后再保存。" }
        if (current?.workspaceId == editor.defaultWorkspaceId) return
        val candidate = if (current == null) {
            AgentWorkspaceDefault(
                agentId = agentId,
                workspaceId = editor.defaultWorkspaceId,
                revision = 1L,
                updatedAt = Utc.nowIso(),
            )
        } else {
            current.copy(
                workspaceId = editor.defaultWorkspaceId,
                revision = current.revision + 1L,
                updatedAt = Utc.nowIso(),
            )
        }
        val persisted = port.saveAgentWorkspaceDefault(candidate)
        require(
            persisted.agentId == agentId &&
                persisted.workspaceId == candidate.workspaceId &&
                persisted.revision == candidate.revision,
        ) { "默认工作区保存返回了不一致的绑定。" }
    }

    private fun editorFrom(id: String?): AgentEditorUi {
        val agent = id?.let { app.container.agents.get(it) }
        val options = app.container.profiles.listModels().map { model ->
            val provider = app.container.profiles.getProvider(model.providerId)
            AgentModelOptionUi(model.id, "${provider?.name.orEmpty()} / ${model.modelId}", model.role.name, model.capabilities)
        }
        val knowledge = app.container.db.query("SELECT id,name FROM knowledge_bases WHERE deleted_at IS NULL ORDER BY name,id").map { row ->
            AgentResourceBindingUi(row.string("id"), row.string("name"), "knowledge",
                row.string("id") in agent?.knowledgeBaseIds.orEmpty(), "仅本 Agent 可检索已绑定的知识库")
        }
        val grantData = loadGrantData(agent?.id)
        val skills = grantData.trustedSkills.map { skill ->
            val bound = skill.installId in agent?.skillIds.orEmpty()
            AgentResourceBindingUi(
                id = skill.installId,
                name = skill.name,
                type = "skill",
                enabled = bound,
                // A disabled Skill cannot be newly selected, but an existing
                // stale binding must remain removable so the editor is not trapped.
                selectable = (skill.enabled && skill.trusted) || bound,
                available = skill.enabled && skill.trusted,
                permissionSummary = when {
                    !skill.enabled && bound -> "已绑定但当前未启用；请先在技能页启用后再保存"
                    !skill.enabled -> "未启用；请先在技能页启用后绑定"
                    !skill.trusted -> "包身份或 Skill 授权未验证"
                    else -> "执行仍受当前逐资源授权约束"
                },
            )
        }
        val revisions = agent?.let { app.container.agents.listPromptRevisions(it.id) }.orEmpty()
        val prompt = agent?.let { app.container.agents.getPromptRevision(it.promptRevisionId)?.template }.orEmpty()
        return AgentEditorUi(
            id = agent?.id, name = agent?.name.orEmpty(), modelOptions = options,
            chatModelId = agent?.chatProfileId, visionModelId = agent?.visionProfileId,
            embeddingModelId = null, rerankerModelId = agent?.rerankerProfileId,
            prompt = prompt, promptRevisions = revisions.mapIndexed { index, item ->
                PromptRevisionUi(item.id, index + 1, item.id.take(8), item.template, item.createdAt, item.parentRevisionId, item.id == agent?.promptRevisionId)
            },
            parameters = agent?.parameterOverridesJson?.let { raw ->
                runCatching { Json.parseToJsonElement(raw).jsonObject.mapValues { it.value.toString() } }.getOrDefault(emptyMap())
            }.orEmpty(),
            resourceBindings = knowledge + skills,
            workspaces = grantData.workspaces,
            grants = grantData.grants,
            trustedSkills = grantData.trustedSkills,
            snapshotGrantBindings = grantData.snapshotBindings,
            defaultWorkspaceId = grantData.workspaceDefault?.workspaceId,
            defaultWorkspaceRevision = grantData.workspaceDefault?.revision ?: 0L,
            workspacePresetWorkspaceId = grantData.workspaces.firstOrNull { it.enabled }?.id,
            retrievalMode = agent?.retrievalMode ?: "explicit",
            snapshotLabel = "用此智能体新建会话时会冻结当前配置和能力授权；现有会话不会新增工具，撤权仍立即生效。",
            revision = agent?.revision ?: 0,
        )
    }

    private fun loadGrantData(agentId: String?): GrantUiData {
        grantPortError = null
        if (!grantPort.available) {
            grantPortError = grantPort.unavailableMessage
            return GrantUiData()
        }
        return try {
            val workspaceDefault = agentId?.let { threadWorkspacePort?.agentWorkspaceDefault(it) }
            val workspaceRows = grantPort.listWorkspaces()
            val workspaceById = workspaceRows.associateBy { it.id }
            val workspaces = workspaceRows.map { workspace ->
                AgentWorkspaceUi(
                    id = workspace.id,
                    displayName = workspace.displayName,
                    backendType = workspace.backendType,
                    readable = workspace.readable,
                    writable = workspace.writable,
                    quotaBytes = workspace.quotaBytes,
                    maxFileBytes = workspace.maxFileBytes,
                    enabled = workspace.enabled,
                    revision = workspace.revision,
                )
            }
            val installedSkills = grantPort.listInstalledSkills()
            val permissionGrants = installedSkills.associate { skill ->
                skill.installId to grantPort.listSkillGrants(skill.installId)
            }
            val trustedSkills = installedSkills.map { skill ->
                val permission = permissionGrants[skill.installId]
                    .orEmpty()
                    .firstOrNull { it.packageHash == skill.packageHash && !it.revoked }
                AgentTrustedSkillUi(
                    installId = skill.installId,
                    packageHash = skill.packageHash,
                    name = skill.name,
                    enabled = skill.enabled,
                    trusted = skill.enabled && permission != null,
                    capabilities = permission?.capabilities.orEmpty(),
                    grantRevision = permission?.revision ?: 0,
                )
            }
            val now = Utc.nowIso()
            val grants = agentId?.let { grantPort.listGrants(it, includeRevoked = true) }.orEmpty().map { grant ->
                val skill = grant.skillInstallId?.let { installId -> installedSkills.firstOrNull { it.installId == installId } }
                val skillPermission = skill?.let { installed ->
                    permissionGrants[installed.installId].orEmpty().firstOrNull {
                        it.packageHash == installed.packageHash && !it.revoked
                    }
                }
                val skillTrusted = grant.skillInstallId == null ||
                    (skill != null && skill.enabled && skillPermission != null && skillPermission.packageHash == grant.packageHash)
                val expired = isGrantExpired(grant, now)
                AgentGrantUi(
                    grant = grant,
                    workspaceName = grant.workspaceId?.let { workspaceById[it]?.displayName },
                    skillName = skill?.name,
                    expired = expired,
                    enabled = !grant.revoked && !expired && skillTrusted,
                    skillTrusted = skillTrusted,
                )
            }
            val snapshotBindings = agentId?.let { app.container.agents.snapshot(it) }?.let { snapshot ->
                grantPort.listSnapshotBindings(snapshot.id).map { binding ->
                    AgentSnapshotGrantUi(binding, binding.workspaceId?.let { workspaceById[it]?.displayName })
                }
            }.orEmpty()
            GrantUiData(workspaces, grants, trustedSkills, snapshotBindings, workspaceDefault)
        } catch (error: Exception) {
            grantPortError = SecretRedactor.redact(error.message ?: "读取 Agent 授权失败。")
            GrantUiData()
        }
    }

    private fun isGrantExpired(grant: CapabilityGrant, now: String): Boolean {
        val expiresAt = grant.expiresAt ?: return false
        val nowInstant = runCatching { java.time.Instant.parse(now) }.getOrNull() ?: return true
        val expiry = runCatching { java.time.Instant.parse(expiresAt) }.getOrNull() ?: return true
        return !expiry.isAfter(nowInstant)
    }

    private companion object {
        const val SELECTED_AGENT_KEY = "agents.selectedAgentId"
        const val EDITOR_ID_KEY = "agents.editorId"
    }
}
