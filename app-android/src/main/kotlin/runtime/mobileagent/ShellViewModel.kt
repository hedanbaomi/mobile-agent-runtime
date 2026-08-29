// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import runtime.mobileagent.ui.AppRoutes

class ShellViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    fun route(): String = savedStateHandle.get<String>(KEY) ?: AppRoutes.CHAT

    fun setRoute(value: String) {
        savedStateHandle[KEY] = value
    }

    private companion object {
        const val KEY = "shell.route"
    }
}
