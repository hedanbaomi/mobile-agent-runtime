// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

/** Canonical Linux boot_id shape. Authenticity comes from the pinned UID0 handshake. */
object RootKernelBootIdentity {
    fun isValid(value: String?): Boolean = value != null && value.length == 36 &&
        runCatching { java.util.UUID.fromString(value).toString() == value }.getOrDefault(false)
}
