// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import runtime.mobileagent.data.TrpgRepository
import runtime.mobileagent.domain.AgentMode
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.trpg.TrpgSession

/** Called by request orchestration; presentation code never supplies campaign data to the model. */
internal fun trpgSessionReader(repository: TrpgRepository, snapshot: AgentSnapshot,
    conversationId: String): (suspend () -> TrpgSession)? = if (snapshot.mode == AgentMode.TRPG) {
    { withContext(Dispatchers.IO) { repository.get(conversationId) } }
} else null
