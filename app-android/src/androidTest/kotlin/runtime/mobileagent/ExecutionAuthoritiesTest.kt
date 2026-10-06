// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.AuthorityUserIntent
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.DesktopTrustStatus
import runtime.mobileagent.skills.tooling.Availability
import runtime.mobileagent.skills.tooling.Connection
import runtime.mobileagent.skills.tooling.PlatformGrant
import runtime.mobileagent.shizuku.ShizukuAuthorityState
import runtime.mobileagent.integration.safPersistableFlags
import runtime.mobileagent.integration.safRequestedFlags

@RunWith(AndroidJUnit4::class)
class ExecutionAuthoritiesTest {
    @Test
    fun failedResidentRevokePreservesConfiguredStateAndReportsFailure() {
        val app = ApplicationProvider.getApplicationContext<MobileAgentApp>()
        val fake = FakeSettingsAuthorityPort().apply { prepareFailedRevoke() }
        registerSettingsAuthorityPortProvider(app) { fake }
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        var viewModel: SettingsViewModel? = null
        try {
            instrumentation.runOnMainSync {
                viewModel = SettingsViewModel(app)
                viewModel!!.refreshAuthorities()
                viewModel!!.forgetWiredAdb()
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000L
            var finished = false
            while (!finished && android.os.SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { finished = viewModel!!.uiState(0).error != null }
                if (!finished) Thread.sleep(20)
            }
            assertTrue("Failed revoke must be visible", finished)
            instrumentation.runOnMainSync {
                val state = viewModel!!.uiState(0)
                assertTrue(state.wiredAdbAuthority.configured)
                assertTrue(state.error!!.contains("激活凭据已保留"))
            }
        } finally { registerSettingsAuthorityPortProvider(app, app.container) }
    }

    @Test
    fun defaultSnapshotIsFailClosedAndContainsOnlyPeerAuthorities() {
        val snapshot = SettingsAuthoritySnapshot()

        assertEquals(runtime.mobileagent.domain.Authority.NONE, snapshot.selectedAuthority)
        assertTrue(snapshot.appPrivateAvailable)
        assertEquals(runtime.mobileagent.domain.Authority.SHIZUKU, snapshot.shizuku.authority)
        assertEquals(runtime.mobileagent.domain.Authority.WIRED_ADB, snapshot.wiredAdb.authority)
        assertFalse(snapshot.shizuku.configured)
        assertFalse(snapshot.wiredAdb.configured)
        assertFalse(snapshot.dangerousModeBuildAllowed)
        assertFalse(snapshot.dangerousModeBuildKnown)
        assertEquals(DangerousMode.DISABLED, snapshot.durableDangerousMode)
    }

    @Test
    fun shizukuConnectionPreservesConnectingAndDegradedStates() {
        val connecting = shizukuProviderState(
            ShizukuAuthorityState(
                installedHint = true,
                binderAlive = true,
                permissionGranted = true,
                apiVersion = 13,
                serverUid = 2000,
                userServiceAlive = false,
                ready = false,
                errorCode = "USER_SERVICE_UNAVAILABLE",
            ),
        )
        val degraded = shizukuProviderState(
            ShizukuAuthorityState(
                installedHint = true,
                binderAlive = true,
                permissionGranted = true,
                apiVersion = 13,
                serverUid = 2000,
                userServiceAlive = true,
                ready = false,
                errorCode = "USER_SERVICE_HANDSHAKE_INVALID",
            ),
        )

        assertEquals(Connection.CONNECTING, connecting.connection)
        assertEquals(Connection.DEGRADED, degraded.connection)
    }

    @Test
    fun safFlagsUseActualReadOnlyProjectionAndLegacyDefaultRequestsBoth() {
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, safPersistableFlags(readGranted = true, writeGranted = false))
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            safPersistableFlags(readGranted = true, writeGranted = true),
        )
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
            safRequestedFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
        )
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            safRequestedFlags(0),
        )
    }

    @Test
    fun lifecycleFactsRemainSeparateFromUserIntentAndTrust() {
        val state = SettingsAuthorityProviderState(
            authority = runtime.mobileagent.domain.Authority.WIRED_ADB,
            userIntent = runtime.mobileagent.domain.AuthorityUserIntent.WIRED_ADB,
            platformGrant = runtime.mobileagent.skills.tooling.PlatformGrant.GRANTED,
            availability = runtime.mobileagent.skills.tooling.Availability.TEMPORARILY_UNAVAILABLE,
            connection = runtime.mobileagent.skills.tooling.Connection.DISCONNECTED,
            configured = true,
            trust = runtime.mobileagent.domain.DesktopTrustStatus.REAUTH_REQUIRED,
        )

        assertEquals(runtime.mobileagent.domain.AuthorityUserIntent.WIRED_ADB, state.userIntent)
        assertEquals(runtime.mobileagent.skills.tooling.PlatformGrant.GRANTED, state.platformGrant)
        assertEquals(runtime.mobileagent.skills.tooling.Availability.TEMPORARILY_UNAVAILABLE, state.availability)
        assertEquals(runtime.mobileagent.skills.tooling.Connection.DISCONNECTED, state.connection)
        assertEquals(runtime.mobileagent.domain.DesktopTrustStatus.REAUTH_REQUIRED, state.trust)
    }

    @Test
    fun pairingPromptAndUiProjectionNeverExposeTokenInToStringAndClearWipesIt() {
        val token = "abcdef0123456789".repeat(4)
        val prompt = SettingsWiredPairingPrompt(
            token = token,
            expiresAtEpochMs = System.currentTimeMillis() + 60_000L,
            remainingAttempts = 5,
        )

        assertEquals(token, prompt.tokenDisplay())
        assertFalse(prompt.toString().contains(token))
        assertFalse(
            runtime.mobileagent.feature.settings.WiredPairingUiState(hasToken = true)
                .toString().contains(token),
        )
        assertFalse(SettingsWiredPairingRequestResult(true, prompt).toString().contains(token))

        prompt.clear()
        assertTrue(prompt.isCleared())
        assertEquals("", prompt.tokenDisplay())
    }

    @Test
    fun settingsViewModelKeepsPairingEphemeralUntilExplicitCancel() {
        val app = ApplicationProvider.getApplicationContext<MobileAgentApp>()
        app.ensureHostInitialized()
        val fake = FakeSettingsAuthorityPort()
        // AppContainer has already registered the production provider. Replace
        // that provider for this test so resolution cannot silently fall back
        // to the real Wired bridge and mint a genuine foreground token.
        registerSettingsAuthorityPortProvider(app, SettingsAuthorityPortProvider { fake })
        try {
            val viewModel = SettingsViewModel(app)
            viewModel.requestWiredAdbPairing()
            val active = viewModel.uiState(noticeCount = 0).wiredPairing
            assertTrue(active.hasToken)
            assertNotNull(viewModel.wiredPairingToken())
            assertEquals(fake.token, viewModel.wiredPairingToken())
            assertEquals(5, active.remainingAttempts)
            assertEquals(listOf("intent:true", "select:WIRED_ADB", "pair"), fake.wiredCalls)
            assertEquals(Authority.WIRED_ADB.name, viewModel.uiState(0).selectedAuthority)
            assertTrue(viewModel.uiState(0).wiredAdbAuthority.userIntentEnabled)
            assertEquals(DangerousMode.DISABLED.name, viewModel.uiState(0).dangerousMode)

            viewModel.cancelWiredAdbPairing()
            val cleared = viewModel.uiState(noticeCount = 0).wiredPairing
            assertFalse(cleared.hasToken)
            assertNull(viewModel.wiredPairingToken())
            assertTrue(fake.cancelCalls > 0)
        } finally {
            registerSettingsAuthorityPortProvider(app, app.container)
        }
    }

    @Test
    fun wiredPairingStopsBeforeMintingTokenWhenSelectionFails() {
        val app = ApplicationProvider.getApplicationContext<MobileAgentApp>()
        app.ensureHostInitialized()
        val fake = FakeSettingsAuthorityPort().apply { failWiredSelection = true }
        registerSettingsAuthorityPortProvider(app, SettingsAuthorityPortProvider { fake })
        try {
            val viewModel = SettingsViewModel(app)
            viewModel.requestWiredAdbPairing()
            assertEquals(listOf("intent:true", "select:WIRED_ADB"), fake.wiredCalls)
            assertFalse(viewModel.uiState(0).wiredPairing.hasToken)
            assertNull(viewModel.wiredPairingToken())
            assertEquals(Authority.NONE.name, viewModel.uiState(0).selectedAuthority)
        } finally {
            registerSettingsAuthorityPortProvider(app, app.container)
        }
    }

    @Test
    fun enableShizukuRecordsIntentSelectionAndRequestsGrantInOrder() {
        val app = ApplicationProvider.getApplicationContext<MobileAgentApp>()
        app.ensureHostInitialized()
        val fake = FakeSettingsAuthorityPort()
        registerSettingsAuthorityPortProvider(app, SettingsAuthorityPortProvider { fake })
        try {
            val viewModel = SettingsViewModel(app)
            viewModel.enableShizuku()

            assertEquals(listOf("intent:true", "select:SHIZUKU", "request"), fake.shizukuCalls)
            assertEquals(Authority.SHIZUKU.name, viewModel.uiState(0).selectedAuthority)
            assertTrue(viewModel.uiState(0).shizukuAuthority.userIntentEnabled)
        } finally {
            registerSettingsAuthorityPortProvider(app, app.container)
        }
    }
}

/** A deterministic adapter fake used only by Settings authority seam tests. */
private class FakeSettingsAuthorityPort : SettingsAuthorityPort {
    val token = "0123456789abcdef".repeat(4)
    var requestReplaceExistingTrust = false
    var cancelCalls = 0
    val shizukuCalls = mutableListOf<String>()
    val wiredCalls = mutableListOf<String>()
    var failWiredSelection = false
    private var failRevoke = false
    private var current = SettingsAuthoritySnapshot(
        wiredAdb = SettingsAuthorityProviderState(
            authority = Authority.WIRED_ADB,
            userIntent = AuthorityUserIntent.WIRED_ADB,
            platformGrant = PlatformGrant.GRANTED,
            availability = Availability.READY,
            connection = Connection.DISCONNECTED,
            configured = false,
            trust = DesktopTrustStatus.FORGOTTEN,
        ),
    )

    override fun snapshot(): SettingsAuthoritySnapshot = current
    override fun refresh(): SettingsAuthoritySnapshot = current
    override fun selectAuthority(authority: Authority): SettingsAuthoritySnapshot {
        if (authority == Authority.WIRED_ADB) {
            wiredCalls += "select:${authority.name}"
            check(!failWiredSelection) { "AUTHORITY_POLICY_CHANGED" }
        } else shizukuCalls += "select:${authority.name}"
        current = current.copy(selectedAuthority = authority)
        return current
    }
    override fun setUserIntent(authority: Authority, enabled: Boolean): SettingsAuthoritySnapshot {
        if (authority == Authority.WIRED_ADB) wiredCalls += "intent:$enabled"
        else shizukuCalls += "intent:$enabled"
        if (authority == Authority.SHIZUKU) {
            current = current.copy(
                shizuku = current.shizuku.copy(
                    userIntent = if (enabled) AuthorityUserIntent.SHIZUKU else AuthorityUserIntent.NONE,
                ),
            )
        } else if (authority == Authority.WIRED_ADB) {
            current = current.copy(wiredAdb = current.wiredAdb.copy(
                userIntent = if (enabled) AuthorityUserIntent.WIRED_ADB else AuthorityUserIntent.NONE,
            ))
        }
        return current
    }
    override fun requestShizukuPermission(): SettingsAuthoritySnapshot {
        shizukuCalls += "request"
        return current
    }
    override fun openShizuku(): Boolean = false
    override fun reauthorizeWiredAdb(): SettingsAuthoritySnapshot = current
    override fun forgetWiredAdb(): SettingsAuthoritySnapshot = current
    fun prepareFailedRevoke() {
        failRevoke = true
        current = current.copy(selectedAuthority = Authority.WIRED_ADB,
            wiredAdb = current.wiredAdb.copy(configured = true))
    }
    override suspend fun revokeWiredAdb(): SettingsAuthorityMutation =
        SettingsAuthorityMutation(!failRevoke, current,
            if (failRevoke) "RESIDENT_ADB_REVOKE_FAILED" else null)
    override fun requestWiredAdbPairingToken(
        replaceExistingTrust: Boolean,
    ): SettingsWiredPairingRequestResult {
        requestReplaceExistingTrust = replaceExistingTrust
        wiredCalls += "pair"
        return SettingsWiredPairingRequestResult(
            accepted = true,
            prompt = SettingsWiredPairingPrompt(
                token = token,
                expiresAtEpochMs = System.currentTimeMillis() + 60_000L,
                remainingAttempts = 5,
            ),
            snapshot = current,
        )
    }
    override suspend fun completeWiredAdbPairing(): SettingsAuthorityMutation =
        SettingsAuthorityMutation(accepted = true, snapshot = current)
    override fun cancelWiredAdbPairing(): SettingsAuthoritySnapshot {
        cancelCalls += 1
        return current
    }
    override fun authorizeSaf(uri: Uri): SettingsAuthoritySnapshot = current
    override fun revokeSaf(): SettingsAuthoritySnapshot = current
    override fun setDangerousMode(mode: DangerousMode, confirmed: Boolean): SettingsAuthorityMutation =
        SettingsAuthorityMutation(accepted = true, snapshot = current)
}
