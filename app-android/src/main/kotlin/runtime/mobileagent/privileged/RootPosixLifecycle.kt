// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

/**
 * Close/ordering barrier shared by inspection, reads and mutations. A close closes every FD that
 * is still registered (including parent/target FDs of an operation that already consumed its
 * retained handle), refuses new opens, and serializes against commit-time syscalls so close
 * cannot interleave a rename with a half-finished commit.
 */
internal class RootPosixLifecycle {
    private val gate = Any()
    @Volatile
    var closed: Boolean = false
        private set

    private val openFds = mutableSetOf<PosixRetainedFd>()

    fun isActive(): Boolean = !closed && !Thread.currentThread().isInterrupted

    fun requireActive() {
        if (!isActive()) throw PosixOpException("ECANCELED")
    }

    fun openTracked(open: () -> PosixRetainedFd?): PosixRetainedFd? = synchronized(gate) {
        if (!isActive()) return@synchronized null
        openFds.removeAll { !it.isOpen }
        val fd = open() ?: return@synchronized null
        // An injected callback can close reentrantly while open is running.
        if (!isActive()) {
            fd.close()
            return@synchronized null
        }
        openFds += fd
        fd
    }

    fun mutateWhileActive(mutate: () -> Unit) = synchronized(gate) {
        requireActive()
        mutate()
        requireActive()
    }

    fun closeAll(closeRetained: () -> Unit): Unit = synchronized(gate) {
        closed = true
        closeRetained()
        openFds.forEach { runCatching { it.close() } }
        openFds.removeAll { !it.isOpen }
    }

    fun openFdCount(): Int = synchronized(gate) { openFds.count { it.isOpen } }

    fun <T> withGate(block: () -> T): T = synchronized(gate) { block() }
}
