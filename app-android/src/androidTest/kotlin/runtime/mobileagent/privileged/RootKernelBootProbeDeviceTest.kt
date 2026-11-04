// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.MobileAgentApp
import runtime.mobileagent.domain.*

/** Real app bootstrap with synthetic transport facts: acquisition fencing, not su acceptance. */
@RunWith(AndroidJUnit4::class)
class RootKernelBootProbeDeviceTest {
    private val boot = "01234567-89ab-4def-8123-456789abcdef"

    private class Transport(
        private val bootId: String?,
        private val accept: Boolean = true,
        private val wrongSigner: Boolean = false,
        gated: Boolean = false,
    ) : RootChannelTransport {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(if (gated) 1 else 0)
        @Volatile var closed = false
        var dispatches = 0

        override fun handshake(request: RootServiceHandshakeRequest): RootServiceHandshakeResponse {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            // Deliberately returns a valid late response even after close, testing the attempt fence.
            val identity = PrivilegedSessionIdentity(
                request.authority, PrivilegeTier.ROOT, 0, request.appUid, request.androidUserId,
                request.appInstanceId, request.packageName,
                if (wrongSigner) "f".repeat(64) else request.signingSha256,
                request.identityEpoch, "kernel-probe-service-0001", "kernel-probe-session-0001",
                PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION,
            )
            return RootServiceHandshakeResponse(accept, identity, kernelBootId = bootId)
        }
        override fun submitEnvelope(controlSequence: Long, rawEnvelopeJson: String): RootServiceExecuteResponse? {
            dispatches++; error("Probe must not submit any envelope")
        }
        override fun executeAccepted(requestId: String, payloadBytes: ByteArray): RootServiceExecuteResponse? {
            dispatches++; error("Probe must not execute")
        }
        override fun submitAndExecute(controlSequence: Long, rawEnvelopeJson: String, payloadBytes: ByteArray): RootServiceExecuteResponse? {
            dispatches++; error("Probe must not submit or execute")
        }
        override fun cancelCommand(requestId: String) = false
        override fun revokeAndShutdown(controlSequence: Long, reason: String) = "DISCONNECTED"
        override fun close() { closed = true }
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext<Context>().also {
        (it as MobileAgentApp).ensureHostInitialized()
    }

    @Test
    fun probeReturnsOnlyVerifiedBootIdentityAfterClosingAndNeverPublishesATransport() {
        val transport = Transport(boot)
        val connector = AndroidRootSessionConnector(context(), directTransportFactory = { _, _ -> transport })
        try {
            val observation = connector.probeBootIdentity(Authority.ROOT_DIRECT, 1, "kernel-probe-app-0001", null)
            assertNotNull(observation)
            assertEquals(boot, observation!!.kernelBootId)
            assertEquals(0, observation.identity.serviceUid)
            assertTrue(transport.closed)
            assertNull(connector.activeTransport(Authority.ROOT_DIRECT))
            assertFalse(connector.cancelPendingAcquisition(Authority.ROOT_DIRECT))
            assertEquals(0, transport.dispatches)
        } finally { connector.close() }
    }

    @Test
    fun rejectedMalformedOrUnpinnedHandshakeCannotProduceBootObservation() {
        for (transport in listOf(Transport(boot, accept = false), Transport("4"), Transport(null), Transport(boot, wrongSigner = true))) {
            val connector = AndroidRootSessionConnector(context(), directTransportFactory = { _, _ -> transport })
            try {
                assertNull(connector.probeBootIdentity(Authority.ROOT_DIRECT, 1, "kernel-probe-app-0001", null))
                assertTrue(transport.closed)
                assertNull(connector.activeTransport(Authority.ROOT_DIRECT))
                assertEquals(0, transport.dispatches)
            } finally { connector.close() }
        }
    }

    @Test
    fun probeDoesNotReplaceOrCloseAnExistingNormallyAcquiredChannel() {
        val normal = Transport(boot)
        val probe = Transport(boot)
        var launches = 0
        val connector = AndroidRootSessionConnector(context(), directTransportFactory = { _, _ -> if (launches++ == 0) normal else probe })
        try {
            val acquired = connector.tryAcquireSession(Authority.ROOT_DIRECT, 1, "kernel-probe-app-0001", null)
            assertNotNull(acquired)
            assertTrue(connector.completeAcquisition(Authority.ROOT_DIRECT, acquired!!.attemptToken))
            assertNotNull(connector.probeBootIdentity(Authority.ROOT_DIRECT, 2, "kernel-probe-app-0001", null))
            assertSame(normal, connector.activeTransport(Authority.ROOT_DIRECT))
            assertFalse(normal.closed)
            assertTrue(probe.closed)
        } finally { connector.close() }
    }

    @Test
    fun cancelOrConnectorCloseDuringProbeRejectsEvenAValidLateHandshake() {
        for (closeConnector in listOf(false, true)) {
            val transport = Transport(boot, gated = true)
            val connector = AndroidRootSessionConnector(context(), directTransportFactory = { _, _ -> transport })
            val result = AtomicReference<RootBootObservation?>()
            val failure = AtomicReference<Throwable?>()
            val worker = Thread {
                try { result.set(connector.probeBootIdentity(Authority.ROOT_DIRECT, 1, "kernel-probe-app-0001", null)) }
                catch (error: Throwable) { failure.set(error) }
            }.apply { isDaemon = true; start() }
            try {
                assertTrue(transport.entered.await(10, TimeUnit.SECONDS))
                if (closeConnector) connector.close() else assertTrue(connector.cancelPendingAcquisition(Authority.ROOT_DIRECT))
                transport.release.countDown()
                worker.join(10_000)
                assertFalse(worker.isAlive)
                assertNull(failure.get())
                assertNull(result.get())
                assertTrue(transport.closed)
                assertNull(connector.activeTransport(Authority.ROOT_DIRECT))
                assertEquals(0, transport.dispatches)
            } finally { transport.release.countDown(); connector.close(); worker.join(10_000) }
        }
    }

    @Test
    fun normalReplacementClosesTheSupersededTransport() {
        val first = Transport(boot)
        val second = Transport(boot)
        var launches = 0
        val connector = AndroidRootSessionConnector(context(), directTransportFactory = { _, _ -> if (launches++ == 0) first else second })
        try {
            val acquired = connector.tryAcquireSession(Authority.ROOT_DIRECT, 1, "kernel-probe-app-0001", null)!!
            assertTrue(connector.completeAcquisition(Authority.ROOT_DIRECT, acquired.attemptToken))
            assertNotNull(connector.tryAcquireSession(Authority.ROOT_DIRECT, 2, "kernel-probe-app-0001", null))
            assertTrue(first.closed)
            assertSame(second, connector.activeTransport(Authority.ROOT_DIRECT))
        } finally { connector.close() }
    }
}
