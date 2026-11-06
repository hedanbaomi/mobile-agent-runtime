// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

/** Fixed admission reservation for one inline image. */
const val DEFAULT_IMAGE_INPUT_UNITS = 4_096L

/** Count UTF-8 bytes, charging three bytes for an unpaired surrogate. */
fun conservativeUtf8Units(text: String): Long {
    var units = 0L
    var index = 0
    while (index < text.length) {
        val char = text[index++]
        units += when {
            char.code < 0x80 -> 1
            char.code < 0x800 -> 2
            Character.isHighSurrogate(char) && index < text.length && Character.isLowSurrogate(text[index]) -> {
                index++
                4
            }
            else -> 3
        }
    }
    return units
}

/** Clamp addition at either end of the signed Long range. */
fun saturatingAddUnits(current: Long, extra: Long): Long = when {
    extra > 0L && current > Long.MAX_VALUE - extra -> Long.MAX_VALUE
    extra < 0L && current < Long.MIN_VALUE - extra -> Long.MIN_VALUE
    else -> current + extra
}

/** Zero costs no units. A negative factor or product overflow uses the maximum reservation. */
fun saturatingMultiplyUnits(left: Long, right: Long): Long = when {
    left == 0L || right == 0L -> 0L
    left < 0L || right < 0L -> Long.MAX_VALUE
    left > Long.MAX_VALUE / right -> Long.MAX_VALUE
    else -> left * right
}
