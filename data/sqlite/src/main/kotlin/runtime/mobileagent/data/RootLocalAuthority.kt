// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RootAuthorityRecord
import runtime.mobileagent.domain.RootExecutionStamp

/** DB checks only. Callers hold a transaction and separately verify the live OS/transport identity. */
internal fun requireLocalRootState(db: SqlConnection, execution: RootExecutionStamp): RootAuthorityRecord {
    val identity = execution.identity
    val policy = AuthorityPolicyRepository(db).getPolicy()
    val root = RootAuthorityRepository(db).get(identity.authority)
    require(identity.protocolVersion == PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION)
    require(policy.selectedAuthority == identity.authority && policy.policyVersion == execution.policyVersion)
    require(root.consent.enabled && !root.degraded && root.identityEpoch == identity.identityEpoch)
    require(root.consent.revision == execution.consentRevision && root.consent.policyVersion == execution.policyVersion)
    return root
}
