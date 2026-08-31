// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

class JdbcSqlConnection(url: String = "jdbc:sqlite::memory:") : SqlConnection, AutoCloseable {
    private val connection: Connection = DriverManager.getConnection(url).apply {
        createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
    }

    override fun execute(sql: String, args: List<Any?>) {
        // Xerial SQLite reports a result set for ALTER TABLE ... ADD COLUMN statements
        // containing CHECK constraints.  executeUpdate() then throws "Query returns results"
        // even though the DDL succeeded; execute() is the JDBC contract for mixed SQLite DDL/DML.
        prepare(sql, args).use { it.execute() }
    }

    override fun query(sql: String, args: List<Any?>): List<SqlRow> {
        prepare(sql, args).use { stmt ->
            stmt.executeQuery().use { rs ->
                val rows = mutableListOf<SqlRow>()
                val meta = rs.metaData
                while (rs.next()) {
                    val map = linkedMapOf<String, Any?>()
                    for (i in 1..meta.columnCount) {
                        map[meta.getColumnLabel(i)] = rs.getObject(i)
                    }
                    rows += SqlRow(map)
                }
                return rows
            }
        }
    }

    override fun <T> transaction(block: () -> T): T {
        val prev = connection.autoCommit
        connection.autoCommit = false
        return try {
            val result = block()
            connection.commit()
            result
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            connection.autoCommit = prev
        }
    }

    private fun prepare(sql: String, args: List<Any?>): PreparedStatement {
        val stmt = connection.prepareStatement(sql)
        args.forEachIndexed { index, value -> stmt.setObject(index + 1, value) }
        return stmt
    }

    override fun close() = connection.close()
}
