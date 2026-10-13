// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.data.*

class ChatDraftPersistenceTest {
    @Test fun latestEditWinsEvenWhenAnOlderWriteIsAlreadyRunning() = runBlocking {
        DraftConnection().use { raw ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val first = AtomicBoolean(true)
            val observed = object : SqlConnection by raw {
                override fun execute(sql: String, args: List<Any?>) {
                    if (sql.startsWith("INSERT INTO app_prefs") && first.compareAndSet(true, false)) {
                        started.countDown(); check(release.await(10, TimeUnit.SECONDS))
                    }
                    raw.execute(sql, args)
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val repo = ChatDraftRepository(observed)
                val writer = ChatDraftPersistence(repo, scope, debounceMs = 60_000)
                val owner = ChatDraftOwner("unassigned", "composer")
                writer.stage(owner, "older")
                val flush = async(Dispatchers.IO) { writer.flush(owner) }
                try {
                    assertTrue(started.await(10, TimeUnit.SECONDS))
                    writer.stage(owner, "newer")
                } finally { release.countDown() }
                flush.await()
                assertEquals("newer", writer.load(owner))
                writer.flush(owner)
                assertEquals("newer", repo.load(owner))
            } finally { release.countDown(); scope.cancel() }
        }
    }

    @Test fun failedSaveRetainsInputUntilAnExplicitRetrySucceeds() = runBlocking {
        DraftConnection().use { raw ->
            val failWrite = AtomicBoolean(true)
            val db = object : SqlConnection by raw {
                override fun execute(sql: String, args: List<Any?>) {
                    if (sql.startsWith("INSERT INTO app_prefs") && failWrite.get()) error("synthetic disk failure")
                    raw.execute(sql, args)
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val writer = ChatDraftPersistence(ChatDraftRepository(db), scope, debounceMs = 60_000)
                val owner = ChatDraftOwner("unassigned", "composer")
                var failures = 0
                writer.stage(owner, "retained") { failures++ }
                assertThrows(IllegalStateException::class.java) { runBlocking { writer.flush(owner) } }
                assertEquals(1, failures)
                assertEquals("retained", writer.load(owner))
                failWrite.set(false)
                writer.flush(owner)
                assertEquals("retained", ChatDraftRepository(raw).load(owner))
            } finally { scope.cancel() }
        }
    }

    @Test fun lifecycleFlushKeepsLargeOwnersIsolatedAcrossWriterRecreation() = runBlocking {
        DraftConnection().use { db ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val repo = ChatDraftRepository(db)
                val writer = ChatDraftPersistence(repo, scope, debounceMs = 60_000)
                val first = ChatDraftOwner("agent", "first")
                val second = ChatDraftOwner("agent", "second")
                val large = "中a\n".repeat(200_000)
                writer.stage(first, large); writer.stage(second, "other")
                writer.flushAll()
                val restored = ChatDraftPersistence(ChatDraftRepository(db), scope, debounceMs = 60_000)
                assertEquals(large, restored.load(first))
                assertEquals("other", restored.load(second))
                writer.stage(first, ""); writer.flush(first)
                repo.migrateLegacy(first, "stale restored Bundle")
                assertEquals("", restored.load(first))
            } finally { scope.cancel() }
        }
    }
}

internal class DraftConnection : SqlConnection, AutoCloseable {
    private val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    init {
        execute("CREATE TABLE app_prefs(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        execute("CREATE TABLE agent_profiles(id TEXT PRIMARY KEY)")
        execute("INSERT INTO agent_profiles VALUES('first'),('second')")
    }
    @Synchronized override fun execute(sql: String, args: List<Any?>) {
        connection.prepareStatement(sql).use { s -> args.forEachIndexed { i, value -> s.setObject(i+1, value) }; s.execute() }
    }
    @Synchronized override fun query(sql: String, args: List<Any?>): List<SqlRow> = connection.prepareStatement(sql).use { s ->
        args.forEachIndexed { i, value -> s.setObject(i+1, value) }
        s.executeQuery().use { result -> buildList { while (result.next()) add(SqlRow((1..result.metaData.columnCount).associate {
            result.metaData.getColumnLabel(it) to result.getObject(it)
        })) } }
    }
    @Synchronized override fun <T> transaction(block: () -> T): T {
        if (!connection.autoCommit) return block()
        connection.autoCommit = false
        return try { block().also { connection.commit() } }
        catch (failure: Throwable) { connection.rollback(); throw failure }
        finally { connection.autoCommit = true }
    }
    override fun close() = connection.close()
}
