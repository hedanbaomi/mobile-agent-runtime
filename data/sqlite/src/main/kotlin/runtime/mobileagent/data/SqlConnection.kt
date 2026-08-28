// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

data class SqlRow(val columns: Map<String, Any?>) {
    fun string(name: String): String = columns[name]?.toString().orEmpty()
    fun long(name: String): Long =
        (columns[name] as? Number)?.toLong() ?: columns[name]?.toString()?.toLongOrNull() ?: 0L
}

interface SqlConnection {
    fun execute(sql: String, args: List<Any?> = emptyList())
    fun query(sql: String, args: List<Any?> = emptyList()): List<SqlRow>
    fun <T> transaction(block: () -> T): T
}
