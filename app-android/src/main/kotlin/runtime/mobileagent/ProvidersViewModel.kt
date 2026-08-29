// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.provider.SecretRedactor
import java.net.URI

data class ProviderDraft(
    val providerId: String? = null,
    val modelProfileId: String? = null,
    val name: String,
    val baseUrl: String,
    val modelId: String,
    val apiKey: String = "",
    val role: ModelRole = ModelRole.CHAT,
    val capabilities: Set<String> = setOf("stream"),
    val parametersJson: String = "{}",
    val contextLimit: Int = 32_768,
    val outputLimit: Int = 4_096,
)

class ProvidersViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MobileAgentApp
    val providers = mutableStateListOf<ProviderProfile>()
    val models = mutableStateListOf<ModelProfile>()
    val status = mutableStateOf("")
    val busy = mutableStateOf(false)

    init { reload() }

    fun reload() {
        providers.clear()
        providers.addAll(app.container.profiles.listProviders())
        models.clear()
        models.addAll(app.container.profiles.listModels())
    }

    fun save(name: String, baseUrl: String, modelId: String, apiKey: String, vision: Boolean, tools: Boolean = false): Boolean =
        saveDraft(ProviderDraft(
            name = name, baseUrl = baseUrl, modelId = modelId, apiKey = apiKey,
            capabilities = buildSet { add("stream"); if (vision) add("image"); if (tools) add("tools") },
        ))

    fun saveDraft(draft: ProviderDraft): Boolean {
        if (busy.value) return false
        return try {
            require(draft.name.isNotBlank() && draft.modelId.isNotBlank()) { "请填写名称和模型 ID。" }
            val endpoint = URI(draft.baseUrl.trim().trimEnd('/'))
            require(endpoint.host != null && endpoint.rawUserInfo == null && endpoint.rawFragment == null && endpoint.rawQuery == null) { "服务地址必须是有效的 Base URL，不能含凭据、查询或片段。" }
            val debugLocal = BuildConfig.DEBUG && endpoint.host in setOf("localhost", "127.0.0.1", "10.0.2.2", "[::1]")
            require(endpoint.scheme == "https" || (debugLocal && endpoint.scheme == "http")) { "服务地址必须使用 HTTPS。Debug 仅允许本机测试 HTTP。" }
            require(draft.contextLimit > 0 && draft.outputLimit > 0 && draft.outputLimit <= draft.contextLimit) { "上下文和输出预算必须为正数，输出不能超过上下文。" }
            val parameters = Json.parseToJsonElement(draft.parametersJson)
            require(parameters is JsonObject) { "模型参数必须是 JSON 对象。" }
            rejectReserved(parameters)
            val previous = draft.providerId?.let { app.container.profiles.getProvider(it) }
            require(draft.providerId == null || previous != null) { "服务已被删除，请重新打开表单。" }
            require(previous != null || draft.apiKey.isNotBlank()) { "新服务需要 API Key；密钥只会以 Keystore 密文保存。" }
            require(previous == null || previous.baseUrl == endpoint.toASCIIString() || draft.apiKey.isNotBlank()) {
                "服务目标发生变化，请重新填写该目标的 API Key；不会把旧目标的密钥转发到新地址。"
            }
            val providerId = previous?.id ?: EntityId.random().value
            val modelPrevious = draft.modelProfileId?.let { app.container.profiles.getModel(it) }
            require(modelPrevious == null || modelPrevious.providerId == providerId) { "模型不属于当前服务。" }
            val provider = ProviderProfile(
                id = providerId, name = draft.name.trim(), apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = endpoint.toASCIIString(), secretRef = if (draft.apiKey.isNotBlank()) "provider:$providerId:${EntityId.random().value}" else previous!!.secretRef,
                headerSecretRefs = previous?.headerSecretRefs.orEmpty(), nonSecretHeaders = previous?.nonSecretHeaders.orEmpty(),
                revision = (previous?.revision ?: 0) + 1,
            )
            val model = ModelProfile(
                id = modelPrevious?.id ?: EntityId.random().value, providerId = providerId,
                role = draft.role, modelId = draft.modelId.trim(), capabilities = draft.capabilities,
                parameterSchemaJson = modelPrevious?.parameterSchemaJson ?: "{}",
                parametersJson = parameters.toString(), contextLimit = draft.contextLimit, outputLimit = draft.outputLimit,
                revision = (modelPrevious?.revision ?: 0) + 1,
            )
            app.container.db.transaction {
                if (draft.apiKey.isNotBlank()) app.container.secrets.put(provider.secretRef, draft.apiKey.toCharArray())
                app.container.profiles.upsertProvider(provider)
                app.container.profiles.upsertModel(model)
            }
            reload()
            status.value = "已保存 ${provider.name} / ${model.modelId}。能力标记来自手动配置，尚未发送探测请求。"
            true
        } catch (error: Exception) {
            status.value = SecretRedactor.redact(error.message ?: "保存失败。", listOf(draft.apiKey).filter { it.isNotBlank() })
            false
        }
    }

    fun deleteModel(id: String) {
        try {
            val deleted = app.container.profiles.deleteModel(id)
            status.value = if (deleted) "模型配置已删除。" else "此模型仍被 Agent 或会话快照使用，不能删除。"
            reload()
        } catch (error: Exception) { status.value = SecretRedactor.redact(error.message ?: "删除失败。") }
    }

    fun deleteProvider(id: String) {
        try {
            val deleted = app.container.profiles.deleteProvider(id)
            status.value = if (deleted) "服务配置已删除。" else "请先移除未被引用的模型；被 Agent 或快照引用的配置不能删除。"
            reload()
        } catch (error: Exception) { status.value = SecretRedactor.redact(error.message ?: "删除失败。") }
    }

    /** Requires a separate UI confirmation because even a short capability probe can be billed. */
    fun probe(modelId: String, approved: Boolean) {
        if (!approved || busy.value) return
        val model = app.container.profiles.getModel(modelId) ?: return
        val provider = app.container.profiles.getProvider(model.providerId) ?: return
        busy.value = true
        viewModelScope.launch {
            var secret: CharArray? = null
            try {
                val report = withContext(Dispatchers.IO) {
                    secret = app.container.secrets.resolveForHost(provider.secretRef)
                    runtime.mobileagent.provider.openai.OpenAiCompatibleAdapter(app.container.http, provider.baseUrl)
                        .probe(model, secret!!, runtime.mobileagent.provider.ProbeConsent.GRANTED, EntityId.random().value)
                }
                status.value = "探测完成：${report.source}；stream=${report.supportsStream}，tools=${report.supportsTools}，image=${report.supportsImages}。"
            } catch (error: Exception) {
                status.value = SecretRedactor.redact(error.message ?: "探测失败。", listOfNotNull(secret?.let(::String)))
            } finally { secret?.fill('\u0000'); busy.value = false }
        }
    }

    private fun rejectReserved(objectValue: JsonObject) {
        objectValue.forEach { (key, value) ->
            require(key.lowercase() !in setOf("model", "messages", "tools", "stream", "authorization", "api_key", "headers")) { "参数 $key 由运行时控制，不能覆盖。" }
            if (value is JsonObject) rejectReserved(value)
        }
    }
}
