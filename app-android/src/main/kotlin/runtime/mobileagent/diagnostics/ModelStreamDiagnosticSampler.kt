// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.diagnostics

/** Normalized metadata only; the caller holds the rolling log store lock. */
internal class ModelStreamDiagnosticSampler {
    private val samples = LinkedHashMap<String, Long>()

    fun recordIfNeeded(event: String, fields: Map<String, Any?>, write: () -> Boolean): Boolean {
        // Per-token SSE metadata must not evict tool failures from the rolling log.
        // Keep transport stages and errors; sample normal progress once per second.
        if (event == "model_request_state") {
            val key = "${fields["requestRef"] ?: ""}|${fields["endpointKind"] ?: ""}"
            when (fields["stage"]) {
                "request_dispatch", "terminal" -> samples.remove(key)
                "stream_event" -> if (fields["errorCode"] == null && fields["exceptionType"] == null) {
                    val duration = fields["durationMs"] as Long
                    val previous = samples[key]
                    if (previous != null && duration >= previous && duration - previous < 1_000L) {
                        return false
                    }
                    samples[key] = duration
                    if (samples.size > 128) {
                        samples.entries.iterator().apply { next(); remove() }
                    }
                }
            }
        }
        return write()
    }

    fun clear() = samples.clear()
}
