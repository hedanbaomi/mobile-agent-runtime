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
import runtime.mobileagent.data.SqlStorageSize
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock

class AndroidContextSqlite(
    context: Context,
    name: String = "mobile-agent.db",
) : SqlConnection, SqlStorageSize, AutoCloseable {
    private val delegate = BundledSqliteConnection(context.getDatabasePath(name).absolutePath)
    override fun execute(sql: String, args: List<Any?>) = delegate.execute(sql, args)
    override fun query(sql: String, args: List<Any?>) = delegate.query(sql, args)
    override fun <T> transaction(block: () -> T): T = delegate.transaction(block)
    override fun close() = delegate.close()
    override fun allocatedDatabaseBytes(): Long = delegate.allocatedDatabaseBytes()
}

/** One serialized writer and three bounded WAL readers. Logical transactions stay on their writer. */
class BundledSqliteConnection(
    private val path: String,
    private val waitMillis: Long = 5_000,
    readerCount: Int = 3,
) : SqlConnection, SqlStorageSize, AutoCloseable {
    private val writerLock = ReentrantLock(true)
    private val lifetime = ReentrantReadWriteLock(true)
    private val connection: SQLiteConnection
    private val readers: ArrayBlockingQueue<SQLiteConnection>
    private val allReaders: List<SQLiteConnection>

    init {
        require(waitMillis in 1..60_000 && readerCount in 1..8)
        require(path != ":memory:") { "Concurrent WAL connections require a file database" }
        val driver = BundledSQLiteDriver()
        val opened = mutableListOf<SQLiteConnection>()
        try {
            connection = driver.open(path).also { opened += it }
            connection.prepare("PRAGMA journal_mode=WAL").use {
                check(it.step() && it.getText(0).equals("wal", ignoreCase = true)) { "SQLite WAL unavailable" }
            }
            configure(connection)
            connection.prepare("PRAGMA synchronous=FULL").use { it.step() }
            allReaders = List(readerCount) {
                driver.open(path).also {
                    opened += it
                    configure(it)
                    it.prepare("PRAGMA query_only=ON").use { statement -> statement.step() }
                }
            }
            readers = ArrayBlockingQueue(readerCount, true, allReaders)
        } catch (failure: Throwable) {
            opened.forEach { runCatching { it.close() } }
            throw failure
        }
    }

    private fun configure(value: SQLiteConnection) {
        value.prepare("PRAGMA busy_timeout=$waitMillis").use { it.step() }
        value.prepare("PRAGMA foreign_keys=ON").use { it.step() }
    }

    override fun allocatedDatabaseBytes(): Long = listOf(path, "$path-wal", "$path-shm").sumOf { java.io.File(it).length() }
    // JVM monitors are re-entrant, but SQLite transactions are not.  The
    // repository can legitimately call a helper that opens another logical
    // transaction while holding the same connection, so nested scopes use
    // savepoints instead of issuing a second BEGIN.
    private var transactionDepth = 0
    private var savepointSequence = 0L
    private var closed = false
    private var writerPoisoned = false

    override fun close() {
        check(!writerLock.isHeldByCurrentThread) { "Cannot close SQLite inside a transaction" }
        val lock = lifetime.writeLock()
        check(lock.tryLock(waitMillis, TimeUnit.MILLISECONDS)) { "SQLite close wait exceeded" }
        try {
            if (!closed) {
                closed = true
                var failure: Throwable? = null
                (allReaders + connection).forEach { value ->
                    try { value.close() }
                    catch (error: Throwable) {
                        if (failure == null) failure = error else failure!!.addSuppressed(error)
                    }
                }
                failure?.let { throw it }
            }
        } finally {
            lock.unlock()
        }
    }

    override fun execute(sql: String, args: List<Any?>) {
        withWriter {
            connection.prepare(sql).use { stmt ->
                bind(stmt, args)
                stmt.step()
            }
        }
    }

    override fun query(sql: String, args: List<Any?>): List<SqlRow> {
        return withLifetime {
            if (writerLock.isHeldByCurrentThread) return@withLifetime queryConnection(connection, sql, args)
            val reader = readers.poll(waitMillis, TimeUnit.MILLISECONDS) ?: error("SQLite reader wait exceeded")
            try { queryConnection(reader, sql, args) } finally { check(readers.offer(reader)) }
        }
    }

    private fun queryConnection(value: SQLiteConnection, sql: String, args: List<Any?>): List<SqlRow> =
            value.prepare(sql).use { stmt ->
                bind(stmt, args)
                val rows = mutableListOf<SqlRow>()
                while (stmt.step()) {
                    val map = linkedMapOf<String, Any?>()
                    for (i in 0 until stmt.getColumnCount()) {
                        map[stmt.getColumnName(i)] = readColumn(stmt, i)
                    }
                    rows += SqlRow(map)
                }
                rows
            }

    override fun <T> transaction(block: () -> T): T {
        return withWriter {
            val nested = transactionDepth > 0
            val savepoint = if (nested) "mobileagent_sp_${++savepointSequence}" else null
            if (nested) {
                connection.prepare("SAVEPOINT $savepoint").use { it.step() }
            } else {
                connection.prepare("BEGIN IMMEDIATE").use { it.step() }
            }
            transactionDepth += 1
            try {
                val result = block()
                if (nested) {
                    connection.prepare("RELEASE SAVEPOINT $savepoint").use { it.step() }
                } else {
                    connection.prepare("COMMIT").use { it.step() }
                }
                transactionDepth -= 1
                result
            } catch (t: Throwable) {
                transactionDepth = (transactionDepth - 1).coerceAtLeast(0)
                if (nested) {
                    rollback(t, "ROLLBACK TO SAVEPOINT $savepoint")
                    rollback(t, "RELEASE SAVEPOINT $savepoint")
                } else {
                    rollback(t, "ROLLBACK")
                }
                throw t
            }
        }
    }

    private fun <T> withLifetime(block: () -> T): T {
        val lock = lifetime.readLock()
        check(lock.tryLock(waitMillis, TimeUnit.MILLISECONDS)) { "SQLite lifecycle wait exceeded" }
        try { check(!closed) { "SQLite is closed" }; return block() } finally { lock.unlock() }
    }

    private fun <T> withWriter(block: () -> T): T = withLifetime {
        check(writerLock.tryLock(waitMillis, TimeUnit.MILLISECONDS)) { "SQLite writer wait exceeded" }
        try { check(!writerPoisoned) { "SQLite rollback failed; close and reopen the connection" }; block() }
        finally { writerLock.unlock() }
    }

    private fun rollback(original: Throwable, sql: String) {
        try { connection.prepare(sql).use { it.step() } }
        catch (failure: Throwable) { writerPoisoned = true; original.addSuppressed(failure) }
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
