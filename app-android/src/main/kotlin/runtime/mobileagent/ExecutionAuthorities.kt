// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Context
import android.content.Intent
import android.net.Uri
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.AuthorityUserIntent
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.DesktopTrustStatus
import runtime.mobileagent.domain.SafGrantStatus
import runtime.mobileagent.shizuku.ShizukuAuthorityState
import runtime.mobileagent.skills.tooling.Availability
import runtime.mobileagent.skills.tooling.Connection
import runtime.mobileagent.skills.tooling.PlatformGrant
import java.util.WeakHashMap

/**
 * A UI-safe projection of one authority. The shared domain owns the state
 * enums; this class only groups the values required by Settings. In
 * particular, no serial, endpoint, URI, token, UID, or Binder object belongs
 * in this projection.
 */
data class SettingsAuthorityProviderState(
    val authority: Authority,
    val userIntent: AuthorityUserIntent = AuthorityUserIntent.NONE,
    val platformGrant: PlatformGrant = PlatformGrant.UNKNOWN,
    val availability: Availability = Availability.UNSUPPORTED,
    val connection: Connection = Connection.DISCONNECTED,
    val configured: Boolean = false,
    val trust: DesktopTrustStatus? = null,
) {
    init {
        require(authority == Authority.SHIZUKU || authority == Authority.WIRED_ADB) {
            "Settings only supports Shizuku and wired ADB authorities"
        }
    }
}

/** SAF state intentionally contains capability booleans, never the selected URI. */
data class SettingsSafGrantState(
    val configured: Boolean = false,
    val readGranted: Boolean = false,
    val writeGranted: Boolean = false,
    val persisted: Boolean = false,
    val status: SafGrantStatus = SafGrantStatus.REVOKED,
)

/**
 * The single authority snapshot consumed by SettingsViewModel. Durable user
 * intent/configuration is kept alongside live grant/availability/connection;
 * lifecycle changes must not be represented by silently changing selection.
 */
data class SettingsAuthoritySnapshot(
    val selectedAuthority: Authority = Authority.NONE,
    val appPrivateAvailable: Boolean = true,
    val shizuku: SettingsAuthorityProviderState = SettingsAuthorityProviderState(Authority.SHIZUKU),
    val wiredAdb: SettingsAuthorityProviderState = SettingsAuthorityProviderState(Authority.WIRED_ADB),
    val saf: SettingsSafGrantState = SettingsSafGrantState(),
    val dangerousMode: DangerousMode = DangerousMode.DISABLED,
    /** Durable policy from the canonical store; [dangerousMode] is the effective fail-closed policy. */
    val durableDangerousMode: DangerousMode = DangerousMode.DISABLED,
    val dangerousModeBuildAllowed: Boolean = false,
    val dangerousModeBuildKnown: Boolean = false,
    val dangerousModeReason: String = "DANGEROUS_MODE_BUILD_DENIED",
    val revision: Long = 0L,
)

data class SettingsAuthorityMutation(
    val accepted: Boolean,
    val snapshot: SettingsAuthoritySnapshot,
    val reason: String? = null,
)

/**
 * One foreground-only Wired ADB pairing prompt.
 *
 * The token is deliberately not a property.  It is held in a wipeable
 * character array for the short period in which Settings needs to render or
 * copy it, and [clear] overwrites that array when the prompt is completed,
 * cancelled, expires, or the ViewModel is cleared.  Do not turn this into a
 * data class: generated equality/copy/toString methods are the wrong shape for
 * a one-time secret.
 */
class SettingsWiredPairingPrompt(
    token: String,
    val expiresAtEpochMs: Long,
    val remainingAttempts: Int,
) {
    private var tokenChars = token.toCharArray()
    private var cleared = false

    init {
        require(token.length == WIRED_PAIRING_TOKEN_LENGTH && token.all { it in WIRED_PAIRING_TOKEN_HEX }) {
            "Invalid Wired ADB pairing token"
        }
        require(expiresAtEpochMs > 0L) { "Invalid Wired ADB pairing expiry" }
        require(remainingAttempts > 0) { "Invalid Wired ADB pairing attempts" }
    }

    /** Returns an ephemeral display/copy value; callers must not retain it. */
    @Synchronized
    fun tokenDisplay(): String = if (cleared) "" else String(tokenChars)

    @Synchronized
    fun clear() {
        if (cleared) return
        tokenChars.fill('\u0000')
        cleared = true
    }

    @Synchronized
    fun isCleared(): Boolean = cleared

    /** Secret-free diagnostic representation. */
    @Synchronized
    override fun toString(): String =
        "SettingsWiredPairingPrompt(expiresAtEpochMs=$expiresAtEpochMs, " +
            "remainingAttempts=$remainingAttempts, cleared=$cleared)"
}

data class SettingsWiredPairingRequestResult(
    val accepted: Boolean,
    val prompt: SettingsWiredPairingPrompt? = null,
    val snapshot: SettingsAuthoritySnapshot = SettingsAuthoritySnapshot(),
    val reason: String? = null,
) {
    init {
        require(accepted == (prompt != null)) { "Pairing result acceptance must match prompt" }
    }
}

private const val WIRED_PAIRING_TOKEN_LENGTH = 64
private const val WIRED_PAIRING_TOKEN_HEX = "0123456789abcdefABCDEF"

/**
 * Minimal seam between the Settings UI and the AppContainer authority
 * adapters. The adapter that owns AuthorityManager, DangerousModeManager,
 * and SafWorkspaceGrantRepository should be exposed by
 * [SettingsAuthorityPortProvider]. This keeps UI code from creating a second
 * persistence/database truth while those adapters are assembled.
 */
interface SettingsAuthorityPort {
    fun snapshot(): SettingsAuthoritySnapshot
    fun refresh(): SettingsAuthoritySnapshot

    fun selectAuthority(authority: Authority): SettingsAuthoritySnapshot
    fun setUserIntent(authority: Authority, enabled: Boolean): SettingsAuthoritySnapshot

    /** Explicit foreground user action; no request is made during refresh. */
    fun requestShizukuPermission(): SettingsAuthoritySnapshot
    fun openShizuku(): Boolean

    /** Explicit trust lifecycle actions for the wired companion. */
    fun reauthorizeWiredAdb(): SettingsAuthoritySnapshot = snapshot()
    fun forgetWiredAdb(): SettingsAuthoritySnapshot = snapshot()

    /**
     * Starts a foreground-only pairing exchange.  Implementations must create
     * and retain the raw token in the Wired bridge, never in a durable store.
     * The returned prompt is the only UI-facing projection of that token.
     */
    fun requestWiredAdbPairingToken(
        replaceExistingTrust: Boolean = false,
    ): SettingsWiredPairingRequestResult = error("AUTHORITY_ADAPTER_UNAVAILABLE")

    /** Completes the bridge-held pairing exchange; no token is passed by UI. */
    suspend fun completeWiredAdbPairing(): SettingsAuthorityMutation =
        error("AUTHORITY_ADAPTER_UNAVAILABLE")

    /** Cancels the foreground exchange and clears any bridge-held token. */
    fun cancelWiredAdbPairing(): SettingsAuthoritySnapshot = error("AUTHORITY_ADAPTER_UNAVAILABLE")

    /** URI is consumed by the adapter and must never be copied to a snapshot. */
    fun authorizeSaf(uri: Uri): SettingsAuthoritySnapshot

    /**
     * Persist only the provider flags actually granted for this result. The
     * one-argument form remains the compatibility path for callers that do
     * not receive activity-result flags.
     */
    fun authorizeSaf(uri: Uri, resultFlags: Int): SettingsAuthoritySnapshot = authorizeSaf(uri)
    fun revokeSaf(): SettingsAuthoritySnapshot

    /** [confirmed] is supplied only after the UI displays the risk dialog. */
    fun setDangerousMode(mode: DangerousMode, confirmed: Boolean): SettingsAuthorityMutation
    fun disableDangerousMode(): SettingsAuthoritySnapshot =
        setDangerousMode(DangerousMode.DISABLED, confirmed = true).snapshot
}

/** AppContainer implements this provider once its canonical adapters are wired. */
fun interface SettingsAuthorityPortProvider {
    fun settingsAuthorityPort(): SettingsAuthorityPort
}

private val settingsAuthorityPorts = WeakHashMap<Context, SettingsAuthorityPort>()
private val settingsAuthorityProviders = WeakHashMap<Context, SettingsAuthorityPortProvider>()

/** Install the AppContainer-owned adapter before SettingsViewModel is created. */
@Synchronized
fun registerSettingsAuthorityPort(context: Context, port: SettingsAuthorityPort) {
    settingsAuthorityPorts[context.applicationContext] = port
}

/**
 * Preferred registration path: AppContainer remains the owner of authority
 * persistence and can construct the adapter with its repositories/managers.
 */
@Synchronized
fun registerSettingsAuthorityPortProvider(context: Context, provider: SettingsAuthorityPortProvider) {
    val appContext = context.applicationContext
    settingsAuthorityProviders[appContext] = provider
    settingsAuthorityPorts.remove(appContext)
}

/**
 * Resolve the application adapter. The fallback is deliberately unavailable
 * with respect to durable authority policy: it does not infer live platform
 * facts as a canonical grant and refuses to pretend that a persistence adapter
 * exists.
 */
@Synchronized
fun settingsAuthorityPort(context: Context): SettingsAuthorityPort {
    val appContext = context.applicationContext
    settingsAuthorityProviders[appContext]?.settingsAuthorityPort()?.let { port ->
        settingsAuthorityPorts[appContext] = port
        return port
    }
    return settingsAuthorityPorts[appContext]
        ?: FailingClosedSettingsAuthorityPort(appContext).also { settingsAuthorityPorts[appContext] = it }
}

/**
 * Transitional fallback used until AppContainer wiring registers the canonical
 * adapter. It intentionally has no durable selection, trust, SAF, or
 * Dangerous Mode storage, and it does not infer a canonical grant from a live
 * Shizuku probe. That boundary is safer than introducing a second settings
 * database with values that could disagree with execution.
 */
private class FailingClosedSettingsAuthorityPort(
    private val context: Context,
) : SettingsAuthorityPort {
    @Volatile
    private var value = SettingsAuthoritySnapshot()

    override fun snapshot(): SettingsAuthoritySnapshot = value

    override fun refresh(): SettingsAuthoritySnapshot {
        return value
    }

    override fun selectAuthority(authority: Authority): SettingsAuthoritySnapshot {
        error("AUTHORITY_ADAPTER_UNAVAILABLE")
    }

    override fun setUserIntent(authority: Authority, enabled: Boolean): SettingsAuthoritySnapshot {
        error("AUTHORITY_ADAPTER_UNAVAILABLE")
    }

    override fun requestShizukuPermission(): SettingsAuthoritySnapshot {
        // The canonical adapter owns the bridge and permission lifecycle. A
        // missing adapter must not issue a platform permission request.
        error("AUTHORITY_ADAPTER_UNAVAILABLE")
    }

    override fun openShizuku(): Boolean = openShizuku(context)

    override fun reauthorizeWiredAdb(): SettingsAuthoritySnapshot = error("AUTHORITY_ADAPTER_UNAVAILABLE")

    override fun forgetWiredAdb(): SettingsAuthoritySnapshot = error("AUTHORITY_ADAPTER_UNAVAILABLE")

    override fun requestWiredAdbPairingToken(
        replaceExistingTrust: Boolean,
    ): SettingsWiredPairingRequestResult = error("AUTHORITY_ADAPTER_UNAVAILABLE")

    override suspend fun completeWiredAdbPairing(): SettingsAuthorityMutation =
        error("AUTHORITY_ADAPTER_UNAVAILABLE")

    override fun cancelWiredAdbPairing(): SettingsAuthoritySnapshot =
        error("AUTHORITY_ADAPTER_UNAVAILABLE")

    override fun authorizeSaf(uri: Uri): SettingsAuthoritySnapshot = error("AUTHORITY_ADAPTER_UNAVAILABLE")

    override fun revokeSaf(): SettingsAuthoritySnapshot = error("AUTHORITY_ADAPTER_UNAVAILABLE")

    override fun setDangerousMode(mode: DangerousMode, confirmed: Boolean): SettingsAuthorityMutation {
        // Even an idempotent-looking disable is a durable policy write. The
        // fallback must never claim that policy state was saved without the
        // canonical repository/adapter.
        error("AUTHORITY_ADAPTER_UNAVAILABLE")
    }
}

/**
 * Maps live Shizuku facts for the canonical AppContainer adapter. Durable
 * user intent/configuration are explicit inputs and are never inferred from
 * Binder permission or connection state.
 */
fun shizukuProviderState(
    state: ShizukuAuthorityState,
    userIntent: AuthorityUserIntent = AuthorityUserIntent.NONE,
    configured: Boolean = false,
): SettingsAuthorityProviderState {
    val grant = when {
        state.permissionGranted -> PlatformGrant.GRANTED
        state.binderAlive -> PlatformGrant.DENIED
        else -> PlatformGrant.UNKNOWN
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
    return SettingsAuthorityProviderState(
        authority = Authority.SHIZUKU,
        userIntent = userIntent,
        platformGrant = grant,
        availability = availability,
        connection = connection,
        configured = configured,
    )
}

/** Opens the installed Shizuku manager, or its official installation guide when absent. */
fun openShizuku(context: Context): Boolean {
    val manager = context.packageManager.getLaunchIntentForPackage(SHIZUKU_MANAGER_PACKAGE)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val guide = Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_INSTALL_GUIDE))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching {
        context.startActivity(manager ?: guide)
        true
    }.getOrDefault(false)
}

private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"
private const val SHIZUKU_INSTALL_GUIDE = "https://shizuku.rikka.app/download/"
