// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.*
import runtime.mobileagent.knowledge.BlobSink
import runtime.mobileagent.knowledge.sha256Hex
import runtime.mobileagent.provider.InlineImage
import java.util.Base64

/** Uploads share managed CAS/quota/GC, but authorization comes from their owning conversation. */
class ConversationImageRepository(
    private val db: SqlConnection,
    private val conversations: ConversationRepository,
    private val blobs: BlobSink,
    private val indexes: java.io.File? = null,
) {
    private val json = Json { ignoreUnknownKeys = false }
    private data class PreparedUpload(val mediaType: String, val bytes: ByteArray, val hash: String)

    fun appendUser(conversationId: String, text: String, uploads: List<ChatImageUpload>): Message {
        require(text.isNotBlank() || uploads.isNotEmpty()) { "CONFIG_INVALID: empty chat message" }
        require(uploads.size <= ChatImageUpload.MAX_IMAGES_PER_MESSAGE) { "INPUT_OVERFLOW: at most 8 chat images per message" }
        val prepared = uploads.map { upload ->
            val bytes = upload.bytes()
            PreparedUpload(upload.mediaType, bytes, sha256Hex(bytes))
        }
        return db.transaction {
            blobs.withStorageLock {
                val storedHashes = blobs.storedHashes()
                val growth = prepared.distinctBy { it.hash }.sumOf { upload ->
                    if (upload.hash in storedHashes) 0L else upload.bytes.size.toLong()
                }
                StorageMaintenance(db, blobs, indexes).requireCapacity(growth + 16_384)
                val parts = mutableListOf<MessagePart>()
                if (text.isNotBlank()) parts += TextPart(text)
                prepared.forEach { upload ->
                    val stored = blobs.put(upload.bytes, upload.mediaType)
                    db.execute("INSERT OR IGNORE INTO blobs(hash,byte_length,media_type,local_ref,ref_count) VALUES(?,?,?,?,0)",
                        listOf(stored.sha256, stored.byteLength, stored.mediaType, stored.localRef))
                    parts += ImagePart(ChatImageUpload.ASSET_PREFIX + EntityId.random().value, upload.mediaType, stored.sha256)
                }
                // The message and blob metadata publish in one transaction. A failed append leaves
                // only unreferenced CAS bytes, which ordinary storage collection can reclaim.
                conversations.append(conversationId, MessageRole.USER, text, parts = parts)
            }
        }
    }

    fun references(conversationId: String, parts: List<ImagePart>): List<InlineImage> = parts.map { part ->
        val durable = ownedPart(conversationId, part.assetId)
        require(part.mediaType == durable.mediaType && (part.blobHash == null || part.blobHash == durable.blobHash)) {
            "PERMISSION_DENIED: chat image reference changed"
        }
        val row = db.query("SELECT byte_length FROM blobs WHERE hash=?", listOf(durable.blobHash)).singleOrNull()
            ?: error("PERMISSION_DENIED: chat image is missing")
        val size = row.long("byte_length")
        require(size in 1..ChatImageUpload.MAX_IMAGE_BYTES.toLong()) { "INPUT_OVERFLOW: chat image exceeds limit" }
        InlineImage(durable.mediaType, "", durable.assetId, size, durable.blobHash)
    }

    /** A durable successful analysis replaces only the original with that same content hash. */
    fun referencesForHistory(
        conversationId: String,
        parts: List<ImagePart>,
        analyzedHashes: Map<String, String>,
        allowOriginals: Boolean,
    ): List<InlineImage> {
        // Ownership is checked for every part; only originals still needing dispatch require an image model.
        val pending = references(conversationId, parts).filter { analyzedHashes[it.assetId] != it.sha256 }
        require(pending.isEmpty() || allowOriginals) {
            "CONFIG_INVALID: 历史消息包含尚未分析的上传图片；当前模型或纯文本降级不允许图片，请开启新会话。"
        }
        return pending
    }

    fun load(conversationId: String, image: InlineImage): InlineImage =
        image.copy(base64 = Base64.getEncoder().encodeToString(verifiedBytes(conversationId, image)))

    private fun verifiedBytes(conversationId: String, image: InlineImage): ByteArray = db.transaction { blobs.withStorageLock {
        val part = ownedPart(conversationId, image.assetId.orEmpty())
        val hash = checkNotNull(part.blobHash)
        val reference = references(conversationId, listOf(part)).single()
        require(reference == image.copy(base64 = "")) { "PERMISSION_DENIED: chat image reference changed" }
        require(blobs.storedByteLength(hash) == reference.byteLength) {
            "PERMISSION_DENIED: chat image size changed"
        }
        val bytes = blobs.get(hash) ?: error("PERMISSION_DENIED: chat image is missing or changed")
        require(sha256Hex(bytes) == hash) { "PERMISSION_DENIED: chat image content changed" }
        ChatImageUpload(bytes, part.mediaType) // Revalidate format as well as CAS hash.
        bytes
    } }

    /** Recheck at the last dispatch boundary, including after preview/persistence suspensions. */
    fun verify(conversationId: String, image: InlineImage) { verifiedBytes(conversationId, image) }

    private fun ownedPart(conversationId: String, assetId: String): ImagePart {
        require(assetId.startsWith(ChatImageUpload.ASSET_PREFIX)) { "PERMISSION_DENIED: not a chat upload" }
        val matches = db.query(
            "SELECT p.payload_json FROM message_parts p JOIN messages m ON m.id=p.message_id " +
                "WHERE m.conversation_id=? AND m.role='USER' AND p.part_type='image' AND json_extract(p.payload_json,'$.assetId')=?",
            listOf(conversationId, assetId),
        ).map { json.decodeFromString<MessagePart>(it.string("payload_json")) }.filterIsInstance<ImagePart>()
            .filter { it.assetId == assetId && it.blobHash != null }
        require(matches.isNotEmpty() && matches.distinct().size == 1) { "PERMISSION_DENIED: chat image belongs to another conversation or is missing" }
        return matches.first()
    }
}
