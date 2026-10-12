// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import kotlinx.coroutines.withTimeoutOrNull
import runtime.mobileagent.provider.ModelRequest

/** Admission after durable preview, before any answering request reaches the transport. */
internal suspend fun AgentRuntimeRequest.validateAnsweringImages(
    request: ModelRequest,
    remainingMillis: Long,
    budgetExhausted: () -> Boolean,
): Boolean {
    if (budgetExhausted()) return false
    val images = request.messages.flatMap { it.images }
    if (images.isEmpty()) return true
    return withTimeoutOrNull(remainingMillis) {
        beforeModelRequest()
        beforeImageRequest(images)
        true
    } == true && !budgetExhausted()
}
