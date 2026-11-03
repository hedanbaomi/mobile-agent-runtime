// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.Serializable

/** Local immutable snapshot data; not part of the transfer format or platform trust. */
@Serializable
data class RootSnapshotGrantBinding(
    val snapshotId: String,
    val grant: CapabilityGrant,
    val root: RootGrantBinding,
    val workspaceRevisionHash: String,
) {
    init {
        require(snapshotId.isNotBlank() && snapshotId.length <= 256)
        require(grant.grantId == root.grantId && grant.revision == root.grantRevision)
        require(grant.workspaceId == root.workspaceId && grant.policyVersion == root.policyVersion)
        require(workspaceRevisionHash.matches(Regex("[0-9a-f]{64}")))
    }
}
