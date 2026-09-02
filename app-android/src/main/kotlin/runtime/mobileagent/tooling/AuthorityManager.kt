// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.AuthorityPreferences
import runtime.mobileagent.domain.AuthorityUserIntent
import runtime.mobileagent.skills.tooling.Availability
import runtime.mobileagent.skills.tooling.AuthoritySelection
import runtime.mobileagent.skills.tooling.AuthorityState
import runtime.mobileagent.skills.tooling.Connection
import runtime.mobileagent.skills.tooling.ElevatedAuthority
import runtime.mobileagent.skills.tooling.PlatformGrant

/**
 * Durable selection and user intent are kept separate from live grant,
 * availability, and connection facts.  Binder death and USB loss only update
 * the in-memory [AuthorityState]; they never write a revocation or select a
 * fallback provider.
 */
class AuthorityManager(
    private val store: AuthorityStateStore = InMemoryAuthorityStateStore(),
) {
    private val lock = Any()
    private val _state: MutableStateFlow<AuthorityManagerState>
    val state: StateFlow<AuthorityManagerState>

    private val _selection: MutableStateFlow<AuthoritySelection>
    val selection: StateFlow<AuthoritySelection>

    init {
        val persisted = normalize(store.load())
        val initial = persisted.toManagerState(previous = null)
        _state = MutableStateFlow(initial)
        state = _state.asStateFlow()
        _selection = MutableStateFlow(initial.toSelection())
        selection = _selection.asStateFlow()
    }

    /** Persist an explicit provider selection; null means no elevated provider. */
    fun selectAuthority(authority: ElevatedAuthority?): Boolean = synchronized(lock) {
        val current = _state.value
        val next = current.toPersistent().copy(
            selectedAuthority = authority ?: Authority.NONE,
        )
        persistUserChoice(current.persistenceRevision, next)
    }

    /** Persist user intent only.  This is never inferred from connection state. */
    fun setUserIntent(authority: ElevatedAuthority, enabled: Boolean): Boolean = synchronized(lock) {
        require(authority != Authority.NONE) { "NONE cannot have provider intent" }
        val current = _state.value
        val preferences = current.toPersistent().preferences.toMutableMap()
        val previous = preferences[authority] ?: AuthorityPreferences(authority = authority)
        preferences[authority] = previous.copy(
            authority = authority,
            userIntentEnabled = enabled,
            explicitlyConfigured = previous.explicitlyConfigured,
        )
        persistUserChoice(current.persistenceRevision, current.toPersistent().copy(preferences = preferences))
    }

    /** Persist provider setup/configuration; this is not a connection probe. */
    fun setConfigured(authority: ElevatedAuthority, configured: Boolean): Boolean = synchronized(lock) {
        require(authority != Authority.NONE) { "NONE cannot be configured" }
        val current = _state.value
        val preferences = current.toPersistent().preferences.toMutableMap()
        val previous = preferences[authority] ?: AuthorityPreferences(authority = authority)
        preferences[authority] = previous.copy(
            authority = authority,
            explicitlyConfigured = configured,
        )
        persistUserChoice(current.persistenceRevision, current.toPersistent().copy(preferences = preferences))
    }

    /** Current platform grant; grant revocation is distinct from connection loss. */
    fun updatePlatformGrant(authority: ElevatedAuthority, grant: PlatformGrant) = updateRuntime(authority) {
        it.copy(grant = grant)
    }

    /** Current provider availability; no persistent state is changed. */
    fun updateAvailability(authority: ElevatedAuthority, availability: Availability) = updateRuntime(authority) {
        it.copy(availability = availability)
    }

    /** Current Binder/USB connection; no persistent state is changed. */
    fun updateConnection(
        authority: ElevatedAuthority,
        connection: Connection,
        identity: String? = null,
    ) = updateRuntime(authority) {
        it.copy(connection = connection, identity = identity)
    }

    fun onBinderDisconnected() {
        updateAvailability(ElevatedAuthority.SHIZUKU, Availability.TEMPORARILY_UNAVAILABLE)
        updateConnection(ElevatedAuthority.SHIZUKU, Connection.DISCONNECTED)
    }

    fun onUsbDisconnected() {
        updateAvailability(ElevatedAuthority.WIRED_ADB, Availability.TEMPORARILY_UNAVAILABLE)
        updateConnection(ElevatedAuthority.WIRED_ADB, Connection.DISCONNECTED)
    }

    fun onBinderConnected(identity: String? = null) = updateConnection(
        ElevatedAuthority.SHIZUKU,
        Connection.CONNECTED,
        identity,
    )

    fun onUsbConnected(identity: String? = null) = updateConnection(
        ElevatedAuthority.WIRED_ADB,
        Connection.CONNECTED,
        identity,
    )

    /** Lifecycle hooks intentionally preserve durable policy and live grant facts. */
    fun onTaskEnded() = Unit
    fun onSessionEnded() = Unit
    fun onBackgrounded() = Unit
    fun onProcessRestarted() = Unit

    fun selectedAuthorityForExposure(): ElevatedAuthority? = _state.value.selectedAuthority

    fun selectedAuthorityForExecution(): ElevatedAuthority? = _state.value.selectedAuthority

    fun selectedStatus(): AuthorityState? = _state.value.selectedAuthority?.let(_state.value.statuses::get)

    fun selectedAuthorityState(): AuthorityState? = selectedStatus()

    /**
     * Resolve exactly the selected authority.  A missing selected backend is a
     * temporary-unavailable result, never a reason to scan another provider.
     */
    fun <T> withSelectedBackend(backends: Map<ElevatedAuthority, T>): Result<T> {
        val selected = _state.value.selectedAuthority
            ?: return Result.failure(IllegalStateException("AUTHORITY_PROVIDER_NOT_SELECTED"))
        return backends[selected]?.let(Result.Companion::success)
            ?: Result.failure(IllegalStateException("AUTHORITY_TEMPORARILY_UNAVAILABLE"))
    }

    private fun updateRuntime(authority: ElevatedAuthority, update: (AuthorityState) -> AuthorityState) {
        require(authority != Authority.NONE) { "NONE is not a provider" }
        synchronized(lock) {
            val current = _state.value
            val existing = current.statuses[authority] ?: defaultRuntimeState(authority)
            val next = current.copy(
                statuses = current.statuses + (authority to update(existing).copy(revision = existing.revision + 1L)),
            )
            publish(next)
        }
    }

    private fun persistUserChoice(expectedRevision: Long, candidate: AuthorityPersistentState): Boolean {
        val next = candidate.copy(revision = expectedRevision + 1L)
        if (!store.compareAndSet(expectedRevision, next)) {
            val reloaded = normalize(store.load())
            publish(reloaded.toManagerState(_state.value))
            return false
        }
        publish(next.toManagerState(_state.value))
        return true
    }

    private fun publish(next: AuthorityManagerState) {
        _state.value = next
        _selection.value = next.toSelection()
    }

    private fun AuthorityManagerState.toPersistent(): AuthorityPersistentState = AuthorityPersistentState(
        revision = persistenceRevision,
        selectedAuthority = selectedAuthority ?: Authority.NONE,
        preferences = statuses.mapValues { (authority, status) ->
            AuthorityPreferences(
                authority = authority,
                userIntentEnabled = status.userIntent == AuthorityState.intentFor(authority),
                explicitlyConfigured = status.configured,
            )
        },
    )

    private fun AuthorityPersistentState.toManagerState(previous: AuthorityManagerState?): AuthorityManagerState {
        val oldStatuses = previous?.statuses.orEmpty()
        val statuses = ElevatedAuthority.entries.associateWith { authority ->
            val old = oldStatuses[authority]
            val preferences = this.preferences[authority]
            val configured = preferences?.explicitlyConfigured == true
            AuthorityState(
                authority = authority,
                userIntent = if (preferences?.userIntentEnabled == true) AuthorityState.intentFor(authority) else AuthorityUserIntent.NONE,
                // The durable configured marker is written only after an
                // explicit, successful authorization/trust flow.  Rehydrate
                // it as the last confirmed grant after process recreation;
                // an offline Binder/USB/Wi-Fi probe is not a revocation.
                grant = old?.grant ?: if (configured) PlatformGrant.GRANTED else PlatformGrant.UNKNOWN,
                availability = old?.availability
                    ?: if (configured) Availability.TEMPORARILY_UNAVAILABLE else Availability.UNSUPPORTED,
                connection = old?.connection ?: Connection.DISCONNECTED,
                configured = configured,
                revision = old?.revision ?: 1L,
                identity = old?.identity,
            )
        }
        return AuthorityManagerState(
            persistenceRevision = revision,
            selectedAuthority = selectedAuthority.takeUnless { it == Authority.NONE },
            statuses = statuses,
        )
    }

    private fun AuthorityManagerState.toSelection(): AuthoritySelection = AuthoritySelection(
        selected = selectedAuthority,
        states = statuses,
    )

    private fun normalize(value: AuthorityPersistentState): AuthorityPersistentState {
        val preferences = ElevatedAuthority.entries.associateWith { authority ->
            val existing = value.preferences[authority]
            (existing ?: AuthorityPreferences(authority = authority)).copy(authority = authority)
        }
        return value.copy(
            revision = value.revision.coerceAtLeast(0L),
            selectedAuthority = value.selectedAuthority.takeIf { it in Authority.entries } ?: Authority.NONE,
            preferences = preferences,
        )
    }

    private fun defaultRuntimeState(authority: ElevatedAuthority): AuthorityState = AuthorityState(authority = authority)
}
/** Adapter aggregate; authority enums and lifecycle facts remain shared-owned. */
data class AuthorityManagerState(
    val persistenceRevision: Long,
    val selectedAuthority: ElevatedAuthority?,
    val statuses: Map<ElevatedAuthority, AuthorityState>,
) {
    fun status(authority: ElevatedAuthority): AuthorityState = statuses.getValue(authority)
    fun selectedIsConfigured(): Boolean = selectedAuthority?.let { statuses[it]?.isConfiguredForSelection == true } == true
    fun selectedIsReady(): Boolean = selectedAuthority?.let { statuses[it]?.isReady == true } == true
}
