// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import runtime.mobileagent.AppContainer
import runtime.mobileagent.domain.trpg.TrpgJournalView
import runtime.mobileagent.domain.trpg.TrpgSession

/** UI callbacks carry the actual write receipt; a failed optional refresh cannot negate a commit. */
internal data class TrpgSessionUiCallbacks(
    val session: (TrpgSession) -> Unit,
    val view: (TrpgJournalView) -> Unit,
    val error: (String?) -> Unit,
    val saving: (Boolean) -> Unit,
)

internal suspend fun mutateTrpgUi(
    container: AppContainer, conversationId: String, chinese: Boolean, ui: TrpgSessionUiCallbacks,
    onCommitted: () -> Unit = {}, action: () -> TrpgSession,
): TrpgSession {
    ui.saving(true)
    ui.error(null)
    try {
        val updated = withContext(Dispatchers.IO) { action() }
        onCommitted()
        ui.session(updated)
        val view = try { withContext(Dispatchers.IO) { container.trpg.journalView(conversationId) } }
            catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
        if (view != null) ui.view(view)
        return updated
    } catch (cancel: CancellationException) { throw cancel
    } catch (failure: Exception) {
        val latest = try { withContext(Dispatchers.IO) { container.trpg.journalView(conversationId) } }
            catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
        if (latest != null) ui.view(latest)
        ui.error(if (chinese) "操作未完成：版本冲突、资料无效、活动运行或存储失败。草稿已保留，请核对后重试。"
            else "Action failed: revision conflict, invalid data, active run or storage error. Draft retained; review and retry.")
        throw failure
    } finally { ui.saving(false) }
}

internal fun launchTrpgUiMutation(
    scope: CoroutineScope, container: AppContainer, conversationId: String, chinese: Boolean,
    ui: TrpgSessionUiCallbacks, onCommitted: () -> Unit, action: () -> TrpgSession,
) {
    // Set before dispatch, so two clicks in the same composition cannot queue two writes.
    ui.saving(true)
    scope.launch {
        try { mutateTrpgUi(container, conversationId, chinese, ui, onCommitted, action) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* The shared mutation path already retained input and exposed the error. */ }
    }
}
