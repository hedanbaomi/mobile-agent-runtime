// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.RootRunAdmission
import runtime.mobileagent.data.RootRunTarget
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.domain.RootExecutionStamp

/** Why a send was stopped before any paid model request or durable USER turn. */
internal enum class ChatRootSendRefusal {
    AUTHORITY_UNAVAILABLE,
    BINDING_INVALID,
}

internal sealed interface ChatRootSendCheck {
    data object Allowed : ChatRootSendCheck
    data class Refused(val refusal: ChatRootSendRefusal) : ChatRootSendCheck
}

/** Production seam: both the pre-clear check and the pre-persist check use this gate. */
internal fun interface ChatRootSendGate {
    fun check(conversationId: String, snapshotId: String): ChatRootSendCheck
}

/**
 * The frozen conversation workspace/snapshot is the admission input. This is deliberately a
 * read-only preflight: the later RootRunAdmission at Run creation still revalidates the same
 * facts under its coordinator lock to close the race between this check and dispatch.
 */
internal object ChatRootSendDecision {
    fun decide(
        target: RootRunTarget,
        selectedAuthority: Authority,
        executionStamp: RootExecutionStamp?,
    ): ChatRootSendCheck = when (target) {
        RootRunTarget.None -> ChatRootSendCheck.Allowed
        RootRunTarget.Invalid -> ChatRootSendCheck.Refused(ChatRootSendRefusal.BINDING_INVALID)
        is RootRunTarget.Bound ->
            if (target.authority == selectedAuthority && executionStamp != null) {
                ChatRootSendCheck.Allowed
            } else {
                ChatRootSendCheck.Refused(ChatRootSendRefusal.AUTHORITY_UNAVAILABLE)
            }
    }
}

/** Read-only adapter over the existing admission and live Root-session evidence. */
internal class RootRunAdmissionChatGate(
    private val admission: RootRunAdmission,
    private val selectedAuthority: () -> Authority,
    private val executionStamp: (Authority) -> RootExecutionStamp?,
) : ChatRootSendGate {
    override fun check(conversationId: String, snapshotId: String): ChatRootSendCheck {
        val target = admission.resolveRunTarget(conversationId, snapshotId)
        val selected = selectedAuthority()
        val stamp = (target as? RootRunTarget.Bound)?.let { executionStamp(it.authority) }
        return ChatRootSendDecision.decide(target, selected, stamp)
    }
}

/** Closed Root error identity so a non-Root temporary failure never inherits Root copy. */
internal object ChatRootErrors {
    const val NEW_THREAD_STAGE = "root-new-thread"
    const val SEND_STAGE = "root-send-preflight"

    fun isRootAuthorityFailure(error: AppError): Boolean =
        error.code == ErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE && error.stage.startsWith("root-")

    /** Exact stage/code mapping: pre-persist copy must never claim a saved message. */
    fun preflightMessageResource(error: AppError): Int? = when {
        error.code == ErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE && error.stage == NEW_THREAD_STAGE ->
            R.string.root_new_thread_unavailable
        error.code == ErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE && error.stage == SEND_STAGE ->
            R.string.root_send_preflight_unavailable
        error.code == ErrorCode.CAPABILITY_MISMATCH && error.stage == SEND_STAGE ->
            R.string.root_send_preflight_binding_invalid
        error.code == ErrorCode.CAPABILITY_MISMATCH && error.stage == "chat-send-preflight" ->
            R.string.root_send_preflight_check_failed
        else -> null
    }

    /**
     * Pre-persist refusal only. This copy promises that the composer draft is still present;
     * a failure after the USER row was stored must use the saved-message failure path instead.
     */
    fun unavailable(stage: String, operationId: String): AppException = AppError(
        ErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE,
        if (stage == NEW_THREAD_STAGE) {
            "默认 Root 工作区当前不可用，未创建新会话；请重新连接 Root 或改用其他工作区。"
        } else {
            "Root 通道暂时不可用，消息未发送。请重新连接 Root 后重试；草稿已保留。"
        },
        RetryClass.USER_ACTION,
        stage,
        operationId,
    ).asException()

    fun checkFailed(operationId: String): AppException = AppError(
        ErrorCode.CAPABILITY_MISMATCH,
        "无法验证本次发送所需的 Root/工作区状态，已停止发送；请重试。",
        RetryClass.USER_ACTION,
        "chat-send-preflight",
        operationId,
    ).asException()
}

internal fun ChatRootSendRefusal.asException(operationId: String): AppException = when (this) {
    ChatRootSendRefusal.AUTHORITY_UNAVAILABLE -> ChatRootErrors.unavailable(ChatRootErrors.SEND_STAGE, operationId)
    ChatRootSendRefusal.BINDING_INVALID -> AppError(
        ErrorCode.CAPABILITY_MISMATCH,
        "该会话的 Root 工作区快照已失效，消息未发送；请重新选择 Root 工作区。",
        RetryClass.USER_ACTION,
        ChatRootErrors.SEND_STAGE,
        operationId,
    ).asException()
}

/** Build the one production gate used by ChatViewModel; tests inject a stub instead. */
internal fun AppContainer.chatRootSendGate(): ChatRootSendGate = RootRunAdmissionChatGate(
    admission = RootRunAdmission(db),
    selectedAuthority = { AuthorityPolicyRepository(db).getPolicy().selectedAuthority },
    executionStamp = { authority -> rootExecutionPort.currentExecutionStamp(authority) },
)

/** Resolve Root failure copy using the accepted app-language mirror, not the device locale. */
internal fun android.content.Context.rootChatMessage(resource: Int): String =
    hostChatText(resource)
