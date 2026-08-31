// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.wired

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import runtime.mobileagent.bridge.BridgeProtocolException

fun interface WiredAdbSocketFactory {
    fun open(): Socket
}

/**
 * The only production connector.  It rejects every address and port except
 * the device loopback endpoint created by `adb reverse`.
 */
class FixedLoopbackConnector(
    private val socketFactory: WiredAdbSocketFactory = WiredAdbSocketFactory { Socket() },
    private val connectTimeoutMs: Int = 5_000,
) : WiredAdbLoopbackConnector {
    override fun connect(address: String, port: Int): WiredAdbChannel {
        if (address != WIRED_ADB_LOOPBACK_ADDRESS || port != WIRED_ADB_LOOPBACK_PORT) {
            throw IOException("fixed loopback endpoint rejected")
        }
        val socket = socketFactory.open()
        try {
            socket.connect(InetSocketAddress(WIRED_ADB_LOOPBACK_ADDRESS, WIRED_ADB_LOOPBACK_PORT), connectTimeoutMs)
            socket.tcpNoDelay = true
            // The bridge installs a per-operation absolute deadline before
            // each read. Keep the socket unbounded until that happens; a
            // typed shell request may legitimately run for several minutes.
            socket.soTimeout = 0
            return SocketWiredAdbChannel(socket)
        } catch (error: Throwable) {
            runCatching { socket.close() }
            if (error is IOException) throw error
            throw IOException("loopback connect failed")
        }
    }
}

/** Length-prefixed, bounded socket framing. `readFrame` returns payload only. */
class SocketWiredAdbChannel(private val socket: Socket) : WiredAdbChannel {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val writeLock = Any()
    @Volatile private var readDeadlineEpochMs: Long = Long.MAX_VALUE

    override fun readFrame(): ByteArray {
        val header = readExactly(4)
        val length = ((header[0].toInt() and 0xff) shl 24) or
            ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or
            (header[3].toInt() and 0xff)
        if (length !in 1..WIRED_ADB_MAX_FRAME_BYTES) {
            throw BridgeProtocolException("bridge frame is too large")
        }
        return readExactly(length)
    }

    override fun writeFrame(frame: ByteArray) {
        if (frame.isEmpty() || frame.size > WIRED_ADB_MAX_FRAME_BYTES) {
            throw BridgeProtocolException("bridge frame is too large")
        }
        val header = byteArrayOf(
            (frame.size ushr 24).toByte(),
            (frame.size ushr 16).toByte(),
            (frame.size ushr 8).toByte(),
            frame.size.toByte(),
        )
        synchronized(writeLock) {
            output.write(header)
            output.write(frame)
            output.flush()
        }
    }

    override fun setReadDeadline(deadlineEpochMs: Long) {
        require(deadlineEpochMs > 0L) { "read deadline is invalid" }
        readDeadlineEpochMs = deadlineEpochMs
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun readExactly(length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val deadline = readDeadlineEpochMs
            if (deadline != Long.MAX_VALUE) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) throw SocketTimeoutException("bridge read deadline exceeded")
                socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } else {
                socket.soTimeout = 0
            }
            val count = input.read(result, offset, length - offset)
            if (count < 0) throw EOFException("loopback frame ended")
            if (count == 0) continue
            offset += count
        }
        return result
    }
}

/** In-memory channel useful for protocol tests; it enforces the same bounds. */
class QueueWiredAdbChannel(
    private val incoming: java.util.concurrent.BlockingQueue<ByteArray>,
    private val outgoing: java.util.concurrent.BlockingQueue<ByteArray>,
) : WiredAdbChannel {
    @Volatile private var closed = false
    @Volatile private var readDeadlineEpochMs: Long = Long.MAX_VALUE

    override fun readFrame(): ByteArray {
        if (closed) throw EOFException("channel closed")
        val frame = try {
            val deadline = readDeadlineEpochMs
            if (deadline == Long.MAX_VALUE) {
                incoming.take()
            } else {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) throw SocketTimeoutException("bridge read deadline exceeded")
                incoming.poll(remaining, TimeUnit.MILLISECONDS)
                    ?: throw SocketTimeoutException("bridge read deadline exceeded")
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("bridge queue read interrupted", error)
        }
        if (frame.isEmpty() || frame.size > WIRED_ADB_MAX_FRAME_BYTES) {
            throw BridgeProtocolException("bridge frame is too large")
        }
        return frame.copyOf()
    }

    override fun writeFrame(frame: ByteArray) {
        if (closed) throw EOFException("channel closed")
        if (frame.isEmpty() || frame.size > WIRED_ADB_MAX_FRAME_BYTES) {
            throw BridgeProtocolException("bridge frame is too large")
        }
        outgoing.put(frame.copyOf())
    }

    override fun setReadDeadline(deadlineEpochMs: Long) {
        require(deadlineEpochMs > 0L) { "read deadline is invalid" }
        readDeadlineEpochMs = deadlineEpochMs
    }

    override fun close() {
        if (!closed) {
            closed = true
            // Wake a test reader blocked in BlockingQueue.take(). A closed
            // sentinel is rejected by readFrame just like a truncated wire
            // frame; no caller can mistake it for protocol data.
            incoming.offer(ByteArray(0))
        }
    }
}
