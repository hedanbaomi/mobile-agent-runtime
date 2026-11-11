// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.integration

import runtime.mobileagent.domain.Authority
import runtime.mobileagent.skills.tooling.AuthorityState
import runtime.mobileagent.skills.tooling.ElevatedAuthority
import runtime.mobileagent.wired.WiredAdbStatus
import runtime.mobileagent.wired.WiredAdbLifecycleState

/** Every authority records its readiness edge, including while it is unselected. */
internal fun privilegedReadyEdgeAuthorities(
    statuses: Map<ElevatedAuthority, AuthorityState>,
    readySeen: MutableMap<ElevatedAuthority, Boolean>,
): Set<ElevatedAuthority> {
    val sweep = mutableSetOf<ElevatedAuthority>()
    statuses.forEach { (authority, status) ->
        if (authority == ElevatedAuthority.SHIZUKU && status.isReady && readySeen[authority] == false) sweep += authority
        readySeen[authority] = status.isReady
    }
    return sweep
}

internal fun missingBindingIsOrphan(authority: Authority?, residentWired: Boolean = false): Boolean =
    authority != Authority.WIRED_ADB || residentWired

internal fun residentWorkspaceHandlesNeedReattach(previous: WiredAdbStatus?, current: WiredAdbStatus): Boolean =
    previous != null && current.state == WiredAdbLifecycleState.READY &&
        (previous.state != WiredAdbLifecycleState.READY || previous.serviceSessionId != current.serviceSessionId)
