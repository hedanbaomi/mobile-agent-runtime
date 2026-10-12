// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ChatImageUpload
import runtime.mobileagent.domain.ImagePart
import runtime.mobileagent.domain.MessagePart
import runtime.mobileagent.knowledge.BlobSink
import runtime.mobileagent.knowledge.StoredBlob
import runtime.mobileagent.serialization.BlobTransfer
import runtime.mobileagent.serialization.ConversationTransfer
import runtime.mobileagent.serialization.TransferBundle

/** Portable conversation image metadata and restoration inside the import transaction. */
internal class ConversationImageTransfer(
    private val db: SqlConnection,
    private val json: Json,
    private val invalid: (String) -> AppException,
) {
    fun archiveBlobs(bundle: TransferBundle): List<BlobTransfer> =
        bundle.knowledgeBases.filter { it.contentIncluded }.flatMap { it.blobs } +
            bundle.conversations.filter { it.contentIncluded }.flatMap { it.uploadedImages }

    fun expectedBlob(bundle: TransferBundle, hash: String): BlobTransfer {
        val matches = archiveBlobs(bundle).filter { it.hash == hash }
        if (matches.map { it.byteLength to it.mediaType }.distinct().size > 1) throw invalid("Conflicting blob metadata")
        return matches.firstOrNull() ?: throw invalid("Archive contains an unlisted blob $hash")
    }

    fun requireUnchanged(conversationId: String, expected: List<BlobTransfer>) {
        if (metadata(conversationId) != expected) throw invalid("Chat images changed during export; retry the backup")
    }

    fun metadata(conversationId: String): List<BlobTransfer> = db.query(
        "SELECT p.payload_json FROM message_parts p JOIN messages m ON m.id=p.message_id " +
            "WHERE m.conversation_id=? AND p.part_type='image' ORDER BY m.rowid,p.ordinal",
        listOf(conversationId),
    ).map { json.decodeFromString<MessagePart>(it.string("payload_json")) }
        .filterIsInstance<ImagePart>()
        .filter { it.assetId.startsWith(ChatImageUpload.ASSET_PREFIX) && it.blobHash != null }
        .distinctBy { it.blobHash }.map { part ->
            val row = db.query("SELECT byte_length FROM blobs WHERE hash=?", listOf(part.blobHash)).singleOrNull()
                ?: throw invalid("Uploaded chat image metadata is missing")
            BlobTransfer(checkNotNull(part.blobHash), row.long("byte_length"), part.mediaType)
        }.sortedBy { it.hash }

    fun restore(
        full: ConversationTransfer,
        manifest: ConversationTransfer,
        stagedBlobs: Map<String, StoredBlob>,
        blobSink: BlobSink?,
    ) {
        if (full.conversation != manifest.conversation || full.snapshot != manifest.snapshot ||
            full.uploadedImages != manifest.uploadedImages) throw invalid("Conversation content does not match its manifest")
        full.uploadedImages.forEach { blob ->
            val stored = stagedBlobs[blob.hash] ?: throw invalid("Uploaded chat image content is missing")
            val bytes = checkNotNull(blobSink).get(blob.hash) ?: throw invalid("Uploaded chat image is missing")
            ChatImageUpload(bytes, blob.mediaType)
            db.execute("INSERT OR IGNORE INTO blobs(hash,byte_length,media_type,local_ref,ref_count) VALUES(?,?,?,?,0)",
                listOf(blob.hash, blob.byteLength, blob.mediaType, stored.localRef))
        }
    }
}
