// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RootAuthorityConsent
import runtime.mobileagent.domain.RootAuthorityRecord
import runtime.mobileagent.domain.RootCallerObservation
import runtime.mobileagent.domain.RootHostControlGate
import runtime.mobileagent.domain.RootServiceLifecycleCoordinator
import runtime.mobileagent.domain.RootServiceSession

/** A selected Magisk observer that disappears must not silently keep the direct-Root permit. */
class RootManagerObserverLossRegressionTest {
    @Test
    fun losingTheSelectedObserverDoesNotApplyASessionPermit() {
        val identity = identity()
        val session = RootServiceSession(identity = identity)
        val state = ManagedRootSessionState(
            session = session,
            lifecycle = RootServiceLifecycleCoordinator(session),
            caller = RootCallerObservation(
                callingUid = identity.appUid,
                observedServiceUid = 0,
                packageName = identity.packageName,
                androidUserId = identity.androidUserId,
                signingSha256 = identity.signingSha256,
            ),
            transport = SyntheticRootSessionTransport(identity, RootManagerAuthorization.UNSUPPORTED),
            hostControlGate = RootHostControlGate(identity),
        )
        val record = RootAuthorityRecord(
            consent = RootAuthorityConsent(
                authority = Authority.ROOT_DIRECT,
                enabled = true,
                shellEnabled = true,
                revision = 1,
                shellRevision = 1,
                policyVersion = 1,
            ),
            revision = 1,
            identityEpoch = 1,
        )
        var applied: RootManagerAuthorization? = null
        val watcher = RootManagerAuthorizationWatcher(
            intervalMs = { 1_000L },
            currentSession = { state },
            currentRecord = { record },
            onObservation = {},
            isClosed = { false },
            applyObservation = { _, _, _, decision, _ ->
                applied = decision
                decision.permitsSession
            },
        )

        assertFalse(watcher.observeOnce(), "UNSUPPORTED must not be treated as a permit")
        assertEquals(RootManagerAuthorization.UNSUPPORTED, applied)
    }

    private fun identity() = PrivilegedSessionIdentity(
        authority = Authority.ROOT_DIRECT,
        privilegeTier = PrivilegeTier.ROOT,
        serviceUid = 0,
        appUid = 10_123,
        androidUserId = 0,
        appInstanceId = "app-instance-000001",
        packageName = "runtime.mobileagent",
        signingSha256 = "a".repeat(64),
        identityEpoch = 1L,
        serviceInstanceId = "root-service-000001",
        sessionId = "root-session-000001",
        protocolVersion = PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION,
    )
}
