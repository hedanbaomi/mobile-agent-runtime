// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.data.RootRunTarget
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.domain.RootExecutionStamp

class ChatRootPreflightTest {
    @Test fun rootSendNeedsTheSelectedAuthorityAndALiveExecutionStamp() {
        assertEquals(
            ChatRootSendCheck.Allowed,
            ChatRootSendDecision.decide(RootRunTarget.None, Authority.NONE, null),
        )
        assertEquals(
            ChatRootSendCheck.Refused(ChatRootSendRefusal.AUTHORITY_UNAVAILABLE),
            ChatRootSendDecision.decide(RootRunTarget.Bound(Authority.ROOT_DIRECT), Authority.SHIZUKU_ROOT, stamp(Authority.ROOT_DIRECT)),
        )
        assertEquals(
            ChatRootSendCheck.Refused(ChatRootSendRefusal.AUTHORITY_UNAVAILABLE),
            ChatRootSendDecision.decide(RootRunTarget.Bound(Authority.ROOT_DIRECT), Authority.ROOT_DIRECT, null),
        )
        assertEquals(
            ChatRootSendCheck.Allowed,
            ChatRootSendDecision.decide(RootRunTarget.Bound(Authority.ROOT_DIRECT), Authority.ROOT_DIRECT, stamp(Authority.ROOT_DIRECT)),
        )
        assertEquals(
            ChatRootSendCheck.Refused(ChatRootSendRefusal.BINDING_INVALID),
            ChatRootSendDecision.decide(RootRunTarget.Invalid, Authority.ROOT_DIRECT, null),
        )
    }

    @Test fun onlyRootStagesMayClaimRootAuthorityFailure() {
        val root = AppError(
            ErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE, "safe", RetryClass.USER_ACTION,
            ChatRootErrors.SEND_STAGE, "conversation",
        )
        assertTrue(ChatRootErrors.isRootAuthorityFailure(root))
        val admission = root.copy(stage = "root-admission")
        assertTrue(ChatRootErrors.isRootAuthorityFailure(admission))
        // A Shizuku/ADB/SAF temporary failure must keep its ordinary mapping.
        assertFalse(ChatRootErrors.isRootAuthorityFailure(root.copy(stage = "shizuku-permission")))
        assertFalse(ChatRootErrors.isRootAuthorityFailure(root.copy(code = ErrorCode.NETWORK_UNAVAILABLE)))

        val refusal = ChatRootSendRefusal.AUTHORITY_UNAVAILABLE.asException("conversation")
        assertEquals(ChatRootErrors.SEND_STAGE, refusal.error.stage)
        assertTrue(ChatRootErrors.isRootAuthorityFailure(refusal.error))
    }

    @Test fun checkFailureIsNotReportedAsARootPermissionError() {
        val error = ChatRootErrors.checkFailed("conversation")
        assertFalse(ChatRootErrors.isRootAuthorityFailure(error.error))
        assertEquals(ErrorCode.CAPABILITY_MISMATCH, error.error.code)
        assertTrue(error.error.sanitizedDetails.isBlank())
    }

    @Test fun rootDefaultClassificationFailureStopsInsteadOfBecomingUnbound() {
        val error = assertThrows(AppException::class.java) {
            NewThreadRootDefaultGuard.requireResolved("root-workspace", null) {
                error("workspace classification lookup failed")
            }
        }
        assertEquals("chat-send-preflight", error.error.stage)
        assertEquals(ErrorCode.CAPABILITY_MISMATCH, error.error.code)
        assertTrue(error.cause is IllegalStateException)
        assertFalse(error.message.orEmpty().contains("草稿"))
    }

    @Test fun unsealedRootDefaultIsRejectedInsteadOfSilentlyBecomingUnbound() {
        val error = assertThrows(AppException::class.java) {
            NewThreadRootDefaultGuard.requireResolved("root-workspace", null) { it == "root-workspace" }
        }
        assertEquals(ChatRootErrors.NEW_THREAD_STAGE, error.error.stage)
        assertEquals(ErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE, error.error.code)
        assertFalse(error.message.orEmpty().contains("草稿"))
        // The pre-persist send failure may promise the draft; post-USER failures use the
        // saved-message resource and must never reach this copy path.
        assertTrue(ChatRootErrors.unavailable(ChatRootErrors.SEND_STAGE, "conversation").message.orEmpty().contains("草稿"))

        // Cleared defaults, ordinary defaults and successfully resolved Root defaults keep their
        // existing behavior.
        NewThreadRootDefaultGuard.requireResolved(null, null) { true }
        NewThreadRootDefaultGuard.requireResolved("saf-workspace", null) { it == "root-workspace" }
        NewThreadRootDefaultGuard.requireResolved("root-workspace", "root-workspace") { true }
    }

    @Test fun preflightCopyUsesExactCodeAndStageWithoutChangingSavedMessageFailures() {
        assertEquals(R.string.root_new_thread_unavailable,
            ChatRootErrors.preflightMessageResource(ChatRootErrors.unavailable(ChatRootErrors.NEW_THREAD_STAGE, "new").error))
        val send = ChatRootErrors.unavailable(ChatRootErrors.SEND_STAGE, "send").error
        assertEquals(R.string.root_send_preflight_unavailable, ChatRootErrors.preflightMessageResource(send))
        assertEquals(R.string.root_send_preflight_binding_invalid,
            ChatRootErrors.preflightMessageResource(ChatRootSendRefusal.BINDING_INVALID.asException("send").error))
        assertEquals(R.string.root_send_preflight_check_failed,
            ChatRootErrors.preflightMessageResource(ChatRootErrors.checkFailed("send").error))
        assertEquals(null, ChatRootErrors.preflightMessageResource(send.copy(stage = "root-admission")))
        assertEquals(null, ChatRootErrors.preflightMessageResource(send.copy(stage = "shizuku-permission")))
        assertEquals(null, ChatRootErrors.preflightMessageResource(send.copy(code = ErrorCode.NETWORK_UNAVAILABLE)))
    }

    private fun stamp(authority: Authority) = RootExecutionStamp(
        PrivilegedSessionIdentity(
            authority, PrivilegeTier.ROOT, 0, 10123, 0,
            "app-instance-000001", "runtime.mobileagent", "a".repeat(64), 1,
            "service-instance-001", "session-instance-001", 4,
        ), 1, 1, 1,
    )
}
