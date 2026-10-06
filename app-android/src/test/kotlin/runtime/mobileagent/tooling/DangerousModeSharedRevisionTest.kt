// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode

class DangerousModeSharedRevisionTest {
    private val build = DangerousBuildPolicy.fromBuildFlags(isDebuggable = false, controlPlaneAllowed = true)

    private fun chooseWired(store: InMemoryDangerousModeStateStore) {
        val state = store.load()
        assertTrue(store.compareAndSet(state.revision, state.copy(revision = state.revision + 1,
            policy = state.policy.copy(selectedAuthority = Authority.WIRED_ADB, policyVersion = state.revision + 1))))
    }

    @Test fun `foreground enable uses fresh shared revision after selecting authority`() {
        val store = InMemoryDangerousModeStateStore()
        val manager = DangerousModeManager(store, build)
        chooseWired(store)
        val result = manager.setPolicy(DangerousMode.ENABLED_AUTONOMOUS)
        assertTrue(result.accepted)
        assertEquals(Authority.WIRED_ADB, store.load().policy.selectedAuthority)
        assertEquals(DangerousMode.ENABLED_AUTONOMOUS, store.load().policy.dangerousMode)
        assertEquals(2L, store.load().revision)
    }

    @Test fun `explicit stale revision is rejected without overwriting newer authority choice`() {
        val store = InMemoryDangerousModeStateStore()
        val manager = DangerousModeManager(store, build)
        chooseWired(store)
        val result = manager.setPolicy(DangerousMode.ENABLED_AUTONOMOUS, expectedRevision = 0)
        assertFalse(result.accepted)
        assertEquals("CAS_CONFLICT", result.reason)
        assertEquals(Authority.WIRED_ADB, store.load().policy.selectedAuthority)
        assertEquals(DangerousMode.DISABLED, store.load().policy.dangerousMode)
        assertEquals(1L, result.state.revision)
    }

    @Test fun `fresh foreground load cannot overwrite a concurrent policy change`() {
        val delegate = InMemoryDangerousModeStateStore()
        val store = object : DangerousModeStateStore {
            var reads = 0
            override fun load(): DangerousModePersistentState {
                if (++reads == 3) chooseWired(delegate)
                return delegate.load()
            }
            override fun compareAndSet(expectedRevision: Long, next: DangerousModePersistentState) =
                delegate.compareAndSet(expectedRevision, next)
        }
        val result = DangerousModeManager(store, build).setPolicy(DangerousMode.ENABLED_AUTONOMOUS)
        assertFalse(result.accepted)
        assertEquals("CAS_CONFLICT", result.reason)
        assertEquals(Authority.WIRED_ADB, delegate.load().policy.selectedAuthority)
        assertEquals(DangerousMode.DISABLED, delegate.load().policy.dangerousMode)
    }
}
