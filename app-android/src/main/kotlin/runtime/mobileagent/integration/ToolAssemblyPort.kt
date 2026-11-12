// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.tooling.ToolExecutionContext
import runtime.mobileagent.tooling.ToolExecutorFactory

/**
 * Narrow tool assembly port for run lifecycle and executor composition.
 */
interface ToolAssemblyPort {
    fun createToolExecutorFactory(
        context: ToolExecutionContext,
        web: ToolExecutor? = null,
        mcp: ToolExecutor? = null,
        python: ToolExecutor? = null,
    ): ToolExecutorFactory

    fun toolExposureDiagnostics(context: ToolExecutionContext): RuntimeToolExposureDiagnostics

    fun frozenRootWorkspaceIds(context: ToolExecutionContext): Set<String>
}

fun interface ToolAssemblyPortProvider {
    fun toolAssemblyPort(): ToolAssemblyPort
}
