// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Bounded, cancellable child-process execution for the manager observation.
 *
 * The deadline covers process start, reading and exit. Reads happen on a daemon thread with a
 * byte-level stream read, so a child that emits a partial multi-byte UTF-8 sequence and then sleeps
 * cannot block the caller past the budget (`BufferedReader.read()` would wait for the rest of the
 * sequence). Output is capped, decoding is strict, and the child is force-killed on timeout.
 */
internal object BoundedProcess {
    data class Output(val exitCode: Int, val text: String)

    /**
     * Runs [command] and returns its stdout on a clean exit, or null on timeout, overflow, kill,
     * non-zero exit, malformed UTF-8 or a cancellation request.
     */
    fun run(
        command: List<String>,
        timeoutMs: Long,
        maxBytes: Int = 4_096,
        isCancelled: () -> Boolean = { false },
    ): Output? {
        if (isCancelled()) return null
        // The deadline is fixed BEFORE the child starts so process creation is inside the budget.
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        val process = runCatching {
            ProcessBuilder(command).redirectErrorStream(true).start()
        }.getOrNull() ?: return null
        val buffer = ByteArrayOutputStream()
        val overflow = java.util.concurrent.atomic.AtomicBoolean(false)
        val readFailed = java.util.concurrent.atomic.AtomicBoolean(false)
        val reader = Thread({
            try {
                val chunk = ByteArray(256)
                var done = false
                while (!done) {
                    val read = process.inputStream.read(chunk)
                    if (read < 0) {
                        done = true
                    } else {
                        synchronized(buffer) {
                            if (buffer.size() + read > maxBytes) {
                                overflow.set(true)
                                done = true
                            } else {
                                buffer.write(chunk, 0, read)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // An IO failure is never a clean EOF: the caller must see null, not empty output.
                readFailed.set(true)
            }
        }, "mar-bounded-process-reader").apply { isDaemon = true; start() }

        try {
            while (reader.isAlive) {
                if (isCancelled() || overflow.get() || readFailed.get() || System.nanoTime() > deadline) return null
                reader.join(10L)
            }
            if (readFailed.get() || overflow.get()) return null
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
            if (isCancelled() || remainingMs <= 0) return null
            if (!process.waitFor(remainingMs, java.util.concurrent.TimeUnit.MILLISECONDS)) return null
            if (isCancelled() || process.exitValue() != 0) return null
            val bytes = synchronized(buffer) { buffer.toByteArray() }
            val text = decodeStrict(bytes) ?: return null
            return Output(exitCode = process.exitValue(), text = text)
        } finally {
            runCatching { process.destroyForcibly() }
            runCatching { process.inputStream.close() }
            runCatching { reader.join(50L) }
        }
    }

    /** Strict UTF-8 decoding: a partial/malformed sequence is "unknown", never an empty success. */
    private fun decodeStrict(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
}
