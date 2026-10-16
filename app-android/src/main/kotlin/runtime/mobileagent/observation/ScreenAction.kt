// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.observation

import kotlinx.coroutines.flow.StateFlow
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.BuildConfig
import runtime.mobileagent.tooling.AuthorityManagerState

/** Local authorization binding; never exposed in model arguments or results. */
data class ScreenActionAuthority(val authority: Authority, val selectionRevision: Long)

internal object ScreenActionAdmission {
    fun from(state: AuthorityManagerState,
        buildAllowed: Boolean = !BuildConfig.DEBUG && BuildConfig.HIGH_PRIVILEGE_CONTROL_PLANE_ENABLED): ScreenActionAuthority? {
        if (!buildAllowed) return null
        val selected = state.selectedAuthority?.takeIf { it != Authority.NONE } ?: return null
        if (state.statuses[selected]?.isReady != true) return null
        return ScreenActionAuthority(selected, state.persistenceRevision)
    }
}

enum class ScreenAction(val wireName: String, val needsNode: Boolean = true) {
    CLICK("click"), LONG_CLICK("long_click"), SET_TEXT("set_text"),
    SCROLL_FORWARD("scroll_forward"), SCROLL_BACKWARD("scroll_backward"),
    BACK("back", false), HOME("home", false), RECENTS("recents", false),
}

data class ScreenActionRequest(val observationId: String, val action: ScreenAction,
    val nodeIndex: Int? = null, val text: String? = null)

interface ScreenActionPort {
    val access: StateFlow<ScreenAccess>
    /** Calls onDispatch exactly before the OS action, after the last authorization check. */
    suspend fun perform(request: ScreenActionRequest, authorized: suspend () -> Boolean, onDispatch: () -> Unit): Boolean
}
