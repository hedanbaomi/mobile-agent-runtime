// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.Serializable

/** Local binding for a newly confirmed canonical grant. Never derive this from a legacy grant. */
@Serializable
data class RootGrantBinding(
    val grantId: String,
    val grantRevision: Long,
    val workspaceId: String,
    val workspaceGenerationId: String,
    val authority: Authority,
    val appInstanceId: String,
    val packageName: String,
    val appUid: Int,
    val androidUserId: Int,
    val signingSha256: String,
    val consentRevision: Long,
    val policyVersion: Long,
    val generationId: String,
    val privilegeTier: PrivilegeTier = PrivilegeTier.ROOT,
) {
    init {
        require(authority.isRootAuthority && privilegeTier == PrivilegeTier.ROOT)
        require(grantId.isNotBlank() && workspaceId.isNotBlank() && generationId.length in 16..128 &&
            workspaceGenerationId.length in 16..128)
        require(grantRevision > 0 && consentRevision > 0 && policyVersion >= 0)
        require(appInstanceId.length in 16..128 && packageName.isNotBlank() && appUid % 100_000 >= 10_000)
        require(androidUserId >= 0 && appUid / 100_000 == androidUserId)
        require(signingSha256.matches(Regex("[0-9a-f]{64}")))
    }

    /** Scope match only: lifetime, snapshot, OS identity and live consent remain mandatory gates. */
    fun matches(grant: CapabilityGrant, execution: RootExecutionStamp): Boolean {
        val identity = execution.identity
        return grant.grantId == grantId && grant.revision == grantRevision &&
            grant.workspaceId == workspaceId && grant.skillInstallId == null && grant.packageHash == null &&
            grant.policyVersion == policyVersion && identity.authority == authority &&
            identity.privilegeTier == privilegeTier && identity.appInstanceId == appInstanceId &&
            identity.packageName == packageName && identity.appUid == appUid && identity.androidUserId == androidUserId &&
            identity.signingSha256 == signingSha256 && execution.consentRevision == consentRevision &&
            execution.policyVersion == policyVersion
    }
}
