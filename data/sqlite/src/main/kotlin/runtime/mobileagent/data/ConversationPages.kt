// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.Conversation
import runtime.mobileagent.domain.Message

data class ConversationCursor(val updatedAt: String, val id: String)
data class ConversationPage(val conversations: List<Conversation>, val nextCursor: ConversationCursor?)
data class MessagePage(val messages: List<Message>, val nextBeforeMessageId: String?)
