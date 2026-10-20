// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import runtime.mobileagent.provider.ModelRequest

/** Commit answering-only host state at the adapter's final, locally validated HTTP gate. */
internal fun AgentRuntimeRequest.answeringDispatchRequest(
    request: ModelRequest,
    allowed: () -> Boolean,
): ModelRequest {
    val originalGate = request.beforeDispatch
    return request.copy(beforeDispatch = {
        if (!originalGate() || !allowed()) false
        else { beforeAnsweringDispatch(); true }
    })
}
