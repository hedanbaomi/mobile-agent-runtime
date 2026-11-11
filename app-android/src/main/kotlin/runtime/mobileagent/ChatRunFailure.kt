// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import runtime.mobileagent.agent.toSafeErrorPart
import runtime.mobileagent.domain.*
import runtime.mobileagent.feature.chat.*
import runtime.mobileagent.knowledge.*

/** Maps pipeline failures to durable errors while retaining archive and UNKNOWN semantics. */
internal suspend fun settleChatRunFailure(
    application: Application,
    container: AppContainer,
    owner: ChatRunOwner,
    runAdmitted: Boolean,
    sentInputRevision: Long,
    text: String,
    failure: Exception,
    progress: ChatRunEventState,
    response: ChatRunResponse,
    ui: ChatExecutionUi,
): Boolean = with(ui) { with(response) {
    val conversationId = owner.conversationId
                val queryUnknown = failure is ApiQueryUnknownOutcomeException
                val archivedBeforeAdmission = !runAdmitted && withContext(Dispatchers.IO) {
                    runCatching { container.conversations.get(conversationId)?.archived == true }.getOrDefault(false)
                }
                val errorPart = when {
                    archivedBeforeAdmission ->
                        ErrorPart(MessageErrorCode.PERMISSION_DENIED, application.hostChatText(R.string.chat_message_saved_conversation_archived))
                    failure is AppException -> {
                        // Domain failures already carry a stable code; the closed table decides the
                        // message category instead of guessing from the text. A Root admission refusal
                        // is product copy, so it is localized here; other codes keep the domain message.
                        val mapped = toSafeErrorPart(failure.error.code.name)
                        val localized = ChatRootErrors.preflightMessageResource(failure.error)?.let {
                            application.rootChatMessage(it)
                        } ?: when {
                            ChatRootErrors.isRootAuthorityFailure(failure.error) ->
                                application.rootChatMessage(R.string.root_authority_temporarily_unavailable)
                            else -> null
                        }
                        mapped.copy(message = localized ?: failure.error.userMessage.ifBlank { mapped.message })
                    }
                    failure is ChatInputBudgetExceeded -> ErrorPart(
                        MessageErrorCode.CONTEXT_OVERFLOW,
                        failure.userMessage(application),
                    )
                    queryUnknown -> ErrorPart(
                        MessageErrorCode.UNKNOWN_OUTCOME,
                        application.hostChatText(R.string.chat_knowledge_query_unknown),
                    )
                    else -> toSafeErrorPart(failure.message.orEmpty())
                }
                progress.preparationStage?.let { stage ->
                    runCatching {
                        val extras = (failure as? ChatInputBudgetExceeded)?.diagnosticUnits().orEmpty()
                        (application as MobileAgentApp).diagnostics
                            .recordRunPreparationFailed(stage, errorPart.code, failure, extras)
                    }
                }
                progress.record = progress.record.copy(state = if (queryUnknown) RunStatus.UNKNOWN_OUTCOME else if (progress.record.state in TERMINAL) progress.record.state else RunStatus.FAILED,
                    errorCode = if (queryUnknown) "UNKNOWN_OUTCOME" else progress.record.errorCode ?: errorPart.code.name,
                    stopReason = errorPart.message)
                if (archivedBeforeAdmission) {
                    // The user message was saved before the archive won admission. Keep the draft
                    // for an explicit restore and leave the newly read-only history alone.
                    if (inputRevision == sentInputRevision) {
                        publishRunState(owner, state.value.copy(input = text))
                        saveRunDraft(owner)
                    }
                } else {
                    persistTerminalError(errorPart)
                }
                publishRunState(owner, state.value.copy(status = errorPart.message, statusKind = "error", error = null))
    archivedBeforeAdmission
} }
private val TERMINAL = setOf(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.BUDGET_EXHAUSTED, RunStatus.UNKNOWN_OUTCOME)
