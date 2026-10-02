// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.build

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PinnedArtifactDownloadTest {
    @TempDir
    lateinit var tempDir: Path

    private val exchangeCounter = AtomicInteger()
    private val responseSent = CountDownLatch(1)

    @Test
    fun reusesValidCacheWithoutRequestOrMtimeChange() = withHttpServer({ exchange ->
        exchangeCounter.incrementAndGet()
        respond(exchange, 200, payload)
    }) { baseUrl ->
        val destination = tempDir.resolve("cached.bin")
        Files.write(destination, payload)
        val oldTime = FileTime.fromMillis(1_600_000_000_000L)
        Files.setLastModifiedTime(destination, oldTime)

        val returned = PinnedArtifactDownload.download(
            url = "$baseUrl/cache",
            destination = destination.toFile(),
            expectedSha256 = sha256(payload),
            initialBackoffMillis = 0,
        )

        assertEquals(destination.toFile(), returned)
        assertEquals(0, exchangeCounter.get())
        assertEquals(oldTime, Files.getLastModifiedTime(destination))
        assertOnly(destination.fileName.toString())
    }

    @Test
    fun truncatedResponseRetriesAndInstallsVerifiedCompleteFile() {
        val requests = AtomicInteger()
        val server = startHttpServer { exchange ->
            if (requests.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(200, (payload.size + 17).toLong())
                exchange.responseBody.use { output ->
                    output.write(payload)
                    output.flush()
                }
            } else {
                respond(exchange, 200, payload)
            }
        }
        try {
            val destination = tempDir.resolve("retry.bin")
            val result = PinnedArtifactDownload.download(
                url = "${baseUrl(server)}/truncated",
                destination = destination.toFile(),
                expectedSha256 = sha256(payload),
                initialBackoffMillis = 0,
            )
            assertEquals(2, requests.get())
            assertEquals(destination.toFile(), result)
            assertArrayEquals(payload, Files.readAllBytes(destination))
            assertOnly(destination.fileName.toString())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun retriesHttp503ThenSucceeds() = withHttpServer({ exchange ->
        if (exchangeCounter.incrementAndGet() == 1) {
            respond(exchange, 503, "temporarily unavailable".toByteArray())
        } else {
            respond(exchange, 200, payload)
        }
    }) { baseUrl ->
        val destination = tempDir.resolve("503.bin")
        PinnedArtifactDownload.download(
            url = "$baseUrl/retry?query=opaque-value",
            destination = destination.toFile(),
            expectedSha256 = sha256(payload),
            initialBackoffMillis = 0,
        )
        assertEquals(2, exchangeCounter.get())
        assertArrayEquals(payload, Files.readAllBytes(destination))
        assertOnly(destination.fileName.toString())
    }

    @Test
    fun exhaustedHttpRetriesStopAtThreeAndPreserveExistingDestination() = withHttpServer({ exchange ->
        exchangeCounter.incrementAndGet()
        respond(exchange, 503, byteArrayOf())
    }) { baseUrl ->
        val destination = tempDir.resolve("stable.bin")
        val original = "original bytes".toByteArray()
        Files.write(destination, original)

        val failure = assertThrows(PinnedArtifactDownloadException::class.java) {
            PinnedArtifactDownload.download(
                url = "$baseUrl/private?query=never-echo-this",
                destination = destination.toFile(),
                expectedSha256 = sha256(payload),
                initialBackoffMillis = 0,
            )
        }

        assertEquals(3, exchangeCounter.get())
        assertTrue(failure.message.orEmpty().contains("stable.bin: HTTP 503"))
        assertFalse(failure.message.orEmpty().contains("never-echo-this"))
        assertArrayEquals(original, Files.readAllBytes(destination))
        assertOnly(destination.fileName.toString())
    }

    @Test
    fun wrongHashIsNotRetriedAndPartialFileIsRemovedWithoutReplacingDestination() = withHttpServer({ exchange ->
        exchangeCounter.incrementAndGet()
        respond(exchange, 200, "untrusted bytes".toByteArray())
    }) { baseUrl ->
        val destination = tempDir.resolve("preserved.bin")
        val original = "keep me".toByteArray()
        Files.write(destination, original)

        val failure = assertThrows(PinnedArtifactDownloadException::class.java) {
            PinnedArtifactDownload.download(
                url = "$baseUrl/wrong-hash",
                destination = destination.toFile(),
                expectedSha256 = sha256(payload),
                initialBackoffMillis = 0,
            )
        }

        assertEquals(1, exchangeCounter.get())
        assertTrue(failure.message.orEmpty().contains("preserved.bin: SHA-256 mismatch"))
        assertArrayEquals(original, Files.readAllBytes(destination))
        assertOnly(destination.fileName.toString())
    }

    @Test
    fun http404IsNotRetried() = withHttpServer({ exchange ->
        exchangeCounter.incrementAndGet()
        respond(exchange, 404, "not found".toByteArray())
    }) { baseUrl ->
        val destination = tempDir.resolve("missing.bin")
        val failure = assertThrows(PinnedArtifactDownloadException::class.java) {
            PinnedArtifactDownload.download(
                url = "$baseUrl/missing",
                destination = destination.toFile(),
                expectedSha256 = sha256(payload),
                initialBackoffMillis = 0,
            )
        }
        assertEquals(1, exchangeCounter.get())
        assertTrue(failure.message.orEmpty().contains("missing.bin: HTTP 404"))
        assertOnly()
    }

    @Test
    fun tlsProtocolFailureIsNotRetried() {
        val listener = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        listener.soTimeout = 250
        val accepted = AtomicInteger()
        val acceptor = Thread {
            while (!listener.isClosed) {
                try {
                    listener.accept().use { socket ->
                        accepted.incrementAndGet()
                        val input = socket.getInputStream()
                        val header = input.readNBytes(5)
                        if (header.size == 5) {
                            val recordLength = ((header[3].toInt() and 0xff) shl 8) or
                                (header[4].toInt() and 0xff)
                            input.readNBytes(recordLength)
                            socket.getOutputStream().write(
                                byteArrayOf(21.toByte(), 3.toByte(), 3.toByte(), 0.toByte(),
                                    2.toByte(), 2.toByte(), 40.toByte()),
                            )
                            socket.getOutputStream().flush()
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // Keep listening until the download attempt finishes.
                } catch (_: SocketException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        try {
            val failure = assertThrows(PinnedArtifactDownloadException::class.java) {
                PinnedArtifactDownload.download(
                    url = "https://127.0.0.1:${listener.localPort}/artifact",
                    destination = tempDir.resolve("tls.bin").toFile(),
                    expectedSha256 = sha256(payload),
                    initialBackoffMillis = 0,
                )
            }
            assertEquals(1, accepted.get())
            assertTrue(
                failure.message.orEmpty().contains("TLS failure") ||
                    failure.message.orEmpty().contains("protocol failure"),
                failure.message,
            )
            assertOnly()
        } finally {
            listener.close()
            acceptor.join(2_000)
        }
    }

    @Test
    fun interruptionDuringRetryDelayStopsFurtherRequestsAndRestoresFlag() = withHttpServer({ exchange ->
        exchangeCounter.incrementAndGet()
        respond(exchange, 503, byteArrayOf())
        responseSent.countDown()
    }) { baseUrl ->
        val destination = tempDir.resolve("interrupted.bin")
        val failureRef = AtomicReference<Throwable?>()
        val interruptedAtCatch = AtomicReference(false)
        val worker = Thread {
            try {
                PinnedArtifactDownload.download(
                    url = "$baseUrl/wait",
                    destination = destination.toFile(),
                    expectedSha256 = sha256(payload),
                    initialBackoffMillis = 5_000,
                )
            } catch (failure: Throwable) {
                failureRef.set(failure)
                interruptedAtCatch.set(Thread.currentThread().isInterrupted)
            }
        }
        worker.start()
        assertTrue(responseSent.await(5, TimeUnit.SECONDS), "server did not answer the first request")
        worker.interrupt()
        worker.join(5_000)

        assertFalse(worker.isAlive)
        assertEquals(1, exchangeCounter.get())
        assertNotNull(failureRef.get())
        assertTrue(failureRef.get() is PinnedArtifactDownloadException)
        assertTrue(failureRef.get()?.message.orEmpty().contains("interrupted.bin: cancelled"))
        assertTrue(interruptedAtCatch.get())
        assertOnly()
    }

    @Test
    fun destinationIsReplacedOnlyAfterTheVerifiedBodyIsComplete() {
        val bodyStarted = CountDownLatch(1)
        val allowCompletion = CountDownLatch(1)
        val server = startHttpServer { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { output ->
                output.write(payload, 0, 3)
                output.flush()
                bodyStarted.countDown()
                if (!allowCompletion.await(5, TimeUnit.SECONDS)) {
                    throw IOException("test did not release the response")
                }
                output.write(payload, 3, payload.size - 3)
            }
        }
        val destination = tempDir.resolve("atomic.bin")
        val oldBytes = "old complete artifact".toByteArray()
        Files.write(destination, oldBytes)
        val failureRef = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                PinnedArtifactDownload.download(
                    url = "${baseUrl(server)}/slow",
                    destination = destination.toFile(),
                    expectedSha256 = sha256(payload),
                    readTimeoutMillis = 5_000,
                    initialBackoffMillis = 0,
                )
            } catch (failure: Throwable) {
                failureRef.set(failure)
            }
        }
        try {
            worker.start()
            assertTrue(bodyStarted.await(5, TimeUnit.SECONDS), "server did not begin the response")
            assertArrayEquals(oldBytes, Files.readAllBytes(destination))
        } finally {
            allowCompletion.countDown()
        }
        worker.join(5_000)
        server.stop(0)

        assertFalse(worker.isAlive)
        assertNull(failureRef.get())
        assertArrayEquals(payload, Files.readAllBytes(destination))
        assertOnly(destination.fileName.toString())
    }

    @Test
    fun firstDownloadCreatesMissingCacheDirectories() = withHttpServer({ exchange ->
        exchangeCounter.incrementAndGet()
        respond(exchange, 200, payload)
    }) { baseUrl ->
        val destination = tempDir.resolve("absent-parent").resolve("nested").resolve("first.bin")
        val result = PinnedArtifactDownload.download(
            url = "$baseUrl/first-download",
            destination = destination.toFile(),
            expectedSha256 = sha256(payload),
            initialBackoffMillis = 0,
        )
        assertEquals(1, exchangeCounter.get())
        assertEquals(destination.toFile(), result)
        assertArrayEquals(payload, Files.readAllBytes(destination))
        Files.list(destination.parent).use { files ->
            assertEquals(listOf(destination), files.toList())
        }
    }

    @Test
    fun blockedParentIsLocalIoFailureAndDoesNotRequestNetwork() = withHttpServer({ exchange ->
        exchangeCounter.incrementAndGet()
        respond(exchange, 200, payload)
    }) { baseUrl ->
        val blockedParent = tempDir.resolve("blocked-parent")
        val original = "not a directory".toByteArray()
        Files.write(blockedParent, original)
        val destination = blockedParent.resolve("local.bin")
        val failure = assertThrows(PinnedArtifactDownloadException::class.java) {
            PinnedArtifactDownload.download(
                url = "$baseUrl/local-io",
                destination = destination.toFile(),
                expectedSha256 = sha256(payload),
                initialBackoffMillis = 0,
            )
        }
        assertEquals(0, exchangeCounter.get())
        assertTrue(failure.message.orEmpty().contains("local.bin: local I/O failure"))
        assertArrayEquals(original, Files.readAllBytes(blockedParent))
        assertOnly("blocked-parent")
    }

    @Test
    fun interruptedSuccessfulResponseNeverReplacesTheDestination() {
        val bodyStarted = CountDownLatch(1)
        val allowCompletion = CountDownLatch(1)
        val requests = AtomicInteger()
        val server = startHttpServer { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { output ->
                output.write(payload, 0, 3)
                output.flush()
                bodyStarted.countDown()
                allowCompletion.await(5, TimeUnit.SECONDS)
                output.write(payload, 3, payload.size - 3)
            }
        }
        val destination = tempDir.resolve("cancel-transfer.bin")
        val original = "preserve existing artifact".toByteArray()
        Files.write(destination, original)
        val failureRef = AtomicReference<Throwable?>()
        val interruptedRef = AtomicReference<Boolean>()
        val worker = Thread {
            try {
                PinnedArtifactDownload.download(
                    url = "${baseUrl(server)}/cancel-success",
                    destination = destination.toFile(),
                    expectedSha256 = sha256(payload),
                    readTimeoutMillis = 5_000,
                    initialBackoffMillis = 0,
                )
            } catch (failure: Throwable) {
                failureRef.set(failure)
                interruptedRef.set(Thread.currentThread().isInterrupted)
            }
        }
        try {
            worker.start()
            assertTrue(bodyStarted.await(5, TimeUnit.SECONDS))
            worker.interrupt()
        } finally {
            allowCompletion.countDown()
            worker.join(5_000)
            server.stop(0)
        }
        assertFalse(worker.isAlive)
        assertTrue(failureRef.get() is PinnedArtifactDownloadException)
        assertTrue(failureRef.get()?.message.orEmpty().contains("cancel-transfer.bin: cancelled"))
        assertEquals(true, interruptedRef.get())
        assertEquals(1, requests.get())
        assertArrayEquals(original, Files.readAllBytes(destination))
        assertOnly(destination.fileName.toString())
    }

    @Test
    fun connectionRefusalUsesTheBoundedRetryPolicy() {
        val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val destination = tempDir.resolve("refused.bin")
        val failure = assertThrows(PinnedArtifactDownloadException::class.java) {
            PinnedArtifactDownload.download(
                url = "http://127.0.0.1:$closedPort/refused",
                destination = destination.toFile(),
                expectedSha256 = sha256(payload),
                connectTimeoutMillis = 500,
                readTimeoutMillis = 500,
                initialBackoffMillis = 0,
            )
        }
        assertTrue(failure.message.orEmpty().contains("network failure (retry limit reached)"))
        assertOnly()
    }

    private fun withHttpServer(handler: (HttpExchange) -> Unit, block: (String) -> Unit) {
        val server = startHttpServer(handler)
        try {
            block(baseUrl(server))
        } finally {
            server.stop(0)
        }
    }

    private fun startHttpServer(handler: (HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handler(exchange) }
        server.start()
        return server
    }

    private fun baseUrl(server: HttpServer): String = "http://127.0.0.1:${server.address.port}"

    private fun respond(exchange: HttpExchange, status: Int, body: ByteArray) {
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun assertOnly(vararg names: String) {
        val actual = Files.newDirectoryStream(tempDir).use { entries -> entries.map { it.fileName.toString() }.toSet() }
        assertEquals(names.toSet(), actual)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { "%02x".format(it) }

    companion object {
        private val payload = "verified artifact contents".toByteArray()
    }
}
