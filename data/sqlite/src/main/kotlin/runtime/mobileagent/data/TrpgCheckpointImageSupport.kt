// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ChatImageUpload
import runtime.mobileagent.domain.ImagePart
import runtime.mobileagent.domain.Message

/**
 * Image continuity is ownership-based: a checkpoint must not claim an upload it cannot verify.
 * Without an injected verifier any uploaded chat image fails closed instead of being dropped.
 */
internal class TrpgCheckpointImageSupport(
    private val db: SqlConnection,
    private val imageValidator: ((conversationId: String, image: ImagePart) -> Unit)?,
) {
    fun requireConfigured(messages: List<Message>) {
        if (parts(messages).none { it.assetId.startsWith(ChatImageUpload.ASSET_PREFIX) }) return
        if (imageValidator == null) {
            TrpgCheckpointGuard.fail("TRPG checkpoints with uploaded chat images require an image verifier")
        }
    }

    fun validate(conversationId: String, messages: List<Message>) {
        parts(messages).forEach { image ->
            if (image.assetId.startsWith(ChatImageUpload.ASSET_PREFIX)) {
                val validator = imageValidator
                    ?: TrpgCheckpointGuard.fail("TRPG checkpoints with uploaded chat images require an image verifier")
                try {
                    validator(conversationId, image)
                } catch (error: AppException) {
                    throw error
                } catch (error: Exception) {
                    TrpgCheckpointGuard.fail("TRPG checkpoint image could not be verified: ${error.message ?: "missing image"}")
                }
            } else {
                // Knowledge/asset images are shared references; verify the CAS row survived at least.
                image.blobHash?.let { hash ->
                    val row = db.query("SELECT byte_length FROM blobs WHERE hash=?", listOf(hash)).singleOrNull()
                        ?: TrpgCheckpointGuard.fail("TRPG checkpoint image blob is missing")
                    if (row.long("byte_length") <= 0) TrpgCheckpointGuard.fail("TRPG checkpoint image blob is empty")
                }
            }
        }
    }

    private fun parts(messages: List<Message>): List<ImagePart> =
        messages.flatMap { it.parts }.filterIsInstance<ImagePart>().filter { it.assetId.isNotBlank() }
}
