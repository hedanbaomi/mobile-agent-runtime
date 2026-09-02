// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import runtime.mobileagent.domain.AuditEvent
import runtime.mobileagent.domain.Conversation
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.PromptRevision
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.MessagePart
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.ToolInvocation
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.knowledge.BlobSink
import runtime.mobileagent.knowledge.StoredBlob
import runtime.mobileagent.knowledge.sha256Hex
import runtime.mobileagent.serialization.AgentTransfer
import runtime.mobileagent.serialization.BlobTransfer
import runtime.mobileagent.serialization.ChunkTransfer
import runtime.mobileagent.serialization.AssetTransfer
import runtime.mobileagent.serialization.ConversationTransfer
import runtime.mobileagent.serialization.DocumentVersionTransfer
import runtime.mobileagent.serialization.DocumentTransfer
import runtime.mobileagent.serialization.KnowledgeTransfer
import runtime.mobileagent.serialization.ModelTransfer
import runtime.mobileagent.serialization.ProviderTransfer
import runtime.mobileagent.serialization.SkillTransfer
import runtime.mobileagent.serialization.TransferBundle
import runtime.mobileagent.serialization.TransferCodec
import runtime.mobileagent.serialization.TransferConflictPolicy
import runtime.mobileagent.serialization.TransferArchiveLimits
import runtime.mobileagent.serialization.TransferOptions

data class TransferImportResult(
    val agentId: String? = null,
    val knowledgeBaseIds: List<String> = emptyList(),
    val skillIds: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/**
 * Metadata and configuration transfer boundary. Export never reads secret ciphertext and import
 * validates the complete bundle before a single transaction writes any row.
 */
class TransferRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
    private val blobSink: BlobSink? = null,
) {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false; encodeDefaults = true }

    /**
     * Legacy JSON export.  It remains metadata-only for knowledge and conversations; the boolean
     * only preserves the old bounded Skill package base64 behavior.
     */
    fun exportAgent(agentId: String, includeSkillPackageBytes: Boolean = false): String =
        exportAgent(agentId, TransferOptions(includeSkillPackageBytes = includeSkillPackageBytes))

    /** JSON export with explicit options. Full content must use [exportArchive]. */
    fun exportAgent(agentId: String, options: TransferOptions): String {
        if (options.includeKnowledgeContent || options.includeConversations) {
            throw invalid("Full knowledge or conversation content requires the streaming exportArchive API")
        }
        val payload = buildAgentBundle(agentId, options, forArchive = false)
        return TransferCodec.encode(payload.manifest, operationId = "export-agent-$agentId")
    }

    /**
     * Stream an explicit archive without materializing the source/asset bytes or conversation
     * content in one JSON document.  The caller retains ownership of [output].
     */
    fun exportArchive(
        agentId: String,
        options: TransferOptions = TransferOptions(),
        output: OutputStream,
    ) {
        if (options.includeKnowledgeContent && blobSink == null) {
            throw invalid("Full knowledge export requires a BlobSink")
        }
        val payload = buildAgentBundle(agentId, options, forArchive = true)
        val manifest = TransferCodec.encode(payload.manifest, operationId = "export-archive-$agentId")
        // ZipOutputStream.close() would close the caller's stream as well.  Keep ownership at the
        // API boundary while still releasing the ZIP deflater when the archive is complete.
        ZipOutputStream(NonClosingOutputStream(output)).use { zip ->
            var entries = 0
            var total = 0L
            fun writeBytes(name: String, bytes: ByteArray, limit: Long = TransferArchiveLimits.MAX_ENTRY_BYTES) {
                if (!isSafeArchiveName(name)) throw invalid("Unsafe archive entry path")
                if (++entries > TransferArchiveLimits.MAX_ENTRIES) throw invalid("Archive has too many entries")
                if (bytes.size.toLong() > limit) throw invalid("Archive entry $name exceeds the per-entry limit")
                total = checkedArchiveTotal(total, bytes.size.toLong())
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }

            writeBytes(MANIFEST_ENTRY, manifest.toByteArray(Charsets.UTF_8), TransferArchiveLimits.MAX_METADATA_BYTES)
            if (options.includeKnowledgeContent) {
                val sink = blobSink ?: throw invalid("Full knowledge export requires a BlobSink")
                payload.manifest.knowledgeBases.flatMap { it.blobs }.distinctBy { it.hash }.forEach { blob ->
                    val bytes = sink.get(blob.hash) ?: throw invalid("Blob ${blob.hash} is missing from the configured BlobSink")
                    verifyBlob(blob, bytes)
                    writeBytes(blobEntry(blob.hash), bytes)
                }
            }
            if (options.includeSkillPackageBytes) {
                payload.manifest.skills.forEach { skill ->
                    val bytes = db.query("SELECT package_bytes FROM skill_packages WHERE package_hash=?", listOf(skill.packageHash))
                        .singleOrNull()?.columns?.get("package_bytes") as? ByteArray
                        ?: throw invalid("Skill package ${skill.packageHash} is missing")
                    verifyHash(skill.packageHash, bytes, "Skill package")
                    writeBytes(skillEntry(skill.packageHash), bytes)
                }
            }
            if (options.includeConversations) {
                payload.conversations.forEach { item ->
                    val content = TransferCodec.encodeConversation(item.full, "export-conversation-${item.full.conversation.id}")
                    writeBytes(item.entryName, content.toByteArray(Charsets.UTF_8), TransferArchiveLimits.MAX_METADATA_BYTES)
                }
            }
            zip.finish()
        }
    }

    fun exportArchive(agentId: String, output: OutputStream) =
        exportArchive(agentId, TransferOptions(), output)

    private fun buildAgentBundle(agentId: String, options: TransferOptions, forArchive: Boolean): ArchivePayload {
        val agent = AgentRepository(db).get(agentId) ?: throw invalid("Agent $agentId does not exist")
        val agentRepo = AgentRepository(db)
        val prompts = agentRepo.listPromptRevisions(agentId)
        val profiles = ProfileRepository(db)
        val modelIds = buildSet {
            add(agent.chatProfileId)
            agent.visionProfileId?.let(::add)
            agent.embeddingProfileId?.let(::add)
            agent.rerankerProfileId?.let(::add)
        }
        val models = modelIds.map { modelId ->
            ModelTransfer(profiles.getModel(modelId) ?: throw invalid("Agent references missing model $modelId"))
        }
        val providerIds = models.map { it.profile.providerId }.toSet()
        val providers = providerIds.map { providerId ->
            val provider = profiles.getProvider(providerId) ?: throw invalid("Model references missing provider $providerId")
            ProviderTransfer(
                id = provider.id,
                name = provider.name,
                apiFormat = provider.apiFormat.name,
                baseUrl = provider.baseUrl,
                nonSecretHeaders = provider.nonSecretHeaders,
                revision = provider.revision,
            )
        }
        val knowledge = agent.knowledgeBaseIds.map { id ->
            exportKnowledge(id, options.includeKnowledgeContent) ?: throw invalid("Agent references missing knowledge base $id")
        }
        val skills = agent.skillIds.map { id ->
            exportSkill(id, includeBytes = options.includeSkillPackageBytes && !forArchive,
                packageIncluded = options.includeSkillPackageBytes && forArchive) ?: throw invalid("Agent references missing skill $id")
        }
        val conversations = if (options.includeConversations) exportConversations(agent.id) else emptyList()
        val manifestConversations = conversations.map { it.manifest }
        val manifest = TransferBundle(
                schemaVersion = runtime.mobileagent.serialization.SchemaVersion.CURRENT,
                exportedAt = clock(),
                // Runtime snapshots are intentionally not part of the portable configuration:
                // their immutable provider expansion may contain host-only secret references.
                // A fresh snapshot is created after import once local credentials are configured.
                agent = AgentTransfer(agent, prompts, emptyList(), providers, models),
                knowledgeBases = knowledge,
                skills = skills,
                conversations = manifestConversations,
        )
        return ArchivePayload(manifest, conversations)
    }

    private fun exportConversations(agentId: String): List<ArchiveConversation> {
        val agentRepo = AgentRepository(db)
        val conversations = db.query(
            "SELECT c.* FROM conversations c JOIN agent_snapshots s ON s.id=c.snapshot_id WHERE s.agent_id=? ORDER BY c.created_at,c.rowid",
            listOf(agentId),
        )
        val conversationRepo = ConversationRepository(db)
        val runsRepo = RunRepository(db)
        val auditRepo = AuditRepository(db)
        return conversations.map { row ->
            val conversation = Conversation(
                id = row.string("id"),
                snapshotId = row.string("snapshot_id"),
                title = row.string("title"),
                createdAt = row.string("created_at"),
                updatedAt = row.string("updated_at"),
            )
            val snapshot = agentRepo.getSnapshot(conversation.snapshotId)
                ?: throw invalid("Conversation ${conversation.id} references a missing snapshot")
            if (snapshot.bindingManifestJson.isBlank() || snapshot.bindingManifestJson == "{}") {
                throw invalid("Conversation ${conversation.id} has no immutable snapshot expansion")
            }
            val portableSnapshot = sanitizeSnapshot(snapshot)
            val runs = runsRepo.list(conversation.id)
            val invocations = runs.flatMap { runsRepo.invocations(it.runId) }
            val audits = runs.flatMap { auditRepo.list(it.runId) }
            val entryName = conversationEntry(conversation.id)
            val full = ConversationTransfer(
                conversation = conversation,
                snapshot = portableSnapshot,
                snapshotRebindPolicy = LOCAL_CREDENTIALS_REQUIRED,
                messages = conversationRepo.messages(conversation.id),
                runs = runs,
                toolInvocations = invocations,
                auditEvents = audits,
                contentIncluded = true,
                contentEntry = entryName,
            )
            val manifest = full.copy(messages = emptyList(), runs = emptyList(), toolInvocations = emptyList(), auditEvents = emptyList())
            ArchiveConversation(manifest, full, entryName)
        }
    }

    /** Remove provider secret fields while preserving the immutable value expansion. */
    private fun sanitizeSnapshot(snapshot: AgentSnapshot): AgentSnapshot {
        fun scrub(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> buildJsonObject {
                element.forEach { (key, value) ->
                    if (key == PUBLIC_HEADERS_FIELD || !SECRET_TRANSFER_KEY.matches(key)) {
                        // Model/agent JSON fields are encoded as strings inside the immutable
                        // manifest. Parse those nested objects too, otherwise a secret key could
                        // hide behind a JSON string value.
                        if (key.endsWith("Json") && value is JsonPrimitive && value.isString) {
                            val nested = runCatching { json.parseToJsonElement(value.content) }.getOrNull()
                            if (nested is JsonObject) {
                                put(key, scrub(nested).toString())
                            } else {
                                put(key, value)
                            }
                        } else {
                            put(key, scrub(value))
                        }
                    }
                }
            }
            else -> element
        }
        fun scrubObject(raw: String, field: String): String {
            val parsed = runCatching { json.parseToJsonElement(raw) }.getOrElse { throw invalid("Snapshot $field is not valid JSON") }
            if (parsed !is JsonObject) throw invalid("Snapshot $field must be a JSON object")
            val clean = scrub(parsed) as JsonObject
            return if (field == "bindingManifestJson") {
                buildJsonObject {
                    clean.forEach { (key, value) -> put(key, value) }
                    put("snapshotRebindPolicy", LOCAL_CREDENTIALS_REQUIRED)
                }.toString()
            } else {
                clean.toString()
            }
        }
        return snapshot.copy(
            parameterOverridesJson = scrubObject(snapshot.parameterOverridesJson, "parameterOverridesJson"),
            contextPolicyJson = scrubObject(snapshot.contextPolicyJson, "contextPolicyJson"),
            permissionSettingsJson = scrubObject(snapshot.permissionSettingsJson, "permissionSettingsJson"),
            bindingManifestJson = scrubObject(snapshot.bindingManifestJson, "bindingManifestJson"),
        )
    }

    private data class ArchivePayload(
        val manifest: TransferBundle,
        val conversations: List<ArchiveConversation>,
    )

    private data class ArchiveConversation(
        val manifest: ConversationTransfer,
        val full: ConversationTransfer,
        val entryName: String,
    )

    fun export(agentId: String, includeSkillPackageBytes: Boolean = false): String =
        exportAgent(agentId, includeSkillPackageBytes)

    fun export(agentId: String, options: TransferOptions): String =
        exportAgent(agentId, options)

    fun exportBundle(bundle: TransferBundle): String = TransferCodec.encode(bundle, "transfer-export")

    fun importBundle(raw: String, conflictPolicy: TransferConflictPolicy = TransferConflictPolicy.REJECT): TransferImportResult {
        val bundle = TransferCodec.decode(raw, "transfer-import")
        return importBundle(bundle, conflictPolicy)
    }

    fun importTransfer(raw: String, conflictPolicy: TransferConflictPolicy = TransferConflictPolicy.REJECT): TransferImportResult =
        importBundle(raw, conflictPolicy)

    fun `import`(raw: String, conflictPolicy: TransferConflictPolicy = TransferConflictPolicy.REJECT): TransferImportResult =
        importBundle(raw, conflictPolicy)

    fun importBundle(bundle: TransferBundle, conflictPolicy: TransferConflictPolicy = TransferConflictPolicy.REJECT): TransferImportResult {
        TransferCodec.validate(bundle, "transfer-import")
        requireMetadataOnly(bundle)
        preflight(bundle, conflictPolicy)
        val warnings = mutableListOf<String>()
        var importedAgentId: String? = null
        db.transaction {
            bundle.knowledgeBases.forEach { kb -> importKnowledge(kb, conflictPolicy, warnings) }
            bundle.skills.forEach { skill -> importSkill(skill, conflictPolicy, warnings) }
            bundle.agent?.let { transfer ->
                importedAgentId = importAgent(transfer, conflictPolicy, warnings)
            }
            // Metadata-only conversation transfers still carry the immutable snapshot boundary.
            // Restore that conversation row instead of silently dropping it; full message/run
            // content is handled by importArchive and is rejected by requireMetadataOnly above.
            bundle.conversations.forEach { transfer ->
                importConversation(transfer, conflictPolicy, warnings)
            }
        }
        return TransferImportResult(
            agentId = importedAgentId,
            knowledgeBaseIds = bundle.knowledgeBases.map { it.id },
            skillIds = bundle.skills.map { it.id },
            warnings = warnings,
        )
    }

    /**
     * Import an explicit streaming archive.  The manifest and every content entry are validated
     * before any SQLite row is written.  CAS writes happen during preflight and may leave an
     * unreferenced orphan if the final database transaction fails; no partial database is exposed.
     */
    fun importArchive(
        input: InputStream,
        conflictPolicy: TransferConflictPolicy = TransferConflictPolicy.REJECT,
    ): TransferImportResult {
        val stagedSkills = linkedMapOf<String, Path>()
        val stagedConversations = linkedMapOf<String, Path>()
        val stagedBlobs = linkedMapOf<String, StoredBlob>()
        try {
            val countedInput = CountingInputStream(input)
            ZipInputStream(countedInput).use { zip ->
                val counter = ArchiveCounter(countedInput)
                val first = nextArchiveEntry(zip, "archive", counter)
                    ?: throw invalid("Transfer archive is empty")
                if (first.isDirectory || first.name != MANIFEST_ENTRY) throw invalid("Transfer archive must start with manifest.json")
                val manifestRaw = readEntryBytes(zip, first, TransferArchiveLimits.MAX_METADATA_BYTES, counter)
                val bundle = TransferCodec.decode(manifestRaw.toString(Charsets.UTF_8), "transfer-archive-manifest")
                requireArchiveBundle(bundle)
                preflight(bundle, conflictPolicy)

                val seen = mutableSetOf<String>()
                while (true) {
                    val entry = nextArchiveEntry(zip, "archive", counter) ?: break
                    if (entry.isDirectory || !isSafeArchiveName(entry.name)) throw invalid("Unsafe archive entry path")
                    if (!seen.add(entry.name)) throw invalid("Duplicate archive entry ${entry.name}")
                    when {
                        entry.name.startsWith(BLOB_PREFIX) -> {
                            val hash = entry.name.removePrefix(BLOB_PREFIX)
                            val blob = expectedBlob(bundle, hash)
                            val bytes = readEntryBytes(zip, entry, TransferArchiveLimits.MAX_ENTRY_BYTES, counter)
                            verifyBlob(blob, bytes)
                            val sink = blobSink ?: throw invalid("Full knowledge import requires a BlobSink")
                            val stored = sink.put(bytes, blob.mediaType)
                            if (stored.sha256 != blob.hash || stored.byteLength.toLong() != blob.byteLength) {
                                throw invalid("Blob sink returned inconsistent metadata for ${blob.hash}")
                            }
                            stagedBlobs[hash] = stored
                        }
                        entry.name.startsWith(SKILL_PREFIX) -> {
                            val hash = entry.name.removePrefix(SKILL_PREFIX)
                            val skill = bundle.skills.singleOrNull { it.packageHash == hash && it.packageIncluded }
                                ?: throw invalid("Archive contains an unlisted Skill package $hash")
                            val bytes = readEntryBytes(zip, entry, TransferArchiveLimits.MAX_ENTRY_BYTES, counter)
                            verifyHash(hash, bytes, "Skill package")
                            stagedSkills[hash] = stageBytes(bytes, "skill")
                            if (skill.packageBase64 != null) throw invalid("Skill package $hash must not be duplicated in manifest base64")
                        }
                        entry.name.startsWith(CONVERSATION_PREFIX) -> {
                            val transfer = bundle.conversations.singleOrNull { it.contentEntry == entry.name && it.contentIncluded }
                                ?: throw invalid("Archive contains an unlisted conversation entry ${entry.name}")
                            val bytes = readEntryBytes(zip, entry, TransferArchiveLimits.MAX_METADATA_BYTES, counter)
                            val full = TransferCodec.decodeConversation(bytes.toString(Charsets.UTF_8), "transfer-conversation-${transfer.conversation.id}")
                            if (full.conversation != transfer.conversation || full.snapshot != transfer.snapshot ||
                                full.snapshotRebindPolicy != transfer.snapshotRebindPolicy || full.contentEntry != transfer.contentEntry
                            ) {
                                throw invalid("Conversation entry ${entry.name} does not match its manifest binding")
                            }
                            stagedConversations[entry.name] = stageBytes(bytes, "conversation")
                        }
                        else -> throw invalid("Unknown transfer archive entry ${entry.name}")
                    }
                }
                requireExpectedArchiveEntries(bundle, stagedBlobs.keys, stagedSkills.keys, stagedConversations.keys)

                val warnings = mutableListOf<String>()
                var importedAgentId: String? = null
                db.transaction {
                    bundle.knowledgeBases.forEach { kb ->
                        importKnowledge(kb, conflictPolicy, warnings, stagedBlobs)
                    }
                    bundle.skills.forEach { skill ->
                        val bytes = if (skill.packageIncluded) stagedSkills[skill.packageHash]
                            ?.let { Files.readAllBytes(it) }
                            else null
                        importSkill(skill, conflictPolicy, warnings, bytes)
                    }
                    bundle.agent?.let { importedAgentId = importAgent(it, conflictPolicy, warnings) }
                    bundle.conversations.forEach { manifest ->
                        val path = manifest.contentEntry?.let(stagedConversations::get)
                        val full = path?.let { TransferCodec.decodeConversation(Files.readString(it), "transfer-conversation-${manifest.conversation.id}") }
                            ?: throw invalid("Conversation ${manifest.conversation.id} content is missing")
                        importConversation(full, conflictPolicy, warnings)
                    }
                }
                return TransferImportResult(
                    agentId = importedAgentId,
                    knowledgeBaseIds = bundle.knowledgeBases.map { it.id },
                    skillIds = bundle.skills.map { it.id },
                    warnings = warnings,
                )
            }
        } catch (error: AppException) {
            throw error
        } catch (error: Exception) {
            throw invalid("Transfer archive could not be imported: ${error.message ?: "malformed archive"}")
        } finally {
            (stagedSkills.values + stagedConversations.values).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    fun importArchive(bytes: ByteArray, conflictPolicy: TransferConflictPolicy = TransferConflictPolicy.REJECT): TransferImportResult =
        importArchive(ByteArrayInputStream(bytes), conflictPolicy)

    private fun requireMetadataOnly(bundle: TransferBundle) {
        if (bundle.knowledgeBases.any { it.contentIncluded } ||
            bundle.skills.any { it.packageIncluded } ||
            bundle.conversations.any {
                it.contentIncluded || it.messages.isNotEmpty() || it.runs.isNotEmpty() ||
                    it.toolInvocations.isNotEmpty() || it.auditEvents.isNotEmpty()
            }
        ) {
            throw invalid("Full transfer content must be imported with importArchive")
        }
    }

    private fun requireArchiveBundle(bundle: TransferBundle) {
        val expectedBytes = bundle.knowledgeBases
            .filter { it.contentIncluded }
            .flatMap { it.blobs }
            .sumOf { it.byteLength }
        if (expectedBytes > TransferArchiveLimits.MAX_TOTAL_UNCOMPRESSED_BYTES) {
            throw invalid("Archive content exceeds the total uncompressed transfer limit")
        }
        bundle.conversations.forEach { conversation ->
            if (!conversation.contentIncluded || conversation.contentEntry.isNullOrBlank()) {
                throw invalid("Archive conversations must provide a separate content entry")
            }
        }
        bundle.skills.forEach { skill ->
            if (skill.packageIncluded && skill.packageBase64 != null) {
                throw invalid("Archive Skill package bytes must not be embedded in manifest base64")
            }
        }
    }

    private fun requireExpectedArchiveEntries(
        bundle: TransferBundle,
        blobHashes: Set<String>,
        skillHashes: Set<String>,
        conversationEntries: Set<String>,
    ) {
        val expectedBlobs = bundle.knowledgeBases.filter { it.contentIncluded }
            .flatMap { it.blobs }.map { blobEntry(it.hash) }.toSet()
        val expectedSkills = bundle.skills.filter { it.packageIncluded }.map { skillEntry(it.packageHash) }.toSet()
        val expectedConversations = bundle.conversations.filter { it.contentIncluded }
            .mapNotNull { it.contentEntry }.toSet()
        val actual = blobHashes.map(::blobEntry).toSet() + skillHashes.map(::skillEntry).toSet() + conversationEntries
        val expected = expectedBlobs + expectedSkills + expectedConversations
        if (actual != expected) {
            throw invalid("Transfer archive content entries do not match the manifest")
        }
    }

    private fun expectedBlob(bundle: TransferBundle, hash: String): BlobTransfer {
        val matches = bundle.knowledgeBases.filter { it.contentIncluded }
            .flatMap { it.blobs }.filter { it.hash == hash }
        return matches.firstOrNull() ?: throw invalid("Archive contains an unlisted blob $hash")
    }

    private fun verifyBlob(blob: BlobTransfer, bytes: ByteArray) {
        if (bytes.size.toLong() != blob.byteLength) throw invalid("Blob ${blob.hash} length does not match its manifest")
        verifyHash(blob.hash, bytes, "Blob")
    }

    private fun verifyHash(expected: String, bytes: ByteArray, kind: String) {
        val actual = sha256Hex(bytes)
        if (actual != expected) throw invalid("$kind hash does not match manifest")
    }

    private fun stageBytes(bytes: ByteArray, kind: String): Path {
        val path = Files.createTempFile("mobile-agent-transfer-$kind-", ".bin")
        try {
            Files.write(path, bytes)
            return path
        } catch (error: Exception) {
            runCatching { Files.deleteIfExists(path) }
            throw invalid("Could not stage transfer $kind content")
        }
    }

    private fun nextArchiveEntry(zip: ZipInputStream, operationId: String, counter: ArchiveCounter): ZipEntry? {
        val entry = try {
            zip.nextEntry
        } catch (error: Exception) {
            throw invalid("Transfer archive ZIP structure is invalid")
        }
        if (entry != null && ++counter.entries > TransferArchiveLimits.MAX_ENTRIES) {
            throw invalid("Archive has too many entries")
        }
        return entry
    }

    private fun readEntryBytes(
        zip: ZipInputStream,
        entry: ZipEntry,
        limit: Long,
        counter: ArchiveCounter,
    ): ByteArray {
        // ZipInputStream commonly reports -1 for DEFLATED entries using a data descriptor.  Use
        // bytes consumed from the underlying stream as a conservative fallback so the ratio cap
        // cannot be bypassed by omitting the optional central-directory size.
        val compressedStart = counter.compressedBytes()
        val declaredCompressed = entry.compressedSize
        val output = ByteArrayOutputStream(minOf(limit, 64L * 1024L).toInt())
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = try { zip.read(buffer) } catch (error: Exception) {
                throw invalid("Transfer archive entry ${entry.name} could not be read")
            }
            if (count < 0) break
            if (count == 0) continue
            val nextSize = output.size().toLong() + count
            if (nextSize > limit) throw invalid("Archive entry ${entry.name} exceeds its size limit")
            val nextTotal = counter.total + count
            if (nextTotal > TransferArchiveLimits.MAX_TOTAL_UNCOMPRESSED_BYTES) {
                throw invalid("Archive exceeds the total uncompressed transfer limit")
            }
            output.write(buffer, 0, count)
            counter.total = nextTotal
            val observedCompressed = counter.compressedBytes() - compressedStart
            val compressed = if (declaredCompressed > 0) declaredCompressed else observedCompressed
            val ratioLimit = if (compressed > 0 &&
                compressed <= Long.MAX_VALUE / TransferArchiveLimits.MAX_COMPRESSION_RATIO
            ) {
                compressed * TransferArchiveLimits.MAX_COMPRESSION_RATIO
            } else {
                Long.MAX_VALUE
            }
            if (compressed <= 0 && nextSize > 0) {
                throw invalid("Archive entry ${entry.name} has no measurable compressed size")
            }
            if (nextSize > ratioLimit) {
                throw invalid("Archive entry ${entry.name} exceeds the compression ratio limit")
            }
        }
        return output.toByteArray()
    }

    private fun checkedArchiveTotal(current: Long, added: Long): Long {
        if (added < 0 || current > TransferArchiveLimits.MAX_TOTAL_UNCOMPRESSED_BYTES - added) {
            throw invalid("Archive exceeds the total uncompressed transfer limit")
        }
        return current + added
    }

    private fun isSafeArchiveName(name: String): Boolean {
        if (name.isBlank() || name.length > TransferArchiveLimits.MAX_ENTRY_NAME_LENGTH ||
            name.contains('\\') || name.contains('\u0000') || name.startsWith('/') ||
            Regex("^[A-Za-z]:").containsMatchIn(name)
        ) return false
        return name.split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    private fun blobEntry(hash: String): String = "$BLOB_PREFIX$hash"
    private fun skillEntry(hash: String): String = "$SKILL_PREFIX$hash"
    private fun conversationEntry(id: String): String = "$CONVERSATION_PREFIX$id.json"

    private class ArchiveCounter(private val input: CountingInputStream, var total: Long = 0L, var entries: Int = 0) {
        fun compressedBytes(): Long = input.bytesRead
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) bytesRead++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) bytesRead += count
            return count
        }

        override fun skip(length: Long): Long {
            val count = super.skip(length)
            if (count > 0) bytesRead += count
            return count
        }
    }

    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() {
            flush()
        }
    }

    private fun preflight(bundle: TransferBundle, policy: TransferConflictPolicy) {
        bundle.agent?.let { transfer ->
            val existing = db.query("SELECT * FROM agent_profiles WHERE id=?", listOf(transfer.profile.id)).singleOrNull()
            if (existing != null && policy == TransferConflictPolicy.REJECT) throw conflict("Agent ${transfer.profile.id} already exists")
            transfer.providers.forEach { provider ->
                val row = db.query("SELECT * FROM provider_profiles WHERE id=?", listOf(provider.id)).singleOrNull()
                if (row != null && policy == TransferConflictPolicy.REJECT && !sameProvider(row, provider)) {
                    throw conflict("Provider ${provider.id} conflicts with local data")
                }
            }
            transfer.models.forEach { model ->
                val row = db.query("SELECT * FROM model_profiles WHERE id=?", listOf(model.profile.id)).singleOrNull()
                if (row != null && policy == TransferConflictPolicy.REJECT && !sameModel(row, model.profile)) {
                    throw conflict("Model ${model.profile.id} conflicts with local data")
                }
            }
            transfer.promptRevisions.forEach { prompt ->
                val row = db.query("SELECT * FROM prompt_revisions WHERE id=?", listOf(prompt.id)).singleOrNull()
                if (row != null && policy == TransferConflictPolicy.REJECT && row.toPrompt(json) != prompt) {
                    throw conflict("Prompt ${prompt.id} conflicts with local data")
                }
            }
            transfer.snapshots.forEach { snapshot ->
                val row = db.query("SELECT * FROM agent_snapshots WHERE id=?", listOf(snapshot.id)).singleOrNull()
                if (row != null && policy == TransferConflictPolicy.REJECT && row.toSnapshot(json) != snapshot) {
                    throw conflict("Snapshot ${snapshot.id} conflicts with local data")
                }
            }
        }
        bundle.knowledgeBases.forEach { kb ->
            val existing = db.query("SELECT * FROM knowledge_bases WHERE id=?", listOf(kb.id)).singleOrNull()
            if (existing != null && policy == TransferConflictPolicy.REJECT &&
                (existing.string("name") != kb.name || existing.string("embedding_space_id") != kb.embeddingSpaceId.orEmpty())
            ) throw conflict("Knowledge base ${kb.id} conflicts with local data")
            kb.documents.forEach { document ->
                val row = db.query("SELECT * FROM documents WHERE id=?", listOf(document.id)).singleOrNull()
                if (row != null && policy == TransferConflictPolicy.REJECT && row.string("blob_hash") != document.blobHash) {
                    throw conflict("Document ${document.id} conflicts with local data")
                }
            }
            kb.blobs.forEach { blob ->
                val row = db.query("SELECT * FROM blobs WHERE hash=?", listOf(blob.hash)).singleOrNull()
                if (row != null && policy == TransferConflictPolicy.REJECT &&
                    (row.long("byte_length") != blob.byteLength || row.string("media_type") != blob.mediaType)
                ) throw conflict("Blob ${blob.hash} conflicts with local data")
            }
        }
        bundle.skills.forEach { skill ->
            val row = db.query("SELECT * FROM skill_packages WHERE package_hash=?", listOf(skill.packageHash)).singleOrNull()
            if (row != null && policy == TransferConflictPolicy.REJECT &&
                (row.string("id") != skill.id || row.string("version") != skill.version)
            ) throw conflict("Skill package ${skill.packageHash} conflicts with local data")
        }
        bundle.conversations.forEach { transfer ->
            val conversation = transfer.conversation
            val existing = db.query("SELECT * FROM conversations WHERE id=?", listOf(conversation.id)).singleOrNull()
            if (existing != null && policy == TransferConflictPolicy.REJECT) {
                throw conflict("Conversation ${conversation.id} already exists")
            }
            val snapshot = db.query("SELECT * FROM agent_snapshots WHERE id=?", listOf(conversation.snapshotId)).singleOrNull()
            if (snapshot != null && snapshot.string("agent_id") != transfer.snapshot.agentId) {
                throw invalid("Conversation ${conversation.id} snapshot is bound to another agent")
            }
            if (snapshot != null && policy == TransferConflictPolicy.REJECT && snapshot.toSnapshot(json) != transfer.snapshot) {
                throw conflict("Snapshot ${conversation.snapshotId} conflicts with local data")
            }
            val bundleAgentId = bundle.agent?.profile?.id
            if (bundleAgentId != null && transfer.snapshot.agentId != bundleAgentId) {
                throw invalid("Conversation ${conversation.id} is bound to another agent")
            }
            if (bundleAgentId == null &&
                db.query("SELECT id FROM agent_profiles WHERE id=?", listOf(transfer.snapshot.agentId)).isEmpty()
            ) {
                throw invalid("Conversation ${conversation.id} references missing agent ${transfer.snapshot.agentId}")
            }
        }
        bundle.agent?.let { transfer ->
            val localKbIds = db.query("SELECT id FROM knowledge_bases WHERE deleted_at IS NULL").map { it.string("id") }.toSet()
            val includedKbIds = bundle.knowledgeBases.map { it.id }.toSet()
            transfer.profile.knowledgeBaseIds.filter { it !in localKbIds && it !in includedKbIds }.forEach {
                throw invalid("Agent references missing knowledge base $it")
            }
            val localSkillIds = db.query("SELECT DISTINCT p.id FROM skill_packages p JOIN skill_installs i ON i.package_hash=p.package_hash AND i.enabled=1").map { it.string("id") }.toSet()
            val includedSkillIds = bundle.skills.map { it.id }.toSet()
            transfer.profile.skillIds.filter { it !in localSkillIds && it !in includedSkillIds }.forEach {
                throw invalid("Agent references missing skill $it")
            }
            val localModelIds = db.query("SELECT id FROM model_profiles").map { it.string("id") }.toSet()
            val includedModelIds = transfer.models.map { it.profile.id }.toSet()
            listOf(
                transfer.profile.chatProfileId,
                transfer.profile.visionProfileId,
                transfer.profile.embeddingProfileId,
                transfer.profile.rerankerProfileId,
            ).filterNotNull().filter { it !in localModelIds && it !in includedModelIds }.forEach {
                throw invalid("Agent references missing model $it")
            }
            if (transfer.promptRevisions.none { it.id == transfer.profile.promptRevisionId } &&
                db.query("SELECT id FROM prompt_revisions WHERE id=? AND agent_id=?", listOf(transfer.profile.promptRevisionId, transfer.profile.id)).isEmpty()
            ) throw invalid("Agent references missing prompt ${transfer.profile.promptRevisionId}")
        }
    }

    private fun importAgent(transfer: AgentTransfer, policy: TransferConflictPolicy, warnings: MutableList<String>): String {
        val profile = transfer.profile
        val existing = db.query("SELECT id FROM agent_profiles WHERE id=?", listOf(profile.id)).isNotEmpty()
        if (existing && policy == TransferConflictPolicy.KEEP_EXISTING) {
            warnings += "Agent ${profile.id} already exists; kept local configuration"
            return profile.id
        }
        transfer.providers.forEach { importProvider(it, policy, warnings) }
        transfer.models.forEach { importModel(it.profile, policy, warnings) }
        transfer.promptRevisions.forEach { importPrompt(it, policy, warnings) }
        profile.knowledgeBaseIds.forEach { id ->
            if (db.query("SELECT id FROM knowledge_bases WHERE id=? AND deleted_at IS NULL", listOf(id)).isEmpty()) {
                throw invalid("Agent references missing knowledge base $id")
            }
        }
        profile.skillIds.forEach { id ->
            if (db.query("SELECT id FROM skill_packages WHERE id=?", listOf(id)).isEmpty()) throw invalid("Agent references missing skill $id")
        }
        if (existing) {
                db.execute(
                "UPDATE agent_profiles SET name=?,prompt_revision_id=?,chat_profile_id=?,vision_profile_id=?,embedding_profile_id=?,reranker_profile_id=?,knowledge_base_ids=?,skill_ids=?,retrieval_mode=?,revision=?,parameter_overrides_json=?,context_policy_json=?,permission_settings_json=? WHERE id=?",
                profileArgs(profile).drop(1) + profile.id,
            )
        } else {
            db.execute(
                "INSERT INTO agent_profiles(id,name,prompt_revision_id,chat_profile_id,vision_profile_id,embedding_profile_id,reranker_profile_id,knowledge_base_ids,skill_ids,retrieval_mode,revision,parameter_overrides_json,context_policy_json,permission_settings_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                profileArgs(profile),
            )
        }
        transfer.snapshots.forEach { snapshot -> importSnapshot(snapshot, policy, warnings) }
        if (profile.skillIds.isNotEmpty()) warnings += "Imported skill bindings remain subject to local enable/grant approval"
        return profile.id
    }

    private fun importProvider(provider: ProviderTransfer, policy: TransferConflictPolicy, warnings: MutableList<String>) {
        val existing = db.query("SELECT * FROM provider_profiles WHERE id=?", listOf(provider.id)).singleOrNull()
        if (existing != null) {
            if (policy == TransferConflictPolicy.KEEP_EXISTING) return
            if (!sameProvider(existing, provider)) throw conflict("Provider ${provider.id} conflicts with local data")
            return
        }
        db.execute(
            "INSERT INTO provider_profiles(id,name,api_format,base_url,header_secret_refs,non_secret_headers,secret_ref,revision) VALUES(?,?,?,?,?,?,?,?)",
            listOf(provider.id, provider.name, ApiFormat.valueOf(provider.apiFormat).name, provider.baseUrl, "{}", json.encodeToString(provider.nonSecretHeaders), "", provider.revision),
        )
        warnings += "Provider ${provider.id} imported without secret; configure a local key before use"
    }

    private fun importModel(model: ModelProfile, policy: TransferConflictPolicy, warnings: MutableList<String>) {
        val existing = db.query("SELECT * FROM model_profiles WHERE id=?", listOf(model.id)).singleOrNull()
        if (existing != null) {
            if (policy == TransferConflictPolicy.KEEP_EXISTING) return
            if (!sameModel(existing, model)) throw conflict("Model ${model.id} conflicts with local data")
            return
        }
        if (db.query("SELECT id FROM provider_profiles WHERE id=?", listOf(model.providerId)).isEmpty()) throw invalid("Model references missing provider ${model.providerId}")
        db.execute(
            "INSERT INTO model_profiles(id,provider_id,role,model_id,capabilities,parameter_schema_json,parameters_json,context_limit,output_limit,revision) VALUES(?,?,?,?,?,?,?,?,?,?)",
            listOf(model.id, model.providerId, model.role.name, model.modelId, json.encodeToString(model.capabilities.toList().sorted()), model.parameterSchemaJson, model.parametersJson, model.contextLimit, model.outputLimit, model.revision),
        )
    }

    private fun importPrompt(prompt: PromptRevision, policy: TransferConflictPolicy, warnings: MutableList<String>) {
        val existing = db.query("SELECT * FROM prompt_revisions WHERE id=?", listOf(prompt.id)).singleOrNull()
        if (existing != null) {
            if (existing.toPrompt(json) != prompt && policy == TransferConflictPolicy.REJECT) throw conflict("Prompt ${prompt.id} conflicts with local data")
            return
        }
        db.execute(
            "INSERT INTO prompt_revisions(id,agent_id,parent_revision_id,template,allowed_variables,created_at) VALUES(?,?,?,?,?,?)",
            listOf(prompt.id, prompt.agentId, prompt.parentRevisionId, prompt.template, json.encodeToString(prompt.allowedVariables.toList().sorted()), prompt.createdAt),
        )
    }

    private fun importSnapshot(snapshot: AgentSnapshot, policy: TransferConflictPolicy, warnings: MutableList<String>) {
        val existing = db.query("SELECT * FROM agent_snapshots WHERE id=?", listOf(snapshot.id)).singleOrNull()
        if (existing != null) {
            if (existing.toSnapshot(json) != snapshot && policy == TransferConflictPolicy.REJECT) throw conflict("Snapshot ${snapshot.id} conflicts with local data")
            return
        }
        val expanded = snapshot.bindingManifestJson.ifBlank { "{}" }
        db.execute(
            "INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at,provider_id,chat_model_revision,vision_model_id,vision_model_revision,embedding_model_id,embedding_model_revision,reranker_model_id,reranker_model_revision,parameter_overrides_json,context_policy_json,permission_settings_json,binding_manifest_json,expanded_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                snapshot.id, snapshot.schemaVersion, snapshot.agentId, snapshot.promptRevisionId, snapshot.chatModelId, snapshot.providerRevision,
                json.encodeToString(snapshot.knowledgeBaseIds), json.encodeToString(snapshot.skillIds), snapshot.createdAt, snapshot.providerId,
                snapshot.chatModelRevision, snapshot.visionModelId, snapshot.visionModelRevision, snapshot.embeddingModelId, snapshot.embeddingModelRevision,
                snapshot.rerankerModelId, snapshot.rerankerModelRevision, snapshot.parameterOverridesJson, snapshot.contextPolicyJson,
                snapshot.permissionSettingsJson, snapshot.bindingManifestJson, expanded,
            ),
        )
    }

    private fun importKnowledge(
        kb: KnowledgeTransfer,
        policy: TransferConflictPolicy,
        warnings: MutableList<String>,
        stagedBlobs: Map<String, StoredBlob> = emptyMap(),
    ) {
        if (kb.contentIncluded) {
            kb.blobs.forEach { blob ->
                val staged = stagedBlobs[blob.hash]
                    ?: throw invalid("Full knowledge import is missing blob ${blob.hash}")
                if (staged.sha256 != blob.hash || staged.byteLength.toLong() != blob.byteLength) {
                    throw invalid("Full knowledge import has invalid blob ${blob.hash}")
                }
            }
        }
        val existing = db.query("SELECT * FROM knowledge_bases WHERE id=?", listOf(kb.id)).singleOrNull()
        if (existing == null) {
            db.execute(
                "INSERT INTO knowledge_bases(id,name,active_generation_id,embedding_space_id,created_at,deleted_at) VALUES(?,?,?,?,?,NULL)",
                listOf(kb.id, kb.name, null, kb.embeddingSpaceId, clock()),
            )
        } else if (policy == TransferConflictPolicy.REJECT &&
            (existing.string("name") != kb.name || existing.string("embedding_space_id") != kb.embeddingSpaceId.orEmpty())
        ) throw conflict("Knowledge base ${kb.id} conflicts with local data")
        kb.blobs.forEach { blob ->
            val row = db.query("SELECT * FROM blobs WHERE hash=?", listOf(blob.hash)).singleOrNull()
            if (row == null) {
                val localRef = stagedBlobs[blob.hash]?.localRef ?: blob.relativePath
                db.execute("INSERT INTO blobs(hash,byte_length,media_type,local_ref,ref_count) VALUES(?,?,?,?,0)", listOf(blob.hash, blob.byteLength, blob.mediaType, localRef))
            } else if (row.long("byte_length") != blob.byteLength || row.string("media_type") != blob.mediaType) {
                throw conflict("Blob ${blob.hash} conflicts with local data")
            }
        }
        kb.documents.forEach { document ->
            val row = db.query("SELECT * FROM documents WHERE id=?", listOf(document.id)).singleOrNull()
            if (row == null) {
                db.execute(
                    "INSERT INTO documents(id,kb_id,blob_hash,display_name,format,active_version_id,deleted_at) VALUES(?,?,?,?,?,?,NULL)",
                    listOf(document.id, document.knowledgeBaseId, document.blobHash, document.displayName, document.format,
                        if (kb.contentIncluded) document.activeVersionId else null),
                )
            } else if (row.string("blob_hash") != document.blobHash || row.string("kb_id") != document.knowledgeBaseId) {
                throw conflict("Document ${document.id} conflicts with local data")
            }
        }
        if (kb.contentIncluded) {
            kb.documentVersions.forEach { version ->
                val existingVersion = db.query("SELECT * FROM document_versions WHERE id=?", listOf(version.id)).singleOrNull()
                if (existingVersion == null) {
                    db.execute(
                        "INSERT INTO document_versions(id,document_id,parser_fingerprint,content_hash,status,created_at) VALUES(?,?,?,?,?,?)",
                        listOf(version.id, version.documentId, version.parserFingerprint, version.contentHash, version.status, version.createdAt),
                    )
                } else if (
                    existingVersion.string("document_id") != version.documentId ||
                    existingVersion.string("content_hash") != version.contentHash
                ) {
                    throw conflict("Document version ${version.id} conflicts with local data")
                }
            }
            kb.chunks.forEach { chunk ->
                val existingChunk = db.query("SELECT * FROM chunks WHERE id=?", listOf(chunk.id)).singleOrNull()
                if (existingChunk == null) {
                    db.execute(
                        "INSERT INTO chunks(id,document_version_id,ordinal,text,content_hash,source_span,asset_ids,page) VALUES(?,?,?,?,?,?,?,?)",
                        listOf(chunk.id, chunk.documentVersionId, chunk.ordinal, chunk.text, chunk.contentHash,
                            chunk.sourceSpan, chunk.assetIds.joinToString(","), chunk.page),
                    )
                } else if (
                    existingChunk.string("document_version_id") != chunk.documentVersionId ||
                    existingChunk.string("content_hash") != chunk.contentHash
                ) {
                    throw conflict("Chunk ${chunk.id} conflicts with local data")
                }
                val rowid = db.query("SELECT rowid AS rid FROM chunks WHERE id=?", listOf(chunk.id)).single().long("rid")
                runCatching {
                    db.execute("INSERT OR REPLACE INTO chunks_fts(rowid,text) VALUES(?,?)", listOf(rowid, chunk.text))
                }
            }
            kb.assets.forEach { asset ->
                val existingAsset = db.query("SELECT * FROM assets WHERE id=?", listOf(asset.id)).singleOrNull()
                if (existingAsset == null) {
                    db.execute(
                        "INSERT INTO assets(id,document_id,document_version_id,blob_hash,page,section,kind,surrounding_text_hash) VALUES(?,?,?,?,?,?,?,?)",
                        listOf(asset.id, asset.documentId, asset.documentVersionId, asset.blobHash, asset.page, asset.section, asset.kind, asset.surroundingTextHash),
                    )
                } else if (
                    existingAsset.string("document_id") != asset.documentId ||
                    existingAsset.string("blob_hash") != asset.blobHash
                ) {
                    throw conflict("Asset ${asset.id} conflicts with local data")
                }
            }
            kb.documents.forEach { document ->
                document.activeVersionId?.let { versionId ->
                    db.execute("UPDATE documents SET active_version_id=?,deleted_at=NULL WHERE id=?", listOf(versionId, document.id))
                }
            }
            if (kb.documentVersions.any { it.status == "READY" }) {
                // Derived embeddings/index generations are intentionally not imported: they are
                // host-specific and must be rebuilt locally without inheriting remote consent.
                db.execute("UPDATE knowledge_bases SET active_generation_id=NULL WHERE id=?", listOf(kb.id))
                warnings += "Knowledge base ${kb.id} content imported; embeddings and index generation require local rebuild"
            }
        }
        kb.blobs.forEach { blob ->
            val count = db.query(
                "SELECT COUNT(*) AS n FROM documents WHERE kb_id=? AND blob_hash=? AND deleted_at IS NULL",
                listOf(kb.id, blob.hash),
            ).single().long("n")
            db.execute("UPDATE blobs SET ref_count=? WHERE hash=?", listOf(count, blob.hash))
        }
    }

    private fun importSkill(
        skill: SkillTransfer,
        policy: TransferConflictPolicy,
        warnings: MutableList<String>,
        archiveBytes: ByteArray? = null,
    ) {
        val bytes = archiveBytes ?: skill.packageBase64?.let { Base64.getDecoder().decode(it) }
        if (skill.packageIncluded && archiveBytes == null) throw invalid("Skill package ${skill.packageHash} content is missing")
        bytes?.let { verifyHash(skill.packageHash, it, "Skill package") }
        val existing = db.query("SELECT * FROM skill_packages WHERE package_hash=?", listOf(skill.packageHash)).singleOrNull()
        if (existing == null) {
            db.execute(
                "INSERT INTO skill_packages(package_hash,id,name,version,license_id,classification,manifest_json,skill_markdown,reasons,created_at,package_bytes,source_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                listOf(skill.packageHash, skill.id, skill.name, skill.version, skill.licenseId, skill.classification, skill.manifestJson, skill.skillMarkdown, "imported", clock(), bytes, skill.sourceHash),
            )
            val installId = "transfer-${skill.packageHash.take(16)}"
            db.execute("INSERT OR IGNORE INTO skill_installs(install_id,package_hash,enabled,created_at) VALUES(?,?,0,?)", listOf(installId, skill.packageHash, clock()))
            warnings += "Skill ${skill.id} imported disabled until the user enables and grants it"
        } else if (policy == TransferConflictPolicy.REJECT &&
            (existing.string("id") != skill.id || existing.string("version") != skill.version)
        ) throw conflict("Skill package ${skill.packageHash} conflicts with local data")
    }

    private fun exportKnowledge(id: String, includeContent: Boolean = false): KnowledgeTransfer? {
        val kb = db.query("SELECT * FROM knowledge_bases WHERE id=? AND deleted_at IS NULL", listOf(id)).singleOrNull() ?: return null
        val docs = db.query("SELECT * FROM documents WHERE kb_id=? AND deleted_at IS NULL ORDER BY id", listOf(id)).map { row ->
            val versionId = row.string("active_version_id").ifBlank { null }
            val contentHash = versionId?.let { version ->
                db.query("SELECT content_hash FROM document_versions WHERE id=?", listOf(version)).singleOrNull()?.string("content_hash")?.ifBlank { null }
            }
            val path = db.query("SELECT local_ref FROM blobs WHERE hash=?", listOf(row.string("blob_hash"))).singleOrNull()?.string("local_ref").orEmpty()
            DocumentTransfer(row.string("id"), id, row.string("blob_hash"), row.string("display_name"), row.string("format"), versionId, contentHash, safeRelativePath(path))
        }
        val assetHashes = db.query("SELECT DISTINCT blob_hash FROM assets WHERE document_id IN (SELECT id FROM documents WHERE kb_id=? AND deleted_at IS NULL)", listOf(id))
            .map { it.string("blob_hash") }
        val hashes = (docs.map { it.blobHash } + assetHashes).distinct()
        val blobs = hashes.mapNotNull { hash ->
            db.query("SELECT hash,byte_length,media_type,local_ref FROM blobs WHERE hash=?", listOf(hash)).singleOrNull()?.let { row ->
                BlobTransfer(row.string("hash"), row.long("byte_length"), row.string("media_type"), safeRelativePath(row.string("local_ref")))
            }
        }
        if (!includeContent) {
            return KnowledgeTransfer(kb.string("id"), kb.string("name"), kb.string("embedding_space_id").ifBlank { null }, blobs, docs)
        }
        val versions = db.query(
            "SELECT v.* FROM document_versions v JOIN documents d ON d.id=v.document_id WHERE d.kb_id=? AND d.deleted_at IS NULL ORDER BY v.created_at,v.id",
            listOf(id),
        ).map { row ->
            DocumentVersionTransfer(
                id = row.string("id"), documentId = row.string("document_id"),
                parserFingerprint = row.string("parser_fingerprint"), contentHash = row.string("content_hash"),
                status = row.string("status"), createdAt = row.string("created_at"),
            )
        }
        val versionIds = versions.map { it.id }
        val chunks = if (versionIds.isEmpty()) emptyList() else db.query(
            "SELECT c.* FROM chunks c WHERE c.document_version_id IN (${versionIds.joinToString(",") { "?" }}) ORDER BY c.document_version_id,c.ordinal,c.rowid",
            versionIds,
        ).map { row ->
            ChunkTransfer(
                id = row.string("id"), documentVersionId = row.string("document_version_id"), ordinal = row.long("ordinal").toInt(),
                text = row.string("text"), contentHash = row.string("content_hash"), sourceSpan = row.string("source_span").ifBlank { null },
                assetIds = row.string("asset_ids").split(',').filter { it.isNotBlank() }, page = row.columns["page"]?.toString()?.toIntOrNull(),
            )
        }
        val assets = db.query(
            "SELECT a.* FROM assets a JOIN documents d ON d.id=a.document_id WHERE d.kb_id=? AND d.deleted_at IS NULL ORDER BY a.id",
            listOf(id),
        ).map { row ->
            AssetTransfer(
                id = row.string("id"), documentId = row.string("document_id"), documentVersionId = row.string("document_version_id").ifBlank { null },
                blobHash = row.string("blob_hash"), page = row.columns["page"]?.toString()?.toIntOrNull(), section = row.string("section").ifBlank { null },
                kind = row.string("kind"), surroundingTextHash = row.string("surrounding_text_hash"),
            )
        }
        return KnowledgeTransfer(
            id = kb.string("id"), name = kb.string("name"), embeddingSpaceId = kb.string("embedding_space_id").ifBlank { null },
            blobs = blobs, documents = docs, contentIncluded = true, documentVersions = versions, chunks = chunks, assets = assets,
        )
    }

    private fun importConversation(
        transfer: ConversationTransfer,
        policy: TransferConflictPolicy,
        warnings: MutableList<String>,
    ) {
        val conversation = transfer.conversation
        val existing = db.query("SELECT * FROM conversations WHERE id=?", listOf(conversation.id)).singleOrNull()
        if (existing != null) {
            if (policy == TransferConflictPolicy.REJECT) throw conflict("Conversation ${conversation.id} already exists")
            warnings += "Conversation ${conversation.id} already exists; kept local history"
            return
        }
        importSnapshot(transfer.snapshot, policy, warnings)
        db.execute(
            "INSERT INTO conversations(id,snapshot_id,agent_snapshot_id,title,created_at,updated_at) VALUES(?,?,?,?,?,?)",
            listOf(conversation.id, conversation.snapshotId, conversation.snapshotId, conversation.title, conversation.createdAt, conversation.updatedAt),
        )
        transfer.messages.forEach { message ->
            db.execute(
                "INSERT INTO messages(id,conversation_id,parent_message_id,role,text,status,created_at,parts_json,metadata_json) VALUES(?,?,?,?,?,?,?,?,?)",
                listOf(message.id, message.conversationId, message.parentMessageId, message.role.name, message.text, message.status,
                    message.createdAt, json.encodeToString(message.parts), message.metadataJson),
            )
            message.parts.forEachIndexed { index, part ->
                db.execute(
                    "INSERT INTO message_parts(id,message_id,ordinal,part_type,payload_json) VALUES(?,?,?,?,?)",
                    listOf("${message.id}:$index", message.id, index, transferPartType(part), json.encodeToString(part)),
                )
            }
        }
        transfer.runs.forEach { run ->
            db.execute(
                "INSERT INTO runs(run_id,snapshot_id,conversation_id,state,budget_json,stop_reason,error_code,model_rounds,tool_calls,input_tokens,output_tokens,started_at,finished_at,created_at,updated_at,retry_acknowledged_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                listOf(run.runId, run.snapshotId, run.conversationId, run.state.name, run.budgetJson, run.stopReason, run.errorCode,
                    run.modelRounds, run.toolCalls, run.inputTokens, run.outputTokens, run.startedAt, run.finishedAt,
                    run.createdAt, run.updatedAt, run.retryAcknowledgedAt),
            )
        }
        transfer.toolInvocations.forEach { invocation ->
            db.execute(
                "INSERT INTO tool_invocations(invocation_id,run_id,call_id,name,arguments_json,permission_decision,state,result_json,error_code,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                listOf(invocation.invocationId, invocation.runId, invocation.callId, invocation.name, invocation.argumentsJson,
                    invocation.permissionDecision, invocation.state, invocation.resultJson, invocation.errorCode,
                    invocation.createdAt, invocation.updatedAt),
            )
        }
        transfer.auditEvents.forEach { event ->
            db.execute(
                "INSERT INTO audit_events(id,run_id,created_at,component,action,result,error_code,summary,input_bytes,output_bytes,input_tokens,output_tokens,metadata_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                listOf(event.id, event.runId, event.createdAt, event.component, event.action, event.result, event.errorCode,
                    event.summary, event.inputBytes, event.outputBytes, event.inputTokens, event.outputTokens, event.metadataJson),
            )
        }
    }

    private fun transferPartType(part: MessagePart): String = when (part) {
        is runtime.mobileagent.domain.TextPart -> "text"
        is runtime.mobileagent.domain.ImagePart -> "image"
        is runtime.mobileagent.domain.ToolCallPart -> "tool_call"
        is runtime.mobileagent.domain.ToolResultPart -> "tool_result"
        is runtime.mobileagent.domain.CitationPart -> "citation"
        is runtime.mobileagent.domain.ReasoningPart -> "reasoning"
        is runtime.mobileagent.domain.DiffPart -> "diff"
        is runtime.mobileagent.domain.ErrorPart -> "error"
    }

    private fun exportSkill(id: String, includeBytes: Boolean, packageIncluded: Boolean = false): SkillTransfer? {
        val row = db.query("SELECT * FROM skill_packages WHERE id=? ORDER BY created_at DESC LIMIT 1", listOf(id)).singleOrNull() ?: return null
        val bytes = if (includeBytes) row.columns["package_bytes"] as? ByteArray else null
        return SkillTransfer(
            packageHash = row.string("package_hash"), id = row.string("id"), name = row.string("name"), version = row.string("version"),
            licenseId = row.string("license_id"), classification = row.string("classification"), manifestJson = row.string("manifest_json").ifBlank { null },
            skillMarkdown = row.string("skill_markdown").ifBlank { null }, sourceHash = row.string("source_hash").ifBlank { null },
            packageBase64 = bytes?.let(Base64.getEncoder()::encodeToString),
            packageIncluded = packageIncluded,
        )
    }

    private fun sameProvider(row: SqlRow, provider: ProviderTransfer): Boolean =
        row.string("name") == provider.name && row.string("api_format") == provider.apiFormat && row.string("base_url") == provider.baseUrl &&
            decodeMap(row.string("non_secret_headers")) == provider.nonSecretHeaders && row.long("revision").toInt() == provider.revision

    private fun sameModel(row: SqlRow, model: ModelProfile): Boolean =
        row.string("provider_id") == model.providerId && row.string("role") == model.role.name && row.string("model_id") == model.modelId &&
            decodeStrings(row.string("capabilities")) == model.capabilities && row.string("parameter_schema_json") == model.parameterSchemaJson &&
            row.string("parameters_json").ifBlank { "{}" } == model.parametersJson && row.long("context_limit").toInt() == model.contextLimit &&
            row.long("output_limit").toInt() == model.outputLimit && row.long("revision").toInt() == model.revision

    private fun decodeStrings(raw: String): Set<String> = runCatching { json.decodeFromString<List<String>>(raw.ifBlank { "[]" }).toSet() }.getOrDefault(emptySet())

    private fun decodeMap(raw: String): Map<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(raw.ifBlank { "{}" }) }.getOrDefault(emptyMap())

    private fun safeRelativePath(raw: String): String {
        val normalized = raw.replace('\\', '/')
        if (normalized.isBlank() || normalized.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(normalized)) return ""
        if (normalized.split('/').any { it == ".." || it == "." || it.isBlank() }) return ""
        return normalized
    }

    private fun profileArgs(profile: AgentProfile): List<Any?> = listOf(
        profile.id, profile.name, profile.promptRevisionId, profile.chatProfileId, profile.visionProfileId, profile.embeddingProfileId,
        profile.rerankerProfileId, json.encodeToString(profile.knowledgeBaseIds), json.encodeToString(profile.skillIds), profile.retrievalMode,
        profile.revision, profile.parameterOverridesJson, profile.contextPolicyJson, profile.permissionSettingsJson,
    )

    private fun SqlRow.toPrompt(json: Json): PromptRevision = PromptRevision(
        id = string("id"), agentId = string("agent_id"), parentRevisionId = string("parent_revision_id").ifBlank { null },
        template = string("template"), allowedVariables = runCatching { json.decodeFromString<List<String>>(string("allowed_variables").ifBlank { "[]" }).toSet() }.getOrDefault(emptySet()),
        createdAt = string("created_at"),
    )

    private fun SqlRow.toSnapshot(json: Json): AgentSnapshot = AgentSnapshot(
        id = string("id"), schemaVersion = long("schema_version").toInt(), agentId = string("agent_id"), promptRevisionId = string("prompt_revision_id"),
        chatModelId = string("chat_model_id"), providerRevision = long("provider_revision").toInt(), knowledgeBaseIds = decodeStringsList(string("knowledge_base_ids")),
        skillIds = decodeStringsList(string("skill_ids")), createdAt = string("created_at"), providerId = string("provider_id"), chatModelRevision = long("chat_model_revision").toInt(),
        visionModelId = string("vision_model_id").ifBlank { null }, visionModelRevision = columns["vision_model_revision"]?.toString()?.toIntOrNull(),
        embeddingModelId = string("embedding_model_id").ifBlank { null }, embeddingModelRevision = columns["embedding_model_revision"]?.toString()?.toIntOrNull(),
        rerankerModelId = string("reranker_model_id").ifBlank { null }, rerankerModelRevision = columns["reranker_model_revision"]?.toString()?.toIntOrNull(),
        parameterOverridesJson = string("parameter_overrides_json").ifBlank { "{}" }, contextPolicyJson = string("context_policy_json").ifBlank { "{}" },
        permissionSettingsJson = string("permission_settings_json").ifBlank { "{}" }, bindingManifestJson = string("binding_manifest_json").ifBlank { "{}" },
    )

    private fun decodeStringsList(raw: String): List<String> = runCatching { json.decodeFromString<List<String>>(raw.ifBlank { "[]" }) }.getOrDefault(emptyList())

    companion object {
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val BLOB_PREFIX = "blobs/"
        private const val SKILL_PREFIX = "skills/"
        private const val CONVERSATION_PREFIX = "conversations/"
        private const val LOCAL_CREDENTIALS_REQUIRED = "LOCAL_CREDENTIALS_REQUIRED"
        private const val PUBLIC_HEADERS_FIELD = "nonSecretHeaders"
        private val SECRET_TRANSFER_KEY = Regex("(?i).*(secret|api[_-]?key|authorization|cookie|password|private[_-]?key).*")
    }

    private fun conflict(message: String): AppException = AppError(ErrorCode.TRANSFER_INVALID, message, RetryClass.USER_ACTION, "transfer", "transfer-import", message).asException()

    private fun invalid(message: String): AppException = AppError(ErrorCode.TRANSFER_INVALID, message, RetryClass.USER_ACTION, "transfer", "transfer-import", message).asException()
}
