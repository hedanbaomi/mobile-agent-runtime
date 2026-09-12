// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

/**
 * Destination-bound credentials stay with the host they were entered for.
 * Changing a Provider URL does not authorize sending the previous target's
 * auxiliary header secrets to the new host. Snapshot rows may keep the old
 * refs; the live profile must not.
 */
object ProviderDestinationBinding {
    fun headerSecretRefsForSave(
        previous: ProviderProfile?,
        newBaseUrl: String,
        requested: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        if (previous == null) return requested
        if (previous.baseUrl == newBaseUrl) return requested.ifEmpty { previous.headerSecretRefs }
        if (requested.isEmpty() || requested == previous.headerSecretRefs) return emptyMap()
        return requested
    }
}
