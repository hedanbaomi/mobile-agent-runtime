// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.desktop.bridge

import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

/**
 * Quickstart uses one companion and one loopback listener for both phases.
 * A new authenticated socket is routed only after the verified commit ack has
 * been persisted and consumed by [DesktopCompanion.commitPairing].
 */
internal class PairingThenAuthenticatedConnectionHandler(
    private val authenticatedHandler: CompanionConnectionHandler,
    private val transitionWaitMs: Long = 30_000L,
    pairingHandlerFactory: ((() -> Unit) -> CompanionConnectionHandler) = { onCommitted ->
        PairingLoopbackConnectionHandler(onCommitted)
    },
) : CompanionConnectionHandler, AutoCloseable {
    private val committed = CountDownLatch(1)
    private val pairingStarted = AtomicBoolean(false)
    private val pairingHandler = pairingHandlerFactory { committed.countDown() }

    init {
        require(transitionWaitMs in 1..5 * 60 * 1_000L)
    }

    override fun handle(socket: Socket, companion: DesktopCompanion) {
        if (committed.count == 0L) {
            authenticatedHandler.handle(socket, companion)
            return
        }
        if (pairingStarted.compareAndSet(false, true)) {
            if (committed.count == 0L) {
                pairingStarted.set(false)
                authenticatedHandler.handle(socket, companion)
                return
            }
            try {
                pairingHandler.handle(socket, companion)
            } finally {
                if (committed.count != 0L) pairingStarted.set(false)
            }
            return
        }
        val mayAuthenticate = try {
            committed.await(transitionWaitMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (mayAuthenticate && committed.count == 0L) {
            authenticatedHandler.handle(socket, companion)
        } else {
            runCatching { socket.close() }
        }
    }

    fun awaitPairing(timeoutMs: Long): Boolean = committed.await(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        (authenticatedHandler as? AutoCloseable)?.close()
    }
}
