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
    private val images: runtime.mobileagent.data.ConversationImageRepository? = null,
) {
    suspend fun prepare(
        conversationId: String,
        text: String,
        uploads: List<ChatImageUpload> = emptyList(),
        textOnly: Boolean = false,
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
        // Persisting an image turn the frozen model cannot accept would poison every later history
        // projection, so this capability check precedes the message write. Text-only degradation is
        // known without the snapshot; a snapshot that cannot be resolved keeps the existing
        // persist-then-fail behavior below so no durable user content is lost to a transient read.
        var resolved: SnapshotBinding? = null
        if (uploads.isNotEmpty()) {
            if (textOnly) return imagesRejected()
            resolved = try {
                withContext(Dispatchers.IO) { transfer.resolveRunBinding(conversation.snapshotId) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            if (resolved?.chatModel?.acceptsImages() == false) return imagesRejected()
        }
        val userMessage = try {
            withContext(Dispatchers.IO) {
                if (uploads.isEmpty()) conversations.append(conversationId, MessageRole.USER, text, parts = listOf(TextPart(text)))
                else checkNotNull(images) { "Chat image storage is unavailable" }.appendUser(conversationId, text, uploads)
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
            binding = resolved ?: withContext(Dispatchers.IO) { transfer.resolveRunBinding(conversation.snapshotId) }
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

    private fun imagesRejected() = ChatPreflightResult.Failed(
        IllegalArgumentException("CONFIG_INVALID: 当前聊天模型不支持图片，或已开启纯文本降级；图片未发送，已保留待发内容。"),
        userMessagePersisted = false,
    )
}
