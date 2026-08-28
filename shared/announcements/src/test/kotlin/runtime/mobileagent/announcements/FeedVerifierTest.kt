// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.announcements

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64

class FeedVerifierTest {
    private val seed = ByteArray(32).also { it[31] = 1 }
    private val privateKey = Ed25519PrivateKeyParameters(seed, 0)
    private val publicKey = privateKey.generatePublicKey().encoded
    private val keys = mapOf("test-only-1" to publicKey)
    private val client = ClientContext(
        platform = "android",
        channel = "stable",
        versionCode = 1,
        locale = "en",
        installId = "00000000-0000-4000-8000-000000000002",
    )

    @Test
    fun nodeTestOnlySeedSignatureVerifies() {
        val payloadBase64 = Base64.getEncoder().encodeToString("{}".toByteArray())
        assertTrue(
            AnnouncementSignature.verify(
                payloadBase64,
                "C48ZO30i1TLzWgACUFPm5uYnVvP5e5M7nFnI5gqB+9ZGDyBH2YnBgxWzz4Yiorcsas49M0tjJYSu8KPUhh5eAw==",
                publicKey,
            ),
        )
        assertEquals(
            "4cb5abf6ad79fbf5abbccafcc269d85cd2651ed4b885b5869f241aedf0a5ba29",
            publicKey.joinToString("") { "%02x".format(it.toInt() and 0xFF) },
        )
    }

    @Test
    fun acceptsSignedFeedAndRejectsTamperAudienceAndOldVersion() {
        val payload = """{"feedVersion":2,"issuedAt":"2026-08-28T12:00:00Z","expiresAt":"2026-08-29T12:00:00Z","requestTarget":{"platform":"android","channel":"stable","versionCode":1,"locale":"en"},"audienceHash":"${FeedVerifier.audienceHash(client.installId)}","complete":true,"items":[{"id":"security-demo","revision":1,"category":"SECURITY","severity":"WARNING","displayMode":"MODAL","title":"Notice","summary":"Read","bodyMarkdown":"Body text.","mustAcknowledge":true,"dismissible":false,"pinned":true,"actions":[{"type":"ACKNOWLEDGE","key":"ack","label":"OK"}],"target":{"platform":"android","channel":"stable","rolloutPercent":100,"rolloutSalt":"stable-salt"}}],"withdrawn":[]}"""
        val payloadBase64 = Base64.getEncoder().encodeToString(payload.toByteArray())
        val envelope = envelope(payloadBase64)
        val accepted = FeedVerifier.verify(envelope, keys, client, Instant.parse("2026-08-28T12:00:00Z"))
        assertTrue(accepted is FeedVerifyResult.Accepted)

        val tampered = envelope.replace(payloadBase64.takeLast(4), "AAAA")
        assertTrue(FeedVerifier.verify(tampered, keys, client, Instant.parse("2026-08-28T12:00:00Z")) is FeedVerifyResult.Rejected)

        val otherClient = client.copy(installId = "00000000-0000-4000-8000-000000000001")
        assertTrue(FeedVerifier.verify(envelope, keys, otherClient, Instant.parse("2026-08-28T12:00:00Z")) is FeedVerifyResult.Rejected)

        val unsigned = """{"schemaVersion":1,"keyId":"test-only-1","payloadBase64":"$payloadBase64","signatureBase64":"AAAA"}"""
        assertTrue(FeedVerifier.verify(unsigned, keys, client, Instant.parse("2026-08-28T12:00:00Z")) is FeedVerifyResult.Rejected)

        assertTrue(
            FeedVerifier.verify(envelope, keys, client, Instant.parse("2026-08-28T12:00:00Z"), previousFeedVersion = 5) is FeedVerifyResult.Rejected,
        )
    }

    @Test
    fun rejectsHtmlScriptAndBlockedActions() {
        assertFalse(AnnouncementContentGuard.allowedMarkdown("hi <script>alert(1)</script>"))
        assertFalse(
            AnnouncementActions.allowed(AnnouncementAction("OPEN_HTTPS_URL", "x", "x", "javascript:alert(1)")),
        )
        assertFalse(AnnouncementActions.allowed(AnnouncementAction("OPEN_APP_ROUTE", "x", "x", "app://skills/grant")))
    }

    @Test
    fun readIsNotAcknowledgeAndRevisionStateIsIndependent() {
        val item = AnnouncementItem(
            id = "n1",
            revision = 1,
            category = AnnouncementCategory.SECURITY,
            severity = Severity.WARNING,
            displayMode = DisplayMode.MODAL,
            title = "t",
            summary = "s",
            bodyMarkdown = "body",
            mustAcknowledge = true,
            dismissible = false,
            pinned = true,
        )
        val read = CachedAnnouncement(item, AnnouncementLocalState(readAt = "t"), withdrawn = false, signatureExpired = false)
        assertTrue(AnnouncementPresentation.modal(listOf(read)) != null)
        val acked = read.copy(state = read.state.copy(acknowledgedAt = "t"))
        assertEquals(null, AnnouncementPresentation.modal(listOf(acked)))
        assertEquals(null, AnnouncementPresentation.banner(listOf(read.copy(signatureExpired = true))))
        val next = CachedAnnouncement(item.copy(revision = 2), AnnouncementLocalState(), withdrawn = false, signatureExpired = false)
        assertEquals(2, AnnouncementPresentation.modal(listOf(acked, next))?.item?.revision)
    }

    private fun envelope(payloadBase64: String): String {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        val message = (AnnouncementSignature.PREFIX + payloadBase64).toByteArray()
        signer.update(message, 0, message.size)
        val signature = Base64.getEncoder().encodeToString(signer.generateSignature())
        return """{"schemaVersion":1,"keyId":"test-only-1","payloadBase64":"$payloadBase64","signatureBase64":"$signature"}"""
    }
}
