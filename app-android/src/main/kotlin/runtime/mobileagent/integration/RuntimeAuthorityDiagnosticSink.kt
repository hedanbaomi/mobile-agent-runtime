// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import runtime.mobileagent.diagnostics.AndroidDiagnosticLogger
import runtime.mobileagent.diagnostics.AuthorityConfigurationStateRecord
import runtime.mobileagent.diagnostics.AuthoritySelectionChangedRecord
import runtime.mobileagent.diagnostics.AuthorityStateChangedRecord
import runtime.mobileagent.diagnostics.DangerousModeChangedRecord
import runtime.mobileagent.diagnostics.DiagnosticAuthority
import runtime.mobileagent.diagnostics.DiagnosticAuthorityConfigurationReason
import runtime.mobileagent.diagnostics.DiagnosticAuthorityState
import runtime.mobileagent.diagnostics.DiagnosticAvailability
import runtime.mobileagent.diagnostics.DiagnosticConnection
import runtime.mobileagent.diagnostics.DiagnosticDangerousModePolicy
import runtime.mobileagent.diagnostics.DiagnosticPlatformGrant
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.AuthorityUserIntent
import runtime.mobileagent.skills.tooling.Availability
import runtime.mobileagent.skills.tooling.Connection
import runtime.mobileagent.skills.tooling.PlatformGrant
import runtime.mobileagent.tooling.AuthorityManagerState

/**
 * Projects runtime authority observations into diagnostic records only.
 * Collection order, selection history, and recovery stay with RuntimeIntegration.
 */
internal class RuntimeAuthorityDiagnosticSink(private val diagnostics: AndroidDiagnosticLogger) {
    fun recordSelection(selected: DiagnosticAuthority, previous: DiagnosticAuthority?) {
        diagnostics.recordAuthoritySelectionChanged(AuthoritySelectionChangedRecord(selected, previous))
    }

    fun recordDangerousMode(enabled: Boolean, policy: DiagnosticDangerousModePolicy) {
        diagnostics.recordDangerousModeChanged(DangerousModeChangedRecord(enabled = enabled, policy = policy))
    }

    fun recordStates(state: AuthorityManagerState) {
        state.statuses.values.forEach { status ->
            diagnostics.recordAuthorityStateChanged(
                AuthorityStateChangedRecord(
                    authority = status.authority.toDiagnostic(),
                    state = status.toDiagnostic(),
                ),
            )
        }
    }

    fun recordConfiguration(
        reason: DiagnosticAuthorityConfigurationReason,
        state: AuthorityManagerState,
    ) {
        state.statuses.values.forEach { status ->
            diagnostics.recordAuthorityConfigurationState(
                AuthorityConfigurationStateRecord(
                    authority = status.authority.toDiagnostic(),
                    userIntentEnabled = status.userIntent != AuthorityUserIntent.NONE,
                    selected = state.selectedAuthority == status.authority,
                    platformGrant = when (status.grant) {
                        PlatformGrant.GRANTED -> DiagnosticPlatformGrant.GRANTED
                        PlatformGrant.DENIED, PlatformGrant.REVOKED -> DiagnosticPlatformGrant.DENIED
                        PlatformGrant.UNKNOWN -> DiagnosticPlatformGrant.UNKNOWN
                    },
                    availability = when (status.availability) {
                        Availability.READY -> DiagnosticAvailability.READY
                        Availability.TEMPORARILY_UNAVAILABLE -> DiagnosticAvailability.TEMPORARILY_UNAVAILABLE
                        Availability.UNSUPPORTED -> DiagnosticAvailability.UNSUPPORTED
                    },
                    connection = when (status.connection) {
                        Connection.CONNECTED -> DiagnosticConnection.CONNECTED
                        Connection.CONNECTING -> DiagnosticConnection.CONNECTING
                        Connection.DISCONNECTED -> DiagnosticConnection.DISCONNECTED
                        Connection.DEGRADED -> DiagnosticConnection.DEGRADED
                    },
                    configured = status.configured,
                    reason = reason,
                ),
            )
        }
    }

    private fun runtime.mobileagent.skills.tooling.AuthorityState.toDiagnostic(): DiagnosticAuthorityState = when {
        isReady -> DiagnosticAuthorityState.CONNECTED
        availability == Availability.TEMPORARILY_UNAVAILABLE -> DiagnosticAuthorityState.DISCONNECTED
        connection == Connection.CONNECTING -> DiagnosticAuthorityState.CONNECTING
        grant == PlatformGrant.DENIED || grant == PlatformGrant.REVOKED -> DiagnosticAuthorityState.REAUTH_REQUIRED
        availability == Availability.READY -> DiagnosticAuthorityState.AVAILABLE
        else -> DiagnosticAuthorityState.UNAVAILABLE
    }
}

internal fun Authority.toDiagnostic(): DiagnosticAuthority = when (this) {
    Authority.NONE -> DiagnosticAuthority.NONE
    Authority.SHIZUKU -> DiagnosticAuthority.SHIZUKU
    Authority.WIRED_ADB -> DiagnosticAuthority.WIRED_ADB
    Authority.ROOT_DIRECT -> DiagnosticAuthority.ROOT_DIRECT
    Authority.SHIZUKU_ROOT -> DiagnosticAuthority.SHIZUKU_ROOT
}
