// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

/** Provenance of a newly created Root workspace. It is not a decrypted locator or ready backend. */
data class RootWorkspaceOrigin(
    val workspaceId: String,
    val authority: Authority,
    val appInstanceId: String,
    val packageName: String,
    val appUid: Int,
    val androidUserId: Int,
    val signingSha256: String,
    val generationId: String,
) {
    init {
        require(authority.isRootAuthority && workspaceId.isNotBlank())
        require(generationId.length in 16..128 && appInstanceId.length in 16..128)
        require(packageName.isNotBlank() && appUid % 100_000 >= 10_000 &&
            androidUserId >= 0 && appUid / 100_000 == androidUserId)
        require(signingSha256.matches(Regex("[0-9a-f]{64}")))
    }

    fun matches(identity: PrivilegedSessionIdentity): Boolean =
        authority == identity.authority && appInstanceId == identity.appInstanceId &&
            packageName == identity.packageName && appUid == identity.appUid &&
            androidUserId == identity.androidUserId && signingSha256 == identity.signingSha256

    override fun toString(): String =
        "RootWorkspaceOrigin(authority=$authority,androidUserId=$androidUserId,appUid=$appUid,redacted=true)"
}
