// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.privileged.RootShizukuLiveFacts
import runtime.mobileagent.shizuku.ShizukuAuthorityState

/**
 * F13 Root permission entry: decisions come from *fresh* AIDL facts, so an SDK-cached GRANTED that
 * the platform already revoked still allows the explicit Root request. No callback starts the Root
 * service.
 */
class RootPermissionEntryTest {
    private fun facts(
        binder: Boolean = true,
        permission: Boolean = false,
        uid: Int? = 0,
        version: Int? = 13,
        preV11: Boolean = false,
    ) = RootShizukuLiveFacts(
        binderAlive = binder,
        preV11 = preV11,
        permissionGranted = permission,
        serverUid = uid,
        serverVersion = version,
    )

    private fun state(permission: Boolean, ready: Boolean) = ShizukuAuthorityState(
        installedHint = true,
        binderAlive = true,
        permissionGranted = permission,
        apiVersion = 13,
        serverUid = if (permission) 0 else null,
        userServiceAlive = false,
        ready = ready,
        errorCode = if (permission) null else "PERMISSION_REQUIRED",
    )

    private class Recorder {
        val requests = AtomicInteger()
        val refreshes = AtomicInteger()
    }

    @Test
    fun freshDenialStillIssuesTheExplicitRootRequestEvenIfTheSdkCacheSaysGranted() {
        val recorder = Recorder()
        // The SDK/bridge cache may still say granted; the fresh observation says revoked.
        RootPermissionEntry.request(
            sample = { facts(permission = false) },
            refresh = { recorder.refreshes.incrementAndGet() },
            requester = { code ->
                recorder.requests.incrementAndGet()
                assertEquals(RootPermissionEntry.ROOT_PERMISSION_REQUEST_CODE, code)
                true
            },
        )
        assertEquals(1, recorder.requests.get(), "a fresh denial must still allow the explicit request")
        assertEquals(0, recorder.refreshes.get())
    }

    @Test
    fun grantedFreshFactsOnlyRefreshAndNeverRequest() {
        val recorder = Recorder()
        RootPermissionEntry.request(
            sample = { facts(permission = true) },
            refresh = { recorder.refreshes.incrementAndGet() },
            requester = { recorder.requests.incrementAndGet(); true },
        )
        assertEquals(0, recorder.requests.get())
        assertEquals(1, recorder.refreshes.get())
    }

    @Test
    fun unknownFreshFactsFailClosed() {
        val recorder = Recorder()
        assertThrows(IllegalStateException::class.java) {
            RootPermissionEntry.request(
                sample = { null },
                refresh = { recorder.refreshes.incrementAndGet() },
                requester = { recorder.requests.incrementAndGet(); true },
            )
        }
        assertEquals(0, recorder.requests.get())
    }

    @Test
    fun shellUidServerIsReportedAsStartShizukuAsRootNotAsMissingPermission() {
        val recorder = Recorder()
        val failure = assertThrows(IllegalStateException::class.java) {
            RootPermissionEntry.request(
                sample = { facts(permission = false, uid = 2000) },
                refresh = { recorder.refreshes.incrementAndGet() },
                requester = { recorder.requests.incrementAndGet(); true },
            )
        }
        assertTrue(failure.message.orEmpty().contains("Root"), "must guide the user to start Shizuku as Root")
        assertEquals(0, recorder.requests.get())
    }

    @Test
    fun platformReadyRequiresLiveUidZeroGrantFromFreshFacts() {
        assertTrue(RootPermissionEntry.platformReady(facts(permission = true, uid = 0)))
        assertFalse(RootPermissionEntry.platformReady(facts(permission = false, uid = 0)))
        assertFalse(RootPermissionEntry.platformReady(facts(permission = true, uid = 2000)))
        assertFalse(RootPermissionEntry.platformReady(facts(permission = true, uid = 0, version = null)))
        assertFalse(RootPermissionEntry.platformReady(facts(binder = false)))
        assertFalse(RootPermissionEntry.platformReady(null))
    }

    @Test
    fun entryNeverStartsARootServiceCallback() {
        // The entry exposes no Root-acquire hook at all: a permission callback can only refresh.
        val invoked = AtomicBoolean(false)
        RootPermissionEntry.request(
            sample = { facts(permission = true) },
            refresh = { invoked.set(true) },
            requester = { false },
        )
        assertTrue(invoked.get())
    }

    @Test
    fun resolveStateUnknownFailsClosedInsteadOfPreservingStaleAllow() {
        val staleAllowed = state(permission = true, ready = true)
        val unknown = RootPermissionEntry.resolveState(staleAllowed) { null }
        assertEquals(false, unknown?.permissionGranted)
        assertEquals(false, unknown?.ready)
        assertNull(RootPermissionEntry.resolveState(null) { null })
    }

    @Test
    fun resolveStateFreshDenyClearsReadyAndFreshAllowKeepsNormalState() {
        val staleAllowed = state(permission = true, ready = true)
        val denied = RootPermissionEntry.resolveState(staleAllowed) {
            facts(permission = false, uid = 0)
        }
        assertEquals(false, denied?.permissionGranted)
        assertEquals(false, denied?.ready)

        val allowed = RootPermissionEntry.resolveState(staleAllowed) {
            facts(permission = true, uid = 0)
        }
        assertEquals(true, allowed?.permissionGranted)
        assertEquals(0, allowed?.serverUid)
        assertEquals(true, allowed?.ready)
    }

    @Test
    fun resolveStateFreshUid2000FactsRemainCommonShizukuCompatible() {
        val resolved = RootPermissionEntry.resolveState(state(permission = true, ready = true)) {
            facts(permission = true, uid = 2000)
        }
        assertEquals(true, resolved?.permissionGranted)
        assertEquals(2000, resolved?.serverUid)
        assertEquals(true, resolved?.ready)
    }
}
