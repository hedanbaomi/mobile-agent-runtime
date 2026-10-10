// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import runtime.mobileagent.data.ConversationRepository
import runtime.mobileagent.data.RunRepository
import runtime.mobileagent.data.TransferRepository
import runtime.mobileagent.domain.*

internal sealed interface ChatPreflightResult {
    data class Unknown(val runId: String) : ChatPreflightResult
    data object MissingConversation : ChatPreflightResult
    data class Failed(val failure: Exception, val userMessagePersisted: Boolean) : ChatPreflightResult
    data class Prepared(
        val userMessage: Message,
        val binding: SnapshotBinding,
        val contextPolicy: AgentContextPolicy,
        val workspaceId: String?,
        val workspaceBindingReadFailed: Boolean,
    ) : ChatPreflightResult
}

/**
 * Durable preparation for one conversation, independent of whichever page the UI selects.
 * The UNKNOWN retry gate precedes message mutation. The user's message is then persisted and
 * projected before model/policy/workspace resolution, so preparation failures retain that turn.
 */
internal class ChatRunPreflight(
    private val runs: RunRepository,
    private val conversations: ConversationRepository,
    private val transfer: TransferRepository,
    private val workspace: ThreadWorkspacePort?,
    private val workspaceRuntime: ThreadWorkspaceRuntimePort?,
) {
    suspend fun prepare(
        conversationId: String,
        text: String,
        onUserPersisted: (Message) -> Unit,
    ): ChatPreflightResult {
        val unknown = withContext(Dispatchers.IO) {
            runs.list(conversationId).lastOrNull {
                it.state == RunStatus.UNKNOWN_OUTCOME && it.retryAcknowledgedAt == null
            }
        }
        if (unknown != null) return ChatPreflightResult.Unknown(unknown.runId)
        val conversation = withContext(Dispatchers.IO) { conversations.get(conversationId) }
            ?: return ChatPreflightResult.MissingConversation
        val userMessage = try {
            withContext(Dispatchers.IO) {
                conversations.append(conversationId, MessageRole.USER, text, parts = listOf(TextPart(text)))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            return ChatPreflightResult.Failed(failure, userMessagePersisted = false)
        }
        onUserPersisted(userMessage)
        val binding: SnapshotBinding
        val policy: AgentContextPolicy
        try {
            binding = withContext(Dispatchers.IO) { transfer.resolveRunBinding(conversation.snapshotId) }
            policy = AgentContextPolicy.fromJson(binding.snapshot.contextPolicyJson)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            return ChatPreflightResult.Failed(failure, userMessagePersisted = true)
        }
        var bindingReadFailed = false
        val workspaceBinding = try {
            withContext(Dispatchers.IO) { workspace?.conversationWorkspaceBinding(conversationId) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            bindingReadFailed = true
            null
        }
        // Optional aggregate-only diagnostics cannot authorize a workspace or block delivery.
        try {
            withContext(Dispatchers.IO) {
                workspaceRuntime?.takeIf { it.available }
                    ?.recordConversationWorkspaceResolution(conversationId, binding.snapshot)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Authorization uses the frozen snapshot and canonical workspace binding above.
        }
        return ChatPreflightResult.Prepared(userMessage, binding, policy,
            workspaceBinding?.workspaceId, bindingReadFailed)
    }
}
