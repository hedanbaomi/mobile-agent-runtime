// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

/**
 * Deterministic fail-closed policy for the third-party Shizuku authority.
 *
 * v2 accepts only Android shell (2000).  Root (0), unknown UIDs and stale
 * values are denied before any UserService transaction.  This policy is
 * independent of the Android API and is therefore directly testable without a
 * Shizuku installation or a real device.
 */
object ShizukuBridgePolicy {
    const val MIN_SERVER_VERSION = 13
    const val USER_SERVICE_PROTOCOL_VERSION = 2
    /** Kept as a named compatibility constant; root is never accepted by v2. */
    const val ROOT_UID = 0
    const val SHELL_UID = 2000

    fun evaluateServer(status: ShizukuBridgeStatus): ShizukuGateDecision {
        if (!status.binderAlive) return ShizukuGateDecision.Denied("Shizuku binder is unavailable")
        if (status.preV11) return ShizukuGateDecision.Denied("Shizuku server API is unsupported")
        if (!status.permissionGranted) return ShizukuGateDecision.Denied("Shizuku permission is not granted")
        if (!status.trustedServerUid) return ShizukuGateDecision.Denied("Shizuku server UID is not trusted")
        val version = status.serverVersion
            ?: return ShizukuGateDecision.Denied("Shizuku server version is unavailable")
        if (version < MIN_SERVER_VERSION) {
            return ShizukuGateDecision.Denied("Shizuku server version is unsupported")
        }
        return ShizukuGateDecision.Allowed
    }

    fun evaluateDispatch(status: ShizukuBridgeStatus): ShizukuGateDecision {
        val server = evaluateServer(status)
        if (server !is ShizukuGateDecision.Allowed) return server
        if (!status.userServiceAlive) {
            return ShizukuGateDecision.Denied("Shizuku UserService is not connected")
        }
        if (!status.trustedUserServiceUid) {
            return ShizukuGateDecision.Denied("Shizuku UserService UID is not trusted")
        }
        if (!status.protocolReady) {
            return ShizukuGateDecision.Denied("Shizuku UserService handshake is invalid")
        }
        return ShizukuGateDecision.Allowed
    }

    fun validPermissionRequestCode(requestCode: Int): Boolean = requestCode in 1..0x7fff

    fun validReadLimit(maxBytes: Int): Boolean = maxBytes in 1..ShizukuWorkspaceFileStore.MAX_READ_BYTES
}
