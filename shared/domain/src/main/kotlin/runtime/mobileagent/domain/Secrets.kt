// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.Serializable

@Serializable
enum class SecretStatus { ACTIVE, RETIRED, ORPHANED, DELETED }

data class ProviderDeletePreview(
    val providerId: String,
    val modelCount: Int,
    val snapshotCount: Int,
    val secretRef: String,
    val secretStatus: SecretStatus?,
    val canDelete: Boolean,
)
