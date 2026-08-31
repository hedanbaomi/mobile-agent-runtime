/*
 * SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package runtime.mobileagent.skills.tooling

import java.nio.charset.StandardCharsets

/** Shared model-facing result budget; adapters must reject oversized payloads. */
object ToolResultBudget {
    const val MAX_SERIALIZED_BYTES: Long = 1L * 1024L * 1024L

    fun withinSerializedBudget(serialized: String): Boolean =
        serialized.toByteArray(StandardCharsets.UTF_8).size.toLong() < MAX_SERIALIZED_BYTES

    fun requireWithinSerializedBudget(serialized: String) {
        require(withinSerializedBudget(serialized)) { "ToolResult exceeds the AgentRuntime serialization budget" }
    }
}
