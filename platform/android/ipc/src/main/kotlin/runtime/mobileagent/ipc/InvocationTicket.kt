// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ipc

data class InvocationTicket(
    val invocationId: String,
    val runId: String,
    val packageHash: String,
    val grantRevision: Int,
    val oneTimeToken: String,
)
