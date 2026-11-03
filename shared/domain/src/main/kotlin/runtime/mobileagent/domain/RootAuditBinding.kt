// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.Serializable

/** Historical Root context only. This evidence never grants execution or result disclosure. */
@Serializable
data class RootAuditBinding(val auditId: String, val approval: RootApprovalBinding) {
    init { require(auditId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) }

    override fun toString(): String = "RootAuditBinding(authority=${approval.approval.selectedAuthority})"
}
