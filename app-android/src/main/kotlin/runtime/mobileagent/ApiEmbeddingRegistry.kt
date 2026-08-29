// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import io.ktor.client.HttpClient
import runtime.mobileagent.data.ProfileRepository
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.embedding.ApiEmbeddingTextEmbedderFactory
import runtime.mobileagent.knowledge.ApiEmbeddingBinding
import runtime.mobileagent.knowledge.TextEmbedder
import runtime.mobileagent.provider.HeaderSecretResolver
import runtime.mobileagent.provider.RequestHeaderValue
import runtime.mobileagent.provider.openai.OpenAiCompatibleAdapter
import runtime.mobileagent.security.AndroidSecretStore
import java.net.URI

/** Resolves only the complete API space selected for a KB. Construction never sends traffic. */
class ApiEmbeddingRegistry(
    private val profiles: ProfileRepository,
    private val secrets: AndroidSecretStore,
    private val http: HttpClient,
) {
    fun options(): List<Pair<String, String>> = profiles.listModels()
        .filter { it.role == ModelRole.EMBEDDING }
        .mapNotNull { model -> profiles.getProvider(model.providerId)?.let { provider ->
            model.id to "${provider.name} · ${model.modelId} · r${provider.revision}/${model.revision}"
        } }

    fun binding(modelProfileId: String, dimension: Int, knowledgeBaseId: String): ApiEmbeddingBinding {
        require(dimension in 1..8192) { "Embedding 维度必须为 1—8192，请按服务商模型规格填写。" }
        val model = profiles.getModel(modelProfileId) ?: error("Embedding 模型已被移除。")
        require(model.role == ModelRole.EMBEDDING) { "请选择 Embedding 角色的模型。" }
        val provider = profiles.getProvider(model.providerId) ?: error("Embedding 服务商已被移除。")
        require(provider.secretRef.isNotBlank()) { "请先在本机为 Embedding 服务商配置凭据。" }
        validateEndpoint(provider.baseUrl)
        return ApiEmbeddingBinding(
            providerId = provider.id,
            endpoint = provider.baseUrl.trimEnd('/'),
            providerRevision = provider.revision,
            modelId = model.modelId,
            modelRevision = model.revision,
            dimension = dimension,
            dataScope = scope(knowledgeBaseId),
            modelProfileId = model.id,
        )
    }

    fun resolve(spaceId: String): TextEmbedder? {
        val binding = ApiEmbeddingBinding.parseSpaceId(spaceId) ?: return null
        val model = profiles.getModel(binding.modelProfileId) ?: return null
        val provider = profiles.getProvider(binding.providerId) ?: return null
        if (model.role != ModelRole.EMBEDDING || model.providerId != provider.id ||
            model.modelId != binding.modelId || model.revision != binding.modelRevision ||
            provider.revision != binding.providerRevision ||
            provider.baseUrl.trimEnd('/') != binding.endpoint || provider.secretRef.isBlank()) return null
        val endpoint = runCatching { validateEndpoint(binding.endpoint) }.getOrNull() ?: return null
        // Check the captured profiles again immediately before resolving any credential.
        // Editing/removing a provider during a pending consent cannot redirect old grants.
        fun requireCurrent() {
            check(profiles.getProvider(provider.id) == provider && profiles.getModel(model.id) == model) {
                "Embedding 目标已变更，请重新选择模型并确认授权。"
            }
        }
        val headers = buildMap<String, RequestHeaderValue> {
            provider.nonSecretHeaders.forEach { (name, value) -> put(name, RequestHeaderValue.Plain(value)) }
            provider.headerSecretRefs.forEach { (name, ref) -> put(name, RequestHeaderValue.SecretRef(ref)) }
        }
        val adapter = OpenAiCompatibleAdapter(
            http, binding.endpoint,
            headerSecretResolver = HeaderSecretResolver { host, ref ->
                requireCurrent()
                require(host.equals(endpoint.host, true) && ref in provider.headerSecretRefs.values) {
                    "Embedding header credential destination mismatch"
                }
                secrets.resolveForHost(ref)
            },
            defaultHeaders = headers,
        )
        return ApiEmbeddingTextEmbedderFactory.create(binding, adapter) {
            requireCurrent()
            secrets.resolveForHost(provider.secretRef)
        }
    }

    fun label(binding: ApiEmbeddingBinding): String =
        "${binding.endpoint}\n${binding.modelId} · ${binding.dimension} dimensions\n" +
            "Provider ${binding.providerId} r${binding.providerRevision} · " +
            "Model profile ${binding.modelProfileId} r${binding.modelRevision}\n" +
            "数据范围 / Data scope: ${binding.dataScope}"

    private fun validateEndpoint(raw: String): URI {
        val uri = URI(raw)
        require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "Embedding 地址必须是无凭据、查询及片段的 Base URL。"
        }
        val localDebug = BuildConfig.DEBUG && uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2", "[::1]")
        require(uri.scheme == "https" || (localDebug && uri.scheme == "http")) { "Embedding 地址必须使用 HTTPS。" }
        return uri
    }

    companion object {
        fun scope(knowledgeBaseId: String): String = "knowledge-base:$knowledgeBaseId:text-chunks-and-retrieval-queries"
    }
}
