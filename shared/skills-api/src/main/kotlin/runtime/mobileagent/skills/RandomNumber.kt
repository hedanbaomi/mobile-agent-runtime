// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import kotlin.random.Random

internal object RandomNumber {
    fun draw(min: Int, max: Int, random: Random): Int {
        require(min <= max) { "min must be less than or equal to max" }
        // Widen before adding one: the inclusive Int.MAX_VALUE endpoint must not overflow.
        // nextLong uses uniform bounded sampling, including the full signed Int range.
        return if (min == max) min else random.nextLong(min.toLong(), max.toLong() + 1).toInt()
    }
}
