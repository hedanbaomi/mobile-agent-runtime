// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

/** Only sockets mapped to this process's loopback TLS server can be opened. */
class HostHttpTest {
    private val host = "public.example.invalid"
    private val otherHost = "other.example.invalid"
    private val publicAddress = InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))

    private class LocalTlsFixture(vararg names: String) : AutoCloseable {
        private val certificate = HeldCertificate.Builder().apply { names.forEach { addSubjectAlternativeName(it) } }.build()
        private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        val destinations = CopyOnWriteArrayList<InetSocketAddress>()
        val sockets = CopyOnWriteArrayList<Socket>()
        val calls = CopyOnWriteArrayList<Call>()
        val dnsAnswers = CopyOnWriteArrayList<List<InetAddress>>()
        val ended = CountDownLatch(1)
        val client: OkHttpClient

        init {
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.start(InetAddress.getLoopbackAddress(), 0)
            client = OkHttpClient.Builder()
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .socketFactory(object : SocketFactory() {
                    override fun createSocket(): Socket = object : Socket() {
                        override fun connect(endpoint: SocketAddress, timeout: Int) {
                            destinations.add(endpoint as InetSocketAddress)
                            super.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.port), timeout)
                        }
                    }.also { sockets.add(it) }
                    override fun createSocket(h: String, p: Int): Socket = error("Unexpected socket overload")
                    override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket = error("Unexpected socket overload")
                    override fun createSocket(h: InetAddress, p: Int): Socket = error("Unexpected socket overload")
                    override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket = error("Unexpected socket overload")
                })
                .eventListener(object : EventListener() {
                    override fun callStart(call: Call) { calls.add(call) }
                    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
                        dnsAnswers.add(inetAddressList)
                    }
                    override fun callEnd(call: Call) { ended.countDown() }
                    override fun callFailed(call: Call, ioe: IOException) { ended.countDown() }
                })
                // A supplied/default proxy, cookie jar, and DNS must never bypass the host policy.
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.invalid", 8080)))
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = error("Unvalidated DNS lookup")
                })
                .cookieJar(object : CookieJar {
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                    override fun loadForRequest(url: HttpUrl) = listOf(
                        Cookie.Builder().name("session").value("synthetic-fixture").hostOnlyDomain(url.host).build(),
                    )
                })
                .build()
        }

        override fun close() {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            server.close()
        }
    }

    @Test
    fun actualOkHttpDnsAndSocketUseOnlyTheSingleValidatedResolution() = LocalTlsFixture(host).use { fixture ->
        fixture.server.enqueue(MockResponse().setBody("ok"))
        var resolutions = 0
        val body = HostHttp.get("https://$host/path", setOf(host), {
            resolutions++
            if (resolutions == 1) listOf(publicAddress) else listOf(InetAddress.getLoopbackAddress())
        }, fixture.client)
        assertEquals("ok", body)
        assertEquals(1, resolutions)
        assertEquals(listOf(listOf(publicAddress)), fixture.dnsAnswers)
        assertEquals(listOf(InetSocketAddress(publicAddress, 443)), fixture.destinations)
        val request = fixture.server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals(host, request.getHeader(":authority") ?: request.getHeader("Host"))
        assertNull(request.getHeader("Cookie"))
        assertNull(request.getHeader("Authorization"))
        assertTrue(fixture.sockets.all { it.isClosed })
    }

    @Test
    fun mixedPublicAndPrivateDnsAnswersFailBeforeOpeningAnySocket() = LocalTlsFixture(host).use { fixture ->
        assertThrows(UnknownHostException::class.java) {
            HostHttp.get("https://$host/", setOf(host), {
                listOf(publicAddress, InetAddress.getLoopbackAddress())
            }, fixture.client)
        }
        assertTrue(fixture.destinations.isEmpty())
        assertEquals(0, fixture.server.requestCount)
    }

    @Test
    fun sameHostRedirectRevalidatesDnsAndRejectsRebinding() = LocalTlsFixture(host).use { fixture ->
        fixture.server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/next"))
        var resolutions = 0
        assertThrows(UnknownHostException::class.java) {
            HostHttp.get("https://$host/", setOf(host), {
                resolutions++
                if (resolutions == 1) listOf(publicAddress) else listOf(InetAddress.getLoopbackAddress())
            }, fixture.client)
        }
        assertEquals(2, resolutions)
        assertEquals(1, fixture.server.requestCount)
        assertEquals(1, fixture.destinations.size)
    }

    @Test
    fun crossHostRedirectRequiresAllowListAndDoesNotForwardCookies() = LocalTlsFixture(host, otherHost).use { fixture ->
        fixture.server.enqueue(MockResponse().setResponseCode(302)
            .addHeader("Location", "https://$otherHost/next").addHeader("Set-Cookie", "session=fixture"))
        fixture.server.enqueue(MockResponse().setBody("redirected"))
        val resolutions = mutableListOf<String>()
        assertEquals("redirected", HostHttp.get("https://$host/", setOf(host, otherHost), {
            resolutions.add(it)
            listOf(publicAddress)
        }, fixture.client))
        assertEquals(listOf(host, otherHost), resolutions)
        val first = fixture.server.takeRequest(2, TimeUnit.SECONDS)!!
        val second = fixture.server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals(host, first.getHeader(":authority") ?: first.getHeader("Host"))
        assertEquals(otherHost, second.getHeader(":authority") ?: second.getHeader("Host"))
        assertNull(second.getHeader("Cookie"))
        assertNull(second.getHeader("Authorization"))
        assertEquals(2, fixture.destinations.size)
    }

    @Test
    fun unlistedRedirectAndUrlCredentialsAreRejectedBeforeNextRequest() = LocalTlsFixture(host).use { fixture ->
        fixture.server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://$otherHost/"))
        assertThrows(IllegalStateException::class.java) {
            HostHttp.get("https://$host/", setOf(host), { listOf(publicAddress) }, fixture.client)
        }
        assertThrows(IllegalStateException::class.java) {
            HostHttp.get("https://fixture-user:fixture-pass@$host/", setOf(host), { listOf(publicAddress) }, fixture.client)
        }
        assertEquals(1, fixture.server.requestCount)
    }

    @Test
    fun tlsStillChecksOriginalHostAgainstCertificate() = LocalTlsFixture(otherHost).use { fixture ->
        fixture.server.enqueue(MockResponse().setBody("must not be returned"))
        assertThrows(IOException::class.java) {
            HostHttp.get("https://$host/", setOf(host), { listOf(publicAddress) }, fixture.client)
        }
        assertEquals(0, fixture.server.requestCount)
        assertEquals(1, fixture.destinations.size)
    }

    @Test
    fun retryAfterZeroDoesNotAutomaticallyReplayRequest() = LocalTlsFixture(host).use { fixture ->
        fixture.server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"))
        fixture.server.enqueue(MockResponse().setBody("must not retry"))
        assertThrows(IOException::class.java) {
            HostHttp.get("https://$host/", setOf(host), { listOf(publicAddress) }, fixture.client)
        }
        assertEquals(1, fixture.server.requestCount)
    }

    @Test
    fun redirectLimitAndStreamingResponseLimitCloseConnections() = LocalTlsFixture(host).use { fixture ->
        repeat(HttpPolicy.MAX_REDIRECTS + 1) {
            fixture.server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/again"))
        }
        assertThrows(IllegalStateException::class.java) {
            HostHttp.get("https://$host/", setOf(host), { listOf(publicAddress) }, fixture.client)
        }
        assertEquals(HttpPolicy.MAX_REDIRECTS + 1, fixture.server.requestCount)
        fixture.server.enqueue(MockResponse().setChunkedBody("x".repeat(HttpPolicy.MAX_HTTP_RESPONSE_BYTES + 1), 4096))
        assertThrows(IllegalStateException::class.java) {
            HostHttp.get("https://$host/", setOf(host), { listOf(publicAddress) }, fixture.client)
        }
        assertTrue(fixture.sockets.all { it.isClosed })
    }

    @Test
    fun interruptionCancelsActualCallAndClosesItsSocket() = LocalTlsFixture(host).use { fixture ->
        fixture.server.enqueue(MockResponse().setBody("slow").setBodyDelay(3, TimeUnit.SECONDS))
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try { HostHttp.get("https://$host/", setOf(host), { listOf(publicAddress) }, fixture.client) }
            catch (t: Throwable) { failure.set(t) }
        }
        worker.start()
        try {
            assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(3_000)
            assertFalse(worker.isAlive)
            assertTrue(failure.get() is InterruptedException)
            assertTrue(fixture.calls.single().isCanceled())
            assertTrue(fixture.ended.await(3, TimeUnit.SECONDS))
            assertTrue(fixture.sockets.all { it.isClosed })
        } finally {
            worker.interrupt()
            worker.join(3_000)
        }
    }

    @Test
    fun totalDeadlineCancelsBodyRead() = LocalTlsFixture(host).use { fixture ->
        fixture.server.enqueue(MockResponse().setBody("slow").setBodyDelay(3, TimeUnit.SECONDS))
        assertThrows(IOException::class.java) {
            HostHttp.get("https://$host/", setOf(host), { listOf(publicAddress) }, fixture.client, timeoutMillis = 1_000)
        }
        assertTrue(fixture.calls.single().isCanceled())
        assertTrue(fixture.ended.await(3, TimeUnit.SECONDS))
        assertTrue(fixture.sockets.all { it.isClosed })
    }

    @Test
    fun lateDnsResultAfterDeadlineCannotOpenASocket() = LocalTlsFixture(host).use { fixture ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                HostHttp.get("https://$host/", setOf(host), {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    listOf(publicAddress)
                }, fixture.client, timeoutMillis = 300)
            } catch (t: Throwable) { failure.set(t) }
        }
        worker.start()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            worker.join(2_000)
            assertFalse(worker.isAlive)
            assertTrue(failure.get() is IOException)
            assertTrue(fixture.calls.single().isCanceled())
        } finally {
            release.countDown()
            worker.interrupt()
            worker.join(3_000)
        }
        assertTrue(fixture.ended.await(3, TimeUnit.SECONDS))
        assertTrue(fixture.destinations.isEmpty())
        assertEquals(0, fixture.server.requestCount)
    }
}
