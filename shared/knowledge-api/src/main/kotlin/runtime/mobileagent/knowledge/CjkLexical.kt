// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

object CjkLexical {
    fun indexText(text: String): String {
        val out = StringBuilder()
        val cjk = StringBuilder()
        fun flushCjk() {
            if (cjk.isEmpty()) return
            val run = cjk.toString()
            run.forEach { ch -> out.append(ch).append(' ') }
            if (run.length >= 2) {
                for (i in 0 until run.length - 1) {
                    out.append(run.substring(i, i + 2)).append(' ')
                }
            }
            cjk.clear()
        }
        for (ch in text) {
            if (ch in '\u4e00'..'\u9fff') {
                cjk.append(ch)
            } else {
                flushCjk()
                out.append(ch)
            }
        }
        flushCjk()
        return out.toString().trim()
    }
}
