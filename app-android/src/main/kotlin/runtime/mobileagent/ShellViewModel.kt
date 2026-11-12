// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import runtime.mobileagent.agent.RuntimeMessageIds
import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.data.ProfileRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.ui.AppRoutes
import runtime.mobileagent.ui.QuickStartUiState
import java.util.concurrent.atomic.AtomicLong

internal const val KEY_QUICK_START_COLLAPSED = "quick-start-collapsed"

/**
 * Only a genuinely completed run that produced a non-blank persisted assistant reply satisfies the
 * first-chat QuickStart step. Failed, cancelled, budget-exhausted, unknown-outcome, or silent
 * empty-answer runs leave the MESSAGE guidance available.
 */
internal fun hasCompletedFirstChat(db: SqlConnection): Boolean {
    // The runtime already persists a stable assistant message ID per run and model request.
    // Use the final request so a completed tool round cannot stand in for the final reply.
    var afterRunId = ""
    while (true) {
        val runs = db.query(
            """SELECT run_id, conversation_id, model_rounds FROM runs
               WHERE state = 'COMPLETED' AND model_rounds > 0 AND run_id > ?
               ORDER BY run_id LIMIT 64""",
            listOf(afterRunId),
        )
        if (runs.isEmpty()) return false
        val candidates = runs.associate { row ->
            RuntimeMessageIds.assistant(row.string("run_id"), row.long("model_rounds").toInt()) to
                row.string("conversation_id")
        }
        val replies = db.query(
            """SELECT id, conversation_id FROM messages
               WHERE id IN (${candidates.keys.joinToString(",") { "?" }})
                 AND role = 'ASSISTANT' AND status IN ('COMPLETE', 'COMPLETED')
                 AND trim(text, ' ' || char(9) || char(10) || char(13)) != ''""",
            candidates.keys.toList(),
        )
        if (replies.any { candidates[it.string("id")] == it.string("conversation_id") }) return true
        afterRunId = runs.last().string("run_id")
    }
}

internal fun readQuickStartStep(
    profiles: ProfileRepository,
    agents: AgentRepository,
    db: SqlConnection,
): QuickStartStep = quickStartStep(
    hasChatModel = profiles.listModels().any { it.role == ModelRole.CHAT },
    hasAgent = agents.listOverviews().isNotEmpty(),
    hasCompletedRun = hasCompletedFirstChat(db),
)

internal class QuickStartController(
    private val preferencesProvider: () -> SharedPreferences?,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val loadStep: () -> QuickStartStep?,
) {
    private val refreshGeneration = AtomicLong(0L)
    private var refreshJob: Job? = null

    private val _step = MutableStateFlow<QuickStartStep?>(null)
    val step: StateFlow<QuickStartStep?> = _step.asStateFlow()

    private val _collapsed = MutableStateFlow(
        runCatching { preferencesProvider()?.getBoolean(KEY_QUICK_START_COLLAPSED, false) ?: false }.getOrDefault(false),
    )
    val collapsed: StateFlow<Boolean> = _collapsed.asStateFlow()

    private val _awaitingPostStreamRefresh = MutableStateFlow(false)
    val awaitingPostStreamRefresh: StateFlow<Boolean> = _awaitingPostStreamRefresh.asStateFlow()

    fun sync(streaming: Boolean) {
        if (streaming) {
            refreshGeneration.incrementAndGet()
            refreshJob?.cancel()
            _awaitingPostStreamRefresh.value = true
            return
        }
        refresh()
    }

    fun refresh() {
        val generation = refreshGeneration.incrementAndGet()
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val resolved = withContext(ioDispatcher) {
                runCatching { loadStep() }.getOrNull()
            }
            if (refreshGeneration.get() == generation) {
                _step.value = resolved
                _awaitingPostStreamRefresh.value = false
            }
        }
    }

    suspend fun syncAndAwait(streaming: Boolean): QuickStartStep? {
        if (streaming) {
            refreshGeneration.incrementAndGet()
            refreshJob?.cancel()
            _awaitingPostStreamRefresh.value = true
            return _step.value
        }
        val generation = refreshGeneration.incrementAndGet()
        refreshJob?.cancel()
        val resolved = withContext(ioDispatcher) {
            runCatching { loadStep() }.getOrNull()
        }
        if (refreshGeneration.get() == generation) {
            _step.value = resolved
            _awaitingPostStreamRefresh.value = false
        }
        return _step.value
    }

    fun setCollapsed(value: Boolean) {
        _collapsed.value = value
        runCatching {
            preferencesProvider()?.edit()?.putBoolean(KEY_QUICK_START_COLLAPSED, value)?.apply()
        }
    }

    fun uiState(
        route: String,
        detailOpen: Boolean,
        streaming: Boolean,
        currentStep: QuickStartStep? = _step.value,
        isCollapsed: Boolean = _collapsed.value,
        awaitingRefresh: Boolean = _awaitingPostStreamRefresh.value,
    ): QuickStartUiState {
        val visible = !detailOpen && route == AppRoutes.CHAT && !streaming && !awaitingRefresh
        return QuickStartUiState(
            step = currentStep,
            collapsed = isCollapsed,
            visible = visible,
        )
    }
}

class ShellViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val app = application as MobileAgentApp

    private val quickStart = QuickStartController(
        preferencesProvider = { runCatching { app.container.uiPreferences }.getOrNull() },
        scope = viewModelScope,
    ) {
        val container = app.container
        readQuickStartStep(container.profiles, container.agents, container.db)
    }

    internal val quickStartStep: StateFlow<QuickStartStep?> = quickStart.step
    internal val quickStartCollapsed: StateFlow<Boolean> = quickStart.collapsed
    internal val quickStartAwaitingRefresh: StateFlow<Boolean> = quickStart.awaitingPostStreamRefresh

    fun route(): String = savedStateHandle.get<String>(KEY) ?: AppRoutes.CHAT

    fun setRoute(value: String) {
        savedStateHandle[KEY] = value
    }

    internal fun syncQuickStart(streaming: Boolean) = quickStart.sync(streaming)

    internal fun refreshQuickStart() = quickStart.refresh()

    internal fun setQuickStartCollapsed(value: Boolean) = quickStart.setCollapsed(value)

    internal fun quickStartUiState(
        route: String,
        detailOpen: Boolean,
        streaming: Boolean,
        step: QuickStartStep? = quickStartStep.value,
        collapsed: Boolean = quickStartCollapsed.value,
        awaitingRefresh: Boolean = quickStartAwaitingRefresh.value,
    ): QuickStartUiState = quickStart.uiState(route, detailOpen, streaming, step, collapsed, awaitingRefresh)

    internal fun onQuickStartStepClick(
        stepId: String,
        onChooseAgent: () -> Unit,
        onNavigate: (String) -> Unit,
    ) {
        when (stepId) {
            QuickStartStep.MESSAGE.name -> onChooseAgent()
            QuickStartStep.MODEL.name -> onNavigate(AppRoutes.PROVIDERS)
            QuickStartStep.AGENT.name -> onNavigate(AppRoutes.AGENTS)
            else -> onNavigate(AppRoutes.CHAT)
        }
    }

    private companion object {
        const val KEY = "shell.route"
    }
}
