// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.shizuku.ShizukuBridgePolicy

sealed interface RootBridgeGateDecision {
    data object Allowed : RootBridgeGateDecision
    data class Denied(
        val errorCode: RootBridgeErrorCode,
        val reason: String,
        val retryable: Boolean = false,
    ) : RootBridgeGateDecision
}

/**
 * Deterministic fail-closed gate for `SHIZUKU_ROOT` and `ROOT_DIRECT` handshake verification.
 *
 * Keeps `SHIZUKU` (`UID 2000`, protocol 3, tag [SHELL_USER_SERVICE_TAG]) and `SHIZUKU_ROOT`
 * (`UID 0`, protocol 4, tag [ROOT_USER_SERVICE_TAG]) strictly isolated: neither channel
 * falls back to or inherits grants/sessions from the other.
 */
object RootShizukuBridgePolicy {
    const val MIN_SERVER_VERSION: Int = ShizukuBridgePolicy.MIN_SERVER_VERSION
    const val ROOT_PROTOCOL_VERSION: Int = PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION
    const val ROOT_USER_SERVICE_TAG: String = "mar_root_privileged_v4"
    const val SHELL_USER_SERVICE_TAG: String = "mar_shizuku_shell_v3"

    fun evaluateRootShizukuServer(
        selectedAuthority: Authority,
        binderAlive: Boolean,
        preV11: Boolean,
        permissionGranted: Boolean,
        serverUid: Int?,
        serverVersion: Int?,
        sessionDegraded: Boolean = false,
    ): RootBridgeGateDecision {
        if (selectedAuthority != Authority.SHIZUKU_ROOT) {
            return RootBridgeGateDecision.Denied(
                RootBridgeErrorCode.PRIVILEGE_MISMATCH,
                "selected_authority_not_shizuku_root",
            )
        }
        if (sessionDegraded) {
            return RootBridgeGateDecision.Denied(
                RootBridgeErrorCode.ROOT_SERVICE_UNAVAILABLE,
                "root_session_degraded",
                retryable = false,
            )
        }
        if (!binderAlive) {
            return RootBridgeGateDecision.Denied(
                RootBridgeErrorCode.ROOT_SERVICE_UNAVAILABLE,
                "shizuku_binder_unavailable",
            )
        }
        if (preV11 || serverVersion == null || serverVersion < MIN_SERVER_VERSION) {
            return RootBridgeGateDecision.Denied(
                RootBridgeErrorCode.UNSUPPORTED_OPERATION,
                "shizuku_server_version_unsupported",
            )
        }
        if (!permissionGranted) {
            return RootBridgeGateDecision.Denied(
                RootBridgeErrorCode.ROOT_AUTHORIZATION_REQUIRED,
                "shizuku_permission_required",
            )
        }
        if (serverUid != PrivilegeTier.ROOT.uid) {
            return RootBridgeGateDecision.Denied(
                RootBridgeErrorCode.PRIVILEGE_MISMATCH,
                if (serverUid == ShizukuBridgePolicy.SHELL_UID) {
                    "shizuku_running_as_shell_uid_2000"
                } else {
                    "shizuku_server_uid_untrusted"
                },
            )
        }
        return RootBridgeGateDecision.Allowed
    }

    /**
     * When `SHIZUKU` (ADB shell) is selected but the Shizuku server is running as `UID 0`,
     * reject the call with `PRIVILEGE_MISMATCH` and instruct the user to explicitly select
     * `SHIZUKU_ROOT` in Settings rather than silently elevating old ADB grants.
     */
    fun evaluateShellShizukuServerUid(
        selectedAuthority: Authority,
        serverUid: Int?,
    ): RootBridgeGateDecision {
        if (selectedAuthority != Authority.SHIZUKU) {
            return RootBridgeGateDecision.Denied(
                RootBridgeErrorCode.PRIVILEGE_MISMATCH,
                "selected_authority_not_shizuku_shell",
            )
        }
        return when (serverUid) {
            ShizukuBridgePolicy.SHELL_UID -> RootBridgeGateDecision.Allowed
            PrivilegeTier.ROOT.uid -> RootBridgeGateDecision.Denied(
                RootBridgeErrorCode.PRIVILEGE_MISMATCH,
                "shizuku_running_as_root_select_shizuku_root",
            )
            else -> RootBridgeGateDecision.Denied(
                RootBridgeErrorCode.PRIVILEGE_MISMATCH,
                "shizuku_server_uid_untrusted",
            )
        }
    }

    fun evaluateRootHandshake(
        expectation: PinnedAppBootstrapExpectation,
        handshake: PrivilegedSessionIdentity?,
    ): RootBridgeGateDecision {
        if (handshake == null) {
            return RootBridgeGateDecision.Denied(
                RootBridgeErrorCode.ROOT_SERVICE_UNAVAILABLE,
                "handshake_missing",
            )
        }
        if (handshake.protocolVersion != ROOT_PROTOCOL_VERSION) {
            return RootBridgeGateDecision.Denied(
                RootBridgeErrorCode.PRIVILEGE_MISMATCH,
                "protocol_version_mismatch",
            )
        }
        if (handshake.authority != expectation.expectedAuthority ||
            handshake.privilegeTier != PrivilegeTier.ROOT ||
            handshake.serviceUid != PrivilegeTier.ROOT.uid
        ) {
            return RootBridgeGateDecision.Denied(
                RootBridgeErrorCode.PRIVILEGE_MISMATCH,
                "service_authority_or_uid_mismatch",
            )
        }
        if (handshake.appUid != expectation.expectedAppUid ||
            handshake.androidUserId != expectation.expectedUserId ||
            handshake.appInstanceId != expectation.expectedAppInstanceId ||
            handshake.packageName != expectation.expectedPackageName ||
            handshake.signingSha256 != expectation.expectedSigningSha256 ||
            handshake.identityEpoch != expectation.expectedIdentityEpoch
        ) {
            return RootBridgeGateDecision.Denied(
                RootBridgeErrorCode.IDENTITY_CHANGED,
                "app_or_epoch_binding_mismatch",
            )
        }
        return RootBridgeGateDecision.Allowed
    }
}

/**
 * Monotonic bind-generation guard so asynchronous `onServiceDisconnected` or
 * `BinderDead` callbacks from an unbound/destroyed UserService generation cannot
 * clear a newly established Root connection.
 */
class RootBindGenerationGuard {
    private var activeGeneration: Long = 0L
    private var connectedSessionId: String? = null

    @Synchronized
    fun beginBind(): Long {
        activeGeneration = Math.addExact(activeGeneration, 1L)
        connectedSessionId = null
        return activeGeneration
    }

    @Synchronized
    fun acceptConnected(callbackGeneration: Long, sessionId: String): Boolean {
        if (callbackGeneration <= 0L || callbackGeneration != activeGeneration || sessionId.isBlank()) {
            return false
        }
        connectedSessionId = sessionId
        return true
    }

    @Synchronized
    fun acceptDisconnectOrDeath(callbackGeneration: Long): Boolean {
        if (callbackGeneration <= 0L || callbackGeneration != activeGeneration) {
            return false
        }
        connectedSessionId = null
        return true
    }

    @Synchronized
    fun unbindAndInvalidate(): Long {
        activeGeneration = Math.addExact(activeGeneration, 1L)
        connectedSessionId = null
        return activeGeneration
    }

    @Synchronized
    fun currentSessionId(): String? = connectedSessionId

    @Synchronized
    fun currentGeneration(): Long = activeGeneration
}
