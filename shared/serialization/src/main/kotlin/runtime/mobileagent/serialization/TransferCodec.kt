// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.AuditEvent
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.Conversation
import runtime.mobileagent.domain.ImagePart
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.MessagePart
import runtime.mobileagent.domain.TextPart
import runtime.mobileagent.domain.ToolCallPart
import runtime.mobileagent.domain.ToolResultPart
import runtime.mobileagent.domain.CitationPart
import runtime.mobileagent.domain.DiffPart
import runtime.mobileagent.domain.ErrorPart
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.ToolInvocation
import runtime.mobileagent.domain.MessagePartLimits
import runtime.mobileagent.domain.ReasoningPart
import runtime.mobileagent.domain.RefusalPart
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass

/**
 * Strict, secret-free transfer encoding shared by the repositories and UI. Unknown fields are
 * rejected by kotlinx.serialization and resource references are checked before decoding returns.
 */
object TransferCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    fun encode(bundle: TransferBundle, operationId: String = "transfer-export"): String {
        validate(bundle, operationId)
        return json.encodeToString(bundle)
    }

    /** Encode one conversation content entry used by the streaming archive format. */
    fun encodeConversation(transfer: ConversationTransfer, operationId: String = "transfer-export-conversation"): String {
        validateConversation(transfer, operationId)
        val raw = json.encodeToString(transfer)
        requireMetadataSize(raw, operationId, "conversation")
        rejectSecretKeys(json.parseToJsonElement(raw), operationId)
        return raw
    }

    /** Decode one bounded conversation content entry used by the streaming archive format. */
    fun decodeConversation(raw: String, operationId: String = "transfer-import-conversation"): ConversationTransfer {
        requireMetadataSize(raw, operationId, "conversation")
        val root = parseObject(raw, operationId)
        rejectSecretKeys(root, operationId)
        val transfer = try {
            json.decodeFromString<ConversationTransfer>(raw)
        } catch (error: SerializationException) {
            invalid(operationId, "Invalid conversation transfer: ${error.message ?: "malformed JSON"}")
        } catch (error: IllegalArgumentException) {
            invalid(operationId, "Invalid conversation transfer: ${error.message ?: "malformed value"}")
        }
        validateConversation(transfer, operationId)
        return transfer
    }

    fun decode(raw: String, operationId: String = "transfer-import"): TransferBundle {
        requireMetadataSize(raw, operationId, "manifest")
        val root = parseObject(raw, operationId)
        val version = root["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?: invalid(operationId, "Transfer schemaVersion is required")
        SchemaVersion.requireSupported(version, operationId)
        rejectSecretKeys(root, operationId)
        val bundle = try {
            json.decodeFromString<TransferBundle>(raw)
        } catch (error: SerializationException) {
            invalid(operationId, "Invalid transfer document: ${error.message ?: "malformed JSON"}")
        } catch (error: IllegalArgumentException) {
            invalid(operationId, "Invalid transfer document: ${error.message ?: "malformed value"}")
        }
        validate(bundle, operationId)
        return bundle
    }

    fun validate(bundle: TransferBundle, operationId: String = "transfer-validate"): TransferValidationResult {
        SchemaVersion.requireSupported(bundle.schemaVersion, operationId)
        requireText(bundle.exportedAt, operationId, "exportedAt")
        val agent = bundle.agent
        val kbs = bundle.knowledgeBases
        val skills = bundle.skills
        val conversations = bundle.conversations
        if (agent == null && kbs.isEmpty() && skills.isEmpty() && conversations.isEmpty()) {
            invalid(operationId, "Transfer must contain an agent, knowledge base, skill, or conversation")
        }
        if (agent != null) validateAgent(agent, kbs, skills, operationId)
        validateKnowledge(kbs, operationId)
        validateSkills(skills, operationId)
        validateConversations(conversations, operationId)
        val encoded = json.encodeToString(bundle)
        requireMetadataSize(encoded, operationId, "manifest")
        rejectSecretKeys(json.parseToJsonElement(encoded), operationId)
        return TransferValidationResult(bundle)
    }

    /** Validate decoded dynamic JSON fields as objects without allowing protocol fields to hide. */
    fun requireJsonObject(raw: String, operationId: String, field: String): JsonObject {
        val element = try {
            json.parseToJsonElement(raw)
        } catch (error: SerializationException) {
            invalid(operationId, "$field must be a JSON object")
        }
        return element as? JsonObject ?: invalid(operationId, "$field must be a JSON object")
    }

    private fun validateAgent(
        transfer: AgentTransfer,
        knowledgeBases: List<KnowledgeTransfer>,
        skills: List<SkillTransfer>,
        operationId: String,
    ) {
        val profile = transfer.profile
        requireId(profile.id, operationId, "agent.id")
        requireText(profile.name, operationId, "agent.name")
        requireId(profile.promptRevisionId, operationId, "agent.promptRevisionId")
        requireId(profile.chatProfileId, operationId, "agent.chatProfileId")
        requireNonNegative(profile.revision, operationId, "agent.revision")
        if (profile.retrievalMode !in setOf("explicit", "automatic")) {
            invalid(operationId, "agent.retrievalMode is unsupported")
        }
        listOf(profile.visionProfileId, profile.embeddingProfileId, profile.rerankerProfileId)
            .filterNotNull().forEach { requireId(it, operationId, "agent.modelId") }
        listOf(profile.parameterOverridesJson to "agent.parameterOverridesJson",
            profile.contextPolicyJson to "agent.contextPolicyJson",
            profile.permissionSettingsJson to "agent.permissionSettingsJson")
            .forEach { (raw, field) ->
                requireJsonObject(raw, operationId, field)
                rejectSecretKeys(json.parseToJsonElement(raw), operationId)
            }
        requireDistinctIds(profile.knowledgeBaseIds, operationId, "agent.knowledgeBaseIds")
        requireDistinctIds(profile.skillIds, operationId, "agent.skillIds")
        val kbIds = knowledgeBases.map { it.id }.toSet()
        val skillIds = skills.map { it.id }.toSet()
        profile.knowledgeBaseIds.filterNot { it in kbIds }.forEach { missing ->
            // Existing local resources are allowed; TransferRepository verifies those references.
            requireId(missing, operationId, "agent.knowledgeBaseIds")
        }
        profile.skillIds.filterNot { it in skillIds }.forEach { missing ->
            requireId(missing, operationId, "agent.skillIds")
        }
        transfer.promptRevisions.forEach { prompt ->
            requireId(prompt.id, operationId, "prompt.id")
            requireId(prompt.agentId, operationId, "prompt.agentId")
            if (prompt.agentId != profile.id) invalid(operationId, "Prompt ${prompt.id} is bound to another agent")
            prompt.parentRevisionId?.let { requireId(it, operationId, "prompt.parentRevisionId") }
            requireText(prompt.template, operationId, "prompt.template")
            prompt.allowedVariables.forEach { variable ->
                if (!ALLOWED_PROMPT_VARIABLES.contains(variable)) {
                    invalid(operationId, "Unknown prompt variable $variable")
                }
            }
        }
        requireDistinctIds(transfer.promptRevisions.map { it.id }, operationId, "agent.promptRevisions")
        if (transfer.promptRevisions.none { it.id == profile.promptRevisionId }) {
            // A metadata-only import may reference a prompt already present locally. The DB layer
            // performs the final conflict/reference check before writing.
            requireId(profile.promptRevisionId, operationId, "agent.promptRevisionId")
        }
        transfer.providers.forEach { provider ->
            requireId(provider.id, operationId, "provider.id")
            requireText(provider.name, operationId, "provider.name")
            requireText(provider.apiFormat, operationId, "provider.apiFormat")
            if (provider.apiFormat !in setOf("OPENAI_COMPATIBLE", "OPENAI_RESPONSES")) {
                invalid(operationId, "Unsupported provider apiFormat")
            }
            requireText(provider.baseUrl, operationId, "provider.baseUrl")
            requireNonNegative(provider.revision, operationId, "provider.revision")
            validatePublicHeaders(provider.nonSecretHeaders, operationId)
        }
        requireDistinctIds(transfer.providers.map { it.id }, operationId, "agent.providers")
        transfer.models.forEach { model ->
            val p = model.profile
            requireId(p.id, operationId, "model.id")
            requireId(p.providerId, operationId, "model.providerId")
            requireText(p.modelId, operationId, "model.modelId")
            requirePositive(p.contextLimit, operationId, "model.contextLimit")
            requirePositive(p.outputLimit, operationId, "model.outputLimit")
            requireJsonObject(p.parameterSchemaJson, operationId, "model.parameterSchemaJson")
            requireJsonObject(p.parametersJson, operationId, "model.parametersJson")
            rejectSecretKeys(json.parseToJsonElement(p.parametersJson), operationId)
            requireNonNegative(p.revision, operationId, "model.revision")
        }
        requireDistinctIds(transfer.models.map { it.profile.id }, operationId, "agent.models")
        val providerIds = transfer.providers.map { it.id }.toSet()
        transfer.models.forEach { model ->
            if (model.profile.providerId !in providerIds) {
                invalid(operationId, "Model ${model.profile.id} references an unlisted provider")
            }
        }
        transfer.snapshots.forEach { snapshot ->
            SchemaVersion.requireSupported(snapshot.schemaVersion, operationId)
            requireId(snapshot.id, operationId, "snapshot.id")
            if (snapshot.agentId != profile.id) invalid(operationId, "Snapshot ${snapshot.id} is bound to another agent")
            requireId(snapshot.promptRevisionId, operationId, "snapshot.promptRevisionId")
            requireId(snapshot.chatModelId, operationId, "snapshot.chatModelId")
            listOf(snapshot.visionModelId, snapshot.embeddingModelId, snapshot.rerankerModelId)
                .filterNotNull().forEach { requireId(it, operationId, "snapshot.modelId") }
            requireJsonObject(snapshot.parameterOverridesJson, operationId, "snapshot.parameterOverridesJson")
            rejectSecretKeys(json.parseToJsonElement(snapshot.parameterOverridesJson), operationId)
            requireJsonObject(snapshot.contextPolicyJson, operationId, "snapshot.contextPolicyJson")
            rejectSecretKeys(json.parseToJsonElement(snapshot.contextPolicyJson), operationId)
            requireJsonObject(snapshot.permissionSettingsJson, operationId, "snapshot.permissionSettingsJson")
            rejectSecretKeys(json.parseToJsonElement(snapshot.permissionSettingsJson), operationId)
            requireJsonObject(snapshot.bindingManifestJson, operationId, "snapshot.bindingManifestJson")
            rejectSecretKeys(json.parseToJsonElement(snapshot.bindingManifestJson), operationId)
        }
        requireDistinctIds(transfer.snapshots.map { it.id }, operationId, "agent.snapshots")
    }

    private fun validateKnowledge(knowledgeBases: List<KnowledgeTransfer>, operationId: String) {
        val kbIds = mutableSetOf<String>()
        val blobMetadata = mutableMapOf<String, Pair<Long, String>>()
        val documentIds = mutableSetOf<String>()
        knowledgeBases.forEach { kb ->
            requireId(kb.id, operationId, "knowledgeBase.id")
            requireText(kb.name, operationId, "knowledgeBase.name")
            if (!kbIds.add(kb.id)) invalid(operationId, "Duplicate knowledge base ${kb.id}")
            kb.embeddingSpaceId?.let { requireId(it, operationId, "knowledgeBase.embeddingSpaceId") }
            val localBlobHashes = mutableSetOf<String>()
            kb.blobs.forEach { blob ->
                requireHash(blob.hash, operationId, "blob.hash")
                requireNonNegative(blob.byteLength, operationId, "blob.byteLength")
                if (blob.byteLength > TransferArchiveLimits.MAX_ENTRY_BYTES) {
                    invalid(operationId, "Blob ${blob.hash} exceeds the per-entry transfer limit")
                }
                requireText(blob.mediaType, operationId, "blob.mediaType")
                validateRelativePath(blob.relativePath, operationId)
                blob.licenseId?.let { if (it.length > MAX_TEXT) invalid(operationId, "blob.licenseId is too long") }
                if (!localBlobHashes.add(blob.hash)) invalid(operationId, "Duplicate blob ${blob.hash} in knowledge base ${kb.id}")
                val prior = blobMetadata.putIfAbsent(blob.hash, blob.byteLength to blob.mediaType)
                if (prior != null && prior != (blob.byteLength to blob.mediaType)) {
                    invalid(operationId, "Blob ${blob.hash} has conflicting metadata")
                }
            }
            val documents = mutableSetOf<String>()
            kb.documents.forEach { document ->
                requireId(document.id, operationId, "document.id")
                if (!documents.add(document.id)) invalid(operationId, "Duplicate document ${document.id}")
                if (!documentIds.add(document.id)) invalid(operationId, "Document ${document.id} is listed in multiple knowledge bases")
                if (document.knowledgeBaseId != kb.id) invalid(operationId, "Document ${document.id} references another knowledge base")
                requireHash(document.blobHash, operationId, "document.blobHash")
                if (document.blobHash !in kb.blobs.map { it.hash }.toSet()) {
                    invalid(operationId, "Document ${document.id} references an unlisted blob")
                }
                document.contentHash?.let { requireHash(it, operationId, "document.contentHash") }
                document.activeVersionId?.let { requireId(it, operationId, "document.activeVersionId") }
                validateRelativePath(document.relativePath, operationId)
            }
            if (!kb.contentIncluded && (kb.documentVersions.isNotEmpty() || kb.chunks.isNotEmpty() || kb.assets.isNotEmpty())) {
                invalid(operationId, "Knowledge content rows require an explicit full-content archive")
            }
            val versions = mutableMapOf<String, DocumentVersionTransfer>()
            kb.documentVersions.forEach { version ->
                requireId(version.id, operationId, "documentVersion.id")
                if (versions.put(version.id, version) != null) invalid(operationId, "Duplicate document version ${version.id}")
                if (version.documentId !in documents) invalid(operationId, "Document version ${version.id} references an unlisted document")
                requireId(version.documentId, operationId, "documentVersion.documentId")
                requireText(version.parserFingerprint, operationId, "documentVersion.parserFingerprint")
                requireHash(version.contentHash, operationId, "documentVersion.contentHash")
                requireText(version.status, operationId, "documentVersion.status")
                if (version.status !in setOf("STAGING", "READY", "FAILED", "CANCELLED")) {
                    invalid(operationId, "Document version ${version.id} has an unsupported status")
                }
                requireText(version.createdAt, operationId, "documentVersion.createdAt")
            }
            val chunks = mutableSetOf<String>()
            kb.chunks.forEach { chunk ->
                requireId(chunk.id, operationId, "chunk.id")
                if (!chunks.add(chunk.id)) invalid(operationId, "Duplicate chunk ${chunk.id}")
                val version = versions[chunk.documentVersionId]
                    ?: invalid(operationId, "Chunk ${chunk.id} references an unlisted document version")
                requireId(chunk.documentVersionId, operationId, "chunk.documentVersionId")
                requireNonNegative(chunk.ordinal, operationId, "chunk.ordinal")
                requireText(chunk.text, operationId, "chunk.text")
                requireHash(chunk.contentHash, operationId, "chunk.contentHash")
                if (sha256(chunk.text.toByteArray(Charsets.UTF_8)) != chunk.contentHash) {
                    invalid(operationId, "Chunk ${chunk.id} content hash does not match text")
                }
                chunk.sourceSpan?.let { if (it.length > MAX_TEXT) invalid(operationId, "chunk.sourceSpan is too long") }
                chunk.assetIds.forEach { requireId(it, operationId, "chunk.assetId") }
                if (chunk.assetIds.size != chunk.assetIds.toSet().size) invalid(operationId, "Chunk ${chunk.id} contains duplicate assets")
                chunk.page?.let { requireNonNegative(it, operationId, "chunk.page") }
                if (kb.documentVersions.isNotEmpty() && version.documentId !in documents) {
                    invalid(operationId, "Chunk ${chunk.id} is outside this knowledge base")
                }
            }
            val assets = mutableSetOf<String>()
            kb.assets.forEach { asset ->
                requireId(asset.id, operationId, "asset.id")
                if (!assets.add(asset.id)) invalid(operationId, "Duplicate asset ${asset.id}")
                if (asset.documentId !in documents) invalid(operationId, "Asset ${asset.id} references an unlisted document")
                requireId(asset.documentId, operationId, "asset.documentId")
                asset.documentVersionId?.let { versionId ->
                    requireId(versionId, operationId, "asset.documentVersionId")
                    val version = versions[versionId] ?: invalid(operationId, "Asset ${asset.id} references an unlisted document version")
                    if (version.documentId != asset.documentId) invalid(operationId, "Asset ${asset.id} references another document")
                }
                requireHash(asset.blobHash, operationId, "asset.blobHash")
                if (asset.blobHash !in localBlobHashes) invalid(operationId, "Asset ${asset.id} references an unlisted blob")
                asset.page?.let { requireNonNegative(it, operationId, "asset.page") }
                asset.section?.let { if (it.length > MAX_TEXT) invalid(operationId, "asset.section is too long") }
                requireText(asset.kind, operationId, "asset.kind")
                requireHash(asset.surroundingTextHash, operationId, "asset.surroundingTextHash")
            }
            kb.documents.forEach { document ->
                document.activeVersionId?.let { versionId ->
                    val version = versions[versionId]
                    if (kb.contentIncluded && (version == null || version.documentId != document.id)) {
                        invalid(operationId, "Document ${document.id} active version is not part of the full export")
                    }
                    val contentHash = document.contentHash
                    if (contentHash != null && version != null && contentHash != version.contentHash) {
                        invalid(operationId, "Document ${document.id} content hash does not match its active version")
                    }
                }
            }
        }
    }

    private fun validateSkills(skills: List<SkillTransfer>, operationId: String) {
        val packageHashes = mutableSetOf<String>()
        val ids = mutableSetOf<String>()
        skills.forEach { skill ->
            requireHash(skill.packageHash, operationId, "skill.packageHash")
            if (!packageHashes.add(skill.packageHash)) invalid(operationId, "Duplicate skill package ${skill.packageHash}")
            requireId(skill.id, operationId, "skill.id")
            if (!ids.add(skill.id)) invalid(operationId, "Duplicate skill id ${skill.id}")
            requireText(skill.name, operationId, "skill.name")
            requireText(skill.version, operationId, "skill.version")
            requireText(skill.licenseId, operationId, "skill.licenseId")
            requireText(skill.classification, operationId, "skill.classification")
            skill.sourceHash?.let { requireHash(it, operationId, "skill.sourceHash") }
            skill.manifestJson?.let {
                val manifest = requireJsonObject(it, operationId, "skill.manifestJson")
                rejectSecretKeys(manifest, operationId)
            }
            skill.packageBase64?.let { encoded ->
                val bytes = try {
                    Base64.getDecoder().decode(encoded)
                } catch (_: IllegalArgumentException) {
                    invalid(operationId, "skill.packageBase64 is not valid base64")
                }
                val actual = sha256(bytes)
                if (actual != skill.packageHash) invalid(operationId, "Skill package hash does not match packageBase64")
                if (bytes.size > TransferArchiveLimits.MAX_ENTRY_BYTES) invalid(operationId, "Skill package exceeds the per-entry transfer limit")
            }
            if (skill.packageIncluded && skill.packageBase64 != null) {
                invalid(operationId, "Skill package bytes must be a ZIP entry, not manifest base64")
            }
        }
    }

    private fun validateConversations(conversations: List<ConversationTransfer>, operationId: String) {
        val ids = mutableSetOf<String>()
        val contentEntries = mutableSetOf<String>()
        conversations.forEach { transfer ->
            validateConversation(transfer, operationId)
            if (!ids.add(transfer.conversation.id)) invalid(operationId, "Duplicate conversation ${transfer.conversation.id}")
            transfer.contentEntry?.let { entry ->
                if (!contentEntries.add(entry)) invalid(operationId, "Duplicate conversation content entry $entry")
            }
        }
    }

    private fun validateConversation(transfer: ConversationTransfer, operationId: String) {
        val conversation = transfer.conversation
        requireId(conversation.id, operationId, "conversation.id")
        requireId(conversation.snapshotId, operationId, "conversation.snapshotId")
        requireText(conversation.title, operationId, "conversation.title")
        requireText(conversation.createdAt, operationId, "conversation.createdAt")
        requireText(conversation.updatedAt, operationId, "conversation.updatedAt")
        if (transfer.snapshotRebindPolicy != LOCAL_CREDENTIALS_REQUIRED) {
            invalid(operationId, "Conversation ${conversation.id} has an unsupported snapshot rebind policy")
        }
        if (transfer.contentIncluded && transfer.messages.isEmpty() && transfer.contentEntry.isNullOrBlank()) {
            invalid(operationId, "Conversation ${conversation.id} is marked full but has no content entry")
        }
        if (!transfer.contentIncluded && transfer.contentEntry != null) {
            invalid(operationId, "Conversation ${conversation.id} has a content entry without full export")
        }
        transfer.contentEntry?.let { entry ->
            validateRelativePath(entry, operationId)
            if (!entry.startsWith("conversations/") || !entry.endsWith(".json")) {
                invalid(operationId, "Conversation ${conversation.id} has an invalid archive content entry")
            }
        }
        validateSnapshot(transfer.snapshot, conversation, operationId)

        val messageIds = mutableSetOf<String>()
        transfer.messages.forEach { message ->
            validateMessage(message, conversation, operationId)
            if (!messageIds.add(message.id)) invalid(operationId, "Duplicate message ${message.id}")
        }
        transfer.messages.forEach { message ->
            message.parentMessageId?.let { parent ->
                if (parent !in messageIds) invalid(operationId, "Message ${message.id} references an unlisted parent")
            }
        }
        val runIds = mutableSetOf<String>()
        transfer.runs.forEach { run ->
            validateRun(run, conversation, transfer.snapshot, operationId)
            if (!runIds.add(run.runId)) invalid(operationId, "Duplicate run ${run.runId}")
        }
        val invocationIds = mutableSetOf<String>()
        val callsByRun = mutableSetOf<Pair<String, String>>()
        transfer.toolInvocations.forEach { invocation ->
            validateInvocation(invocation, runIds, operationId)
            if (!invocationIds.add(invocation.invocationId)) invalid(operationId, "Duplicate tool invocation ${invocation.invocationId}")
            if (!callsByRun.add(invocation.runId to invocation.callId)) invalid(operationId, "Duplicate tool call ${invocation.callId}")
        }
        val auditIds = mutableSetOf<String>()
        transfer.auditEvents.forEach { event ->
            validateAudit(event, runIds, operationId)
            if (!auditIds.add(event.id)) invalid(operationId, "Duplicate audit event ${event.id}")
        }
    }

    private fun validateSnapshot(snapshot: AgentSnapshot, conversation: Conversation, operationId: String) {
        SchemaVersion.requireSupported(snapshot.schemaVersion, operationId)
        requireId(snapshot.id, operationId, "snapshot.id")
        if (snapshot.id != conversation.snapshotId) invalid(operationId, "Conversation snapshot id does not match its snapshot")
        requireId(snapshot.agentId, operationId, "snapshot.agentId")
        requireId(snapshot.promptRevisionId, operationId, "snapshot.promptRevisionId")
        requireId(snapshot.chatModelId, operationId, "snapshot.chatModelId")
        requireId(snapshot.providerId, operationId, "snapshot.providerId")
        requireNonNegative(snapshot.providerRevision, operationId, "snapshot.providerRevision")
        requireNonNegative(snapshot.chatModelRevision, operationId, "snapshot.chatModelRevision")
        snapshot.knowledgeBaseIds.forEach { requireId(it, operationId, "snapshot.knowledgeBaseId") }
        snapshot.skillIds.forEach { requireId(it, operationId, "snapshot.skillId") }
        if (snapshot.knowledgeBaseIds.size != snapshot.knowledgeBaseIds.toSet().size ||
            snapshot.skillIds.size != snapshot.skillIds.toSet().size) {
            invalid(operationId, "Snapshot resource bindings contain duplicates")
        }
        listOf(
            snapshot.parameterOverridesJson to "snapshot.parameterOverridesJson",
            snapshot.contextPolicyJson to "snapshot.contextPolicyJson",
            snapshot.permissionSettingsJson to "snapshot.permissionSettingsJson",
            snapshot.bindingManifestJson to "snapshot.bindingManifestJson",
        ).forEach { (raw, field) ->
            requireJsonObject(raw, operationId, field)
            rejectSecretKeys(json.parseToJsonElement(raw), operationId)
        }
        listOf(snapshot.visionModelId to snapshot.visionModelRevision,
            snapshot.embeddingModelId to snapshot.embeddingModelRevision,
            snapshot.rerankerModelId to snapshot.rerankerModelRevision).forEach { (id, revision) ->
            id?.let { requireId(it, operationId, "snapshot.modelId") }
            revision?.let { requireNonNegative(it, operationId, "snapshot.modelRevision") }
        }
    }

    private fun validateMessage(message: Message, conversation: Conversation, operationId: String) {
        requireId(message.id, operationId, "message.id")
        if (message.conversationId != conversation.id) invalid(operationId, "Message ${message.id} references another conversation")
        requireId(message.conversationId, operationId, "message.conversationId")
        message.parentMessageId?.let { requireId(it, operationId, "message.parentMessageId") }
        requireText(message.status, operationId, "message.status")
        requireText(message.createdAt, operationId, "message.createdAt")
        if (message.text.length > MAX_TEXT) invalid(operationId, "Message ${message.id} text is too long")
        val metadata = requireJsonObject(message.metadataJson, operationId, "message.metadataJson")
        rejectSecretKeys(metadata, operationId)
        rejectUnredactedRequest(metadata, operationId)
        if (message.parts.size > MessagePartLimits.MAX_PART_COUNT) {
            invalid(operationId, "Message ${message.id} contains too many parts")
        }
        var encodedPartBytes = 0L
        message.parts.forEach { part ->
            val encoded = try {
                json.encodeToString<MessagePart>(part)
            } catch (_: SerializationException) {
                invalid(operationId, "Message ${message.id} contains an invalid part")
            }
            encodedPartBytes += encoded.toByteArray(Charsets.UTF_8).size
            if (encodedPartBytes > MessagePartLimits.MAX_TOTAL_ENCODED_BYTES) {
                invalid(operationId, "Message ${message.id} parts exceed the durable size limit")
            }
            when (part) {
                is TextPart -> if (part.value.length > MAX_TEXT) invalid(operationId, "Message text part is too long")
                is ReasoningPart -> Unit // The domain constructor enforces real, bounded content.
                is RefusalPart -> Unit // The domain constructor enforces real, bounded content.
                is ImagePart -> {
                    requireId(part.assetId, operationId, "image.assetId")
                    requireText(part.mediaType, operationId, "image.mediaType")
                    part.blobHash?.let { requireHash(it, operationId, "image.blobHash") }
                }
                is ToolCallPart -> {
                    requireId(part.callId, operationId, "tool.callId")
                    requireText(part.name, operationId, "tool.name")
                    val arguments = requireJsonObject(part.argumentsJson, operationId, "tool.argumentsJson")
                    rejectSecretKeys(arguments, operationId)
                }
                is ToolResultPart -> {
                    requireId(part.callId, operationId, "toolResult.callId")
                    requireText(part.status, operationId, "toolResult.status")
                    val result = requireJsonObject(part.resultJson, operationId, "toolResult.resultJson")
                    rejectSecretKeys(result, operationId)
                }
                is DiffPart -> Unit // The domain constructor enforces bounded, path-safe preview.
                is ErrorPart -> Unit // The domain enum and constructor enforce a safe error value.
                is CitationPart -> requireId(part.citationId, operationId, "citation.citationId")
            }
        }
    }

    private fun validateRun(run: RunRecord, conversation: Conversation, snapshot: AgentSnapshot, operationId: String) {
        requireId(run.runId, operationId, "run.runId")
        if (run.conversationId != conversation.id) invalid(operationId, "Run ${run.runId} references another conversation")
        if (run.snapshotId != snapshot.id) invalid(operationId, "Run ${run.runId} references another snapshot")
        requireId(run.conversationId, operationId, "run.conversationId")
        requireId(run.snapshotId, operationId, "run.snapshotId")
        requireJsonObject(run.budgetJson, operationId, "run.budgetJson")
        rejectSecretKeys(json.parseToJsonElement(run.budgetJson), operationId)
        if (run.modelRounds < 0 || run.toolCalls < 0 || run.inputTokens < 0 || run.outputTokens < 0) {
            invalid(operationId, "Run ${run.runId} counters must not be negative")
        }
        requireText(run.createdAt, operationId, "run.createdAt")
        requireText(run.updatedAt, operationId, "run.updatedAt")
        run.stopReason?.let { if (it.length > MAX_TEXT) invalid(operationId, "run.stopReason is too long") }
        run.errorCode?.let { if (it.length > MAX_TEXT) invalid(operationId, "run.errorCode is too long") }
    }

    private fun validateInvocation(invocation: ToolInvocation, runIds: Set<String>, operationId: String) {
        requireId(invocation.invocationId, operationId, "invocation.invocationId")
        requireId(invocation.runId, operationId, "invocation.runId")
        if (invocation.runId !in runIds) invalid(operationId, "Tool invocation ${invocation.invocationId} references an unlisted run")
        requireId(invocation.callId, operationId, "invocation.callId")
        requireText(invocation.name, operationId, "invocation.name")
        val args = requireJsonObject(invocation.argumentsJson, operationId, "invocation.argumentsJson")
        rejectSecretKeys(args, operationId)
        invocation.resultJson?.let {
            val result = requireJsonObject(it, operationId, "invocation.resultJson")
            rejectSecretKeys(result, operationId)
        }
        requireText(invocation.permissionDecision, operationId, "invocation.permissionDecision")
        requireText(invocation.state, operationId, "invocation.state")
        requireText(invocation.createdAt, operationId, "invocation.createdAt")
        requireText(invocation.updatedAt, operationId, "invocation.updatedAt")
    }

    private fun validateAudit(event: AuditEvent, runIds: Set<String>, operationId: String) {
        requireId(event.id, operationId, "audit.id")
        event.runId?.let {
            requireId(it, operationId, "audit.runId")
            if (it !in runIds) invalid(operationId, "Audit event ${event.id} references an unlisted run")
        }
        requireText(event.createdAt, operationId, "audit.createdAt")
        requireText(event.component, operationId, "audit.component")
        requireText(event.action, operationId, "audit.action")
        requireText(event.result, operationId, "audit.result")
        requireText(event.summary, operationId, "audit.summary")
        if (event.inputBytes < 0 || event.outputBytes < 0 || event.inputTokens < 0 || event.outputTokens < 0) {
            invalid(operationId, "Audit counters must not be negative")
        }
        val metadata = requireJsonObject(event.metadataJson, operationId, "audit.metadataJson")
        rejectSecretKeys(metadata, operationId)
        rejectUnredactedRequest(metadata, operationId)
    }

    private fun rejectUnredactedRequest(metadata: JsonObject, operationId: String) {
        metadata.keys.firstOrNull { UNREDACTED_REQUEST_KEY.matches(it) }?.let {
            invalid(operationId, "Unredacted request data is not allowed in transfers")
        }
    }

    private fun rejectSecretKeys(element: JsonElement, operationId: String) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                if (key == PUBLIC_HEADERS_FIELD) {
                    // The container name contains the word "secret" by design.  It is allowed
                    // only as a typed, flat public-header object; individual sensitive header
                    // names and non-string/control-character values are still rejected below.
                    validatePublicHeaders(value, operationId)
                } else {
                    if (SECRET_KEY.matches(key)) invalid(operationId, "Secret field $key is not allowed in transfers")
                    rejectSecretKeys(value, operationId)
                }
            }
            is JsonArray -> element.forEach { rejectSecretKeys(it, operationId) }
            else -> Unit
        }
    }

    private fun validatePublicHeaders(headers: Map<String, String>, operationId: String) {
        headers.forEach { (name, value) ->
            validatePublicHeaderName(name, operationId)
            if (value.length > MAX_HEADER_VALUE || value.any { it == '\r' || it == '\n' }) {
                invalid(operationId, "Public header $name contains an unsafe value")
            }
        }
    }

    private fun validatePublicHeaders(element: JsonElement, operationId: String) {
        val headers = element as? JsonObject
            ?: invalid(operationId, "$PUBLIC_HEADERS_FIELD must be an object")
        headers.forEach { (name, value) ->
            validatePublicHeaderName(name, operationId)
            val primitive = value as? JsonPrimitive
            if (primitive == null || !primitive.isString) {
                invalid(operationId, "Public header $name must be a string")
            }
            val content = primitive.contentOrNull
                ?: invalid(operationId, "Public header $name must be a string")
            if (content.length > MAX_HEADER_VALUE || content.any { it == '\r' || it == '\n' }) {
                invalid(operationId, "Public header $name contains an unsafe value")
            }
        }
    }

    private fun validatePublicHeaderName(name: String, operationId: String) {
        if (name.length > MAX_HEADER_NAME || !PUBLIC_HEADER_NAME.matches(name) || SECRET_KEY.matches(name)) {
            invalid(operationId, "Header $name is not allowed in nonSecretHeaders")
        }
    }

    private fun parseObject(raw: String, operationId: String): JsonObject = try {
        val element = json.parseToJsonElement(raw)
        element as? JsonObject ?: invalid(operationId, "Transfer root must be an object")
    } catch (error: SerializationException) {
        invalid(operationId, "Transfer root is not valid JSON")
    }

    private fun requireText(value: String, operationId: String, field: String) {
        if (value.isBlank() || value.length > MAX_TEXT) invalid(operationId, "$field is empty or too long")
    }

    private fun requireId(value: String, operationId: String, field: String) {
        requireText(value, operationId, field)
        if (!SAFE_ID.matches(value)) invalid(operationId, "$field contains an unsafe identifier")
    }

    private fun requireDistinctIds(values: List<String>, operationId: String, field: String) {
        values.forEach { requireId(it, operationId, field) }
        if (values.size != values.toSet().size) invalid(operationId, "$field contains duplicates")
    }

    private fun requireHash(value: String, operationId: String, field: String) {
        if (!SHA256.matches(value)) invalid(operationId, "$field must be a lowercase SHA-256 hash")
    }

    private fun requirePositive(value: Int, operationId: String, field: String) {
        if (value <= 0) invalid(operationId, "$field must be positive")
    }

    private fun requireNonNegative(value: Int, operationId: String, field: String) {
        if (value < 0) invalid(operationId, "$field must not be negative")
    }

    private fun requireNonNegative(value: Long, operationId: String, field: String) {
        if (value < 0) invalid(operationId, "$field must not be negative")
    }

    private fun validateRelativePath(value: String, operationId: String) {
        if (value.isBlank()) return
        if (value.length > MAX_TEXT || value.startsWith('/') || value.startsWith('\\') ||
            Regex("^[A-Za-z]:").containsMatchIn(value) || value.contains('\u0000')
        ) invalid(operationId, "Unsafe transfer path")
        val normalized = value.replace('\\', '/')
        if (normalized.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            invalid(operationId, "Unsafe transfer path")
        }
    }

    private fun requireMetadataSize(raw: String, operationId: String, field: String) {
        val size = raw.toByteArray(Charsets.UTF_8).size.toLong()
        if (size > TransferArchiveLimits.MAX_METADATA_BYTES) {
            invalid(operationId, "$field exceeds the ${TransferArchiveLimits.MAX_METADATA_BYTES} byte metadata limit")
        }
    }

    private fun invalid(operationId: String, message: String): Nothing = throw AppError(
        code = ErrorCode.TRANSFER_INVALID,
        userMessage = message,
        retryClass = RetryClass.USER_ACTION,
        stage = "transfer",
        operationId = operationId,
        sanitizedDetails = message,
    ).asException()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val SECRET_KEY = Regex("(?i).*(secret|api[_-]?key|authorization|cookie|password|private[_-]?key).*" )
    private val PUBLIC_HEADER_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val UNREDACTED_REQUEST_KEY = Regex("(?i)^(request|request[_-]?json|request[_-]?body|raw[_-]?body|response[_-]?body)$" )
    private const val PUBLIC_HEADERS_FIELD = "nonSecretHeaders"
    private const val MAX_HEADER_NAME = 128
    private const val MAX_HEADER_VALUE = 4096
    private const val MAX_TEXT = 32_768
    private const val LOCAL_CREDENTIALS_REQUIRED = "LOCAL_CREDENTIALS_REQUIRED"
    private val ALLOWED_PROMPT_VARIABLES = setOf("date", "agent_name", "knowledge_bases")
}

/** Alias for callers that prefer a validator named after the public wire format. */
object TransferValidator {
    fun validate(bundle: TransferBundle, operationId: String = "transfer-validate"): TransferValidationResult =
        TransferCodec.validate(bundle, operationId)

    fun decode(raw: String, operationId: String = "transfer-import"): TransferBundle =
        TransferCodec.decode(raw, operationId)
}
