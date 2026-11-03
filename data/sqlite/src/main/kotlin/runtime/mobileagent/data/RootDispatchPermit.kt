// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.*

/** Process-owned reservation only. The platform adapter must independently prove OS identity. */
class RootDispatchPermit internal constructor(
    internal val claim: RootDispatchClaim,
    internal val snapshot: RootSnapshotGrantBinding,
    internal val afterGrant: CapabilityGrant,
) {
    val requestId: String get() = claim.requestId
    internal var closed = false
    internal var disclosureChecked = false
    override fun toString(): String = "RootDispatchPermit(closed=$closed)"
}

/** Only the issuing repository's registry can redeem this ticket, exactly once. */
class RootAttemptTicket internal constructor(internal val permit: RootDispatchPermit) {
    internal var used = false
    override fun toString(): String = "RootAttemptTicket(used=$used)"
}

/** Transient Host-to-service facts; never store or log this object or its raw arguments/scope. */
class RootTransportInvocation internal constructor(
    val claim: RootDispatchClaim,
    val snapshot: RootSnapshotGrantBinding,
    val normalizedArgumentsJson: String,
) {
    override fun toString(): String = "RootTransportInvocation(authority=${claim.approval.approval.selectedAuthority})"
}
