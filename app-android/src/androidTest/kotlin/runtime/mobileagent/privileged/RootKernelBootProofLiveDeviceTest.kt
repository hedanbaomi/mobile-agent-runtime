// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.MobileAgentApp
import runtime.mobileagent.data.RootAuthorityRepository
import runtime.mobileagent.data.RootReceiptRecoveryCoordinator
import runtime.mobileagent.data.RootPendingDrainRepository
import runtime.mobileagent.data.RootPendingDrainLoad
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RootKernelBootIdentity
import runtime.mobileagent.integration.RootPermissionEntry
import runtime.mobileagent.shizuku.ShizukuAuthorityState
import runtime.mobileagent.security.AndroidAppInstanceIdStoreFactory

/**
 * LIVE device proof for the authenticated UID0 kernel boot identity.
 *
 * This is **not** the synthetic acquisition-fence harness (`RootKernelBootProbeDeviceTest`, which
 * injects `directTransportFactory`): no transport factory, no fake UID and no fake app identity are
 * supplied here. `AndroidRootSessionConnector(context)` runs its default path, which resolves the
 * pinned `su` provider and launches this app's own `RootPrivilegedProcessMain`; the boot id is only
 * accepted from that identity-verified handshake. `appInstanceId` comes from
 * `AndroidAppInstanceIdStoreFactory`, and the asserted UID / package / signing digest are the ones
 * the production verifier bound into the handshake.
 *
 * Prerequisites (the device owner prepares them; this test never grants, resets or revokes root):
 * - the `su` manager already grants this package and identifies as a supported MagiskSU, so the
 *   foreground probe can complete (otherwise `RootDirectProviderProbe` yields
 *   `unsupported_root_provider` and the test skips as `LIVE_ROOT_UNAVAILABLE`);
 * - a debug/Review-installed build on a real rooted device or a rooted emulator.
 *
 * Phase matrix (`-e phase=...`) — one instrumentation invocation per phase, because the device must
 * be rebooted between `record` and `afterReboot`:
 * - `record`     : probe and persist the baseline digest in the dedicated test prefs;
 * - `sameBoot`   : probe again on the same boot and require an identical digest;
 * - `afterReboot`: probe after the device was rebooted and require a different digest.
 *
 * The baseline is stored as SHA-256, and only counts/booleans/coarse enums are ever logged: the raw
 * kernel boot UUID, the app instance id, credentials and the connector's raw failure text (which can
 * embed device paths) never reach logcat, assertions, test output or the prefs file.
 */
@RunWith(AndroidJUnit4::class)
class RootKernelBootProofLiveDeviceTest {
    @Test
    fun liveProbeReturnsAuthenticatedUid0BootIdentityAndLeaksNoTransport() {
        val context = context()
        val verified = requireObservation(context, "identity")
        assertTrue(
            "The handshake boot id must be the canonical Linux boot_id shape",
            RootKernelBootIdentity.isValid(verified.kernelBootId),
        )
        val identity = verified.identity
        assertEquals(Authority.ROOT_DIRECT, identity.authority)
        assertEquals(PrivilegeTier.ROOT, identity.privilegeTier)
        assertEquals("The probe must prove a real UID0 service", 0, identity.serviceUid)
        assertEquals("The handshake must bind this app UID", Process.myUid(), identity.appUid)
        assertEquals("The handshake must bind this package", context.packageName, identity.packageName)
        assertEquals(
            "The probe must use the real app identity store, not an injected id",
            appInstanceId(context),
            identity.appInstanceId,
        )
        assertEquals(PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION, identity.protocolVersion)
        assertTrue(
            "A verified handshake must carry the real signing digest",
            identity.signingSha256.matches(HEX_SHA256),
        )
        assertTrue(identity.serviceInstanceId.isNotBlank())
        assertTrue(identity.sessionId.isNotBlank())
        Log.i(TAG, "phase=probe uid0=true bootShape=true leakedTransport=false pendingAttempt=false")
    }

    @Test
    fun ordinaryAcquisitionAndANewDaemonInstanceKeepTheSameKernelBootId() {
        val context = context()
        val first = requireObservation(context, "first")
        val firstBoot = first.kernelBootId

        // A second probe starts its own UID0 daemon instance. The kernel boot id must not move,
        // while the service identity must, or the comparison would prove nothing.
        val secondResult = probe(context)
        assertNotNull("A second foreground probe must also complete the UID0 handshake", secondResult.observation)
        val second = requireNotNull(secondResult.observation)
        assertEquals(
            "A fresh daemon instance must report the same kernel boot id",
            digest(firstBoot),
            digest(second.kernelBootId),
        )
        assertNotEquals(
            "The second probe must come from a new daemon instance for this comparison to mean anything",
            first.identity.serviceInstanceId,
            second.identity.serviceInstanceId,
        )

        // The ordinary foreground acquisition path must bind the same kernel identity as the probe.
        val connector = AndroidRootSessionConnector(context)
        try {
            val acquired = connector.tryAcquireSession(
                Authority.ROOT_DIRECT,
                currentIdentityEpoch(context),
                appInstanceId(context),
                null,
            )
            assertNotNull("The ordinary acquisition path must also bind the kernel identity", acquired)
            assertEquals(
                "An ordinary session acquisition must report the same kernel boot id",
                digest(firstBoot),
                digest(requireNotNull(requireNotNull(acquired).kernelBootId)),
            )
            assertSame(
                "The ordinary connector owns its transport until the coordinator completes or aborts it",
                acquired.transport,
                connector.activeTransport(Authority.ROOT_DIRECT),
            )
        } finally {
            connector.close()
        }
        Log.i(TAG, "phase=sameBoot newInstance=true sameBootId=true ordinaryPathSame=true")
    }

    @Test
    fun phaseComparisonRecordsOrComparesTheKernelBootBaseline() {
        val phase = InstrumentationRegistry.getArguments().getString(ARG_PHASE) ?: PHASE_RECORD
        val context = context()
        val authority = Authority.valueOf(InstrumentationRegistry.getArguments().getString("bootAuthority") ?: "ROOT_DIRECT")
        require(authority == Authority.ROOT_DIRECT || authority == Authority.SHIZUKU_ROOT)
        val store = prefs(context, authority)
        val observation = requireObservation(context, "phase", authority)
        val current = digest(observation.kernelBootId)
        val db = (context.applicationContext as MobileAgentApp).container.db
        val roots = RootAuthorityRepository(db)

        when (phase) {
            PHASE_RECORD -> {
                assertTrue(
                    "The record phase must persist the baseline digest",
                    store.edit().putString(KEY_DIGEST, current).commit(),
                )
                assertEquals("The recorded baseline must read back identically", current, store.getString(KEY_DIGEST, null))
                // Inject a durable uncertain-drain fixture. The identity and kernel proof are from
                // the real authenticated helper; no background process is intentionally leaked.
                val state = roots.get(authority)
                assertTrue("Run the real Root channel acceptance before this recovery fixture", state.consent.enabled)
                assertTrue(RootReceiptRecoveryCoordinator(db).recordSessionBoot(authority, observation.kernelBootId))
                assertNotNull(roots.markDegraded(authority, state.revision, state.identityEpoch))
                RootPendingDrainRepository(db).record(observation.identity, null, state.identityEpoch)
                Log.i(TAG, "phase=record stored=true pendingFixture=true")
            }

            PHASE_SAME_BOOT -> {
                assertEquals(
                    "The same boot must yield the identical kernel boot id",
                    assumeBaseline(store),
                    current,
                )
                RootRuntimeCoordinator(context, db).use { coordinator ->
                    assertNull(coordinator.acknowledgeDegradedRecovery(authority, true, shizukuState = shizukuState(authority)))
                    assertTrue(roots.get(authority).degraded)
                    assertTrue(RootPendingDrainRepository(db).load(authority) is RootPendingDrainLoad.Present)
                }
                Log.i(TAG, "phase=sameBoot equal=true recoveryRefused=true")
            }

            PHASE_AFTER_REBOOT -> {
                assertNotEquals(
                    "A reboot must yield a different kernel boot id",
                    assumeBaseline(store),
                    current,
                )
                val before = roots.get(authority)
                RootRuntimeCoordinator(context, db).use { coordinator ->
                    assertNull(coordinator.acknowledgeDegradedRecovery(authority, false))
                    val recovered = coordinator.acknowledgeDegradedRecovery(authority, true, shizukuState = shizukuState(authority))
                    assertNotNull("An authenticated different kernel boot must recover the fixture", recovered)
                    assertFalse(requireNotNull(recovered).degraded)
                    assertTrue(recovered.identityEpoch > before.identityEpoch)
                    assertTrue(RootPendingDrainRepository(db).load(authority) is RootPendingDrainLoad.Missing)
                    val attached = coordinator.currentExecutionStamp(authority)
                    assertNotNull("The authenticated recovery acquisition must be reused as the live session", attached)
                    assertEquals(recovered.identityEpoch, requireNotNull(attached).identity.identityEpoch)
                    assertEquals(authority, attached.identity.authority)
                }
                Log.i(TAG, "phase=afterReboot different=true recoveryCommitted=true recoverySessionAttached=true")
            }

            else -> throw AssertionError("Unknown -e $ARG_PHASE=$phase; use record|sameBoot|afterReboot")
        }
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext<Context>().also {
        (it as MobileAgentApp).ensureHostInitialized()
    }

    /** Real production identity seam: same store the runtime uses for Root AAD/instance binding. */
    private fun appInstanceId(context: Context): String =
        AndroidAppInstanceIdStoreFactory.create(context).loadOrCreateAppInstanceId()

    private fun currentIdentityEpoch(context: Context, authority: Authority = Authority.ROOT_DIRECT): Long {
        val db = (context.applicationContext as MobileAgentApp).container.db
        return maxOf(1L, RootAuthorityRepository(db).get(authority).identityEpoch)
    }

    /** Observation plus a coarse failure class; the raw reason text is never carried out. */
    private class ProbeResult(val observation: RootBootObservation?, val reasonClass: String)

    /**
     * One foreground probe on a fresh connector, i.e. a fresh UID0 daemon instance. The transport
     * and attempt invariants are asserted before [AndroidRootSessionConnector.close] so a leak is
     * reported rather than masked by the close.
     */
    private fun probe(context: Context, authority: Authority = Authority.ROOT_DIRECT): ProbeResult {
        val connector = AndroidRootSessionConnector(context)
        try {
            val observation = connector.probeBootIdentity(
                authority,
                currentIdentityEpoch(context, authority),
                appInstanceId(context),
                shizukuState(authority),
            )
            assertNull(
                "A boot probe must never publish a dispatchable Root transport",
                connector.activeTransport(authority),
            )
            assertFalse(
                "A boot probe must never leave a pending acquisition behind",
                connector.cancelPendingAcquisition(authority),
            )
            return ProbeResult(observation, reasonClass(connector.lastAcquireFailureReason))
        } finally {
            connector.close()
        }
    }

    private fun requireObservation(context: Context, label: String, authority: Authority = Authority.ROOT_DIRECT): RootBootObservation {
        val result = probe(context, authority)
        val message = "$ROOT_UNAVAILABLE_MESSAGE reason=${result.reasonClass} stage=$label"
        if (InstrumentationRegistry.getArguments().getString("requireLiveRoot") == "true") {
            assertNotNull(message, result.observation)
        } else {
            assumeTrue(message, result.observation != null)
        }
        return requireNotNull(result.observation) { "$label probe returned no verified observation" }
    }

    /**
     * Coarse classification of `lastAcquireFailureReason`. The raw text may embed an exception
     * message with a device path, so it is deliberately mapped to a closed set of tags.
     */
    private fun reasonClass(reason: String?): String = when {
        reason == null -> "none"
        reason == "unsupported_root_provider" -> "unsupported_root_provider"
        reason == "direct_transport_null" -> "direct_transport_null"
        reason == "acquisition_cancelled" -> "acquisition_cancelled"
        reason.startsWith("bootstrap") -> "bootstrap"
        reason.startsWith("host_verify_denied") -> "host_verify_denied"
        reason.startsWith("shizuku") -> "shizuku"
        else -> "other"
    }

    private fun prefs(context: Context, authority: Authority): SharedPreferences =
        context.getSharedPreferences("$PREFS_NAME.${authority.name}", Context.MODE_PRIVATE)

    private fun shizukuState(authority: Authority): ShizukuAuthorityState? {
        if (authority == Authority.ROOT_DIRECT) return null
        repeat(30) {
            val state = RootPermissionEntry.resolveState(null) { RootShizukuLiveSampler.sample(1_000L) }
            if (state?.binderAlive == true && state.permissionGranted && state.serverUid == 0) return state
            Thread.sleep(100L)
        }
        error("A real authorized UID0 Shizuku server is required")
    }

    private fun assumeBaseline(store: SharedPreferences): String {
        val baseline = store.getString(KEY_DIGEST, null)
        assumeTrue(
            "No recorded baseline: run -e $ARG_PHASE=$PHASE_RECORD first",
            baseline != null,
        )
        return requireNotNull(baseline)
    }

    /** SHA-256 hex of the boot id, so the raw kernel UUID is never persisted or printed. */
    private fun digest(bootId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(bootId.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val TAG = "RootBootProofLive"
        const val ARG_PHASE = "phase"
        const val PHASE_RECORD = "record"
        const val PHASE_SAME_BOOT = "sameBoot"
        const val PHASE_AFTER_REBOOT = "afterReboot"
        const val PREFS_NAME = "runtime.mobileagent.root.bootproof.live"
        const val KEY_DIGEST = "boot_proof_digest_sha256"
        val HEX_SHA256 = Regex("[0-9a-f]{64}")
        const val ROOT_UNAVAILABLE_MESSAGE =
            "LIVE_ROOT_UNAVAILABLE: the su manager must already grant this package and identify " +
                "as a supported MagiskSU; this test never requests, changes or revokes root"
    }
}
