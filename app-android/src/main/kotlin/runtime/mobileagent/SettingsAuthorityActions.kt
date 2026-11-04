// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.skills.tooling.PlatformGrant

/**
 * Serializes the foreground authority actions that can bind a su process or a Shizuku
 * UserService. The blocking port calls run on [ioDispatcher]; snapshots, errors and the
 * permission entry stay on [mainDispatcher]/the ViewModel scope.
 *
 * A passive refresh requested while an action is in flight is replayed once after the action,
 * so a permission callback cannot be silently lost. The gate is always cleared in `finally`.
 */
internal class SettingsAuthorityActions(
    private val scope: CoroutineScope,
    private val port: SettingsAuthorityPort,
    private val onSnapshot: (SettingsAuthoritySnapshot) -> Unit,
    private val onError: (String?) -> Unit,
    private val onFactsChanged: () -> Unit,
    private val errorMapper: (Throwable) -> String,
    private val reasonMapper: (String?) -> String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private data class Result(
        val snapshot: SettingsAuthoritySnapshot? = null,
        val error: String? = null,
    )

    private data class PendingAction(
        val identity: String,
        val refreshFacts: Boolean,
        val action: suspend () -> Result,
    )

    // Keys are a fixed set of UI action kinds/channels. Keep the latest explicit choice per kind,
    // while preserving different actions (the Direct Root button selects then enables).
    private val pendingActions = linkedMapOf<String, PendingAction>()
    private var activeIdentity: String? = null
    private var inFlight = false
    private var refreshPending = false
    private var pendingMutation: (() -> SettingsAuthoritySnapshot)? = null

    fun refreshAuthorities() {
        if (inFlight) {
            refreshPending = true
            return
        }
        performAction("refresh") { Result(snapshot = port.refresh()) }
    }

    fun selectAuthority(authority: Authority) = performAction("select", "select:$authority", refreshFacts = true) {
        Result(snapshot = port.selectAuthority(authority))
    }

    fun setAuthorityIntent(authority: Authority, enabled: Boolean) =
        performAction("intent:$authority", "intent:$authority:$enabled", refreshFacts = true) {
        Result(snapshot = port.setUserIntent(authority, enabled))
    }

    fun requestShizukuPermission() = performAction("permission-shizuku") {
        Result(snapshot = withContext(mainDispatcher) { port.requestShizukuPermission() })
    }

    /** First click requests the platform permission; the second selects and binds the intent. */
    fun enableShizukuRoot() = performAction("enable-shizuku-root", refreshFacts = true) {
        if (!port.rootShizukuPlatformReady()) {
            return@performAction Result(snapshot = withContext(mainDispatcher) { port.requestRootShizukuPermission() })
        }
        val selected = port.selectAuthority(Authority.SHIZUKU_ROOT)
        withContext(mainDispatcher) { onSnapshot(selected) }
        Result(snapshot = port.setUserIntent(Authority.SHIZUKU_ROOT, true))
    }

    fun enableShizuku() = performAction("enable-shizuku", refreshFacts = true) {
        val intent = port.setUserIntent(Authority.SHIZUKU, true)
        withContext(mainDispatcher) { onSnapshot(intent) }
        val chosen = port.selectAuthority(Authority.SHIZUKU)
        withContext(mainDispatcher) { onSnapshot(chosen) }
        if (chosen.shizuku.platformGrant != PlatformGrant.GRANTED) {
            return@performAction Result(snapshot = withContext(mainDispatcher) { port.requestShizukuPermission() })
        }
        Result(snapshot = chosen)
    }

    fun setDangerousMode(mode: DangerousMode) = performAction("mode", "mode:$mode", refreshFacts = true) {
        val result = port.setDangerousMode(mode, confirmed = true)
        Result(result.snapshot, if (result.accepted) null else reasonMapper(result.reason))
    }

    fun setRootShellConsent(authority: Authority, enabled: Boolean, confirmed: Boolean) =
        performAction("shell:$authority", "shell:$authority:$enabled:$confirmed", refreshFacts = true) {
            val result = port.setRootShellConsent(authority, enabled, confirmed)
            Result(result.snapshot, if (result.accepted) null else reasonMapper(result.reason))
        }

    /** Non-handshake authority mutations (SAF grants) still publish through the same gate. */
    fun mutate(update: () -> SettingsAuthoritySnapshot) {
        if (inFlight) {
            // An Activity result can arrive while an enable handshake is still running; keep the
            // latest foreground mutation instead of silently dropping the grant.
            pendingMutation = update
            return
        }
        performAction("mutation") { Result(snapshot = update()) }
    }

    private fun performAction(
        key: String,
        identity: String = key,
        refreshFacts: Boolean = false,
        action: suspend () -> Result,
    ) {
        if (inFlight) {
            if (activeIdentity == identity) pendingActions.remove(key)
            else pendingActions[key] = PendingAction(identity, refreshFacts, action)
            return
        }
        inFlight = true
        activeIdentity = identity
        scope.launch {
            try {
                val result = withContext(ioDispatcher) { action() }
                result.snapshot?.let(onSnapshot)
                onError(result.error)
                if (result.error == null && refreshFacts) onFactsChanged()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                onError(errorMapper(failure))
            } finally {
                inFlight = false
                activeIdentity = null
                val queued = pendingActions.entries.firstOrNull()
                if (queued != null) {
                    pendingActions.remove(queued.key)
                    val next = queued.value
                    performAction(queued.key, next.identity, next.refreshFacts, next.action)
                } else {
                    val pending = pendingMutation
                    pendingMutation = null
                    when {
                        pending != null -> mutate(pending)
                        refreshPending -> {
                            refreshPending = false
                            refreshAuthorities()
                        }
                    }
                }
            }
        }
    }
}
