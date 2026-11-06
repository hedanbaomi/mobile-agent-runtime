// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.SharedPreferences
import runtime.mobileagent.feature.chat.ChatPromptLayerUi
import runtime.mobileagent.feature.chat.ChatRequestInspectorAvailability
import runtime.mobileagent.feature.chat.ChatRequestPreviewUi
import runtime.mobileagent.feature.chat.ChatUiState

internal const val REQUEST_INSPECTOR_PREFERENCE = "request-inspector"
internal val SharedPreferences.requestInspectorEnabled get() = getBoolean(REQUEST_INSPECTOR_PREFERENCE, true)

private const val PREVIEW_HINT_PREFIX = "request-inspector.preview-hint."

internal fun SharedPreferences.hadRequestPreview(sessionId: String?): Boolean =
    !sessionId.isNullOrBlank() && getBoolean(PREVIEW_HINT_PREFIX + sessionId, false)

internal fun SharedPreferences.rememberRequestPreview(sessionId: String) {
    edit().putBoolean(PREVIEW_HINT_PREFIX + sessionId, true).apply()
}

/** Main-thread request content, scoped to a conversation and its Agent. Only the prepared hint survives restart. */
internal class ChatRequestPreviews {
    private data class Entry(val agentId: String, val layers: List<ChatPromptLayerUi>, val preview: ChatRequestPreviewUi? = null)
    private val sessions = mutableMapOf<String, Entry>()
    var generation = 0L
        private set

    fun observe(preferences: SharedPreferences, refresh: () -> Unit): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == REQUEST_INSPECTOR_PREFERENCE || key == null) {
                clear()
                refresh()
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
            clear()
        }
    }

    fun canCapture(runGeneration: Long, enabled: Boolean) = enabled && runGeneration == generation

    fun clear() {
        sessions.clear()
        generation++
    }

    fun begin(sessionId: String) { sessions.remove(sessionId) }

    fun leave(sessionId: String?) {
        if (sessions[sessionId]?.preview == null) sessions.remove(sessionId)
    }

    fun setLayers(sessionId: String, agentId: String, layers: List<ChatPromptLayerUi>) {
        sessions[sessionId] = Entry(agentId, layers)
    }

    fun prepared(sessionId: String, agentId: String, preview: ChatRequestPreviewUi?) {
        val layers = sessions[sessionId]?.takeIf { it.agentId == agentId }?.layers.orEmpty()
        sessions[sessionId] = Entry(agentId, layers, preview)
    }

    fun project(state: ChatUiState, enabled: Boolean, persistedHint: Boolean): ChatUiState {
        val entry = sessions[state.selectedSessionId]?.takeIf { enabled && it.agentId == state.selectedAgentId }
        return state.copy(
            requestPreview = entry?.preview,
            promptLayers = entry?.layers.orEmpty(),
            requestInspectorAvailability = resolveRequestInspectorAvailability(enabled, entry?.preview != null, persistedHint),
        )
    }
}

internal fun resolveRequestInspectorAvailability(
    inspectorEnabled: Boolean,
    previewAvailable: Boolean,
    persistedPreviewHint: Boolean,
): ChatRequestInspectorAvailability = when {
    !inspectorEnabled -> ChatRequestInspectorAvailability.DISABLED
    previewAvailable -> ChatRequestInspectorAvailability.READY
    persistedPreviewHint -> ChatRequestInspectorAvailability.CONTEXT_LOST
    else -> ChatRequestInspectorAvailability.NOT_PREPARED
}
