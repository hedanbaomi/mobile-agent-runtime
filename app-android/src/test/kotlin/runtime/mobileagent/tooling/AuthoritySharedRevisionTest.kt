// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.AuthorityPreferences
import runtime.mobileagent.skills.tooling.Availability
import runtime.mobileagent.skills.tooling.Connection

class AuthoritySharedRevisionTest {
    @Test fun `intent action uses fresh shared revision and preserves other provider setup and live state`() {
        val store = InMemoryAuthorityStateStore()
        val manager = AuthorityManager(store)
        manager.updateAvailability(Authority.WIRED_ADB, Availability.READY)
        manager.updateConnection(Authority.WIRED_ADB, Connection.CONNECTED)
        assertTrue(store.compareAndSet(0, AuthorityPersistentState(revision = 1,
            selectedAuthority = Authority.WIRED_ADB, preferences = mapOf(
                Authority.SHIZUKU to AuthorityPreferences(Authority.SHIZUKU, userIntentEnabled = true, explicitlyConfigured = true)))))
        assertTrue(manager.setUserIntent(Authority.WIRED_ADB, true))
        assertTrue(store.load().preferences.getValue(Authority.SHIZUKU).explicitlyConfigured)
        assertTrue(store.load().preferences.getValue(Authority.WIRED_ADB).userIntentEnabled)
        assertEquals(Authority.WIRED_ADB, manager.state.value.selectedAuthority)
        assertEquals(Availability.READY, manager.state.value.statuses.getValue(Authority.WIRED_ADB).availability)
        assertEquals(Connection.CONNECTED, manager.state.value.statuses.getValue(Authority.WIRED_ADB).connection)
    }

    @Test fun `selection and configured actions use the latest durable aggregate`() {
        val store = InMemoryAuthorityStateStore()
        val manager = AuthorityManager(store)
        assertTrue(store.compareAndSet(0, AuthorityPersistentState(revision = 1,
            preferences = mapOf(Authority.WIRED_ADB to AuthorityPreferences(Authority.WIRED_ADB, userIntentEnabled = true)))))
        assertTrue(manager.selectAuthority(Authority.WIRED_ADB))
        assertTrue(store.compareAndSet(2, store.load().copy(revision = 3)))
        assertTrue(manager.setConfigured(Authority.WIRED_ADB, true))
        assertEquals(Authority.WIRED_ADB, store.load().selectedAuthority)
        assertTrue(store.load().preferences.getValue(Authority.WIRED_ADB).userIntentEnabled)
        assertTrue(store.load().preferences.getValue(Authority.WIRED_ADB).explicitlyConfigured)
    }

    @Test fun `concurrent update after fresh load still rejects without overwriting preference`() {
        val delegate = InMemoryAuthorityStateStore()
        val store = object : AuthorityStateStore {
            override fun load() = delegate.load()
            override fun compareAndSet(expectedRevision: Long, next: AuthorityPersistentState): Boolean {
                val current = delegate.load()
                assertTrue(delegate.compareAndSet(current.revision, current.copy(revision = current.revision + 1,
                    selectedAuthority = Authority.SHIZUKU)))
                return delegate.compareAndSet(expectedRevision, next)
            }
        }
        val manager = AuthorityManager(store)
        assertFalse(manager.selectAuthority(Authority.WIRED_ADB))
        assertEquals(Authority.SHIZUKU, manager.state.value.selectedAuthority)
        assertEquals(Authority.SHIZUKU, delegate.load().selectedAuthority)
    }
}
