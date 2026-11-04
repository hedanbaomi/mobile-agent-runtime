// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.AuthorityPolicy
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RootAuthorityConsent
import runtime.mobileagent.domain.RootAuthorityRecord
import runtime.mobileagent.domain.RootCallerObservation
import runtime.mobileagent.domain.RootHostControlGate
import runtime.mobileagent.domain.RootServiceLifecycleCoordinator
import runtime.mobileagent.domain.RootServiceSession

/** Execution stamps must be derived from the live manager/Shizuku facts, not only consent. */
class RootCoordinatorReadinessLiveFactsTest {
    @Test
    fun directRootRequiresASessionPermittingManagerObservation() {
        val authority = Authority.ROOT_DIRECT
        val state = state(authority)
        val record = record(authority)
        val policy = policy(authority)
        fun stamp(authorization: RootManagerAuthorization?) =
            RootCoordinatorReadiness.resolveExecutionStamp(
                authority = authority,
                active = state,
                loadRecord = { record },
                policy = policy,
                requireShellConsent = false,
                live = RootLiveAuthorizationFacts(authorization, shizukuDenied = false),
            )

        val granted = stamp(RootManagerAuthorization.GRANTED)
        assertNotNull(granted)
        assertEquals(authority, granted!!.identity.authority)
        assertNotNull(stamp(RootManagerAuthorization.SESSION_GRANTED))
        listOf<RootManagerAuthorization?>(
            null,
            RootManagerAuthorization.REVOKED,
            RootManagerAuthorization.UNKNOWN,
            RootManagerAuthorization.UNSUPPORTED,
        ).forEach { authorization ->
            assertNull(stamp(authorization), "A non-permitting observation must fail closed")
        }
    }

    @Test
    fun shizukuPlatformDenialBlocksTheStampEvenWithHealthyConsent() {
        val authority = Authority.SHIZUKU_ROOT
        val state = state(authority)
        val record = record(authority)
        val policy = policy(authority)
        fun stamp(denied: Boolean, manager: RootManagerAuthorization? = null) =
            RootCoordinatorReadiness.resolveExecutionStamp(
                authority = authority,
                active = state,
                loadRecord = { record },
                policy = policy,
                requireShellConsent = false,
                live = RootLiveAuthorizationFacts(manager, shizukuDenied = denied),
            )

        assertNull(stamp(denied = true))
        assertNotNull(stamp(denied = false))
        assertNotNull(
            stamp(denied = false, manager = RootManagerAuthorization.REVOKED),
            "The Magisk observer is not the Shizuku authority's gate",
        )
    }

    @Test
    fun shizukuDenialDoesNotSuppressDirectRoot() {
        val authority = Authority.ROOT_DIRECT
        assertNotNull(
            RootCoordinatorReadiness.resolveExecutionStamp(
                authority = authority,
                active = state(authority),
                loadRecord = { record(authority) },
                policy = policy(authority),
                requireShellConsent = false,
                live = RootLiveAuthorizationFacts(
                    managerAuthorization = RootManagerAuthorization.GRANTED,
                    shizukuDenied = true,
                ),
            ),
            "The Shizuku denial fence is scoped to SHIZUKU_ROOT",
        )
    }

    @Test
    fun deadTransportStillFailsClosed() {
        val authority = Authority.ROOT_DIRECT
        val state = state(authority)
        state.transport.close()
        assertNull(
            RootCoordinatorReadiness.resolveExecutionStamp(
                authority = authority,
                active = state,
                loadRecord = { record(authority) },
                policy = policy(authority),
                requireShellConsent = false,
                live = RootLiveAuthorizationFacts(RootManagerAuthorization.GRANTED, shizukuDenied = false),
            ),
        )
    }

    private fun identity(authority: Authority) = PrivilegedSessionIdentity(
        authority = authority,
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

    private fun state(authority: Authority): ManagedRootSessionState {
        val identity = identity(authority)
        val session = RootServiceSession(identity = identity)
        return ManagedRootSessionState(
            session = session,
            lifecycle = RootServiceLifecycleCoordinator(session),
            caller = RootCallerObservation(
                callingUid = identity.appUid,
                observedServiceUid = 0,
                packageName = identity.packageName,
                androidUserId = identity.androidUserId,
                signingSha256 = identity.signingSha256,
            ),
            transport = SyntheticRootSessionTransport(identity),
            hostControlGate = RootHostControlGate(identity),
        )
    }

    private fun record(authority: Authority) = RootAuthorityRecord(
        consent = RootAuthorityConsent(
            authority = authority,
            enabled = true,
            shellEnabled = true,
            revision = 1,
            shellRevision = 1,
            policyVersion = 1,
        ),
        revision = 1,
        identityEpoch = 1,
    )

    private fun policy(authority: Authority) = AuthorityPolicy(
        selectedAuthority = authority,
        dangerousMode = DangerousMode.DISABLED,
        policyVersion = 1,
    )
}
