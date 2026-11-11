// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import runtime.mobileagent.data.PrivilegedWorkspaceBindingRepository
import runtime.mobileagent.diagnostics.AndroidDiagnosticLogger
import runtime.mobileagent.diagnostics.DiagnosticAuthorityConfigurationReason
import runtime.mobileagent.diagnostics.DiagnosticDangerousModePolicy
import runtime.mobileagent.diagnostics.DiagnosticLifecycleState
import runtime.mobileagent.diagnostics.ShizukuLifecycleRecord
import runtime.mobileagent.diagnostics.WiredAdbLifecycleRecord
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.AuthorityUserIntent
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.PrivilegedWorkspaceBindingStatus
import runtime.mobileagent.shizuku.ShizukuAuthorityBridge
import runtime.mobileagent.shizuku.ShizukuAuthorityState
import runtime.mobileagent.skills.tooling.Availability
import runtime.mobileagent.skills.tooling.Connection
import runtime.mobileagent.skills.tooling.ElevatedAuthority
import runtime.mobileagent.skills.tooling.PlatformGrant
import runtime.mobileagent.tooling.AuthorityManager
import runtime.mobileagent.tooling.AuthorityManagerState
import runtime.mobileagent.tooling.DangerousBuildPolicy
import runtime.mobileagent.tooling.DangerousModeManager
import runtime.mobileagent.tooling.ShellToolExecutor
import runtime.mobileagent.tooling.WorkspaceRegistry
import runtime.mobileagent.wired.WiredAdbAuthorityPort
import runtime.mobileagent.wired.WiredAdbConnectionState
import runtime.mobileagent.wired.WiredAdbErrorCode
import runtime.mobileagent.wired.WiredAdbLifecycleState
import runtime.mobileagent.wired.WiredAdbPlatformGrant
import runtime.mobileagent.wired.WiredAdbStatus
import runtime.mobileagent.wired.WiredAdbUserIntent

/** Process-lifetime observation updates canonical authority facts without switching or granting. */
internal class RuntimeAuthorityLifecycle(
    private val authorityManager: AuthorityManager,
    private val dangerousModeManager: DangerousModeManager,
    private val buildPolicy: DangerousBuildPolicy,
    private val wiredAuthority: WiredAdbAuthorityPort,
    private val diagnostics: AndroidDiagnosticLogger,
    private val authorityDiagnostics: RuntimeAuthorityDiagnosticSink,
    private val privilegedWorkspaceBindingRepository: PrivilegedWorkspaceBindingRepository,
    private val workspaceRegistry: WorkspaceRegistry,
    private val workspaceRecovery: RuntimeWorkspaceRecovery,
    private val rootCoordinator: () -> runtime.mobileagent.privileged.RootRuntimeCoordinator,
    private val scope: CoroutineScope,
    private val closed: AtomicBoolean,
    private val wiredReconnectInFlight: AtomicBoolean,
) {
    private val wiredIntentReconciled = AtomicBoolean(false)
    private var previousSelection: Authority? = null
    private val authorityReadySeen = HashMap<ElevatedAuthority, Boolean>()

    /**
     * Defense-in-depth gate for the low-level Wired shell port. The shared
     * ShellToolExecutor remains the authority for per-call capability and
     * approval checks; this callback only prevents direct bridge dispatch
     * while Wired is not the selected, configured, ready provider.
     */
    internal fun wiredShellPermissionAvailable(): Boolean {
        if (closed.get()) return false
        val state = authorityManager.state.value
        val wired = state.statuses[ElevatedAuthority.WIRED_ADB] ?: return false
        return state.selectedAuthority == ElevatedAuthority.WIRED_ADB &&
            wired.isConfiguredForSelection &&
            wired.isReady &&
            wiredIntentReconciled.get() &&
            dangerousModeManager.policy() != DangerousMode.DISABLED &&
            buildPolicy.permitsDangerousMode()
    }

    fun applyShizukuState(state: ShizukuAuthorityState) {
        val definitiveGrant = when {
            state.errorCode == ShizukuAuthorityBridge.PERMISSION_CHECK_FAILED -> null
            state.permissionGranted -> PlatformGrant.GRANTED
            state.binderAlive -> PlatformGrant.DENIED
            else -> null
        }
        val availability = when {
            !state.installedHint || state.preV11 -> Availability.UNSUPPORTED
            state.ready -> Availability.READY
            else -> Availability.TEMPORARILY_UNAVAILABLE
        }
        val connection = when {
            state.ready -> Connection.CONNECTED
            state.binderAlive && state.permissionGranted && !state.userServiceAlive -> Connection.CONNECTING
            state.binderAlive && state.permissionGranted -> Connection.DEGRADED
            else -> Connection.DISCONNECTED
        }
        // Binder loss cannot prove that Shizuku permission was revoked. Keep
        // the last confirmed grant across disconnects and process restarts;
        // only a live Binder reporting denial invalidates the durable setup.
        definitiveGrant?.let { grant ->
            authorityManager.updatePlatformGrant(ElevatedAuthority.SHIZUKU, grant)
            val canonical = authorityManager.state.value.statuses[ElevatedAuthority.SHIZUKU]
            when {
                // User intent is the explicit app-side authorization, while
                // Shizuku's live GRANTED result is the platform-side proof.
                // Their conjunction may durably restore configuration even
                // when the user granted access from Shizuku Manager instead
                // of this app's permission button. Grant alone never selects
                // or enables the authority.
                grant == PlatformGrant.GRANTED &&
                    canonical?.userIntent == AuthorityUserIntent.SHIZUKU &&
                    canonical?.configured == false -> {
                    authorityManager.setConfigured(ElevatedAuthority.SHIZUKU, true)
                }
                grant == PlatformGrant.DENIED && canonical?.configured == true -> {
                    authorityManager.setConfigured(ElevatedAuthority.SHIZUKU, false)
                }
            }
        }
        authorityManager.updateAvailability(ElevatedAuthority.SHIZUKU, availability)
        authorityManager.updateConnection(ElevatedAuthority.SHIZUKU, connection)
        // The live listener must fence the Root session too, not only refresh the UI: an explicit
        // platform denial invalidates old approvals even if the UID0 service is still responsive.
        rootCoordinator().refreshLiveSessions(state)
        // A permission answer or passive refresh only updates state; it never starts the Root
        // service. The user performs the explicit second "enable" click after authorizing.
        rootCoordinator().syncAuthorityManagerStates(authorityManager, state)
        diagnostics.recordShizukuLifecycle(
            ShizukuLifecycleRecord(
                state = when {
                    state.ready -> DiagnosticLifecycleState.READY
                    state.binderAlive && state.permissionGranted && !state.userServiceAlive -> DiagnosticLifecycleState.STARTED
                    state.binderAlive && state.permissionGranted -> DiagnosticLifecycleState.FAILED
                    state.binderAlive -> DiagnosticLifecycleState.DISCONNECTED
                    else -> DiagnosticLifecycleState.UNKNOWN
                },
                errorCode = state.errorCode ?: "unknown",
            ),
        )
    }

    fun applyWiredState(state: WiredAdbStatus) {
        val bindingInvalidated = state.lastError in setOf(
            WiredAdbErrorCode.BRIDGE_BINDING_MISMATCH,
            WiredAdbErrorCode.BRIDGE_PROTOCOL_MISMATCH,
            WiredAdbErrorCode.BRIDGE_AUTH_FAILED,
            WiredAdbErrorCode.BRIDGE_SECRET_UNAVAILABLE,
        )
        val definitiveGrant = when {
            bindingInvalidated -> PlatformGrant.REVOKED
            else -> when (state.platformGrant) {
                WiredAdbPlatformGrant.GRANTED -> PlatformGrant.GRANTED
                WiredAdbPlatformGrant.DENIED -> PlatformGrant.DENIED
                WiredAdbPlatformGrant.REVOKED -> PlatformGrant.REVOKED
                WiredAdbPlatformGrant.UNKNOWN -> null
            }
        }
        val availability = when (state.availability) {
            runtime.mobileagent.wired.WiredAdbAvailability.READY -> Availability.READY
            runtime.mobileagent.wired.WiredAdbAvailability.TEMPORARILY_UNAVAILABLE -> Availability.TEMPORARILY_UNAVAILABLE
            runtime.mobileagent.wired.WiredAdbAvailability.UNSUPPORTED -> Availability.UNSUPPORTED
        }
        val connection = when (state.connection) {
            WiredAdbConnectionState.CONNECTED -> Connection.CONNECTED
            WiredAdbConnectionState.CONNECTING -> Connection.CONNECTING
            WiredAdbConnectionState.DEGRADED -> Connection.DEGRADED
            WiredAdbConnectionState.DISCONNECTED -> Connection.DISCONNECTED
        }
        // A missing cable, Wi-Fi route or desktop process yields UNKNOWN and
        // must retain paired trust. Only a definitive trust/binding failure
        // invalidates the persistent configured state.
        definitiveGrant?.let { grant ->
            authorityManager.updatePlatformGrant(ElevatedAuthority.WIRED_ADB, grant)
            if (grant in setOf(PlatformGrant.DENIED, PlatformGrant.REVOKED) &&
                authorityManager.state.value.statuses[ElevatedAuthority.WIRED_ADB]?.configured == true
            ) {
                authorityManager.setConfigured(ElevatedAuthority.WIRED_ADB, false)
            }
        }
        authorityManager.updateAvailability(ElevatedAuthority.WIRED_ADB, availability)
        authorityManager.updateConnection(ElevatedAuthority.WIRED_ADB, connection)
        // Reconcile both enabled and disabled bridge intent. An older one-way
        // sync could leave a canonical manager enabled after a restart where
        // the durable bridge intent had been cleared. Never write a value when
        // the bridge reports a persistence/load failure; in that case the
        // shell gate stays closed until a later healthy status is observed.
        val intentLoadSucceeded = state.lastError != WiredAdbErrorCode.INTERNAL_ERROR &&
            !(state.state == WiredAdbLifecycleState.REAUTH_REQUIRED && state.lastError == null)
        if (!intentLoadSucceeded) {
            wiredIntentReconciled.set(false)
        } else {
            val desired = if (state.userIntent == WiredAdbUserIntent.ENABLED) {
                AuthorityUserIntent.WIRED_ADB
            } else {
                AuthorityUserIntent.NONE
            }
            val current = authorityManager.state.value.statuses[ElevatedAuthority.WIRED_ADB]?.userIntent
            val matches = current == desired || runCatching {
                authorityManager.setUserIntent(
                    ElevatedAuthority.WIRED_ADB,
                    state.userIntent == WiredAdbUserIntent.ENABLED,
                )
            }.getOrDefault(false)
            wiredIntentReconciled.set(
                matches && authorityManager.state.value.statuses[ElevatedAuthority.WIRED_ADB]?.userIntent == desired,
            )
        }
        diagnostics.recordWiredAdbLifecycle(
            WiredAdbLifecycleRecord(
                state = when (state.state) {
                    WiredAdbLifecycleState.READY -> DiagnosticLifecycleState.READY
                    WiredAdbLifecycleState.CONNECTING,
                    WiredAdbLifecycleState.AUTHENTICATING,
                        -> DiagnosticLifecycleState.STARTED
                    WiredAdbLifecycleState.REAUTH_REQUIRED -> DiagnosticLifecycleState.FAILED
                    WiredAdbLifecycleState.DISCONNECTED -> DiagnosticLifecycleState.DISCONNECTED
                    else -> DiagnosticLifecycleState.UNKNOWN
                },
                errorCode = state.lastError?.name ?: "unknown",
            ),
        )
    }

    fun installDiagnosticsCollectors() {
        scope.launch {
            authorityManager.selection.collect { selection ->
                val selected = selection.selected ?: Authority.NONE
                val previous = previousSelection
                if (previous != selected) {
                    authorityDiagnostics.recordSelection(selected.toDiagnostic(), previous?.toDiagnostic())
                    previousSelection = selected
                }
            }
        }
        scope.launch {
            dangerousModeManager.state.collect { state ->
                authorityDiagnostics.recordDangerousMode(state.enabled, state.policy.toDiagnostic())
            }
        }
        scope.launch {
            var previous: WiredAdbStatus? = null
            wiredAuthority.status.collect { state ->
                val prior = previous
                previous = state
                if (wiredAuthority is runtime.mobileagent.resident.ResidentAdbAuthorityBridge &&
                    residentWorkspaceHandlesNeedReattach(prior, state)
                ) {
                    privilegedWorkspaceBindingRepository.forAuthority(ElevatedAuthority.WIRED_ADB)
                        .filter { it.status != PrivilegedWorkspaceBindingStatus.REVOKED }
                        .forEach { workspaceRegistry.unregister(it.workspaceId) }
                }
                applyWiredState(state)
                workspaceRecovery.schedulePrivilegedWorkspaceReattach()
                recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.PLATFORM_STATE_CHANGE)
                // A transport/provider transition may be the only signal that
                // USB became available again. Retry only on the meaningful
                // disconnected -> trusted edge; reconnect failures themselves
                // never create a tight retry loop.
                if (prior?.state == WiredAdbLifecycleState.DISCONNECTED &&
                    state.state == WiredAdbLifecycleState.TRUSTED
                ) {
                    scheduleWiredReconnect()
                }
            }
        }
        scope.launch {
            authorityManager.state.collect { state ->
                authorityDiagnostics.recordStates(state)
                recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.SNAPSHOT, state)
                // A privileged UserService restart kills every remote workspace
                // handle while the local registry entries stay registered and
                // look alive to enumerate.  The edge is evaluated per authority
                // — including an authority that restarts while unselected,
                // whose stale handles would otherwise survive the later
                // switch — and drops that authority's stale entries so the
                // reattach pass below actually re-opens them instead of
                // skipping them.  Reattach itself stays selected-only;
                // Wired ADB uses its own status collector above; resident
                // daemon generations also invalidate its ephemeral handles.
                privilegedReadyEdgeAuthorities(state.statuses, authorityReadySeen)
                    .forEach { authority ->
                        privilegedWorkspaceBindingRepository.forAuthority(authority)
                            .asSequence()
                            .filter { it.status != PrivilegedWorkspaceBindingStatus.REVOKED }
                            .forEach { workspaceRegistry.unregister(it.workspaceId) }
                    }
                workspaceRecovery.schedulePrivilegedWorkspaceReattach()
            }
        }
    }

    fun recordAuthorityConfigurationSnapshot(
        reason: DiagnosticAuthorityConfigurationReason,
        state: AuthorityManagerState = authorityManager.state.value,
    ) {
        authorityDiagnostics.recordConfiguration(reason, state)
    }

    /**
     * Best-effort process/foreground reconnect for an already trusted, selected
     * Wired provider. This never pairs, changes selection, or retries in a
     * loop; a failed attempt is retried only by a later explicit refresh or
     * disconnected -> trusted status transition.
     */
    fun scheduleWiredReconnect() {
        if (closed.get() || wiredReconnectInFlight.get()) return
        val manager = authorityManager.state.value
        val wired = manager.statuses[ElevatedAuthority.WIRED_ADB] ?: return
        val bridge = runCatching { wiredAuthority.status.value }.getOrNull() ?: return
        if (manager.selectedAuthority != ElevatedAuthority.WIRED_ADB ||
            !wired.isConfiguredForSelection ||
            wired.userIntent != AuthorityUserIntent.WIRED_ADB ||
            bridge.userIntent != WiredAdbUserIntent.ENABLED ||
            !bridge.trusted ||
            bridge.state == WiredAdbLifecycleState.REAUTH_REQUIRED ||
            bridge.connection == WiredAdbConnectionState.CONNECTED
        ) return
        if (!wiredReconnectInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (!closed.get()) runCatching { wiredAuthority.connect() }
            } finally {
                wiredReconnectInFlight.set(false)
            }
        }
    }

    private fun DangerousMode.toDiagnostic(): DiagnosticDangerousModePolicy = when (this) {
        DangerousMode.DISABLED -> DiagnosticDangerousModePolicy.DISABLED
        DangerousMode.ENABLED_CONFIRM_HIGH_RISK -> DiagnosticDangerousModePolicy.CONFIRM_HIGH_RISK
        DangerousMode.ENABLED_AUTONOMOUS -> DiagnosticDangerousModePolicy.AUTONOMOUS
    }

    /** Stable, non-sensitive status for a workspace row exposed to UI callers. */
}
