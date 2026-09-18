// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

data class SqlRow(val columns: Map<String, Any?>) {
    fun string(name: String): String = columns[name]?.toString().orEmpty()
    fun long(name: String): Long =
        (columns[name] as? Number)?.toLong() ?: columns[name]?.toString()?.toLongOrNull() ?: 0L

    /** Nullable INTEGER, so a genuinely absent capability stays unknown instead of 0. */
    fun longOrNull(name: String): Long? {
        val value = columns[name] ?: return null
        return (value as? Number)?.toLong() ?: value.toString().toLongOrNull()
    }
}

interface SqlConnection {
    fun execute(sql: String, args: List<Any?> = emptyList())
    fun query(sql: String, args: List<Any?> = emptyList()): List<SqlRow>
    fun <T> transaction(block: () -> T): T
}
