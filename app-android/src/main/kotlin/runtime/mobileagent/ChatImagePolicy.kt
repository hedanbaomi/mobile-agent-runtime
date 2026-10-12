// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import runtime.mobileagent.domain.ChatImageUpload
import runtime.mobileagent.domain.ImagePart
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.acceptsImages

internal object ChatImagePolicy {
    fun requireImageModel(parts: List<ImagePart>, model: ModelProfile, textOnly: Boolean) {
        require(parts.isEmpty() || (model.acceptsImages() && !textOnly)) {
            "CONFIG_INVALID: 当前聊天模型不支持图片，或已开启纯文本降级；上传图片未发送。"
        }
    }

    fun attachedCount(message: Message, visualReceipt: Boolean, toolEvidence: Boolean): Int =
        if (message.role != MessageRole.USER || visualReceipt || toolEvidence) 0
        else message.parts.count { it is ImagePart && it.assetId.startsWith(ChatImageUpload.ASSET_PREFIX) }
}
