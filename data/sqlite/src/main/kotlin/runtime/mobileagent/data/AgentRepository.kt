// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.PromptRevision
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.domain.SnapshotBinding
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.serialization.SchemaVersion

/** Agent configuration, immutable prompt history, resource bindings, and expanded snapshots. */
class AgentRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
) {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false; encodeDefaults = true }
    private val profiles = ProfileRepository(db)

    fun get(id: String): AgentProfile? =
        db.query("SELECT * FROM agent_profiles WHERE id = ?", listOf(id)).singleOrNull()?.toAgent()

    fun agent(id: String): AgentProfile? = get(id)

    fun list(): List<AgentProfile> =
        db.query("SELECT * FROM agent_profiles ORDER BY name, id").map { it.toAgent() }

    fun create(profile: AgentProfile): AgentProfile {
        validateAgentFields(profile)
        requirePrompt(profile.promptRevisionId, profile.id)
        requireReference("agent", profile.id, get(profile.id) == null)
        requireBindings(profile)
        db.execute(
            "INSERT INTO agent_profiles(id,name,prompt_revision_id,chat_profile_id,vision_profile_id,embedding_profile_id,reranker_profile_id,knowledge_base_ids,skill_ids,retrieval_mode,revision,parameter_overrides_json,context_policy_json,permission_settings_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            profile.args(json),
        )
        return profile
    }

    fun update(profile: AgentProfile): AgentProfile {
        validateAgentFields(profile)
        val current = get(profile.id) ?: throw invalid("Agent ${profile.id} does not exist")
        if (profile.revision < current.revision) throw invalid("Agent revision is older than the stored revision")
        requirePrompt(profile.promptRevisionId, profile.id)
        requireBindings(profile)
        db.execute(
            "UPDATE agent_profiles SET name=?,prompt_revision_id=?,chat_profile_id=?,vision_profile_id=?,embedding_profile_id=?,reranker_profile_id=?,knowledge_base_ids=?,skill_ids=?,retrieval_mode=?,revision=?,parameter_overrides_json=?,context_policy_json=?,permission_settings_json=? WHERE id=?",
            listOf(
                profile.name,
                profile.promptRevisionId,
                profile.chatProfileId,
                profile.visionProfileId,
                profile.embeddingProfileId,
                profile.rerankerProfileId,
                json.encodeToString(profile.knowledgeBaseIds),
                json.encodeToString(profile.skillIds),
                profile.retrievalMode,
                profile.revision,
                profile.parameterOverridesJson,
                profile.contextPolicyJson,
                profile.permissionSettingsJson,
                profile.id,
            ),
        )
        return profile
    }

    fun upsert(profile: AgentProfile): AgentProfile =
        if (get(profile.id) == null) create(profile) else update(profile)

    fun createAgent(profile: AgentProfile): AgentProfile = create(profile)

    fun updateAgent(profile: AgentProfile): AgentProfile = update(profile)

    fun upsertAgent(profile: AgentProfile): AgentProfile = upsert(profile)

    /**
     * Atomically creates the initial prompt and Agent row, or appends a prompt version and updates
     * an existing Agent. The new prompt is inserted before the profile update inside one transaction;
     * a failed binding check rolls both writes back.
     */
    fun saveWithPrompt(
        profile: AgentProfile,
        template: String,
        allowedVariables: Set<String> = DEFAULT_ALLOWED_VARIABLES,
    ): AgentProfile = db.transaction {
        validateAgentFields(profile)
        validatePromptValues(template, allowedVariables)
        val existing = get(profile.id)
        val parent = existing?.promptRevisionId
        val nextRevision = maxOf(1, profile.revision, (existing?.revision ?: 0) + if (existing == null) 0 else 1)
        val prompt = PromptRevision(
            id = EntityId.random().value,
            agentId = profile.id,
            parentRevisionId = parent,
            template = template,
            allowedVariables = allowedVariables,
            createdAt = clock(),
        )
        val saved = profile.copy(promptRevisionId = prompt.id, revision = nextRevision)
        requireBindings(saved)
        db.execute(
            "INSERT INTO prompt_revisions(id,agent_id,parent_revision_id,template,allowed_variables,created_at) VALUES(?,?,?,?,?,?)",
            listOf(prompt.id, prompt.agentId, prompt.parentRevisionId, prompt.template, json.encodeToString(prompt.allowedVariables.toList().sorted()), prompt.createdAt),
        )
        if (existing == null) {
            if (db.query("SELECT id FROM agent_profiles WHERE id=?", listOf(saved.id)).isNotEmpty()) {
                throw invalid("Agent ${saved.id} already exists")
            }
            // Prompt and profile are committed together; no FK is used for prompt_revision_id so
            // the initial profile can be inserted immediately after the new immutable revision.
            db.execute(
                "INSERT INTO agent_profiles(id,name,prompt_revision_id,chat_profile_id,vision_profile_id,embedding_profile_id,reranker_profile_id,knowledge_base_ids,skill_ids,retrieval_mode,revision,parameter_overrides_json,context_policy_json,permission_settings_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                saved.args(json),
            )
        } else {
            if (profile.revision < existing.revision) throw invalid("Agent revision is older than the stored revision")
            db.execute(
                "UPDATE agent_profiles SET name=?,prompt_revision_id=?,chat_profile_id=?,vision_profile_id=?,embedding_profile_id=?,reranker_profile_id=?,knowledge_base_ids=?,skill_ids=?,retrieval_mode=?,revision=?,parameter_overrides_json=?,context_policy_json=?,permission_settings_json=? WHERE id=?",
                listOf(
                    saved.name, saved.promptRevisionId, saved.chatProfileId, saved.visionProfileId,
                    saved.embeddingProfileId, saved.rerankerProfileId, json.encodeToString(saved.knowledgeBaseIds),
                    json.encodeToString(saved.skillIds), saved.retrievalMode, saved.revision,
                    saved.parameterOverridesJson, saved.contextPolicyJson, saved.permissionSettingsJson, saved.id,
                ),
            )
        }
        saved
    }

    fun appendPromptRevision(prompt: PromptRevision): PromptRevision = db.transaction {
        validatePrompt(prompt)
        requireReference("agent", prompt.agentId, get(prompt.agentId) != null)
        prompt.parentRevisionId?.let { parent ->
            val parentRow = db.query("SELECT agent_id FROM prompt_revisions WHERE id=?", listOf(parent)).singleOrNull()
                ?: throw invalid("Prompt parent $parent does not exist")
            if (parentRow.string("agent_id") != prompt.agentId) throw invalid("Prompt parent belongs to another agent")
        }
        val existing = db.query("SELECT * FROM prompt_revisions WHERE id=?", listOf(prompt.id)).singleOrNull()
        if (existing != null) {
            val stored = existing.toPrompt(json)
            if (stored != prompt) throw invalid("Prompt revision ${prompt.id} is immutable")
            return@transaction stored
        }
        db.execute(
            "INSERT INTO prompt_revisions(id,agent_id,parent_revision_id,template,allowed_variables,created_at) VALUES(?,?,?,?,?,?)",
            listOf(prompt.id, prompt.agentId, prompt.parentRevisionId, prompt.template, json.encodeToString(prompt.allowedVariables.toList().sorted()), prompt.createdAt),
        )
        prompt
    }

    fun promptRevision(id: String): PromptRevision? =
        db.query("SELECT * FROM prompt_revisions WHERE id=?", listOf(id)).singleOrNull()?.toPrompt(json)

    fun getPromptRevision(id: String): PromptRevision? = promptRevision(id)

    fun listPromptRevisions(agentId: String): List<PromptRevision> =
        db.query("SELECT * FROM prompt_revisions WHERE agent_id=? ORDER BY created_at,id", listOf(agentId))
            .map { it.toPrompt(json) }

    fun prompts(agentId: String): List<PromptRevision> = listPromptRevisions(agentId)

    /** Returns false if immutable snapshots or conversations still retain the Agent history. */
    fun delete(id: String): Boolean {
        if (get(id) == null) return false
        val snapshotRefs = db.query("SELECT id FROM agent_snapshots WHERE agent_id=? LIMIT 1", listOf(id)).isNotEmpty()
        if (snapshotRefs) return false
        db.transaction {
            db.execute("DELETE FROM prompt_revisions WHERE agent_id=?", listOf(id))
            db.execute("DELETE FROM agent_profiles WHERE id=?", listOf(id))
        }
        return true
    }

    fun deleteAgent(id: String): Boolean = delete(id)

    fun deleteOrThrow(id: String) {
        if (!delete(id)) throw invalid("Agent $id is missing or still referenced by a snapshot")
    }

    /** Build and persist a complete immutable expansion for a conversation/run boundary. */
    fun createSnapshot(
        agentId: String,
        snapshotId: String = EntityId.random().value,
        at: String = clock(),
    ): AgentSnapshot = db.transaction {
        val agent = get(agentId) ?: throw invalid("Agent $agentId does not exist")
        val prompt = promptRevision(agent.promptRevisionId) ?: throw invalid("Agent prompt is missing")
        val chat = profiles.getModel(agent.chatProfileId) ?: throw invalid("Chat model is missing")
        val provider = profiles.getProvider(chat.providerId) ?: throw invalid("Chat provider is missing")
        requireRole(chat, ModelRole.CHAT, "chat")
        val vision = agent.visionProfileId?.let { profiles.getModel(it) ?: throw invalid("Vision model $it is missing") }
        val embedding = agent.embeddingProfileId?.let { profiles.getModel(it) ?: throw invalid("Embedding model $it is missing") }
        val reranker = agent.rerankerProfileId?.let { profiles.getModel(it) ?: throw invalid("Reranker model $it is missing") }
        vision?.let { requireVision(it) }
        embedding?.let { requireRole(it, ModelRole.EMBEDDING, "embedding") }
        reranker?.let { requireRole(it, ModelRole.RERANKER, "reranker") }
        requireBindings(agent)
        requireReference("snapshot", snapshotId, db.query("SELECT id FROM agent_snapshots WHERE id=?", listOf(snapshotId)).isEmpty())
        val bindingManifest = buildJsonObject {
            put("schemaVersion", SchemaVersion.CURRENT)
            put("snapshotId", snapshotId)
            put("agentId", agent.id)
            put("agentRevision", agent.revision)
            put("agentName", agent.name)
            put("retrievalMode", agent.retrievalMode)
            put("provider", json.encodeToJsonElement(provider))
            put("chatModel", json.encodeToJsonElement(chat))
            put("prompt", json.encodeToJsonElement(prompt))
            vision?.let { put("visionModel", json.encodeToJsonElement(it)) }
            embedding?.let { put("embeddingModel", json.encodeToJsonElement(it)) }
            reranker?.let { put("rerankerModel", json.encodeToJsonElement(it)) }
            vision?.let { model ->
                val modelProvider = profiles.getProvider(model.providerId) ?: throw invalid("Vision provider is missing")
                put("visionProvider", json.encodeToJsonElement(modelProvider))
            }
            embedding?.let { model ->
                val modelProvider = profiles.getProvider(model.providerId) ?: throw invalid("Embedding provider is missing")
                put("embeddingProvider", json.encodeToJsonElement(modelProvider))
            }
            reranker?.let { model ->
                val modelProvider = profiles.getProvider(model.providerId) ?: throw invalid("Reranker provider is missing")
                put("rerankerProvider", json.encodeToJsonElement(modelProvider))
            }
            put("parameterOverridesJson", agent.parameterOverridesJson)
            put("contextPolicyJson", agent.contextPolicyJson)
            put("permissionSettingsJson", agent.permissionSettingsJson)
            putJsonArray("knowledgeBaseIds") { agent.knowledgeBaseIds.forEach(::add) }
            putJsonArray("skillIds") { agent.skillIds.forEach(::add) }
        }.toString()
        val expanded = buildJsonObject {
            put("schemaVersion", SchemaVersion.CURRENT)
            put("bindingManifest", json.parseToJsonElement(bindingManifest))
        }.toString()
        val snapshot = AgentSnapshot(
            id = snapshotId,
            schemaVersion = SchemaVersion.CURRENT,
            agentId = agent.id,
            promptRevisionId = prompt.id,
            chatModelId = chat.id,
            providerRevision = provider.revision,
            knowledgeBaseIds = agent.knowledgeBaseIds,
            skillIds = agent.skillIds,
            createdAt = at,
            providerId = provider.id,
            chatModelRevision = chat.revision,
            visionModelId = vision?.id,
            visionModelRevision = vision?.revision,
            embeddingModelId = embedding?.id,
            embeddingModelRevision = embedding?.revision,
            rerankerModelId = reranker?.id,
            rerankerModelRevision = reranker?.revision,
            parameterOverridesJson = agent.parameterOverridesJson,
            contextPolicyJson = agent.contextPolicyJson,
            permissionSettingsJson = agent.permissionSettingsJson,
            bindingManifestJson = bindingManifest,
        )
        db.execute(
            "INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at,provider_id,chat_model_revision,vision_model_id,vision_model_revision,embedding_model_id,embedding_model_revision,reranker_model_id,reranker_model_revision,parameter_overrides_json,context_policy_json,permission_settings_json,binding_manifest_json,expanded_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                snapshot.id, snapshot.schemaVersion, snapshot.agentId, snapshot.promptRevisionId, snapshot.chatModelId,
                snapshot.providerRevision, json.encodeToString(snapshot.knowledgeBaseIds), json.encodeToString(snapshot.skillIds),
                snapshot.createdAt, snapshot.providerId, snapshot.chatModelRevision, snapshot.visionModelId, snapshot.visionModelRevision,
                snapshot.embeddingModelId, snapshot.embeddingModelRevision, snapshot.rerankerModelId, snapshot.rerankerModelRevision,
                snapshot.parameterOverridesJson, snapshot.contextPolicyJson, snapshot.permissionSettingsJson,
                snapshot.bindingManifestJson, expanded,
            ),
        )
        snapshot
    }

    fun snapshot(agentId: String): AgentSnapshot? =
        db.query("SELECT * FROM agent_snapshots WHERE agent_id=? ORDER BY created_at DESC,id DESC LIMIT 1", listOf(agentId))
            .singleOrNull()?.toSnapshot(json)

    fun getSnapshot(id: String): AgentSnapshot? =
        db.query("SELECT * FROM agent_snapshots WHERE id=?", listOf(id)).singleOrNull()?.toSnapshot(json)

    /**
     * Resolve only the immutable expansion stored with the snapshot.  This method deliberately
     * never reads the current provider/model/prompt rows, so editing or deleting a live profile
     * cannot change an already-created conversation/run boundary.
     */
    fun resolveSnapshot(snapshotId: String): SnapshotBinding {
        val snapshot = getSnapshot(snapshotId) ?: throw invalid("Snapshot $snapshotId does not exist")
        val root = try {
            json.parseToJsonElement(snapshot.bindingManifestJson) as? JsonObject
                ?: throw IllegalArgumentException("binding manifest is not an object")
        } catch (error: Exception) {
            throw invalid("Snapshot $snapshotId has an invalid immutable binding manifest")
        }
        val manifestSnapshotId = root["snapshotId"]?.jsonPrimitive?.contentOrNull
        if (manifestSnapshotId != snapshot.id) throw invalid("Snapshot binding manifest id does not match the snapshot")
        val manifestVersion = root["schemaVersion"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: throw invalid("Snapshot binding manifest schemaVersion is missing")
        if (manifestVersion != SchemaVersion.CURRENT) throw invalid("Unsupported snapshot binding schemaVersion $manifestVersion")
        val provider = decodeManifest<ProviderProfile>(root, "provider")
        val chatModel = decodeManifest<ModelProfile>(root, "chatModel")
        val prompt = decodeManifest<PromptRevision>(root, "prompt")
        val agentName = root["agentName"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val retrievalMode = root["retrievalMode"]?.jsonPrimitive?.contentOrNull
            ?.ifBlank { "explicit" } ?: "explicit"
        val visionModel = decodeOptionalManifest<ModelProfile>(root, "visionModel")
        val embeddingModel = decodeOptionalManifest<ModelProfile>(root, "embeddingModel")
        val rerankerModel = decodeOptionalManifest<ModelProfile>(root, "rerankerModel")
        val visionProvider = decodeOptionalManifest<ProviderProfile>(root, "visionProvider")
        val embeddingProvider = decodeOptionalManifest<ProviderProfile>(root, "embeddingProvider")
        val rerankerProvider = decodeOptionalManifest<ProviderProfile>(root, "rerankerProvider")
        if (snapshot.providerId != provider.id || snapshot.providerRevision != provider.revision) {
            throw invalid("Snapshot provider binding does not match its immutable manifest")
        }
        if (snapshot.chatModelId != chatModel.id || snapshot.chatModelRevision != chatModel.revision || chatModel.providerId != provider.id) {
            throw invalid("Snapshot chat model binding does not match its immutable manifest")
        }
        if (snapshot.promptRevisionId != prompt.id || prompt.agentId != snapshot.agentId) {
            throw invalid("Snapshot prompt binding does not match its immutable manifest")
        }
        checkOptionalSnapshotModel(snapshot.visionModelId, snapshot.visionModelRevision, visionModel, "vision")
        checkOptionalSnapshotModel(snapshot.embeddingModelId, snapshot.embeddingModelRevision, embeddingModel, "embedding")
        checkOptionalSnapshotModel(snapshot.rerankerModelId, snapshot.rerankerModelRevision, rerankerModel, "reranker")
        checkOptionalProvider(visionModel, visionProvider, "vision")
        checkOptionalProvider(embeddingModel, embeddingProvider, "embedding")
        checkOptionalProvider(rerankerModel, rerankerProvider, "reranker")
        return SnapshotBinding(
            snapshot = snapshot,
            provider = provider,
            chatModel = chatModel,
            prompt = prompt,
            agentName = agentName,
            retrievalMode = retrievalMode,
            visionModel = visionModel,
            embeddingModel = embeddingModel,
            rerankerModel = rerankerModel,
            visionProvider = visionProvider,
            embeddingProvider = embeddingProvider,
            rerankerProvider = rerankerProvider,
        )
    }

    fun listSnapshots(agentId: String? = null): List<AgentSnapshot> {
        val rows = if (agentId == null) {
            db.query("SELECT * FROM agent_snapshots ORDER BY created_at,id")
        } else {
            db.query("SELECT * FROM agent_snapshots WHERE agent_id=? ORDER BY created_at,id", listOf(agentId))
        }
        return rows.map { it.toSnapshot(json) }
    }

    fun createConversationSnapshot(agentId: String, snapshotId: String = EntityId.random().value, at: String = clock()): AgentSnapshot =
        createSnapshot(agentId, snapshotId, at)

    private fun validateAgentFields(profile: AgentProfile) {
        requireId(profile.id, "agent.id")
        requireText(profile.name, "agent.name")
        requireId(profile.promptRevisionId, "agent.promptRevisionId")
        requireId(profile.chatProfileId, "agent.chatProfileId")
        profile.visionProfileId?.let { requireId(it, "agent.visionProfileId") }
        profile.embeddingProfileId?.let { requireId(it, "agent.embeddingProfileId") }
        profile.rerankerProfileId?.let { requireId(it, "agent.rerankerProfileId") }
        profile.knowledgeBaseIds.forEach { requireId(it, "agent.knowledgeBaseIds") }
        profile.skillIds.forEach { requireId(it, "agent.skillIds") }
        if (profile.knowledgeBaseIds.size != profile.knowledgeBaseIds.toSet().size) throw invalid("Duplicate knowledge base binding")
        if (profile.skillIds.size != profile.skillIds.toSet().size) throw invalid("Duplicate skill binding")
        if (profile.retrievalMode !in setOf("explicit", "automatic")) throw invalid("Unsupported retrieval mode")
        if (profile.revision < 0) throw invalid("Agent revision must not be negative")
        parseObject(profile.parameterOverridesJson, "agent.parameterOverridesJson")
        parseObject(profile.contextPolicyJson, "agent.contextPolicyJson")
        parseObject(profile.permissionSettingsJson, "agent.permissionSettingsJson")
    }

    private fun requirePrompt(promptId: String, agentId: String) {
        val row = db.query("SELECT agent_id FROM prompt_revisions WHERE id=?", listOf(promptId)).singleOrNull()
            ?: throw invalid("Prompt $promptId does not exist")
        if (row.string("agent_id") != agentId) throw invalid("Prompt $promptId belongs to another agent")
    }

    private fun requireBindings(profile: AgentProfile) {
        val chat = profiles.getModel(profile.chatProfileId) ?: throw invalid("Chat model ${profile.chatProfileId} does not exist")
        requireRole(chat, ModelRole.CHAT, "chat")
        profile.visionProfileId?.let {
            val model = profiles.getModel(it) ?: throw invalid("Vision model $it does not exist")
            if (model.role != ModelRole.VISION && "image" !in model.capabilities) throw invalid("Model $it is not vision-capable")
        }
        profile.embeddingProfileId?.let {
            val model = profiles.getModel(it) ?: throw invalid("Embedding model $it does not exist")
            requireRole(model, ModelRole.EMBEDDING, "embedding")
        }
        profile.rerankerProfileId?.let {
            val model = profiles.getModel(it) ?: throw invalid("Reranker model $it does not exist")
            requireRole(model, ModelRole.RERANKER, "reranker")
        }
        profile.knowledgeBaseIds.forEach { id ->
            if (db.query("SELECT id FROM knowledge_bases WHERE id=? AND deleted_at IS NULL", listOf(id)).isEmpty()) {
                throw invalid("Knowledge base $id is missing or deleted")
            }
        }
        profile.skillIds.forEach { id ->
            val exists = db.query(
                "SELECT i.install_id FROM skill_installs i JOIN skill_packages p ON p.package_hash=i.package_hash WHERE i.install_id=? AND i.enabled=1 LIMIT 1",
                listOf(id),
            ).isNotEmpty()
            if (!exists) throw invalid("Skill install $id is missing or disabled")
        }
    }

    private fun requireVision(model: ModelProfile) {
        if (model.role != ModelRole.VISION && "image" !in model.capabilities) {
            throw invalid("vision model ${model.id} is not vision-capable")
        }
    }

    private fun requireRole(model: ModelProfile, expected: ModelRole, label: String) {
        if (model.role != expected) throw invalid("$label model ${model.id} has role ${model.role}")
    }

    private inline fun <reified T> decodeManifest(root: JsonObject, key: String): T {
        val element = root[key] ?: throw invalid("Snapshot binding manifest is missing $key")
        return runCatching { json.decodeFromJsonElement<T>(element) }
            .getOrElse { throw invalid("Snapshot binding manifest has invalid $key") }
    }

    private inline fun <reified T> decodeOptionalManifest(root: JsonObject, key: String): T? {
        val element = root[key] ?: return null
        return runCatching { json.decodeFromJsonElement<T>(element) }
            .getOrElse { throw invalid("Snapshot binding manifest has invalid $key") }
    }

    private fun checkOptionalSnapshotModel(
        expectedId: String?,
        expectedRevision: Int?,
        model: ModelProfile?,
        label: String,
    ) {
        if (expectedId == null) {
            if (model != null) throw invalid("Snapshot $label model is present only in the manifest")
            return
        }
        if (model == null || model.id != expectedId || model.revision != expectedRevision) {
            throw invalid("Snapshot $label model binding does not match its immutable manifest")
        }
    }

    private fun checkOptionalProvider(model: ModelProfile?, provider: ProviderProfile?, label: String) {
        if (model == null) {
            if (provider != null) throw invalid("Snapshot $label provider is present without a model")
            return
        }
        if (provider == null || provider.id != model.providerId) {
            throw invalid("Snapshot $label provider binding does not match its immutable manifest")
        }
    }

    private fun validatePrompt(prompt: PromptRevision) {
        requireId(prompt.id, "prompt.id")
        requireId(prompt.agentId, "prompt.agentId")
        validatePromptValues(prompt.template, prompt.allowedVariables)
    }

    private fun validatePromptValues(template: String, allowedVariables: Set<String>) {
        if (template.isBlank() || template.length > MAX_PROMPT_CHARS) throw invalid("Prompt template is empty or too long")
        if (allowedVariables.any { it !in DEFAULT_ALLOWED_VARIABLES }) throw invalid("Prompt contains an unknown variable")
        val placeholders = TEMPLATE_VARIABLE.findAll(template).map { it.groupValues[1].trim() }.toList()
        if (placeholders.any { it !in allowedVariables }) throw invalid("Prompt contains a variable outside its allow-list")
        val remainder = TEMPLATE_VARIABLE.replace(template, "")
        if (remainder.contains("{{") || remainder.contains("}}")) throw invalid("Prompt contains a malformed variable")
    }

    private fun parseObject(raw: String, field: String) {
        val element = runCatching { json.parseToJsonElement(raw) }.getOrElse { throw invalid("$field must be a JSON object") }
        if (element !is kotlinx.serialization.json.JsonObject) throw invalid("$field must be a JSON object")
    }

    private fun requireId(value: String, field: String) {
        requireText(value, field)
        if (!SAFE_ID.matches(value)) throw invalid("$field contains unsafe characters")
    }

    private fun requireText(value: String, field: String) {
        if (value.isBlank() || value.length > 256) throw invalid("$field is empty or too long")
    }

    private fun requireReference(kind: String, id: String, ok: Boolean) {
        if (!ok) throw invalid("$kind $id already exists")
    }

    private fun invalid(message: String): AppException = AppError(
        code = ErrorCode.INVALID_CONFIG,
        userMessage = message,
        retryClass = RetryClass.USER_ACTION,
        stage = "agent-persistence",
        operationId = "agent-write",
        sanitizedDetails = message,
    ).asException()

    private fun AgentProfile.args(json: Json): List<Any?> = listOf(
        id, name, promptRevisionId, chatProfileId, visionProfileId, embeddingProfileId, rerankerProfileId,
        json.encodeToString(knowledgeBaseIds), json.encodeToString(skillIds), retrievalMode, revision,
        parameterOverridesJson, contextPolicyJson, permissionSettingsJson,
    )

    private fun SqlRow.toAgent(): AgentProfile = AgentProfile(
        id = string("id"),
        name = string("name"),
        promptRevisionId = string("prompt_revision_id"),
        chatProfileId = string("chat_profile_id"),
        visionProfileId = string("vision_profile_id").ifBlank { null },
        embeddingProfileId = string("embedding_profile_id").ifBlank { null },
        rerankerProfileId = string("reranker_profile_id").ifBlank { null },
        knowledgeBaseIds = decodeList(string("knowledge_base_ids")),
        skillIds = decodeList(string("skill_ids")),
        retrievalMode = string("retrieval_mode").ifBlank { "explicit" },
        revision = long("revision").toInt(),
        parameterOverridesJson = string("parameter_overrides_json").ifBlank { "{}" },
        contextPolicyJson = string("context_policy_json").ifBlank { "{}" },
        permissionSettingsJson = string("permission_settings_json").ifBlank { "{}" },
    )

    private fun SqlRow.toPrompt(json: Json): PromptRevision = PromptRevision(
        id = string("id"),
        agentId = string("agent_id"),
        parentRevisionId = string("parent_revision_id").ifBlank { null },
        template = string("template"),
        allowedVariables = runCatching { json.decodeFromString<List<String>>(string("allowed_variables").ifBlank { "[]" }).toSet() }
            .getOrElse { throw invalid("Invalid persisted prompt variables") },
        createdAt = string("created_at"),
    )

    private fun SqlRow.toSnapshot(json: Json): AgentSnapshot = AgentSnapshot(
        id = string("id"),
        schemaVersion = long("schema_version").toInt(),
        agentId = string("agent_id"),
        promptRevisionId = string("prompt_revision_id"),
        chatModelId = string("chat_model_id"),
        providerRevision = long("provider_revision").toInt(),
        knowledgeBaseIds = decodeList(string("knowledge_base_ids")),
        skillIds = decodeList(string("skill_ids")),
        createdAt = string("created_at"),
        providerId = string("provider_id"),
        chatModelRevision = long("chat_model_revision").toInt(),
        visionModelId = string("vision_model_id").ifBlank { null },
        visionModelRevision = columns["vision_model_revision"]?.toString()?.toIntOrNull(),
        embeddingModelId = string("embedding_model_id").ifBlank { null },
        embeddingModelRevision = columns["embedding_model_revision"]?.toString()?.toIntOrNull(),
        rerankerModelId = string("reranker_model_id").ifBlank { null },
        rerankerModelRevision = columns["reranker_model_revision"]?.toString()?.toIntOrNull(),
        parameterOverridesJson = string("parameter_overrides_json").ifBlank { "{}" },
        contextPolicyJson = string("context_policy_json").ifBlank { "{}" },
        permissionSettingsJson = string("permission_settings_json").ifBlank { "{}" },
        bindingManifestJson = string("binding_manifest_json").ifBlank { "{}" },
    )

    private fun decodeList(raw: String): List<String> =
        runCatching { json.decodeFromString<List<String>>(raw.ifBlank { "[]" }) }.getOrElse {
            throw invalid("Invalid persisted Agent binding list")
        }

    companion object {
        private const val MAX_PROMPT_CHARS = 256_000
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
        private val TEMPLATE_VARIABLE = Regex("\\{\\{([^{}]*)\\}\\}")
        val DEFAULT_ALLOWED_VARIABLES: Set<String> = setOf("date", "agent_name", "knowledge_bases")
    }
}
