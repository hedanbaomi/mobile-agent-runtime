// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.domain.ImportedCharacterCard
import runtime.mobileagent.feature.agents.AgentEditorUi

internal fun canSaveCharacterEditor(saving: Boolean, editor: AgentEditorUi?): Boolean =
    !saving && editor != null && !editor.characterCardLoading

internal fun characterCardNeedsLoad(agents: AgentRepository, id: String?): Boolean =
    id != null && agents.hasCharacterCard(id)

/** Metadata stays off Main and stale completions cannot modify a different editor. */
internal fun loadCharacterCardForEditor(scope: CoroutineScope, agents: AgentRepository, id: String?,
    current: () -> Boolean, loaded: (ImportedCharacterCard) -> Unit, failed: () -> Unit) {
    if (id == null || !agents.hasCharacterCard(id)) return
    scope.launch {
        val result = runCatching { withContext(Dispatchers.IO) { checkNotNull(agents.get(id)?.characterCard) } }
        if (!current()) return@launch
        result.fold(onSuccess = loaded, onFailure = {
            if (it is CancellationException) throw it
            failed()
        })
    }
}
