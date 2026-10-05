// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

/** User-selected search service. Destinations and authentication are runtime-owned. */
enum class WebSearchProvider(val id: String, val displayName: String) {
    BRAVE("brave", "Brave"),
    TAVILY("tavily", "Tavily"),
    EXA("exa", "Exa");

    companion object {
        fun fromId(id: String): WebSearchProvider? = entries.firstOrNull { it.id == id }
    }
}
