// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import runtime.mobileagent.domain.ChatImageUpload
import runtime.mobileagent.domain.ImagePart
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.acceptsImages
import runtime.mobileagent.provider.ChatMessage

internal object ChatImagePolicy {
    /** Current uploads come last; older originals beyond the wire limit use existing batching. */
    fun directAssetIds(messages: List<ChatMessage>, maxImages: Int): Set<String> {
        require(maxImages in 1..runtime.mobileagent.domain.MAX_IMAGES_PER_REQUEST)
        return messages.flatMap { it.images }.asReversed().mapNotNull { it.assetId }
            .filter { it.startsWith(ChatImageUpload.ASSET_PREFIX) }.distinct().take(maxImages).toSet()
    }

    fun requireImageModel(parts: List<ImagePart>, model: ModelProfile, textOnly: Boolean) {
        require(parts.isEmpty() || (model.acceptsImages() && !textOnly)) {
            "CONFIG_INVALID: 当前聊天模型不支持图片，或已开启纯文本降级；上传图片未发送。"
        }
    }

    fun attachedCount(message: Message, visualReceipt: Boolean, toolEvidence: Boolean): Int =
        if (message.role != MessageRole.USER || visualReceipt || toolEvidence) 0
        else message.parts.count { it is ImagePart && it.assetId.startsWith(ChatImageUpload.ASSET_PREFIX) }
}
