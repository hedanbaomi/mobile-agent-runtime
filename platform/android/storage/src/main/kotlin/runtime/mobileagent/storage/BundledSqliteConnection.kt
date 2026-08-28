// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.storage

import android.content.Context
import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.SqlRow

class AndroidContextSqlite(
    context: Context,
    name: String = "mobile-agent.db",
) : SqlConnection {
    private val lock = Any()
    private val connection: SQLiteConnection = BundledSQLiteDriver().open(
        context.getDatabasePath(name).absolutePath,
    )

    override fun execute(sql: String, args: List<Any?>) {
        synchronized(lock) {
            connection.prepare(sql).use { stmt ->
                bind(stmt, args)
                stmt.step()
            }
        }
    }

    override fun query(sql: String, args: List<Any?>): List<SqlRow> {
        synchronized(lock) {
            connection.prepare(sql).use { stmt ->
                bind(stmt, args)
                val rows = mutableListOf<SqlRow>()
                while (stmt.step()) {
                    val map = linkedMapOf<String, Any?>()
                    for (i in 0 until stmt.getColumnCount()) {
                        map[stmt.getColumnName(i)] = readColumn(stmt, i)
                    }
                    rows += SqlRow(map)
                }
                return rows
            }
        }
    }

    override fun <T> transaction(block: () -> T): T {
        synchronized(lock) {
            connection.prepare("BEGIN IMMEDIATE").use { it.step() }
            return try {
                val result = block()
                connection.prepare("COMMIT").use { it.step() }
                result
            } catch (t: Throwable) {
                runCatching { connection.prepare("ROLLBACK").use { it.step() } }
                throw t
            }
        }
    }

    private fun bind(stmt: SQLiteStatement, args: List<Any?>) {
        args.forEachIndexed { index, value ->
            val i = index + 1
            when (value) {
                null -> stmt.bindNull(i)
                is ByteArray -> stmt.bindBlob(i, value)
                is Long -> stmt.bindLong(i, value)
                is Int -> stmt.bindLong(i, value.toLong())
                is Double -> stmt.bindDouble(i, value)
                is Float -> stmt.bindDouble(i, value.toDouble())
                is Boolean -> stmt.bindLong(i, if (value) 1 else 0)
                else -> stmt.bindText(i, value.toString())
            }
        }
    }

    private fun readColumn(stmt: SQLiteStatement, index: Int): Any? {
        return when (stmt.getColumnType(index)) {
            SQLITE_DATA_NULL -> null
            SQLITE_DATA_INTEGER -> stmt.getLong(index)
            SQLITE_DATA_FLOAT -> stmt.getDouble(index)
            SQLITE_DATA_BLOB -> stmt.getBlob(index)
            SQLITE_DATA_TEXT -> stmt.getText(index)
            else -> if (stmt.isNull(index)) null else stmt.getText(index)
        }
    }
}
