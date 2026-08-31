// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.desktop.bridge

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

fun interface LoopbackConnectionHandler {
    fun handle(socket: Socket)
}

data class LoopbackEndpoint(val address: String, val port: Int) {
    init {
        require(address == "127.0.0.1")
        require(port in 1..65_535)
    }
}

/** Binds only the IPv4 loopback address and allocates an ephemeral host port. */
class LoopbackBridgeServer(
    private val handler: LoopbackConnectionHandler,
    private val executor: ExecutorService = boundedLoopbackExecutor(),
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val endpoint: LoopbackEndpoint = LoopbackEndpoint("127.0.0.1", server.localPort)
    private val acceptThread = Thread(::acceptLoop, "mar-bridge-loopback-accept").apply {
        isDaemon = true
        start()
    }

    init {
        require(server.inetAddress.hostAddress == "127.0.0.1") { "bridge must bind IPv4 loopback" }
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val socket = try {
                server.accept()
            } catch (_: java.io.IOException) {
                if (!closed.get()) continue
                break
            }
            if (socket.inetAddress.hostAddress != "127.0.0.1") {
                runCatching { socket.close() }
                continue
            }
            connections += socket
            try {
                executor.submit {
                    try {
                        handler.handle(socket)
                    } finally {
                        connections -= socket
                        runCatching { socket.close() }
                    }
                }
            } catch (_: RejectedExecutionException) {
                connections -= socket
                runCatching { socket.close() }
            }
        }
    }

    /** Used by forget/disable to interrupt pairing and authenticated handlers. */
    fun closeActiveConnections() {
        connections.toList().forEach { runCatching { it.close() } }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { server.close() }
            closeActiveConnections()
            runCatching { acceptThread.join(1_000) }
            executor.shutdownNow()
            runCatching { executor.awaitTermination(1, TimeUnit.SECONDS) }
        }
    }
}

private fun boundedLoopbackExecutor(): ExecutorService = ThreadPoolExecutor(
    1,
    8,
    30,
    TimeUnit.SECONDS,
    ArrayBlockingQueue(32),
    LoopbackThreadFactory(),
    ThreadPoolExecutor.AbortPolicy(),
)

private class LoopbackThreadFactory : ThreadFactory {
    private val counter = AtomicInteger()
    override fun newThread(runnable: Runnable): Thread = Thread(
        runnable,
        "mar-bridge-loopback-${counter.incrementAndGet()}",
    ).apply { isDaemon = true }
}
