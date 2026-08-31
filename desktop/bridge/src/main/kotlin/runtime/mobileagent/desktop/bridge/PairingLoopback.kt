// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.desktop.bridge

import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import runtime.mobileagent.bridge.BridgeCodec
import runtime.mobileagent.bridge.BridgePairCommitAck
import runtime.mobileagent.bridge.BridgePairResponse

/** Pairing endpoint handler.  It never writes trust before client commit-ack. */
class PairingLoopbackConnectionHandler(
    private val onCommitted: () -> Unit = {},
) : CompanionConnectionHandler {
    override fun handle(socket: Socket, companion: DesktopCompanion) {
        socket.soTimeout = 15_000
        val io = LoopbackFrameIo(socket, readTimeoutMs = 15_000)
        var pending: runtime.mobileagent.bridge.BridgePairingServerPending? = null
        try {
            val start = BridgeCodec.decodePairStart(io.read())
            pending = companion.beginPairing(start)
            io.write(BridgeCodec.encodePairChallenge(pending!!.challenge))
            val response: BridgePairResponse = BridgeCodec.decodePairResponse(io.read())
            val finished = companion.finishPairing(pending!!, response)
            io.write(BridgeCodec.encodePairFinished(finished))
            val ack: BridgePairCommitAck = BridgeCodec.decodePairCommitAck(io.read())
            val material = companion.commitPairing(pending!!, ack)
            material.close()
            onCommitted()
        } catch (_: Exception) {
            // Pairing failures close the endpoint without error details; the
            // token manager reservation is released by pending.close().
        } finally {
            pending?.close()
            io.close()
        }
    }
}

/** Foreground helper used by `mar-bridge pair`; the listener owns the token manager. */
class PairingWaiter(private val timeoutMs: Long = 5 * 60 * 1_000L) {
    private val completed = CountDownLatch(1)

    fun handler(): PairingLoopbackConnectionHandler = PairingLoopbackConnectionHandler { completed.countDown() }

    fun await(): Boolean = completed.await(timeoutMs, TimeUnit.MILLISECONDS)
}
