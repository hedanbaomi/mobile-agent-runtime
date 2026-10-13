// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

/**
 * Bounded framing layer for `text/event-stream` bodies.
 *
 * The wire is a sequence of *events* terminated by a blank line, and one event
 * may carry several `data` fields whose values are joined with LF before the
 * protocol sees them.  Parsing each `data:` line as its own JSON document
 * therefore silently drops legal multi-line events.  The transport pipeline is
 * split accordingly: [SseLineReader] turns response bytes into lines, this class
 * turns lines into the joined `data` payload of one committed event, and the
 * protocol parser alone decides what that payload means.
 *
 * The interpretation follows the WHATWG event-stream rules: a leading
 * byte-order mark is not part of the first field name; a line beginning with `:`
 * is a comment; `field: value` drops exactly one leading space; a line with no
 * colon is a field whose value is the empty string; fields this protocol does not
 * consume are ignored; a blank line commits the event; and an event the stream
 * never terminated is discarded when the stream ends rather than shipped as
 * content.
 *
 * The bounds are explicit and never silently swallow payload.  A pending event
 * may not grow past [MAX_EVENT_BYTES], and one stream may not commit more than
 * [MAX_EVENTS] events; both are transport defects that fail the read.  No
 * provider text reaches an exception message.
 */
internal class SseEventDecoder(
    private val maxEventBytes: Int = MAX_EVENT_BYTES,
    private val maxEvents: Int = MAX_EVENTS,
) {
    private val data = StringBuilder()
    private var dataBytes = 0
    private var committedEvents = 0
    private var atStreamStart = true

    /**
     * Feed one line as a transport reader produced it.  A trailing CR is the
     * terminator a reader that consumed only the LF of a CRLF left behind, and a
     * CR inside the fragment terminates further lines, so framing is still
     * correct for a reader that does not split on CR at all.
     *
     * [onData] receives the joined `data` payload of every event this fragment
     * commits, in order.  It returns true when the caller has reached a terminal
     * event and the rest of the fragment must not be delivered.
     */
    suspend fun feed(fragment: String, onData: suspend (String) -> Boolean) {
        val text = if (atStreamStart) {
            atStreamStart = false
            fragment.removePrefix(BYTE_ORDER_MARK)
        } else {
            fragment
        }
        val terminated = text.removeSuffix("\r")
        val lines = if (terminated.indexOf('\r') < 0) listOf(terminated) else terminated.split('\r')
        for (line in lines) {
            if (processLine(line, onData)) return
        }
    }

    /**
     * Discard the event the stream never terminated.  Returns whether anything
     * was pending, so a caller can report the discard without printing it.
     */
    fun finish(): Boolean {
        val pending = data.isNotEmpty()
        data.setLength(0)
        dataBytes = 0
        return pending
    }

    /** Returns true when [onData] asked to stop delivering this fragment. */
    private suspend fun processLine(line: String, onData: suspend (String) -> Boolean): Boolean {
        if (line.isEmpty()) return commit(onData)
        if (line.startsWith(COMMENT_PREFIX)) return false
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        if (field != DATA_FIELD) return false
        val value = (if (colon < 0) "" else line.substring(colon + 1)).removePrefix(" ")
        val addedBytes = value.toByteArray(Charsets.UTF_8).size + 1
        require(addedBytes <= maxEventBytes - dataBytes) { EVENT_LIMIT_MESSAGE }
        dataBytes += addedBytes
        data.append(value).append('\n')
        return false
    }

    private suspend fun commit(onData: suspend (String) -> Boolean): Boolean {
        // No data field at all means no event is dispatched, whatever the
        // `event:`/`id:` metadata said.
        if (data.isEmpty()) return false
        val payload = data.toString().removeSuffix("\n")
        data.setLength(0)
        dataBytes = 0
        committedEvents += 1
        require(committedEvents <= maxEvents) { EVENT_LIMIT_MESSAGE }
        return onData(payload)
    }

    private companion object {
        /**
         * One event may not grow past the bound a single line already had, so a
         * stream that never delimits its events still stays bounded.
         */
        const val MAX_EVENT_BYTES = 1_048_576

        /**
         * A committed event costs at least one blank line, so the byte bound
         * alone would still allow a provider to spend it on empty events.  This
         * is above what a real 8 MiB answer can commit at the smallest realistic
         * frame size, and bounds pathological streams.
         */
        const val MAX_EVENTS = 262_144
        const val DATA_FIELD = "data"
        const val COMMENT_PREFIX = ":"
        const val BYTE_ORDER_MARK = "\uFEFF"

        /** Static and provider-free: no payload text may reach a log or a user. */
        const val EVENT_LIMIT_MESSAGE = "Provider event stream exceeds limit"
    }
}
