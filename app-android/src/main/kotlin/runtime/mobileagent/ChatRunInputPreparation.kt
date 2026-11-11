// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import runtime.mobileagent.domain.*
import runtime.mobileagent.feature.chat.*
import runtime.mobileagent.knowledge.*

/** Applies the preflight outcome without admitting a new run on UNKNOWN or failure. */
internal suspend fun prepareChatRunInput(
    text: String,
    owner: ChatRunOwner,
    ui: ChatExecutionUi,
    prepare: suspend (onUserPersisted: (Message) -> Unit) -> ChatPreflightResult,
): ChatPreflightResult.Prepared? = with(ui) {
    val conversationId = owner.conversationId
        when (val result = prepare { message ->
            imageDraft.persisted(conversationId)
            publishRunState(owner, state.value.copy(
                messages = state.value.messages + messageUi(message), input = "",
            ))
            saveRunDraft(owner)
        }) {
            is ChatPreflightResult.Unknown -> {
                if (canProjectRun(owner)) unknownRetry.value = result.runId
                publishRunState(owner, state.value.copy(streaming = false, input = text))
                saveRunDraft(owner)
                null
            }
            ChatPreflightResult.MissingConversation -> {
                publishRunState(owner, state.value.copy(streaming = false, input = text))
                saveRunDraft(owner)
                null
            }
            is ChatPreflightResult.Failed -> {
                failRun(owner, result.failure)
                publishRunState(owner, state.value.copy(streaming = false,
                    input = if (result.userMessagePersisted) state.value.input else text))
                if (!result.userMessagePersisted) saveRunDraft(owner)
                null
            }
            is ChatPreflightResult.Prepared -> result
        }
}
