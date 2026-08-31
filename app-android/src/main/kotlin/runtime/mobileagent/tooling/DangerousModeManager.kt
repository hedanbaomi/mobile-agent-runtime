// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import runtime.mobileagent.domain.AuthorityPolicy
import runtime.mobileagent.domain.DangerousMode

data class DangerousModeChange(
    val accepted: Boolean,
    val state: DangerousModeState,
    val reason: String? = null,
)

/**
 * Persistent Dangerous Mode policy with optimistic concurrency.  Only an
 * explicit successful CAS writes the store.  Lifecycle events and authority
 * disconnects are deliberately no-ops.  Build admission is fail-closed when
 * the generated variant flags were not explicitly injected.
 */
class DangerousModeManager(
    private val store: DangerousModeStateStore = InMemoryDangerousModeStateStore(),
    private val buildPolicy: DangerousBuildPolicy = DangerousBuildPolicy(),
) {
    private val lock = Any()
    private val _state: MutableStateFlow<DangerousModeState>
    val state: StateFlow<DangerousModeState>

    init {
        val persisted = normalize(store.load())
        _state = MutableStateFlow(effectiveState(persisted))
        state = _state.asStateFlow()
    }

    fun setPolicy(policy: DangerousMode): DangerousModeChange = synchronized(lock) {
        compareAndSetLocked(_state.value.revision, policy)
    }

    fun setPolicy(policy: DangerousMode, expectedRevision: Long): DangerousModeChange = synchronized(lock) {
        compareAndSetLocked(expectedRevision, policy)
    }

    fun compareAndSet(expectedRevision: Long, policy: DangerousMode): DangerousModeChange = synchronized(lock) {
        compareAndSetLocked(expectedRevision, policy)
    }

    /** Lifecycle hooks preserve the durable domain policy exactly. */
    fun onTaskEnded() = Unit
    fun onSessionEnded() = Unit
    fun onBackgrounded() = Unit
    fun onActivityRecreated() = Unit
    fun onProcessRestarted() = Unit
    fun onAuthorityDisconnected() = Unit

    fun isEnabled(): Boolean = state.value.policy != DangerousMode.DISABLED

    fun allowsAutonomousExecution(): Boolean = state.value.policy == DangerousMode.ENABLED_AUTONOMOUS

    /** Exposes the persisted domain policy for resolver snapshots. */
    fun policy(): DangerousMode = state.value.policy

    private fun compareAndSetLocked(expectedRevision: Long, policy: DangerousMode): DangerousModeChange {
        val current = _state.value
        if (expectedRevision != current.revision) {
            return DangerousModeChange(false, current, "CAS_CONFLICT")
        }
        if (policy != DangerousMode.DISABLED && !buildPolicy.permitsDangerousMode()) {
            return DangerousModeChange(false, current, "DANGEROUS_MODE_BUILD_DENIED")
        }

        val currentPolicy = store.load().policy
        val nextPersistent = DangerousModePersistentState(
            revision = current.revision + 1L,
            policy = currentPolicy.copy(
                dangerousMode = policy,
                policyVersion = current.revision + 1L,
            ),
        )
        if (!store.compareAndSet(current.revision, nextPersistent)) {
            val reloaded = normalize(store.load())
            val refreshed = effectiveState(reloaded)
            _state.value = refreshed
            return DangerousModeChange(false, refreshed, "CAS_CONFLICT")
        }
        val next = effectiveState(nextPersistent)
        _state.value = next
        return DangerousModeChange(true, next)
    }

    private fun effectiveState(persisted: DangerousModePersistentState): DangerousModeState = DangerousModeState(
        revision = persisted.revision,
        policy = if (persisted.policy.dangerousMode == DangerousMode.DISABLED || buildPolicy.permitsDangerousMode()) {
            persisted.policy.dangerousMode
        } else {
            DangerousMode.DISABLED
        },
    )

    private fun normalize(value: DangerousModePersistentState): DangerousModePersistentState {
        val policy = value.policy.copy(
            policyVersion = value.policy.policyVersion.coerceAtLeast(0L),
        )
        return value.copy(
            revision = value.revision.coerceAtLeast(0L),
            policy = policy,
        )
    }
}
