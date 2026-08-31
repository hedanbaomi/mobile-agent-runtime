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
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.Conversation
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

private fun grantPortUnavailable(message: String): Nothing = error(message)

private data class GrantUiData(
    val workspaces: List<AgentWorkspaceUi> = emptyList(),
    val grants: List<AgentGrantUi> = emptyList(),
    val trustedSkills: List<AgentTrustedSkillUi> = emptyList(),
    val snapshotBindings: List<AgentSnapshotGrantUi> = emptyList(),
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

class AgentsViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val app = application as MobileAgentApp
    private val grantPort: AgentGrantPort =
        (app.container as? AgentGrantPortProvider)?.agentGrantPort ?: AgentGrantPort.EMPTY
    val state = mutableStateOf(AgentsUiState())
    private var editorBaseline: AgentEditorUi? = null
    private var grantPortError: String? = null

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
        savedStateHandle.remove<String>(EDITOR_ID_KEY)
        state.value = state.value.copy(editor = null, error = null, editorDirty = false, editorOpen = false)
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
            val previous = editor.id?.let { app.container.agents.get(it) }
            val grantChanges = editor.grantDraft != null || editor.grants.any {
                !it.enabled && !it.grant.revoked && !it.expired
            }
            require(!grantChanges || grantPort.available) {
                grantPort.unavailableMessage
            }
            if (editor.grantDraft != null) validateAgentGrantDraftForContext(editor)
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
            val saved = app.container.agents.saveWithPrompt(profile, editor.prompt)
            persistGrantChanges(editor, saved.id)
            app.container.uiPreferences.edit().putString("selected-agent", saved.id).apply()
            savedStateHandle[SELECTED_AGENT_KEY] = saved.id
            savedStateHandle.remove<String>(EDITOR_ID_KEY)
            editorBaseline = null
            state.value = state.value.copy(
                selectedAgentId = saved.id,
                editor = null,
                editorOpen = false,
                editorDirty = false,
                error = null,
                status = "已保存 Agent；旧会话快照不变。",
                grantStoreAvailable = grantPort.available,
                grantStoreError = grantPortError,
            )
            reload()
            true
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
        require(grantPort.available) { grantPort.unavailableMessage }
        val now = Utc.nowIso()
        val activeGrants = grantPort.listGrants(agentId, includeRevoked = false)
            .filterNot { isGrantExpired(it, now) }
        val snapshot = app.container.agents.createSnapshot(agentId)
        activeGrants.forEach { grant ->
            val binding = SnapshotGrantBinding(
                snapshotId = snapshot.id,
                grantId = grant.grantId,
                capability = grant.capability,
                workspaceId = grant.workspaceId,
                pathScope = grant.pathScope,
                policyVersion = grant.policyVersion,
                boundAt = now,
            )
            val persisted = grantPort.bindSnapshot(binding)
            require(persisted == binding) { "Snapshot grant binding save returned an unexpected binding" }
        }
        captureMcpSnapshot(app.container, snapshot.id, agentId)
        val conversation = Conversation(EntityId.random().value, snapshot.id, "新对话", now, now)
        app.container.conversations.create(conversation)
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
            retrievalMode = agent?.retrievalMode ?: "explicit",
            snapshotLabel = "修改配置只影响新会话；现有会话保留不可变快照，撤权立即生效。",
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
            GrantUiData(workspaces, grants, trustedSkills, snapshotBindings)
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
