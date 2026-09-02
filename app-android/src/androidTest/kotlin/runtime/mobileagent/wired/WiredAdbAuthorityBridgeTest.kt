// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.wired

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import runtime.mobileagent.bridge.BridgeCodec
import runtime.mobileagent.bridge.BridgeEncoding
import runtime.mobileagent.bridge.BridgeFrameType
import runtime.mobileagent.bridge.BridgeErrorCodes
import runtime.mobileagent.bridge.BridgePairingServer
import runtime.mobileagent.bridge.BridgePairingServerPending
import runtime.mobileagent.bridge.BridgeProtocol
import runtime.mobileagent.bridge.BridgeProtocolException
import runtime.mobileagent.bridge.BridgeRequestState
import runtime.mobileagent.bridge.BridgeResponseEnvelope
import runtime.mobileagent.bridge.BridgeSessionHandshake
import runtime.mobileagent.bridge.BridgeStatusEnvelope
import runtime.mobileagent.bridge.SecretBytes
import runtime.mobileagent.bridge.PairingTokenManager

class WiredAdbAuthorityBridgeTest {
    @Test
    fun loopbackPairingStoresTrustOnlyAfterCommitAckAndDisconnectKeepsTrust() = runBlocking {
        val appId = "app-instance"
        val desktopId = "desktop-1"
        val fingerprint = "11".repeat(32)
        val tokenManager = PairingTokenManager()
        val token = ByteArray(PAIR_TOKEN_BYTES) { (it + 1).toByte() }
        val now = System.currentTimeMillis()
        // Android is the sole issuer; the desktop side registers the
        // foreground token and only then reserves it for this transcript.
        tokenManager.register(token.copyOf(), now + PAIR_TOKEN_TTL_MS, now)
        val incoming = ArrayBlockingQueue<ByteArray>(16)
        val outgoing = ArrayBlockingQueue<ByteArray>(16)
        val channel = QueueWiredAdbChannel(incoming, outgoing)
        val serverDone = AtomicInteger(0)
        val server = Thread {
            var pending: BridgePairingServerPending? = null
            try {
                val start = BridgeCodec.decodePairStart(outgoing.take())
                pending = BridgePairingServer(
                    desktopId = desktopId,
                    serialFingerprint = BridgeEncoding.unhex(fingerprint),
                    tokenManager = tokenManager,
                ).begin(start)
                incoming.put(BridgeCodec.encodePairChallenge(pending!!.challenge))
                val response = BridgeCodec.decodePairResponse(outgoing.take())
                val finished = pending!!.acceptResponse(response)
                incoming.put(BridgeCodec.encodePairFinished(finished))
                val ack = BridgeCodec.decodePairCommitAck(outgoing.take())
                pending!!.prepareCommit(ack).close()
                pending!!.commitPrepared()
                serverDone.set(1)
            } finally {
                pending?.close()
            }
        }.also { it.start() }

        val secrets = TestSecretStore()
        val trust = TestTrustStore()
        val intent = InMemoryWiredAdbIntentStore()
        val bridge = WiredAdbAuthorityBridge(
            appInstanceId = appId,
            trustStore = trust,
            secretStore = secrets,
            intentStore = intent,
            connector = WiredAdbLoopbackConnector { address, port ->
                assertEquals(WIRED_ADB_LOOPBACK_ADDRESS, address)
                assertEquals(WIRED_ADB_LOOPBACK_PORT, port)
                channel
            },
            random = WiredAdbRandom { size ->
                if (size == PAIR_TOKEN_BYTES) token.copyOf() else ByteArray(size) { it.toByte() }
            },
        )
        val prompt = bridge.requestPairingFromForeground()
        assertTrue(prompt is WiredAdbResult.Success)
        val result = bridge.pair()
        server.join(5_000)
        assertTrue(result is WiredAdbResult.Success)
        assertEquals(1, serverDone.get())
        assertEquals(WiredAdbLifecycleState.TRUSTED, bridge.status.value.state)
        val record = trust.value ?: error("pairing did not persist trust")
        assertEquals(desktopId, record.desktopId)
        assertEquals(appId, record.appInstanceId)
        assertEquals(fingerprint, record.serialFingerprint)
        assertNotNull(secrets.resolve(record.secretRef))

        bridge.disconnect()
        assertEquals(WiredAdbLifecycleState.DISCONNECTED, bridge.status.value.state)
        assertEquals(WiredAdbUserIntent.ENABLED, bridge.status.value.userIntent)
        assertTrue(bridge.status.value.trusted)
        assertNotNull(trust.value)
        assertNotNull(secrets.resolve(record.secretRef))
        bridge.close()

        // A missing transport/Wi-Fi connection and even a new app process must
        // not erase explicit intent or paired trust. Only dispatch readiness is
        // unavailable until the user-selected channel reconnects.
        val offlineRestart = WiredAdbAuthorityBridge(
            appInstanceId = appId,
            trustStore = trust,
            secretStore = secrets,
            intentStore = intent,
            connector = WiredAdbLoopbackConnector { _, _ -> error("transport remains offline") },
        )
        assertEquals(WiredAdbLifecycleState.TRUSTED, offlineRestart.status.value.state)
        assertEquals(WiredAdbUserIntent.ENABLED, offlineRestart.status.value.userIntent)
        assertTrue(offlineRestart.status.value.trusted)
        assertEquals(WiredAdbConnectionState.DISCONNECTED, offlineRestart.status.value.connection)
        assertEquals(WiredAdbAvailability.TEMPORARILY_UNAVAILABLE, offlineRestart.status.value.availability)
        assertNotNull(trust.value)
        assertNotNull(secrets.resolve(record.secretRef))
        offlineRestart.close()
    }

    @Test
    fun cancelForegroundPairingInterruptsHandshakeAndKeepsAuthorityUnpaired() = runBlocking {
        val incoming = ArrayBlockingQueue<ByteArray>(4)
        val outgoing = ArrayBlockingQueue<ByteArray>(4)
        val channel = QueueWiredAdbChannel(incoming, outgoing)
        val trust = TestTrustStore()
        val bridge = WiredAdbAuthorityBridge(
            appInstanceId = "app-instance",
            trustStore = trust,
            secretStore = TestSecretStore(),
            intentStore = InMemoryWiredAdbIntentStore(),
            connector = WiredAdbLoopbackConnector { _, _ -> channel },
        )

        bridge.requestPairingFromForeground()
        val pairing = async(Dispatchers.Default) { bridge.pair() }
        assertNotNull(outgoing.poll(5, TimeUnit.SECONDS))
        bridge.cancelPairing()

        val result = pairing.await()
        assertEquals(WiredAdbErrorCode.UNKNOWN_OUTCOME, (result as WiredAdbResult.Failure).code)
        assertEquals(WiredAdbLifecycleState.UNPAIRED, bridge.status.value.state)
        assertNull(trust.value)
        bridge.close()
    }

    @Test
    fun shellResultAcceptsEmptyStdoutFromDesktopFixture() {
        val result = WiredAdbSharedAdapter.decodeShellResult(
            shellResponse(exitCode = 0, stdoutBase64 = "", stderrBase64 = "AQID"),
        )

        assertArrayEquals(ByteArray(0), result.stdout)
        assertArrayEquals(byteArrayOf(1, 2, 3), result.stderr)
    }

    @Test
    fun shellResultAcceptsEmptyStderrFromDesktopFixture() {
        val result = WiredAdbSharedAdapter.decodeShellResult(
            shellResponse(exitCode = 0, stdoutBase64 = "AQID", stderrBase64 = ""),
        )

        assertArrayEquals(byteArrayOf(1, 2, 3), result.stdout)
        assertArrayEquals(ByteArray(0), result.stderr)
    }

    @Test
    fun shellResultPreservesBothEmptyStreamsForNonzeroExitFromDesktopFixture() {
        val result = WiredAdbSharedAdapter.decodeShellResult(
            shellResponse(exitCode = 17, stdoutBase64 = "", stderrBase64 = ""),
        )

        assertEquals(17, result.exitCode)
        assertArrayEquals(ByteArray(0), result.stdout)
        assertArrayEquals(ByteArray(0), result.stderr)
    }

    @Test
    fun shellResultRejectsMalformedBase64FromDesktopFixture() {
        assertProtocolFailure {
            WiredAdbSharedAdapter.decodeShellResult(
                shellResponse(exitCode = 0, stdoutBase64 = "not base64!", stderrBase64 = ""),
            )
        }
        assertProtocolFailure {
            WiredAdbSharedAdapter.decodeShellResult(
                shellResponse(exitCode = 0, stdoutBase64 = "", stderrBase64 = "%%%"),
            )
        }
    }

    @Test
    fun wrongPairingTokenLocksAfterFiveAttempts() = runBlocking {
        val bridge = WiredAdbAuthorityBridge(
            appInstanceId = "app-instance",
            trustStore = TestTrustStore(),
            secretStore = TestSecretStore(),
        )
        bridge.requestPairingFromForeground()
        repeat(PAIR_MAX_ATTEMPTS - 1) {
            val failure = bridge.pair(ByteArray(PAIR_TOKEN_BYTES) { 0 })
            assertTrue(failure is WiredAdbResult.Failure)
            assertEquals(WiredAdbErrorCode.PAIRING_REJECTED, (failure as WiredAdbResult.Failure).code)
        }
        val final = bridge.pair(ByteArray(PAIR_TOKEN_BYTES) { 0 })
        assertEquals(WiredAdbErrorCode.PAIRING_ATTEMPTS_EXCEEDED, (final as WiredAdbResult.Failure).code)
        assertEquals(WiredAdbLifecycleState.UNPAIRED, bridge.status.value.state)
    }

    @Test
    fun connectorIsFixedLoopbackAndUnknownOutcomeFailsClosed() = runBlocking {
        val connectorCalls = AtomicInteger(0)
        val bridge = WiredAdbAuthorityBridge(
            appInstanceId = "app-instance",
            trustStore = TestTrustStore(
                WiredAdbTrustRecord(
                    desktopId = "desktop-1",
                    appInstanceId = "app-instance",
                    serialFingerprint = "33".repeat(32),
                    protocolVersion = WIRED_ADB_PROTOCOL_VERSION,
                    secretRef = "wired-adb:test",
                    transcriptHash = "44".repeat(32),
                ),
            ),
            secretStore = TestSecretStore(
                mapOf("wired-adb:test" to ByteArray(32) { 7 }),
                mapOf(
                    "wired-adb:test" to WiredAdbSecretBinding(
                        appInstanceId = "app-instance",
                        desktopId = "desktop-1",
                        serialFingerprint = "33".repeat(32),
                        protocolVersion = WIRED_ADB_PROTOCOL_VERSION,
                        transcriptHash = "44".repeat(32),
                    ),
                ),
            ),
            intentStore = InMemoryWiredAdbIntentStore(WiredAdbUserIntent.ENABLED),
            connector = WiredAdbLoopbackConnector { address, port ->
                connectorCalls.incrementAndGet()
                assertEquals(WIRED_ADB_LOOPBACK_ADDRESS, address)
                assertEquals(WIRED_ADB_LOOPBACK_PORT, port)
                throw java.io.IOException("offline")
            },
        )
        val result = bridge.connect()
        assertEquals(WiredAdbErrorCode.UNKNOWN_OUTCOME, (result as WiredAdbResult.Failure).code)
        assertEquals(WiredAdbLifecycleState.DISCONNECTED, bridge.status.value.state)
        assertEquals(1, connectorCalls.get())
    }

    @Test
    fun authenticationFailureDispatchesZeroRequests() = runBlocking {
        val secret = ByteArray(32) { 7 }
        val transcript = ByteArray(32) { 8 }
        val incoming = ArrayBlockingQueue<ByteArray>(8)
        val outgoing = ArrayBlockingQueue<ByteArray>(8)
        val channel = QueueWiredAdbChannel(incoming, outgoing)
        val dispatches = AtomicInteger(0)
        val server = Thread {
            val hello = BridgeCodec.decodeSessionHello(outgoing.take())
            val trust = SecretBytes.from(secret)
            val result = try {
                BridgeSessionHandshake.accept(hello, trust, transcript)
            } finally {
                trust.close()
            }
            val proof = result.welcome.serverProofHex.toCharArray()
            proof[0] = if (proof[0] == '0') '1' else '0'
            incoming.put(BridgeCodec.encodeSessionWelcome(result.welcome.copy(serverProofHex = proof.concatToString())))
            result.session.close()
            if (outgoing.poll(250, TimeUnit.MILLISECONDS) != null) dispatches.incrementAndGet()
        }.also { it.start() }
        val bridge = pretrustedBridge(secret, transcript, channel)
        val result = bridge.connect()
        server.join(5_000)
        assertEquals(WiredAdbErrorCode.BRIDGE_AUTH_FAILED, (result as WiredAdbResult.Failure).code)
        assertEquals(0, dispatches.get())
        assertEquals(WiredAdbLifecycleState.REAUTH_REQUIRED, bridge.status.value.state)
        bridge.close()
        secret.fill(0)
        transcript.fill(0)
    }

    @Test
    fun replayedResponseFailsClosedAndDoesNotFallback() = runBlocking {
        val secret = ByteArray(32) { 9 }
        val transcript = ByteArray(32) { 10 }
        val incoming = ArrayBlockingQueue<ByteArray>(16)
        val outgoing = ArrayBlockingQueue<ByteArray>(16)
        val channel = QueueWiredAdbChannel(incoming, outgoing)
        val dispatched = AtomicInteger(0)
        val server = Thread {
            val hello = BridgeCodec.decodeSessionHello(outgoing.take())
            val trust = SecretBytes.from(secret)
            val handshake = try {
                BridgeSessionHandshake.accept(hello, trust, transcript)
            } finally {
                trust.close()
            }
            incoming.put(BridgeCodec.encodeSessionWelcome(handshake.welcome))
            val first = handshake.session.decrypt(outgoing.take())
            dispatched.incrementAndGet()
            val body = BridgeCodec.encodeResponse(
                BridgeResponseEnvelope(
                    BridgeProtocol.VERSION,
                    first.requestId,
                    success = true,
                    payload = buildJsonObject {
                        put("operation", "file_list")
                        put("relative_path", "")
                    },
                ),
            )
            val firstResponse = handshake.session.encrypt(BridgeFrameType.RESPONSE, first.requestId, body)
            incoming.put(firstResponse)
            val second = handshake.session.decrypt(outgoing.take())
            dispatched.incrementAndGet()
            // Deliberately replay the sequence-zero response after the server
            // has consumed the second request.
            incoming.put(firstResponse)
            handshake.session.close()
        }.also { it.start() }
        val bridge = pretrustedBridge(secret, transcript, channel)
        assertTrue(bridge.connect() is WiredAdbResult.Success)
        val first = bridge.executeFile(bridge.newFileRequest(WiredAdbFileOperation.LIST, null))
        assertTrue(first is WiredAdbResult.Success)
        val secondRequest = bridge.newFileRequest(WiredAdbFileOperation.LIST, null)
        val second = bridge.executeFile(secondRequest)
        server.join(5_000)
        assertEquals(WiredAdbErrorCode.PROTOCOL_REPLAY, (second as WiredAdbResult.Failure).code)
        assertEquals(WiredAdbLifecycleState.REAUTH_REQUIRED, bridge.status.value.state)
        assertEquals(2, dispatched.get())
        assertEquals(
            WiredAdbErrorCode.REQUEST_INVALID,
            (bridge.executeFile(secondRequest) as WiredAdbResult.Failure).code,
        )
        bridge.close()
        secret.fill(0)
        transcript.fill(0)
    }

    @Test
    fun cancelWritesAtomicallyWhileRequestReadIsBlocked() = runBlocking {
        val secret = ByteArray(32) { 12 }
        val transcript = ByteArray(32) { 13 }
        val incoming = ArrayBlockingQueue<ByteArray>(16)
        val outgoing = ArrayBlockingQueue<ByteArray>(16)
        val channel = QueueWiredAdbChannel(incoming, outgoing)
        val requestSeen = CountDownLatch(1)
        val cancelSeen = CountDownLatch(1)
        val sequenceValues = ArrayList<Long>()
        val server = Thread {
            val hello = BridgeCodec.decodeSessionHello(outgoing.take())
            val trust = SecretBytes.from(secret)
            val handshake = try {
                BridgeSessionHandshake.accept(hello, trust, transcript)
            } finally {
                trust.close()
            }
            incoming.put(BridgeCodec.encodeSessionWelcome(handshake.welcome))
            val request = handshake.session.decrypt(outgoing.take())
            sequenceValues += request.sequence
            requestSeen.countDown()
            val cancel = handshake.session.decrypt(outgoing.take())
            sequenceValues += cancel.sequence
            assertEquals(BridgeFrameType.CANCEL, cancel.type)
            val cancelRequest = handshake.session.decryptCancel(cancel)
            assertEquals(request.requestId, cancelRequest.targetRequestId)
            cancelSeen.countDown()
            incoming.put(
                handshake.session.encryptStatus(
                    BridgeStatusEnvelope(
                        protocolVersion = BridgeProtocol.VERSION,
                        requestId = cancelRequest.requestId,
                        state = BridgeRequestState.CANCEL_ACK.wireName,
                        terminal = false,
                        accepted = true,
                    ),
                ),
            )
            val response = BridgeResponseEnvelope(
                BridgeProtocol.VERSION,
                request.requestId,
                success = true,
                payload = buildJsonObject { put("operation", "file_list") },
            )
            incoming.put(handshake.session.encryptResponse(response))
            incoming.put(
                handshake.session.encryptStatus(
                    BridgeStatusEnvelope(
                        protocolVersion = BridgeProtocol.VERSION,
                        requestId = cancelRequest.requestId,
                        state = BridgeRequestState.UNKNOWN_OUTCOME.wireName,
                        terminal = true,
                        accepted = true,
                        outcome = BridgeErrorCodes.UNKNOWN_OUTCOME,
                    ),
                ),
            )
            handshake.session.close()
        }.also { it.start() }

        val bridge = pretrustedBridge(secret, transcript, channel)
        assertTrue(bridge.connect() is WiredAdbResult.Success)
        val request = bridge.newFileRequest(WiredAdbFileOperation.LIST, null)
        val execution = async(Dispatchers.Default) { bridge.executeFile(request) }
        assertTrue(withContext(Dispatchers.IO) { requestSeen.await(5, TimeUnit.SECONDS) })

        val cancellation = bridge.cancel(request.requestId)
        assertTrue(cancellation is WiredAdbResult.Success)
        assertTrue(bridge.cancel(request.requestId) !is WiredAdbResult.Success)
        assertTrue(withContext(Dispatchers.IO) { cancelSeen.await(5, TimeUnit.SECONDS) })
        assertEquals(listOf(0L, 1L), sequenceValues)
        assertTrue(execution.await() is WiredAdbResult.Success)
        server.join(5_000)
        bridge.close()
        secret.fill(0)
        transcript.fill(0)
    }

    @Test
    fun cancelBeforeRequestWireCancelsLocallyAndNeverDispatchesRequest() = runBlocking {
        val secret = ByteArray(32) { 14 }
        val transcript = ByteArray(32) { 15 }
        val incoming = ArrayBlockingQueue<ByteArray>(16)
        val outgoing = ArrayBlockingQueue<ByteArray>(16)
        val channel = QueueWiredAdbChannel(incoming, outgoing)
        val requestPublishEntered = CountDownLatch(1)
        val releaseRequestPublish = CountDownLatch(1)
        val postHandshakeFrames = AtomicInteger(0)
        val server = Thread {
            val hello = BridgeCodec.decodeSessionHello(outgoing.take())
            val trust = SecretBytes.from(secret)
            val handshake = try {
                BridgeSessionHandshake.accept(hello, trust, transcript)
            } finally {
                trust.close()
            }
            incoming.put(BridgeCodec.encodeSessionWelcome(handshake.welcome))
            if (outgoing.poll(1, TimeUnit.SECONDS) != null) postHandshakeFrames.incrementAndGet()
            handshake.session.close()
        }.also { it.start() }

        val bridge = pretrustedBridge(
            secret,
            transcript,
            channel,
            beforeRequestPublish = {
                requestPublishEntered.countDown()
                check(releaseRequestPublish.await(5, TimeUnit.SECONDS))
            },
        )
        assertTrue(bridge.connect() is WiredAdbResult.Success)
        val request = bridge.newFileRequest(WiredAdbFileOperation.LIST, null)
        val execution = async(Dispatchers.Default) { bridge.executeFile(request) }
        assertTrue(withContext(Dispatchers.IO) { requestPublishEntered.await(5, TimeUnit.SECONDS) })

        val firstCancellation = bridge.cancel(request.requestId)
        assertEquals(WiredAdbErrorCode.REQUEST_CANCELLED, (firstCancellation as WiredAdbResult.Failure).code)
        assertEquals(
            WiredAdbErrorCode.UNKNOWN_OUTCOME,
            (bridge.cancel(request.requestId) as WiredAdbResult.Failure).code,
        )

        releaseRequestPublish.countDown()
        val result = execution.await()
        assertEquals(WiredAdbErrorCode.REQUEST_CANCELLED, (result as WiredAdbResult.Failure).code)
        server.join(5_000)
        assertEquals(0, postHandshakeFrames.get())
        assertEquals(WiredAdbLifecycleState.READY, bridge.status.value.state)
        bridge.close()
        secret.fill(0)
        transcript.fill(0)
    }

    @Test
    fun cancelConsumesDesktopForceUnknownStatusResponseSequence() = runBlocking {
        val secret = ByteArray(32) { 16 }
        val transcript = ByteArray(32) { 17 }
        val incoming = ArrayBlockingQueue<ByteArray>(16)
        val outgoing = ArrayBlockingQueue<ByteArray>(16)
        val channel = QueueWiredAdbChannel(incoming, outgoing)
        val requestSeen = CountDownLatch(1)
        val sequenceValues = ArrayList<Long>()
        val server = Thread {
            val hello = BridgeCodec.decodeSessionHello(outgoing.take())
            val trust = SecretBytes.from(secret)
            val handshake = try {
                BridgeSessionHandshake.accept(hello, trust, transcript)
            } finally {
                trust.close()
            }
            incoming.put(BridgeCodec.encodeSessionWelcome(handshake.welcome))
            val request = handshake.session.decrypt(outgoing.take())
            sequenceValues += request.sequence
            requestSeen.countDown()
            val cancel = handshake.session.decrypt(outgoing.take())
            sequenceValues += cancel.sequence
            assertEquals(BridgeFrameType.CANCEL, cancel.type)
            val cancelRequest = handshake.session.decryptCancel(cancel)
            assertEquals(request.requestId, cancelRequest.targetRequestId)

            // This is the force-unknown order emitted by desktop: the cancel
            // request is acknowledged, then the target is terminal-unknown
            // and gets its error RESPONSE, then the cancel request is closed.
            incoming.put(
                handshake.session.encryptStatus(
                    BridgeStatusEnvelope(
                        protocolVersion = BridgeProtocol.VERSION,
                        requestId = cancelRequest.requestId,
                        state = BridgeRequestState.CANCEL_ACK.wireName,
                        terminal = false,
                        accepted = true,
                    ),
                ),
            )
            incoming.put(
                handshake.session.encryptStatus(
                    BridgeStatusEnvelope(
                        protocolVersion = BridgeProtocol.VERSION,
                        requestId = request.requestId,
                        state = BridgeRequestState.UNKNOWN_OUTCOME.wireName,
                        terminal = true,
                        accepted = true,
                        outcome = BridgeErrorCodes.UNKNOWN_OUTCOME,
                    ),
                ),
            )
            incoming.put(
                handshake.session.encryptResponse(
                    BridgeResponseEnvelope(
                        protocolVersion = BridgeProtocol.VERSION,
                        requestId = request.requestId,
                        success = false,
                        errorCode = BridgeErrorCodes.UNKNOWN_OUTCOME,
                    ),
                ),
            )
            incoming.put(
                handshake.session.encryptStatus(
                    BridgeStatusEnvelope(
                        protocolVersion = BridgeProtocol.VERSION,
                        requestId = cancelRequest.requestId,
                        state = BridgeRequestState.UNKNOWN_OUTCOME.wireName,
                        terminal = true,
                        accepted = true,
                        outcome = BridgeErrorCodes.UNKNOWN_OUTCOME,
                    ),
                ),
            )
            handshake.session.close()
        }.also { it.start() }

        val bridge = pretrustedBridge(secret, transcript, channel)
        assertTrue(bridge.connect() is WiredAdbResult.Success)
        val request = bridge.newFileRequest(WiredAdbFileOperation.LIST, null)
        val execution = async(Dispatchers.Default) { bridge.executeFile(request) }
        assertTrue(withContext(Dispatchers.IO) { requestSeen.await(5, TimeUnit.SECONDS) })

        val cancellation = bridge.cancel(request.requestId)
        assertTrue(cancellation is WiredAdbResult.Success)
        val result = execution.await()
        assertEquals(WiredAdbErrorCode.UNKNOWN_OUTCOME, (result as WiredAdbResult.Failure).code)
        assertEquals(listOf(0L, 1L), sequenceValues)
        server.join(5_000)
        bridge.close()
        secret.fill(0)
        transcript.fill(0)
    }

    @Test
    fun disconnectDuringConnectCannotPublishReady() = runBlocking {
        val secret = ByteArray(32) { 14 }
        val transcript = ByteArray(32) { 15 }
        val incoming = ArrayBlockingQueue<ByteArray>(4)
        val outgoing = ArrayBlockingQueue<ByteArray>(4)
        val channel = QueueWiredAdbChannel(incoming, outgoing)
        val connectorEntered = CountDownLatch(1)
        val releaseConnector = CountDownLatch(1)
        val invalidated = AtomicBoolean(false)
        val bridge = pretrustedBridge(
            secret,
            transcript,
            channel,
            connector = WiredAdbLoopbackConnector { _, _ ->
                connectorEntered.countDown()
                releaseConnector.await(5, TimeUnit.SECONDS)
                if (invalidated.get()) throw java.io.IOException("connect invalidated")
                channel
            },
        )
        val connecting = async(Dispatchers.Default) { bridge.connect() }
        assertTrue(withContext(Dispatchers.IO) { connectorEntered.await(5, TimeUnit.SECONDS) })
        invalidated.set(true)
        bridge.disconnect()
        releaseConnector.countDown()
        val result = connecting.await()
        assertEquals(WiredAdbErrorCode.UNKNOWN_OUTCOME, (result as WiredAdbResult.Failure).code)
        assertTrue(bridge.status.value.state != WiredAdbLifecycleState.READY)
        bridge.close()
        secret.fill(0)
        transcript.fill(0)
    }

    private fun pretrustedBridge(
        secret: ByteArray,
        transcript: ByteArray,
        channel: WiredAdbChannel,
        connector: WiredAdbLoopbackConnector = WiredAdbLoopbackConnector { _, _ -> channel },
        beforeRequestPublish: () -> Unit = {},
    ): WiredAdbAuthorityBridge {
        val record = WiredAdbTrustRecord(
            desktopId = "desktop-1",
            appInstanceId = "app-instance",
            serialFingerprint = "55".repeat(32),
            protocolVersion = WIRED_ADB_PROTOCOL_VERSION,
            secretRef = "wired-adb:test",
            transcriptHash = transcript.toHex(),
        )
        return WiredAdbAuthorityBridge(
            appInstanceId = "app-instance",
            trustStore = TestTrustStore(record),
            secretStore = TestSecretStore(
                mapOf("wired-adb:test" to secret),
                mapOf(
                    "wired-adb:test" to WiredAdbSecretBinding(
                        appInstanceId = record.appInstanceId,
                        desktopId = record.desktopId,
                        serialFingerprint = record.serialFingerprint,
                        protocolVersion = record.protocolVersion,
                        transcriptHash = record.transcriptHash,
                    ),
                ),
            ),
            intentStore = InMemoryWiredAdbIntentStore(WiredAdbUserIntent.ENABLED),
            connector = connector,
            beforeRequestPublish = beforeRequestPublish,
        )
    }

    private fun shellResponse(exitCode: Int, stdoutBase64: String, stderrBase64: String): BridgeResponseEnvelope =
        BridgeResponseEnvelope(
            protocolVersion = BridgeProtocol.VERSION,
            requestId = "shell-fixture",
            success = true,
            payload = buildJsonObject {
                put("exit_code", exitCode)
                put("stdout_base64", stdoutBase64)
                put("stderr_base64", stderrBase64)
                put("timed_out", false)
                put("cancelled", false)
                put("stdout_truncated", false)
                put("stderr_truncated", false)
                put("duration_ms", 1)
            },
        )

    private fun assertProtocolFailure(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected malformed shell Base64 to fail")
        } catch (_: BridgeProtocolException) {
            // Expected: malformed output is a protocol failure.
        }
    }

    private class TestTrustStore(initial: WiredAdbTrustRecord? = null) : WiredAdbTrustStore {
        @Volatile var value: WiredAdbTrustRecord? = initial
        override fun load(): WiredAdbTrustRecord? = value
        override fun save(record: WiredAdbTrustRecord) { value = record }
        override fun clear() { value = null }
    }

    private class TestSecretStore(
        initial: Map<String, ByteArray> = emptyMap(),
        initialBindings: Map<String, WiredAdbSecretBinding> = emptyMap(),
    ) : WiredAdbBoundSecretStore {
        private val values = initial.mapValuesTo(mutableMapOf()) { it.value.copyOf() }
        private val bindings = initialBindings.toMutableMap()
        override suspend fun put(secretRef: String, secret: ByteArray) { values[secretRef] = secret.copyOf() }
        override suspend fun resolve(secretRef: String): ByteArray? = values[secretRef]?.copyOf()
        override suspend fun remove(secretRef: String) { values.remove(secretRef)?.fill(0) }

        override suspend fun putBound(secretRef: String, secret: ByteArray, binding: WiredAdbSecretBinding) {
            values[secretRef] = secret.copyOf()
            bindings[secretRef] = binding
        }

        override suspend fun resolveBound(secretRef: String, binding: WiredAdbSecretBinding): ByteArray? =
            if (bindings[secretRef] == binding) values[secretRef]?.copyOf() else null
    }
}
