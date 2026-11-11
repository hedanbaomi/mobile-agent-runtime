// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import runtime.mobileagent.domain.AppException
import java.io.IOException

/** Resources created during initialization are retired in reverse order on failure. */
internal class HostResources : AutoCloseable {
    private val actions = ArrayDeque<() -> Unit>()
    private var closed = false
    @Synchronized fun onClose(action: () -> Unit) {
        check(!closed) { "Host resources are closed" }
        actions.addFirst(action)
    }
    fun <T> own(value: T, release: (T) -> Unit): T {
        try { onClose { release(value) } }
        catch (failure: Throwable) { runCatching { release(value) }; throw failure }
        return value
    }
    override fun close() {
        val pending = synchronized(this) {
            if (closed) return
            closed = true
            actions.toList().also { actions.clear() }
        }
        // A callback being drained may itself finish a lazy allocation. Never hold this lock while draining.
        pending.forEach { runCatching(it) }
    }
}

internal enum class HostStartupStage { DATABASE, MIGRATION, CONTAINER, RUN_RECOVERY }
internal enum class HostStartupError { STORAGE, CREDENTIALS, CONFIGURATION, INITIALIZATION }
internal data class HostStartupFailure(val stage: HostStartupStage, val code: HostStartupError) {
    /** No exception text, paths, identities, tokens or user content cross this export. */
    fun diagnosticText(): String = "host-startup-v1\nstage=${stage.name}\ncode=${code.name}\n"
}

internal fun hostStartupFailure(stage: HostStartupStage, error: Throwable): HostStartupFailure =
    HostStartupFailure(stage, when (error) {
        is SecurityException -> HostStartupError.CREDENTIALS
        is IOException -> HostStartupError.STORAGE
        is AppException -> HostStartupError.CONFIGURATION
        else -> HostStartupError.INITIALIZATION
    })

/** Drain resources and storage as one retirement boundary, preserving the caller's interrupt. */
internal fun retireHostAttempt(releaseResources: () -> Unit, closeStorage: () -> Unit) {
    var interrupted = Thread.interrupted()
    try {
        releaseResources()
        interrupted = Thread.interrupted() || interrupted
        while (true) {
            try { closeStorage(); break }
            catch (_: InterruptedException) { interrupted = true }
        }
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}
