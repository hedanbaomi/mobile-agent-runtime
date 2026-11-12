// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import runtime.mobileagent.memory.SkillMemoryAvailabilitySnapshot

/**
 * Narrow read-only query interface for skill memory availability projection.
 */
interface SkillMemoryAvailabilityPort {
    fun skillMemoryAvailability(
        agentId: String,
        snapshotId: String,
        trustedSkillId: String,
    ): SkillMemoryAvailabilitySnapshot
}

fun interface SkillMemoryAvailabilityPortProvider {
    fun skillMemoryAvailabilityPort(): SkillMemoryAvailabilityPort
}
