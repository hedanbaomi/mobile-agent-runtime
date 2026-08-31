// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.desktop.bridge

import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import runtime.mobileagent.bridge.BridgeCodec
import runtime.mobileagent.bridge.BridgeProtocol
import runtime.mobileagent.bridge.BridgeSession
import runtime.mobileagent.bridge.BridgeSessionHandshake

class LoopbackAuthenticationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Length-prefixed control/frame IO with a hard cap and no compression mode. */
class LoopbackFrameIo(
    private val socket: Socket,
    private val readTimeoutMs: Int = 60_000,
) : AutoCloseable {
    private val input = socket.getInputStream()
    private val output = DataOutputStream(socket.getOutputStream())

    init {
        require(readTimeoutMs in 1..5 * 60 * 1_000)
        socket.soTimeout = readTimeoutMs
    }

    @Synchronized
    fun read(): ByteArray {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeoutMs.toLong())
        val header = ByteArray(4)
        readFully(header, deadline)
        val size = ((header[0].toInt() and 0xff) shl 24) or
            ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or
            (header[3].toInt() and 0xff)
        require(size in 1..BridgeProtocol.MAX_FRAME_BYTES) { "loopback frame length is invalid" }
        val body = ByteArray(size)
        readFully(body, deadline)
        return body
    }

    @Synchronized
    fun write(frame: ByteArray) {
        require(frame.size in 1..BridgeProtocol.MAX_FRAME_BYTES) { "loopback frame length is invalid" }
        output.writeInt(frame.size)
        output.write(frame)
        output.flush()
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
    }

    private fun updateReadTimeout(deadline: Long) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw java.net.SocketTimeoutException("loopback read deadline exceeded")
        socket.soTimeout = maxOf(1, TimeUnit.NANOSECONDS.toMillis(remaining).toInt())
    }

    private fun readFully(target: ByteArray, deadline: Long) {
        var offset = 0
        while (offset < target.size) {
            updateReadTimeout(deadline)
            val count = try {
                input.read(target, offset, target.size - offset)
            } catch (error: java.io.EOFException) {
                throw LoopbackAuthenticationException("loopback peer closed", error)
            }
            if (count < 0) throw LoopbackAuthenticationException("loopback peer closed")
            if (count == 0) continue
            offset += count
        }
    }
}

/** Server-side authenticated loopback channel backed by a persisted trust record. */
class DesktopAuthenticatedConnection private constructor(
    private val io: LoopbackFrameIo,
    val session: BridgeSession,
) : AutoCloseable {
    @Volatile
    internal var onClosed: (() -> Unit)? = null
    private val closed = AtomicBoolean(false)

    fun readEncrypted(): runtime.mobileagent.bridge.BridgeDecodedFrame = session.decrypt(io.read())

    @Synchronized
    fun writeEncrypted(
        type: runtime.mobileagent.bridge.BridgeFrameType,
        requestId: String,
        payload: ByteArray,
    ) {
        io.write(session.encrypt(type, requestId, payload))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            session.close()
            io.close()
            onClosed?.invoke()
            onClosed = null
        }
    }

    companion object {
        /** Performs the server proof handshake before exposing any encrypted frame. */
        fun accept(
            socket: Socket,
            trust: DesktopTrustRecord,
            readTimeoutMs: Int = 60_000,
        ): DesktopAuthenticatedConnection {
            require(socket.inetAddress.hostAddress == "127.0.0.1") {
                "bridge peer must be IPv4 loopback"
            }
            require(readTimeoutMs in 1..5 * 60 * 1_000)
            socket.soTimeout = readTimeoutMs
            val io = LoopbackFrameIo(socket, readTimeoutMs)
            var session: BridgeSession? = null
            return try {
                val hello = BridgeCodec.decodeSessionHello(io.read())
                val trustSecret = trust.copyTrust()
                val transcriptHash = trust.transcriptHash.copyOf()
                val result = try {
                    BridgeSessionHandshake.accept(
                        hello,
                        trustSecret,
                        transcriptHash,
                    )
                } finally {
                    trustSecret.close()
                    java.util.Arrays.fill(transcriptHash, 0)
                }
                session = result.session
                io.write(BridgeCodec.encodeSessionWelcome(result.welcome))
                DesktopAuthenticatedConnection(io, result.session)
            } catch (error: Exception) {
                session?.close()
                io.close()
                if (error is LoopbackAuthenticationException) throw error
                throw LoopbackAuthenticationException("loopback authentication failed", error)
            }
        }
    }
}
