// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.feature.agents.AgentResourceBindingUi

internal fun worldBookIds(editor: runtime.mobileagent.feature.agents.AgentEditorUi): List<String> =
    editor.resourceBindings.filter { it.type == "worldbook" && it.enabled }.map { it.id }

internal fun worldBookBindings(container: AppContainer, agent: AgentProfile?): List<AgentResourceBindingUi> {
    val chinese = when (container.settings.locale()) {
        runtime.mobileagent.domain.LocalePreference.ZH_CN -> true
        runtime.mobileagent.domain.LocalePreference.EN_US -> false
        runtime.mobileagent.domain.LocalePreference.SYSTEM -> java.util.Locale.getDefault().language == "zh"
    }
    return container.worldBooks.list().map { book ->
        AgentResourceBindingUi(book.id, book.name, "worldbook", book.id in agent?.worldBookIds.orEmpty(),
            permissionSummary = if (book.enabled) {
                if (chinese) "世界书 · 新对话默认使用，正文按触发规则提供" else "World book · defaults for new chats; content follows activation rules"
            } else {
                if (chinese) "世界书 · 已停用，保留关联" else "World book · disabled; association retained"
            }, available = true)
    }
}
