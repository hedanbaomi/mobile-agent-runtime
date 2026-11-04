// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import rikka.shizuku.Shizuku
import runtime.mobileagent.privileged.RootShizukuLiveFacts
import runtime.mobileagent.privileged.RootShizukuLiveSampler
import runtime.mobileagent.shizuku.ShizukuAuthorityState

/**
 * Root-channel foreground permission entry.
 *
 * Uses a **fresh** bounded AIDL observation (never the SDK's cached `checkSelfPermission`): the SDK
 * caches an earlier GRANTED, so a revoked permission must still be requestable. A fresh UID2000
 * server is reported as "start Shizuku as Root", not as a missing permission. The platform
 * permission is app-wide, but this entry never writes the legacy SHIZUKU configured marker, never
 * binds the legacy user service and never starts the Root service: the user performs the explicit
 * second "enable" click after authorizing.
 */
internal object RootPermissionEntry {
    /** Root-specific request code; kept out of the legacy shell request range. */
    const val ROOT_PERMISSION_REQUEST_CODE = 0x524F

    fun platformReady(facts: RootShizukuLiveFacts?): Boolean =
        facts != null && facts.binderAlive && facts.permissionGranted && facts.serverUid == 0 &&
            !facts.preV11 && (facts.serverVersion ?: 0) >= 13

    fun checkLiveSelfPermission(): Boolean =
        RootShizukuLiveSampler.sample()?.permissionGranted == true

    fun resolveState(
        base: ShizukuAuthorityState?,
        sample: () -> RootShizukuLiveFacts? = { RootShizukuLiveSampler.sample() },
    ): ShizukuAuthorityState? {
        val facts = sample()
        if (facts == null) {
            // Unknown is not evidence of permission. Keep the base non-authorizing fields but
            // never preserve a stale permission/ready=true; no base means no authoritative state.
            return base?.copy(permissionGranted = false, ready = false)
        }
        val target = base ?: ShizukuAuthorityState(
            installedHint = true,
            binderAlive = facts.binderAlive,
            permissionGranted = facts.permissionGranted,
            apiVersion = facts.serverVersion,
            serverUid = facts.serverUid,
            userServiceAlive = false,
            ready = false,
            errorCode = null,
            preV11 = facts.preV11,
        )
        return target.copy(
            binderAlive = facts.binderAlive,
            permissionGranted = facts.permissionGranted,
            apiVersion = facts.serverVersion,
            serverUid = facts.serverUid,
            preV11 = facts.preV11,
            ready = facts.permissionGranted && target.ready,
        )
    }

    fun request(
        sample: () -> RootShizukuLiveFacts?,
        refresh: () -> Unit,
        requester: (Int) -> Boolean = { code ->
            runCatching { Shizuku.requestPermission(code); true }.getOrDefault(false)
        },
    ) {
        val facts = sample() ?: error("ROOT_SHIZUKU_STATE_UNKNOWN")
        if (!facts.binderAlive) error("ROOT_SHIZUKU_NOT_RUNNING")
        // Before platform permission, the fresh sampler may read neither the UID nor the version.
        // Requesting that permission is not Root consent or service acquisition.
        if (facts.serverUid != null && facts.serverUid != 0) error("ROOT_SHIZUKU_UID_NOT_ROOT")
        if (facts.serverVersion != null && facts.serverVersion < 13) error("ROOT_SHIZUKU_VERSION_UNSUPPORTED")
        if (facts.permissionGranted) {
            if (facts.serverUid == null || facts.serverVersion == null) error("ROOT_SHIZUKU_STATE_UNKNOWN")
            if (facts.preV11) error("ROOT_SHIZUKU_VERSION_UNSUPPORTED")
            // Already granted: refresh the observed state only. No Root acquire, no consent write.
            refresh()
            return
        }
        if (!requester(ROOT_PERMISSION_REQUEST_CODE)) error("ROOT_SHIZUKU_PERMISSION_REQUEST_REJECTED")
    }
}
