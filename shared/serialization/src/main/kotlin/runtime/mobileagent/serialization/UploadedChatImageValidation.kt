// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization

import runtime.mobileagent.domain.ChatImageUpload
import runtime.mobileagent.domain.ImagePart

/** Validates the portable image manifest and the message references that consume it. */
internal fun validateUploadedChatImages(
    transfer: ConversationTransfer,
    requireHash: (String) -> Unit,
    reject: (String) -> Nothing,
) {
    val imageHashes = mutableSetOf<String>()
    transfer.uploadedImages.forEach { blob ->
        requireHash(blob.hash)
        if (!imageHashes.add(blob.hash) || blob.byteLength !in 1..ChatImageUpload.MAX_IMAGE_BYTES.toLong() ||
            blob.mediaType !in setOf("image/png", "image/jpeg", "image/gif", "image/webp") ||
            blob.relativePath.isNotEmpty() || !transfer.contentIncluded) reject("Invalid uploaded chat image metadata")
    }
    transfer.messages.flatMap { it.parts }.filterIsInstance<ImagePart>()
        .filter { it.assetId.startsWith(ChatImageUpload.ASSET_PREFIX) && it.blobHash != null }
        .forEach { part ->
            if (transfer.uploadedImages.none { it.hash == part.blobHash && it.mediaType == part.mediaType })
                reject("Chat image references an unlisted blob")
        }
}
