// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RootAuthorityTest {
    private fun identity(authority: Authority = Authority.ROOT_DIRECT) = PrivilegedSessionIdentity(
        authority, PrivilegeTier.ROOT, 0, 10_123, 0,
        "app-instance-0000001", "runtime.mobileagent", "a".repeat(64),
        1, "service-instance-0001", "session-instance-0001", 4,
    )

    private fun consent(authority: Authority = Authority.ROOT_DIRECT) = RootAuthorityConsent(
        authority, enabled = true, shellEnabled = true, revision = 2, shellRevision = 3, policyVersion = 7,
    )

    private fun stamp(authority: Authority = Authority.ROOT_DIRECT) = RootExecutionStamp(identity(authority), 2, 3, 7)

    private fun denial(
        frozen: RootExecutionStamp = stamp(),
        selected: Authority = frozen.identity.authority,
        live: PrivilegedSessionIdentity? = frozen.identity,
        current: RootAuthorityConsent? = consent(frozen.identity.authority),
        platform: Boolean = true,
        degraded: Boolean = false,
        shell: Boolean = false,
        mode: DangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
        livePolicyVersion: Long = frozen.policyVersion,
    ) = RootAccessPolicy.denial(frozen, selected, live, current, platform, degraded, shell, mode, livePolicyVersion)

    @Test fun rootConsentDefaultsAreDisabledForEachIndependentRoute() {
        listOf(Authority.ROOT_DIRECT, Authority.SHIZUKU_ROOT).forEach {
            val value = RootAuthorityConsent(it)
            assertFalse(value.enabled)
            assertFalse(value.shellEnabled)
            assertEquals(0L, value.revision)
        }
        assertThrows(IllegalArgumentException::class.java) { RootAuthorityConsent(Authority.SHIZUKU) }
    }

    @Test fun oldAuthorityNamesRemainShellAndNoneDoesNotProvideAnIdentity() {
        assertNull(Authority.NONE.requiredPrivilegeTier)
        assertEquals(PrivilegeTier.SHELL, Authority.SHIZUKU.requiredPrivilegeTier)
        assertEquals(PrivilegeTier.SHELL, Authority.WIRED_ADB.requiredPrivilegeTier)
        assertEquals(PrivilegeTier.ROOT, Authority.SHIZUKU_ROOT.requiredPrivilegeTier)
        assertEquals(PrivilegeTier.ROOT, Authority.ROOT_DIRECT.requiredPrivilegeTier)
    }

    @Test fun uidZeroCannotSatisfyAnOldShizukuIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            identity().copy(authority = Authority.SHIZUKU)
        }
        assertThrows(IllegalArgumentException::class.java) { identity().copy(serviceUid = 2000) }
        assertThrows(IllegalArgumentException::class.java) { identity().copy(serviceUid = 12345) }
        assertThrows(IllegalArgumentException::class.java) { identity().copy(privilegeTier = PrivilegeTier.SHELL) }
    }

    @Test fun eachRootRouteAdmitsOnlyItsExactVerifiedSession() {
        listOf(Authority.ROOT_DIRECT, Authority.SHIZUKU_ROOT).forEach {
            assertNull(denial(frozen = stamp(it), mode = DangerousMode.DISABLED))
        }
        assertEquals(RootAccessDenial.AUTHORITY_CHANGED, denial(selected = Authority.SHIZUKU_ROOT))
        assertEquals(RootAccessDenial.AUTHORITY_CHANGED, denial(selected = Authority.WIRED_ADB))
        assertEquals(RootAccessDenial.ROOT_CONSENT_REQUIRED, denial(current = consent(Authority.SHIZUKU_ROOT)))
    }

    @Test fun permissionAndApplicationConsentAreBothMandatory() {
        assertEquals(RootAccessDenial.PLATFORM_NOT_GRANTED, denial(platform = false))
        assertEquals(RootAccessDenial.ROOT_CONSENT_REQUIRED, denial(current = null))
        assertEquals(RootAccessDenial.ROOT_CONSENT_REQUIRED, denial(current = RootAuthorityConsent(Authority.ROOT_DIRECT)))
    }

    @Test fun policyOrConsentChangesInvalidateAnAlreadyFrozenCall() {
        assertEquals(RootAccessDenial.CONSENT_CHANGED, denial(current = consent().copy(revision = 3)))
        assertEquals(RootAccessDenial.CONSENT_CHANGED, denial(current = consent().copy(policyVersion = 8)))
        // A policy change can retain the same route and stored consent: check the live DB version too.
        assertEquals(RootAccessDenial.CONSENT_CHANGED, denial(livePolicyVersion = 8))
        // Re-enabling consent creates a newer revision; it cannot resurrect the old stamp.
        assertEquals(RootAccessDenial.CONSENT_CHANGED, denial(current = consent().copy(revision = 4)))
    }

    @Test fun everyIdentityDimensionIsBoundAcrossTheWholeSession() {
        val original = identity()
        val changed = listOf(
            original.copy(appUid = 10_124),
            original.copy(androidUserId = 1, appUid = 110_123),
            original.copy(appInstanceId = "another-app-instance1"),
            original.copy(packageName = "another.application"),
            original.copy(signingSha256 = "b".repeat(64)),
            original.copy(identityEpoch = 2),
            original.copy(serviceInstanceId = "another-service-0001"),
            original.copy(sessionId = "another-session-0001"),
        )
        changed.forEach { assertEquals(RootAccessDenial.IDENTITY_CHANGED, denial(live = it)) }
        assertEquals(RootAccessDenial.IDENTITY_CHANGED, denial(live = null))
    }

    @Test fun oldShellProtocolCannotBecomeRootByReportingUidZero() {
        val old = identity().copy(protocolVersion = 3)
        assertEquals(RootAccessDenial.PROTOCOL_UNSUPPORTED, denial(frozen = stamp().copy(identity = old), live = old))
    }

    @Test fun degradedSessionCannotDispatchEvenAfterPermissionIsRegranted() {
        assertEquals(RootAccessDenial.SESSION_DEGRADED, denial(degraded = true))
    }

    @Test fun dangerousModeAloneDoesNotAuthorizeRootShell() {
        assertEquals(RootAccessDenial.ROOT_SHELL_CONSENT_REQUIRED, denial(shell = true, current = consent().copy(shellEnabled = false)))
        assertEquals(RootAccessDenial.ROOT_SHELL_CONSENT_REQUIRED, denial(shell = true, frozen = stamp().copy(shellConsentRevision = 0)))
        assertEquals(RootAccessDenial.ROOT_SHELL_CONSENT_REQUIRED, denial(shell = true, current = consent().copy(shellRevision = 4)))
        assertEquals(RootAccessDenial.DANGEROUS_MODE_DISABLED, denial(shell = true, mode = DangerousMode.DISABLED))
        assertNull(denial(shell = true, mode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK))
        assertNull(denial(shell = true, mode = DangerousMode.ENABLED_AUTONOMOUS))
    }

    @Test fun malformedOrEmptyIdentityCannotCreateAServiceSession() {
        assertThrows(IllegalArgumentException::class.java) { identity().copy(signingSha256 = "not-a-digest") }
        assertThrows(IllegalArgumentException::class.java) { identity().copy(sessionId = "") }
        assertThrows(IllegalArgumentException::class.java) { identity().copy(identityEpoch = 0) }
        assertThrows(IllegalArgumentException::class.java) { identity().copy(packageName = "../runtime") }
        assertThrows(IllegalArgumentException::class.java) { identity().copy(appUid = 2000) }
    }

    @Test fun shellConsentCannotExistWithoutExplicitRootConsent() {
        assertThrows(IllegalArgumentException::class.java) {
            RootAuthorityConsent(Authority.ROOT_DIRECT, shellEnabled = true, shellRevision = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootAuthorityConsent(Authority.ROOT_DIRECT, enabled = true, revision = 0)
        }
    }
}
