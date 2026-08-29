// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.Conversation
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.feature.agents.*
import runtime.mobileagent.provider.SecretRedactor

class AgentsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MobileAgentApp
    val state = mutableStateOf(AgentsUiState())

    init { reload() }

    fun reload() {
        val profiles = app.container.profiles
        val agents = app.container.agents.list()
        val selected = state.value.selectedAgentId ?: app.container.uiPreferences.getString("selected-agent", null)
        state.value = state.value.copy(
            agents = agents.map { agent ->
                AgentCardUi(agent.id, agent.name, agent.revision,
                    profiles.getModel(agent.chatProfileId)?.modelId ?: "模型不可用",
                    "${agent.knowledgeBaseIds.size} 个知识库 · ${agent.skillIds.size} 个 Skill")
            },
            selectedAgentId = selected?.takeIf { id -> agents.any { it.id == id } },
        )
    }

    fun select(id: String) {
        if (app.container.agents.get(id) == null) return
        app.container.uiPreferences.edit().putString("selected-agent", id).apply()
        state.value = state.value.copy(selectedAgentId = id)
        openEditor(id)
    }

    fun openEditor(id: String?) {
        val agent = id?.let { app.container.agents.get(it) }
        val options = app.container.profiles.listModels().map { model ->
            val provider = app.container.profiles.getProvider(model.providerId)
            AgentModelOptionUi(model.id, "${provider?.name.orEmpty()} / ${model.modelId}", model.role.name, model.capabilities)
        }
        val knowledge = app.container.db.query("SELECT id,name FROM knowledge_bases WHERE deleted_at IS NULL ORDER BY name,id").map { row ->
            AgentResourceBindingUi(row.string("id"), row.string("name"), "knowledge",
                row.string("id") in agent?.knowledgeBaseIds.orEmpty(), "仅本 Agent 可检索已绑定的知识库")
        }
        val skills = app.container.skills.list().map { skill ->
            AgentResourceBindingUi(skill.installId, skill.name, "skill", skill.installId in agent?.skillIds.orEmpty(),
                if (skill.enabled) "执行仍受当前逐资源授权约束" else "未启用；绑定不会自动授予权限")
        }
        val revisions = agent?.let { app.container.agents.listPromptRevisions(it.id) }.orEmpty()
        val prompt = agent?.let { app.container.agents.getPromptRevision(it.promptRevisionId)?.template }.orEmpty()
        state.value = state.value.copy(error = null, editor = AgentEditorUi(
            id = agent?.id, name = agent?.name.orEmpty(), modelOptions = options,
            chatModelId = agent?.chatProfileId, visionModelId = agent?.visionProfileId,
            embeddingModelId = agent?.embeddingProfileId, rerankerModelId = agent?.rerankerProfileId,
            prompt = prompt, promptRevisions = revisions.mapIndexed { index, item ->
                PromptRevisionUi(item.id, index + 1, item.id.take(8), item.template, item.createdAt, item.parentRevisionId, item.id == agent?.promptRevisionId)
            },
            parameters = agent?.parameterOverridesJson?.let { raw ->
                runCatching { Json.parseToJsonElement(raw).jsonObject.mapValues { it.value.toString() } }.getOrDefault(emptyMap())
            }.orEmpty(),
            resourceBindings = knowledge + skills, retrievalMode = agent?.retrievalMode ?: "explicit",
            snapshotLabel = "修改配置只影响新会话；现有会话保留不可变快照，撤权立即生效。", revision = agent?.revision ?: 0,
        ))
    }

    fun edit(editor: AgentEditorUi) { state.value = state.value.copy(editor = editor) }
    fun closeEditor() { state.value = state.value.copy(editor = null, error = null) }
    fun query(value: String) { state.value = state.value.copy(query = value) }

    fun toggleResource(id: String, enabled: Boolean) {
        state.value.editor?.let { editor ->
            edit(editor.copy(resourceBindings = editor.resourceBindings.map { if (it.id == id) it.copy(enabled = enabled) else it }))
        }
    }

    fun save(): Boolean {
        val editor = state.value.editor ?: return false
        return try {
            require(editor.name.isNotBlank()) { "请填写 Agent 名称。" }
            val model = editor.chatModelId ?: error("请选择 Chat 模型。")
            require(editor.retrievalMode in setOf("explicit", "automatic")) { "检索模式必须是 explicit 或 automatic。" }
            val previous = editor.id?.let { app.container.agents.get(it) }
            val parameters = JsonObject(editor.parameters.filterValues { it.isNotBlank() }.mapValues { Json.parseToJsonElement(it.value) })
            val profile = AgentProfile(
                id = previous?.id ?: EntityId.random().value, name = editor.name.trim(),
                promptRevisionId = previous?.promptRevisionId ?: EntityId.random().value,
                chatProfileId = model, visionProfileId = editor.visionModelId,
                embeddingProfileId = editor.embeddingModelId, rerankerProfileId = editor.rerankerModelId,
                knowledgeBaseIds = editor.resourceBindings.filter { it.type == "knowledge" && it.enabled }.map { it.id },
                skillIds = editor.resourceBindings.filter { it.type == "skill" && it.enabled }.map { it.id },
                retrievalMode = editor.retrievalMode, revision = (previous?.revision ?: 0) + 1,
                parameterOverridesJson = parameters.toString(), contextPolicyJson = previous?.contextPolicyJson ?: "{}",
                permissionSettingsJson = previous?.permissionSettingsJson ?: "{}",
            )
            val saved = app.container.agents.saveWithPrompt(profile, editor.prompt)
            app.container.uiPreferences.edit().putString("selected-agent", saved.id).apply()
            state.value = state.value.copy(selectedAgentId = saved.id, editor = null, error = null, status = "已保存 Agent；旧会话快照不变。")
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
        val snapshot = app.container.agents.createSnapshot(agentId)
        captureMcpSnapshot(app.container, snapshot.id, agentId)
        val now = Utc.nowIso()
        val conversation = Conversation(EntityId.random().value, snapshot.id, "新对话", now, now)
        app.container.conversations.create(conversation)
        conversation.id
    } catch (error: Exception) {
        state.value = state.value.copy(error = SecretRedactor.redact(error.message ?: "创建会话失败。"))
        null
    }
}
