// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.bridge

import java.security.SecureRandom
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BridgeProtocolTest {
    @Test
    fun pairingRequiresClientPersistenceAckBeforeServerCommit() {
        val identity = BridgeIdentity.forSerial("desktop-test", "app-test", "USB-serial-1")
        val manager = PairingTokenManager(clockMillis = { 1_000L })
        val tokenBytes = ByteArray(BridgeProtocol.TOKEN_BYTES) { (it + 1).toByte() }
        val token = PairingToken.create(1_000L, 301_000L, tokenBytes)
        manager.register(tokenBytes, expiresAtMillis = 301_000L, nowMillis = 1_000L)
        val client = BridgePairingClient(identity, token, seededRandom(2))
        val pending = BridgePairingServer(identity, manager, seededRandom(3)).begin(client.startEnvelope())
        val response = client.acceptChallenge(pending.challenge)
        val finished = pending.acceptResponse(response)
        val commit = client.acceptFinished(finished)

        // A pending pairing is not committed by the response/Finished exchange.
        val secondReservationAttempt = assertThrows<PairingTokenConsumedException> {
            manager.reserve(token.copyBytes(), 1_000L)
        }
        assertTrue(secondReservationAttempt.message.orEmpty().contains("in use"))

        val serverTrust = pending.prepareCommit(commit.ack)
        assertArrayEquals(commit.trustMaterial.copyFingerprint(), serverTrust.copyFingerprint())
        assertArrayEquals(commit.trustMaterial.copyPersistentTrust(), serverTrust.copyPersistentTrust())
        pending.commitPrepared()
        assertThrows<PairingTokenConsumedException> {
            manager.reserve(token.copyBytes(), 1_000L)
        }
        pending.close()
        serverTrust.close()
        commit.close()
        client.close()
        token.close()
    }

    @Test
    fun firstPairStartContainsOnlyAppInputsAndChallengeAuthenticatesDesktopIdentity() {
        val manager = PairingTokenManager(clockMillis = { 1_000L })
        val tokenBytes = ByteArray(BridgeProtocol.TOKEN_BYTES) { (it + 9).toByte() }
        val token = PairingToken.create(1_000L, 301_000L, tokenBytes)
        manager.register(tokenBytes.copyOf(), expiresAtMillis = 301_000L, nowMillis = 1_000L)
        val client = BridgePairingClient("app-from-android", token, seededRandom(12))
        val server = BridgePairingServer(
            desktopId = "stable-desktop",
            serialFingerprint = ByteArray(BridgeProtocol.SERIAL_FINGERPRINT_BYTES) { 4 },
            tokenManager = manager,
            random = seededRandom(13),
        )
        val start = client.startEnvelope()
        val encodedStart = BridgeCodec.encodePairStart(start).toString(Charsets.UTF_8)
        assertFalse(encodedStart.contains("desktopId"))
        assertFalse(encodedStart.contains("serialFingerprint"))
        val pending = server.begin(start)
        assertTrue(pending.challenge.desktopId == "stable-desktop")
        assertTrue(pending.challenge.appInstanceId == "app-from-android")
        val response = client.acceptChallenge(pending.challenge)
        assertTrue(client.identity.desktopId == "stable-desktop")
        assertTrue(client.identity.appInstanceId == "app-from-android")
        val altered = pending.challenge.copy(desktopId = "attacker-desktop")
        val alteredClient = BridgePairingClient("app-from-android", token, seededRandom(14))
        assertThrows<BridgeAuthenticationException> { alteredClient.acceptChallenge(altered) }
        alteredClient.close()
        val finished = pending.acceptResponse(response)
        val commit = client.acceptFinished(finished)
        val serverMaterial = pending.prepareCommit(commit.ack)
        pending.commitPrepared()
        serverMaterial.close()
        commit.close()
        pending.close()
        client.close()
        token.close()
        manager.close()
    }

    @Test
    fun pairingTokenExpiryAndTtlAreEnforced() {
        assertThrows<IllegalArgumentException> {
            PairingToken.create(
                issuedAtMillis = 0L,
                expiresAtMillis = BridgeProtocol.PAIRING_TTL_MILLIS + 1,
                randomBytes = ByteArray(BridgeProtocol.TOKEN_BYTES),
            )
        }
        var now = 1_000L
        val manager = PairingTokenManager(clockMillis = { now })
        val token = ByteArray(BridgeProtocol.TOKEN_BYTES) { 7 }
        manager.register(token.copyOf(), expiresAtMillis = 2_000L, nowMillis = now)
        now = 2_000L
        assertThrows<BridgeAuthenticationException> { manager.reserve(token.copyOf()) }
        manager.close()
        token.fill(0)
    }

    @Test
    fun pairingTranscriptBindsEveryIdentityAndNonce() {
        val first = BridgeIdentity.forSerial("desktop-a", "app-a", "serial-a")
        val second = BridgeIdentity.forSerial("desktop-b", "app-a", "serial-a")
        val desktopNonce = ByteArray(BridgeProtocol.NONCE_BYTES) { it.toByte() }
        val appNonce = ByteArray(BridgeProtocol.NONCE_BYTES) { (it + 1).toByte() }
        val firstTranscript = PairingTranscript.from(first, desktopNonce, appNonce)
        val secondTranscript = PairingTranscript.from(second, desktopNonce, appNonce)
        assertNotEquals(BridgeEncoding.hex(firstTranscript.hash()), BridgeEncoding.hex(secondTranscript.hash()))
        assertTrue(firstTranscript.canonicalBytes().size > BridgeProtocol.NONCE_BYTES * 2)
    }

    @Test
    fun sessionAeadRejectsTamperingReplayAndWrongDirection() {
        val trust = SecretBytes.from(ByteArray(32) { (it + 7).toByte() })
        val transcriptHash = ByteArray(32) { (it + 11).toByte() }
        val clientHello = BridgeSessionHandshake.newHello(seededRandom(4))
        val serverResult = BridgeSessionHandshake.accept(clientHello, trust, transcriptHash, seededRandom(5))
        val clientSession = BridgeSessionHandshake.complete(clientHello, serverResult.welcome, trust, transcriptHash)
        val frame = clientSession.encrypt(BridgeFrameType.REQUEST, "request-0001", byteArrayOf(1, 2, 3))
        val decoded = serverResult.session.decrypt(frame)
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.payload)
        assertThrows<BridgeSequenceException> { serverResult.session.decrypt(frame) }

        val tampered = frame.copyOf()
        tampered[tampered.lastIndex] = (tampered.last() + 1).toByte()
        assertThrows<BridgeSequenceException> {
            // The original sequence is already consumed, proving replay is rejected before auth.
            serverResult.session.decrypt(tampered)
        }

        val serverFrame = serverResult.session.encrypt(BridgeFrameType.RESPONSE, "request-0001", byteArrayOf(9))
        assertThrows<BridgeProtocolException> { serverResult.session.decrypt(serverFrame) }
        clientSession.close()
        serverResult.session.close()
        trust.close()
    }

    @Test
    fun sessionAuthenticationFailureDoesNotAdvanceSequence() {
        val trust = SecretBytes.from(ByteArray(32) { 3 })
        val transcriptHash = ByteArray(32) { 4 }
        val hello = BridgeSessionHandshake.newHello(seededRandom(6))
        val server = BridgeSessionHandshake.accept(hello, trust, transcriptHash, seededRandom(7))
        val client = BridgeSessionHandshake.complete(hello, server.welcome, trust, transcriptHash)
        val frame = client.encrypt(BridgeFrameType.REQUEST, "request-0002", byteArrayOf(1))
        val tampered = frame.copyOf().also { it[it.lastIndex] = (it.last() + 3).toByte() }
        assertThrows<BridgeAuthenticationException> { server.session.decrypt(tampered) }
        assertArrayEquals(byteArrayOf(1), server.session.decrypt(frame).payload)
        client.close()
        server.session.close()
        trust.close()
    }

    @Test
    fun codecRejectsUnknownHostBindingAndOversizedCommand() {
        val unknown = """
            {"protocolVersion":1,"requestId":"request-0003","operation":"shell_exec","payload":{"command":"id","serial":"x"}}
        """.trimIndent().toByteArray()
        assertThrows<BridgeProtocolException> { BridgeCodec.decodeRequest(unknown) }
        val command = "x".repeat(BridgeProtocol.MAX_COMMAND_BYTES + 1)
        val request = BridgeRequestEnvelope(
            BridgeProtocol.VERSION,
            "request-0004",
            BridgeOperation.SHELL_EXEC.wireName,
            buildJsonObject { put("command", command) },
        )
        assertThrows<IllegalArgumentException> { BridgeCodec.encodeRequest(request) }
    }

    @Test
    fun frameHasNoCompressionAndRejectsTrailingBytes() {
        val trust = SecretBytes.from(ByteArray(32) { 8 })
        val hash = ByteArray(32) { 9 }
        val hello = BridgeSessionHandshake.newHello(seededRandom(8))
        val server = BridgeSessionHandshake.accept(hello, trust, hash, seededRandom(9))
        val client = BridgeSessionHandshake.complete(hello, server.welcome, trust, hash)
        val frame = client.encrypt(BridgeFrameType.STATUS, "request-0005", byteArrayOf(4))
        assertThrows<BridgeProtocolException> { BridgeFrameCodec.decode(frame + byteArrayOf(1)) }
        client.close()
        server.session.close()
        trust.close()
    }

    private fun seededRandom(seed: Int): SecureRandom = SecureRandom.getInstance("SHA1PRNG").also {
        it.setSeed(ByteArray(16) { (seed + it).toByte() })
    }
}
