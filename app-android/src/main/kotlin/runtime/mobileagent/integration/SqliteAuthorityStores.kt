// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.AuthorityPreferencesRepository
import runtime.mobileagent.tooling.AuthorityPersistentState
import runtime.mobileagent.tooling.AuthorityStateStore
import runtime.mobileagent.tooling.DangerousModePersistentState
import runtime.mobileagent.tooling.DangerousModeStateStore

/** SQLite-backed adapter for the existing AuthorityManager store contract. */
internal class SqliteAuthorityStateStore(
    private val policy: AuthorityPolicyRepository,
    private val preferences: AuthorityPreferencesRepository,
) : AuthorityStateStore {
    override fun load(): AuthorityPersistentState {
        val current = policy.getPolicy()
        return AuthorityPersistentState(
            revision = current.policyVersion,
            selectedAuthority = current.selectedAuthority,
            preferences = policy.listPreferences().associateBy { it.authority },
        )
    }

    override fun compareAndSet(expectedRevision: Long, next: AuthorityPersistentState): Boolean {
        if (!policy.compareAndSet(expectedRevision, next.selectedAuthority, policy.getPolicy().dangerousMode)) return false
        next.preferences.values.forEach { preferences.save(it) }
        return true
    }
}

/** SQLite-backed adapter for the existing DangerousModeManager contract. */
internal class SqliteDangerousModeStateStore(
    private val policy: AuthorityPolicyRepository,
) : DangerousModeStateStore {
    override fun load(): DangerousModePersistentState {
        val current = policy.getPolicy()
        return DangerousModePersistentState(current.policyVersion, current)
    }

    override fun compareAndSet(expectedRevision: Long, next: DangerousModePersistentState): Boolean =
        policy.compareAndSet(expectedRevision, next.policy.selectedAuthority, next.policy.dangerousMode)
}
