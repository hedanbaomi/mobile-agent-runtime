// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import runtime.mobileagent.data.ChatDraftOwner
import runtime.mobileagent.data.ChatDraftRepository

/** One process writer serializes edits; jobs survive page/VM recreation, but never process death. */
internal class ChatDraftPersistence(
    private val repository: ChatDraftRepository,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = 350,
) {
    private data class Edit(val revision: Long, val body: String, val failure: (Exception) -> Unit)
    private val lock = Any()
    private val writer = Mutex()
    private val pending = mutableMapOf<ChatDraftOwner, Edit>()
    private val jobs = mutableMapOf<ChatDraftOwner, Job>()
    private var revision = 0L

    fun stage(owner: ChatDraftOwner, body: String, failure: (Exception) -> Unit = {}) {
        synchronized(lock) {
            pending[owner] = Edit(++revision, body, failure)
            jobs.remove(owner)?.cancel()
            jobs[owner] = scope.launch(dispatcher) {
                try {
                    delay(debounceMs)
                    flush(owner)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Retained pending text and the callback describe failure. */ }
                finally {
                    val current = currentCoroutineContext()[Job]
                    synchronized(lock) { if (jobs[owner] === current) jobs.remove(owner) }
                }
            }
        }
    }

    suspend fun load(owner: ChatDraftOwner): String = withContext(dispatcher) {
        writer.withLock {
            val stored = repository.load(owner)
            synchronized(lock) { pending[owner]?.body } ?: stored
        }
    }

    suspend fun flush(owner: ChatDraftOwner) = withContext(dispatcher) {
        writer.withLock {
            val edit = synchronized(lock) { pending[owner] } ?: return@withLock
            try {
                // SQLite completion must not be interrupted after the write starts.
                withContext(NonCancellable) { repository.save(owner, edit.body) }
                synchronized(lock) {
                    if (pending[owner]?.revision == edit.revision) pending.remove(owner)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                edit.failure(failure)
                throw failure
            }
        }
    }

    suspend fun flushAll() {
        val owners = synchronized(lock) { pending.keys.toList() }
        owners.forEach { owner ->
            try { flush(owner) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Other owners still need their durable write. */ }
        }
    }

    fun requestFlushAll() { scope.launch(dispatcher) { flushAll() } }
}
