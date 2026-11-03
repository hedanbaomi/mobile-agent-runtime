// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.MobileAgentApp
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.PrivilegedSessionIdentity

/**
 * F6/F7 fencing: an acquisition that is cancelled or closed must never publish a transport, even
 * when the handshake completes afterwards ("late allow").
 *
 * The `stubborn` fake deliberately ignores `close()` and still answers a *valid* handshake after
 * release, so only the connector's attempt fence can stop the publish. The `cooperative` fake
 * records the close for cleanup verification. The positive control proves a normal attempt still
 * publishes, so a fence that blocked everything could not pass.
 *
 * This uses the `directTransportFactory` seam on purpose: it is a fencing unit test, not the
 * no-injection N4 acceptance.
 */
@RunWith(AndroidJUnit4::class)
class RootConnectorCancellationFenceDeviceTest {
    private open class GatedTransport(private val swallowOnClose: Boolean) : RootChannelTransport {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        @Volatile
        var closed = false

        override fun handshake(request: RootServiceHandshakeRequest): RootServiceHandshakeResponse? {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            if (swallowOnClose && closed) return null
            // Ids must satisfy PrivilegedSessionIdentity's 16..128 character rule.
            val identity = PrivilegedSessionIdentity(
                authority = request.authority,
                privilegeTier = PrivilegeTier.ROOT,
                serviceUid = 0,
                appUid = request.appUid,
                androidUserId = request.androidUserId,
                appInstanceId = request.appInstanceId,
                packageName = request.packageName,
                signingSha256 = request.signingSha256,
                identityEpoch = request.identityEpoch,
                serviceInstanceId = "fence-service-instance-0001",
                sessionId = "fence-session-instance-0001",
                protocolVersion = PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION,
            )
            return RootServiceHandshakeResponse(accepted = true, identity = identity)
        }

        override fun submitEnvelope(
            controlSequence: Long,
            rawEnvelopeJson: String,
        ): RootServiceExecuteResponse? = null

        override fun executeAccepted(
            requestId: String,
            payloadBytes: ByteArray,
        ): RootServiceExecuteResponse? = null

        override fun submitAndExecute(
            controlSequence: Long,
            rawEnvelopeJson: String,
            payloadBytes: ByteArray,
        ): RootServiceExecuteResponse? = null

        override fun cancelCommand(requestId: String): Boolean = false

        override fun revokeAndShutdown(controlSequence: Long, reason: String): String = "DISCONNECTED"

        override fun close() {
            closed = true
            if (swallowOnClose) release.countDown()
        }
    }

    /** Ignores the close and answers its late handshake: the fence alone must block the publish. */
    private class StubbornTransport : GatedTransport(swallowOnClose = false)

    /** Cooperates with close: used only to prove the launched transport is actually closed. */
    private class CooperativeTransport : GatedTransport(swallowOnClose = true)

    private fun newConnector(context: Context, transport: GatedTransport): AndroidRootSessionConnector =
        AndroidRootSessionConnector(
            context = context,
            directTransportFactory = { _, _ -> transport },
        )

    private fun appContext(): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        (context as MobileAgentApp).ensureHostInitialized()
        return context
    }

    /** Positive control: an untouched acquisition still publishes the identity. */
    @Test
    fun normalAcquireStillPublishesOnTheSameSeam() {
        val transport = CooperativeTransport()
        val connector = newConnector(appContext(), transport)
        val session = AtomicReference<RootAcquiredSession?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                session.set(connector.tryAcquireSession(Authority.ROOT_DIRECT, 1L, "fence-control-${System.nanoTime()}", null))
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }.apply { isDaemon = true; start() }
        assertTrue(transport.entered.await(10, TimeUnit.SECONDS))
        transport.release.countDown()
        worker.join(10_000L)
        assertNull("control acquisition must not throw: ${failure.get()}", failure.get())
        assertNotNull("control acquisition must publish a session", session.get())
        assertEquals(0, session.get()!!.identity.serviceUid)
        assertEquals("fence-service-instance-0001", session.get()!!.identity.serviceInstanceId)
    }

    @Test
    fun closeDuringAcquireMustNotPublishTheLateHandshake() {
        val transport = StubbornTransport()
        val connector = newConnector(appContext(), transport)
        val result = AtomicReference<RootAcquiredSession?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                result.set(connector.tryAcquireSession(Authority.ROOT_DIRECT, 1L, "fence-close-${System.nanoTime()}", null))
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }.apply { isDaemon = true; start() }
        assertTrue("acquisition must reach the handshake", transport.entered.await(10, TimeUnit.SECONDS))
        connector.close()
        transport.release.countDown()
        worker.join(10_000L)
        assertNull("the worker must not throw: ${failure.get()}", failure.get())
        assertFalse("worker must finish", worker.isAlive)
        assertNull(
            "a closed connector must not publish a transport that finished its valid handshake afterwards",
            result.get(),
        )
        assertNull(connector.activeTransport(Authority.ROOT_DIRECT))
    }

    @Test
    fun cancelDuringAcquireMustNotPublishTheLateHandshake() {
        val transport = StubbornTransport()
        val connector = newConnector(appContext(), transport)
        val result = AtomicReference<RootAcquiredSession?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                result.set(connector.tryAcquireSession(Authority.ROOT_DIRECT, 1L, "fence-cancel-${System.nanoTime()}", null))
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }.apply { isDaemon = true; start() }
        assertTrue("acquisition must reach the handshake", transport.entered.await(10, TimeUnit.SECONDS))
        assertTrue(
            "a pending acquisition must be cancellable",
            connector.cancelPendingAcquisition(Authority.ROOT_DIRECT),
        )
        // The manager answers "allow" after the cancellation and the fake ignores the close.
        transport.release.countDown()
        worker.join(10_000L)
        assertNull("the worker must not throw: ${failure.get()}", failure.get())
        assertFalse("worker must finish", worker.isAlive)
        assertNull("a cancelled attempt must not publish its late handshake", result.get())
        assertNull(connector.activeTransport(Authority.ROOT_DIRECT))
    }

    @Test
    fun cancelClosesTheLaunchedTransport() {
        val transport = CooperativeTransport()
        val connector = newConnector(appContext(), transport)
        val worker = Thread {
            runCatching {
                connector.tryAcquireSession(Authority.ROOT_DIRECT, 1L, "fence-close-cleanup-${System.nanoTime()}", null)
            }
        }.apply { isDaemon = true; start() }
        assertTrue("acquisition must reach the handshake", transport.entered.await(10, TimeUnit.SECONDS))
        assertTrue(connector.cancelPendingAcquisition(Authority.ROOT_DIRECT))
        worker.join(10_000L)
        assertTrue("cancelling must close the launched transport", transport.closed)
    }
}
