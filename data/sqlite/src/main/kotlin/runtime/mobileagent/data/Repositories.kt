// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.CapabilityVerification
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.ModelEndpoint
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.acceptsImages
import runtime.mobileagent.domain.isChatEndpoint
import runtime.mobileagent.domain.withEndpoint

/**
 * Persistence for user configured providers and models.
 *
 * Provider/model rows are updated in place. SQLite's INSERT OR REPLACE would delete the old
 * provider row first, which is unsafe once models or immutable snapshots reference it.
 */
class ProfileRepository(private val db: SqlConnection) {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    fun getProvider(id: String): ProviderProfile? =
        db.query("SELECT * FROM provider_profiles WHERE id = ?", listOf(id)).singleOrNull()?.toProvider()

    fun provider(id: String): ProviderProfile? = getProvider(id)

    fun listProviders(): List<ProviderProfile> =
        db.query("SELECT * FROM provider_profiles ORDER BY name, id").map { it.toProvider() }

    fun createProvider(profile: ProviderProfile): ProviderProfile {
        validateProvider(profile)
        requireReference("provider", profile.id, getProvider(profile.id) == null)
        db.execute(
            "INSERT INTO provider_profiles (id,name,api_format,base_url,header_secret_refs,non_secret_headers,secret_ref,revision) VALUES (?,?,?,?,?,?,?,?)",
            profile.providerArgs(json),
        )
        return profile
    }

    fun updateProvider(profile: ProviderProfile): ProviderProfile {
        validateProvider(profile)
        val current = getProvider(profile.id)
        requireReference("provider", profile.id, current != null)
        if (current != null && profile.revision < current.revision) throw invalid("Provider revision is older than the stored revision")
        db.execute(
            "UPDATE provider_profiles SET name=?,api_format=?,base_url=?,header_secret_refs=?,non_secret_headers=?,secret_ref=?,revision=? WHERE id=?",
            listOf(
                profile.name,
                profile.apiFormat.name,
                profile.baseUrl,
                json.encodeToString(profile.headerSecretRefs),
                json.encodeToString(profile.nonSecretHeaders),
                profile.secretRef,
                profile.revision,
                profile.id,
            ),
        )
        return profile
    }

    fun upsertProvider(profile: ProviderProfile): ProviderProfile {
        return if (getProvider(profile.id) == null) createProvider(profile) else updateProvider(profile)
    }

    /** Returns false when models or immutable snapshots still reference the provider. */
    fun deleteProvider(id: String): Boolean {
        val preview = providerDeletePreview(id) ?: return false
        if (!preview.canDelete) return false
        db.execute("DELETE FROM provider_profiles WHERE id = ?", listOf(id))
        if (preview.secretRef.isNotBlank()) {
            SecretInventory(db).retireIfUnreferenced(preview.secretRef)
        }
        return true
    }

    fun providerDeletePreview(id: String): runtime.mobileagent.domain.ProviderDeletePreview? {
        val provider = getProvider(id) ?: return null
        val modelCount = db.query("SELECT COUNT(*) AS n FROM model_profiles WHERE provider_id = ?", listOf(id))
            .single().long("n").toInt()
        val snapshotCount = db.query("SELECT COUNT(*) AS n FROM agent_snapshots WHERE provider_id = ?", listOf(id))
            .single().long("n").toInt()
        val secretStatus = provider.secretRef.takeIf { it.isNotBlank() }?.let { SecretInventory(db).status(it) }
        return runtime.mobileagent.domain.ProviderDeletePreview(
            providerId = id,
            modelCount = modelCount,
            snapshotCount = snapshotCount,
            secretRef = provider.secretRef,
            secretStatus = secretStatus,
            canDelete = modelCount == 0 && snapshotCount == 0,
        )
    }

    fun deleteProviderOrThrow(id: String) {
        if (!deleteProvider(id)) {
            throw referenceConflict("Provider $id is missing or still referenced by a model/snapshot")
        }
    }

    fun getModel(id: String): ModelProfile? =
        db.query("SELECT * FROM model_profiles WHERE id = ?", listOf(id)).singleOrNull()?.toModel(json)

    fun model(id: String): ModelProfile? = getModel(id)

    fun listModels(providerId: String? = null): List<ModelProfile> {
        val rows = if (providerId == null) {
            db.query("SELECT * FROM model_profiles ORDER BY model_id, id")
        } else {
            db.query("SELECT * FROM model_profiles WHERE provider_id = ? ORDER BY model_id, id", listOf(providerId))
        }
        return rows.map { it.toModel(json) }
    }

    fun createModel(profile: ModelProfile): ModelProfile {
        validateModel(profile)
        requireReference("model", profile.id, getModel(profile.id) == null)
        requireReference("provider", profile.providerId, getProvider(profile.providerId) != null)
        db.execute(
            "INSERT INTO model_profiles (id,provider_id,role,model_id,capabilities,parameter_schema_json,parameters_json,context_limit,output_limit,revision,endpoint_json) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            profile.modelArgs(json),
        )
        return profile
    }

    fun updateModel(profile: ModelProfile): ModelProfile {
        validateModel(profile)
        val current = getModel(profile.id)
        requireReference("model", profile.id, current != null)
        if (current != null && profile.revision < current.revision) throw invalid("Model revision is older than the stored revision")
        requireReference("provider", profile.providerId, getProvider(profile.providerId) != null)
        db.execute(
            "UPDATE model_profiles SET provider_id=?,role=?,model_id=?,capabilities=?,parameter_schema_json=?,parameters_json=?,context_limit=?,output_limit=?,revision=?,endpoint_json=? WHERE id=?",
            listOf(
                profile.providerId,
                profile.role.name,
                profile.modelId,
                json.encodeToString(profile.capabilities.toList().sorted()),
                profile.parameterSchemaJson,
                profile.parametersJson,
                profile.contextLimit,
                profile.outputLimit,
                profile.revision,
                json.encodeToString(runtime.mobileagent.domain.ModelEndpoint.serializer(), profile.withEndpoint().endpoint),
                profile.id,
            ),
        )
        return profile
    }

    fun upsertModel(profile: ModelProfile): ModelProfile =
        if (getModel(profile.id) == null) createModel(profile) else updateModel(profile)

    /** Returns false when an Agent or immutable snapshot references this model profile. */
    fun deleteModel(id: String): Boolean {
        if (getModel(id) == null) return false
        val agentRef = db.query(
            "SELECT id FROM agent_profiles WHERE chat_profile_id=? OR vision_profile_id=? OR embedding_profile_id=? OR reranker_profile_id=? LIMIT 1",
            listOf(id, id, id, id),
        ).isNotEmpty()
        val snapshotRef = db.query(
            "SELECT id FROM agent_snapshots WHERE chat_model_id=? OR vision_model_id=? OR embedding_model_id=? OR reranker_model_id=? LIMIT 1",
            listOf(id, id, id, id),
        ).isNotEmpty()
        if (agentRef || snapshotRef) return false
        db.execute("DELETE FROM model_profiles WHERE id = ?", listOf(id))
        return true
    }

    fun deleteModelOrThrow(id: String) {
        if (!deleteModel(id)) {
            throw referenceConflict("Model $id is missing or still referenced by an agent/snapshot")
        }
    }

    /** Resolve a chat model and its owning provider in one join; names never influence binding. */
    fun chatBinding(): Pair<ProviderProfile, ModelProfile>? = bindingRows()
        .asSequence()
        .map { it.toBinding() }
        .firstOrNull { (_, model) -> model.isChatEndpoint() }

    /** Resolve an image-capable model and its owning provider in one join. */
    fun visionBinding(): Pair<ProviderProfile, ModelProfile>? = bindingRows()
        .asSequence()
        .map { it.toBinding() }
        .firstOrNull { (_, model) -> model.acceptsImages() }

    fun chatModel(): ModelProfile? = chatBinding()?.second

    fun visionConfigured(): Boolean = listModels().any { it.acceptsImages() }

    fun recordProbe(
        modelId: String,
        providerRevision: Int,
        toolsSummary: String,
        imagesSummary: String,
        source: String,
        probed: Boolean,
    ) {
        val model = getModel(modelId) ?: return
        db.execute(
            "INSERT INTO capability_probes(id,provider_id,model_id,provider_revision,verification,tools_summary,images_summary,source,probed_at) VALUES (?,?,?,?,?,?,?,?,?)",
            listOf(
                EntityId.random().value,
                model.providerId,
                model.modelId,
                providerRevision,
                if (probed) CapabilityVerification.PROBED.name else CapabilityVerification.USER_DECLARED.name,
                toolsSummary,
                imagesSummary,
                source,
                Utc.nowIso(),
            ),
        )
        if (probed) {
            val endpoint = model.withEndpoint().endpoint.copy(verification = CapabilityVerification.PROBED)
            upsertModel(model.copy(endpoint = endpoint, revision = model.revision + 1))
        }
    }

    fun providerForModel(modelId: String): ProviderProfile? =
        getModel(modelId)?.let { getProvider(it.providerId) }

    private fun binding(predicate: String, args: List<Any?>): Pair<ProviderProfile, ModelProfile>? {
        return bindingRows(predicate, args).firstOrNull()?.toBinding()
    }

    private fun bindingRows(predicate: String? = null, args: List<Any?> = emptyList()): List<SqlRow> {
        val where = predicate?.let { " WHERE $it" }.orEmpty()
        return db.query(
            "SELECT p.id AS p_id,p.name AS p_name,p.api_format AS p_api_format,p.base_url AS p_base_url," +
                "p.header_secret_refs AS p_header_secret_refs,p.non_secret_headers AS p_non_secret_headers," +
                "p.secret_ref AS p_secret_ref,p.revision AS p_revision,m.id AS m_id,m.provider_id AS m_provider_id," +
                "m.role AS m_role,m.model_id AS m_model_id,m.capabilities AS m_capabilities," +
                "m.parameter_schema_json AS m_parameter_schema_json,m.parameters_json AS m_parameters_json," +
                "m.context_limit AS m_context_limit,m.output_limit AS m_output_limit,m.revision AS m_revision," +
                "m.endpoint_json AS m_endpoint_json " +
                "FROM provider_profiles p JOIN model_profiles m ON m.provider_id=p.id$where " +
                "ORDER BY p.name,p.id,m.model_id,m.id",
            args,
        )
    }

    private fun SqlRow.toBinding(): Pair<ProviderProfile, ModelProfile> {
        val provider = ProviderProfile(
            id = string("p_id"),
            name = string("p_name"),
            apiFormat = ApiFormat.valueOf(string("p_api_format")),
            baseUrl = string("p_base_url"),
            headerSecretRefs = decodeMap(string("p_header_secret_refs")),
            nonSecretHeaders = decodeMap(string("p_non_secret_headers")),
            secretRef = string("p_secret_ref"),
            revision = long("p_revision").toInt(),
        )
        val caps = decodeList(string("m_capabilities")).toSet()
        val role = ModelRole.valueOf(string("m_role"))
        val model = ModelProfile(
            id = string("m_id"),
            providerId = string("m_provider_id"),
            role = role,
            modelId = string("m_model_id"),
            capabilities = caps,
            parameterSchemaJson = string("m_parameter_schema_json").ifBlank { "{}" },
            contextLimit = long("m_context_limit").toInt(),
            outputLimit = long("m_output_limit").toInt(),
            revision = long("m_revision").toInt(),
            parametersJson = string("m_parameters_json").ifBlank { "{}" },
            endpoint = decodeEndpoint(role, caps, string("m_endpoint_json")),
        ).withEndpoint()
        return provider to model
    }

    private fun validateProvider(profile: ProviderProfile) {
        requireId(profile.id, "provider.id")
        requireText(profile.name, "provider.name")
        requireText(profile.baseUrl, "provider.baseUrl")
        requireNonNegative(profile.revision, "provider.revision")
        parseObject(profile.nonSecretHeaders, "provider.nonSecretHeaders")
        parseObject(profile.headerSecretRefs, "provider.headerSecretRefs")
        profile.nonSecretHeaders.keys.forEach { header ->
            if (FORBIDDEN_SECRET_HEADER.matches(header)) {
                throw invalid("Provider header $header must use a secret reference")
            }
        }
    }

    private fun validateModel(profile: ModelProfile) {
        requireId(profile.id, "model.id")
        requireId(profile.providerId, "model.providerId")
        requireText(profile.modelId, "model.modelId")
        requirePositive(profile.contextLimit, "model.contextLimit")
        requirePositive(profile.outputLimit, "model.outputLimit")
        requireNonNegative(profile.revision, "model.revision")
        parseJsonObject(profile.parameterSchemaJson, "model.parameterSchemaJson")
        parseJsonObject(profile.parametersJson, "model.parametersJson")
    }

    private fun decodeMap(raw: String): Map<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(raw.ifBlank { "{}" }) }.getOrElse {
            throw invalid("Invalid persisted object")
        }

    private fun decodeList(raw: String): List<String> =
        runCatching { json.decodeFromString<List<String>>(raw.ifBlank { "[]" }) }.getOrElse {
            throw invalid("Invalid persisted list")
        }

    private fun parseObject(values: Map<String, String>, field: String) {
        val raw = json.encodeToString(values)
        parseJsonObject(raw, field)
    }

    private fun parseJsonObject(raw: String, field: String) {
        val element = runCatching { json.parseToJsonElement(raw) }.getOrElse { throw invalid("$field must be JSON object") }
        if (element !is JsonObject) throw invalid("$field must be JSON object")
    }

    private fun requireId(value: String, field: String) {
        requireText(value, field)
        if (!SAFE_ID.matches(value)) throw invalid("$field contains unsafe characters")
    }

    private fun requireText(value: String, field: String) {
        if (value.isBlank() || value.length > 256) throw invalid("$field is empty or too long")
    }

    private fun requirePositive(value: Int, field: String) {
        if (value <= 0) throw invalid("$field must be positive")
    }

    private fun requireNonNegative(value: Int, field: String) {
        if (value < 0) throw invalid("$field must not be negative")
    }

    private fun requireReference(kind: String, id: String, exists: Boolean) {
        if (!exists) throw invalid("$kind $id does not exist")
    }

    private fun referenceConflict(message: String): AppException = AppError(
        code = ErrorCode.INVALID_CONFIG,
        userMessage = message,
        retryClass = RetryClass.USER_ACTION,
        stage = "persistence",
        operationId = "profile-delete",
        sanitizedDetails = message,
    ).asException()

    private fun invalid(message: String): AppException = AppError(
        code = ErrorCode.INVALID_CONFIG,
        userMessage = message,
        retryClass = RetryClass.USER_ACTION,
        stage = "persistence",
        operationId = "profile-write",
        sanitizedDetails = message,
    ).asException()

    private fun SqlRow.toProvider(): ProviderProfile = ProviderProfile(
        id = string("id"),
        name = string("name"),
        apiFormat = ApiFormat.valueOf(string("api_format")),
        baseUrl = string("base_url"),
        headerSecretRefs = decodeMap(string("header_secret_refs")),
        nonSecretHeaders = decodeMap(string("non_secret_headers")),
        secretRef = string("secret_ref"),
        revision = long("revision").toInt(),
    )

    private fun SqlRow.toModel(json: Json): ModelProfile {
        val caps = runCatching { json.decodeFromString<List<String>>(string("capabilities").ifBlank { "[]" }) }
            .getOrElse { throw invalid("Invalid persisted model capabilities") }
            .toSet()
        val role = ModelRole.valueOf(string("role"))
        return ModelProfile(
            id = string("id"),
            providerId = string("provider_id"),
            role = role,
            modelId = string("model_id"),
            capabilities = caps,
            parameterSchemaJson = string("parameter_schema_json").ifBlank { "{}" },
            contextLimit = long("context_limit").toInt(),
            outputLimit = long("output_limit").toInt(),
            revision = long("revision").toInt(),
            parametersJson = string("parameters_json").ifBlank { "{}" },
            endpoint = decodeEndpoint(role, caps, string("endpoint_json")),
        ).withEndpoint()
    }

    private fun ProviderProfile.providerArgs(json: Json): List<Any?> = listOf(
        id, name, apiFormat.name, baseUrl,
        json.encodeToString(headerSecretRefs), json.encodeToString(nonSecretHeaders), secretRef, revision,
    )

    private fun ModelProfile.modelArgs(json: Json): List<Any?> {
        val resolved = withEndpoint()
        return listOf(
            id, providerId, role.name, modelId, json.encodeToString(capabilities.toList().sorted()),
            parameterSchemaJson, parametersJson, contextLimit, outputLimit, revision,
            json.encodeToString(ModelEndpoint.serializer(), resolved.endpoint),
        )
    }

    private fun decodeEndpoint(role: ModelRole, capabilities: Set<String>, raw: String): ModelEndpoint {
        if (raw.isBlank() || raw == "{}") return ModelEndpoint.fromLegacy(role, capabilities)
        return runCatching { json.decodeFromString(ModelEndpoint.serializer(), raw) }
            .getOrElse { ModelEndpoint.fromLegacy(role, capabilities) }
    }

    companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
        private val FORBIDDEN_SECRET_HEADER = Regex("(?i).*(authorization|api[_-]?key|cookie|password|private[_-]?key|secret).*")
    }
}
