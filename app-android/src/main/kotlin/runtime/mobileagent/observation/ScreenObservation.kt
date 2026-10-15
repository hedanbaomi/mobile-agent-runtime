// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.observation

import kotlinx.coroutines.flow.StateFlow

data class ScreenAccess(val consent: Boolean = false, val connected: Boolean = false, val revision: Long = 0) {
    val ready: Boolean get() = consent && connected
}

data class ScreenNode(
    val index: Int, val parent: Int?, val text: String, val description: String,
    val role: String, val bounds: List<Int>, val clickable: Boolean,
    val editable: Boolean, val scrollable: Boolean, val enabled: Boolean,
)

data class ScreenSnapshot(
    val packageName: String, val windowId: Int, val sequence: Long,
    val capturedAtElapsedMs: Long, val nodes: List<ScreenNode>,
    val truncated: Boolean, val stable: Boolean,
)

/** No screen content is retained in either observable flow. */
interface ScreenObservationPort {
    val access: StateFlow<ScreenAccess>
    val changes: StateFlow<Long>
    suspend fun capture(expectedPackage: String?): ScreenSnapshot
}

class ScreenObservationException(val code: String) : Exception(code)
