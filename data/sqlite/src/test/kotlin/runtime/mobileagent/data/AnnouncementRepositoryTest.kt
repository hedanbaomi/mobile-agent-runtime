// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.announcements.AnnouncementPresentation
import runtime.mobileagent.announcements.AnnouncementSignature
import runtime.mobileagent.announcements.ClientContext
import runtime.mobileagent.announcements.FeedVerifier
import java.time.Instant
import java.util.Base64

class AnnouncementRepositoryTest {
    private val seed = ByteArray(32).also { it[31] = 1 }
    private val privateKey = Ed25519PrivateKeyParameters(seed, 0)
    private val publicKey = privateKey.generatePublicKey().encoded
    private val client = ClientContext(
        platform = "android",
        channel = "stable",
        versionCode = 1,
        locale = "en",
        installId = "00000000-0000-4000-8000-000000000002",
    )

    @Test
    fun statsDefaultOffAndReadIsNotAck() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = AnnouncementRepository(db)
        repo.setPublicKeyHex(publicKey.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
        repo.setKeyId("test-only-1")
        assertEquals(false, repo.statsEnabled())
        val payload = """{"feedVersion":1,"issuedAt":"2026-08-28T12:00:00Z","expiresAt":"2026-08-29T12:00:00Z","requestTarget":{"platform":"android","channel":"stable","versionCode":1,"locale":"en"},"audienceHash":"${FeedVerifier.audienceHash(client.installId)}","complete":true,"items":[{"id":"security-demo","revision":1,"category":"SECURITY","severity":"WARNING","displayMode":"MODAL","title":"Notice","summary":"Read","bodyMarkdown":"Body text.","mustAcknowledge":true,"dismissible":false,"pinned":true,"actions":[{"type":"ACKNOWLEDGE","key":"ack","label":"OK"}],"target":{"platform":"android","channel":"stable","rolloutPercent":100,"rolloutSalt":"stable-salt"}}],"withdrawn":[]}"""
        val envelope = sign(payload)
        assertNull(repo.applyEnvelope(envelope, "etag-1", client, Instant.parse("2026-08-28T12:00:00Z")))
        val first = repo.records().single()
        repo.markRead(first.item.id, first.item.revision)
        repo.markAllRead(repo.records())
        assertTrue(AnnouncementPresentation.modal(repo.records()) != null)
        repo.markAcknowledged(first.item.id, first.item.revision)
        assertEquals(null, AnnouncementPresentation.modal(repo.records()))

        val next = """{"feedVersion":2,"issuedAt":"2026-08-28T12:00:00Z","expiresAt":"2026-08-29T12:00:00Z","requestTarget":{"platform":"android","channel":"stable","versionCode":1,"locale":"en"},"audienceHash":"${FeedVerifier.audienceHash(client.installId)}","complete":true,"items":[{"id":"security-demo","revision":2,"category":"SECURITY","severity":"WARNING","displayMode":"MODAL","title":"Notice 2","summary":"Read","bodyMarkdown":"Body text two.","mustAcknowledge":true,"dismissible":false,"pinned":true,"actions":[{"type":"ACKNOWLEDGE","key":"ack","label":"OK"}],"target":{"platform":"android","channel":"stable","rolloutPercent":100,"rolloutSalt":"stable-salt"}}],"withdrawn":[]}"""
        assertNull(repo.applyEnvelope(sign(next), "etag-2", client, Instant.parse("2026-08-28T12:00:00Z")))
        assertEquals(2, AnnouncementPresentation.modal(repo.records())?.item?.revision)

        val withdrawn = """{"feedVersion":3,"issuedAt":"2026-08-28T12:00:00Z","expiresAt":"2026-08-29T12:00:00Z","requestTarget":{"platform":"android","channel":"stable","versionCode":1,"locale":"en"},"audienceHash":"${FeedVerifier.audienceHash(client.installId)}","complete":true,"items":[],"withdrawn":[{"id":"security-demo","revision":2}]}"""
        assertNull(repo.applyEnvelope(sign(withdrawn), "etag-3", client, Instant.parse("2026-08-28T12:00:00Z")))
        assertEquals(null, AnnouncementPresentation.modal(repo.records()))
        assertTrue(repo.records().any { it.withdrawn })

        val bad = envelope.replace("test-only-1", "other-key")
        assertEquals("unknown key", repo.applyEnvelope(bad, "etag-bad", client, Instant.parse("2026-08-28T12:00:00Z")))
        assertTrue(repo.records().any { it.withdrawn })
    }

    private fun sign(payload: String): String {
        val payloadBase64 = Base64.getEncoder().encodeToString(payload.toByteArray())
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        val message = (AnnouncementSignature.PREFIX + payloadBase64).toByteArray()
        signer.update(message, 0, message.size)
        val signature = Base64.getEncoder().encodeToString(signer.generateSignature())
        return """{"schemaVersion":1,"keyId":"test-only-1","payloadBase64":"$payloadBase64","signatureBase64":"$signature"}"""
    }
}
