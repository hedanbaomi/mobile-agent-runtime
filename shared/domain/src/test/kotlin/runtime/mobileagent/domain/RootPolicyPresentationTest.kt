// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RootPolicyPresentationTest {
    private val identity = PrivilegedSessionIdentity(
        Authority.ROOT_DIRECT, PrivilegeTier.ROOT, 0, 10123, 0, "app-instance-000001", "runtime.mobileagent",
        "a".repeat(64), 1, "service-instance-001", "session-instance-001", PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION,
    )
    private val record = RootAuthorityRecord(
        RootAuthorityConsent(Authority.ROOT_DIRECT, true, true, 1, 1, 1), revision = 1, identityEpoch = 1,
    )

    @Test
    fun currentExplicitRootShellConsentIsReadyForEveryAdbDangerousMode() {
        for (mode in DangerousMode.entries) {
            val model = model(record, mode, 1)
            assertEquals(RootChannelUiStatus.READY_UID_0, model.status)
            assertTrue(model.effectiveShellReady)
        }
    }

    @Test
    fun staleConsentNeverAdvertisesReadyButAtomicallyCarriedConsentDoes() {
        val stale = model(record, DangerousMode.ENABLED_AUTONOMOUS, 2)
        assertEquals(RootChannelUiStatus.AWAITING_ROOT_CONSENT, stale.status)
        assertFalse(stale.effectiveShellReady)
        assertTrue(RootChannelAction.CONFIRM_CONSENT in stale.availableActions)
        val carried = record.copy(consent = record.consent.copy(policyVersion = 2), revision = 2)
        assertTrue(model(carried, DangerousMode.ENABLED_AUTONOMOUS, 2).effectiveShellReady)
        assertFalse(model(carried.copy(consent = carried.consent.copy(shellEnabled = false)), DangerousMode.ENABLED_AUTONOMOUS, 2).effectiveShellReady)
    }

    @Test
    fun shellNoticeExplainsTheIndependentChannelConsentWithoutRequiringAdbMode() {
        val chinese = model(record, DangerousMode.DISABLED, 1).text.shellConsentNotice
        assertTrue(chinese.contains("不受 ADB 危险模式开关控制"))
        val english = RootAuthorityPresentation.buildChannelModel(Authority.ROOT_DIRECT, Authority.ROOT_DIRECT, record,
            DangerousMode.DISABLED, platformGranted = true, liveIdentity = identity, chinese = false,
            livePolicyVersion = 1).text.shellConsentNotice
        assertTrue(english.contains("does not control Root Shell"))
    }

    private fun model(state: RootAuthorityRecord, mode: DangerousMode, policy: Long) =
        RootAuthorityPresentation.buildChannelModel(Authority.ROOT_DIRECT, Authority.ROOT_DIRECT, state, mode,
            platformGranted = true, liveIdentity = identity, livePolicyVersion = policy)

    @Test
    fun degradedChannelOffersExplicitRecoveryWithoutPretendingANewLiveSessionAlreadyExists() {
        val degraded = record.copy(degradedEpoch = 1)
        val model = RootAuthorityPresentation.buildChannelModel(Authority.ROOT_DIRECT, Authority.ROOT_DIRECT,
            degraded, DangerousMode.DISABLED, livePolicyVersion = 1)
        assertEquals(RootChannelUiStatus.DEGRADED_UNCERTAIN_DESCENDANTS_REQUIRES_ACK, model.status)
        assertTrue(RootChannelAction.ACKNOWLEDGE_DEGRADED_RECOVERY in model.availableActions)
        assertFalse(model.effectiveShellReady)
    }
}
