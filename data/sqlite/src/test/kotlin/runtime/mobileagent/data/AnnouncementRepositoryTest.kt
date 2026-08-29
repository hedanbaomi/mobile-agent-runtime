// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
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
        assertEquals(null, AnnouncementPresentation.modal(repo.records(client = client)))

        val next = """{"feedVersion":2,"issuedAt":"2026-08-28T12:00:00Z","expiresAt":"2026-08-29T12:00:00Z","requestTarget":{"platform":"android","channel":"stable","versionCode":1,"locale":"en"},"audienceHash":"${FeedVerifier.audienceHash(client.installId)}","complete":true,"items":[{"id":"security-demo","revision":2,"category":"SECURITY","severity":"WARNING","displayMode":"MODAL","title":"Notice 2","summary":"Read","bodyMarkdown":"Body text two.","mustAcknowledge":true,"dismissible":false,"pinned":true,"actions":[{"type":"ACKNOWLEDGE","key":"ack","label":"OK"}],"target":{"platform":"android","channel":"stable","rolloutPercent":100,"rolloutSalt":"stable-salt"}}],"withdrawn":[]}"""
        assertNull(repo.applyEnvelope(sign(next), "etag-2", client, Instant.parse("2026-08-28T12:00:00Z")))
        assertEquals(2, AnnouncementPresentation.modal(repo.records(client = client))?.item?.revision)

        val withdrawn = """{"feedVersion":3,"issuedAt":"2026-08-28T12:00:00Z","expiresAt":"2026-08-29T12:00:00Z","requestTarget":{"platform":"android","channel":"stable","versionCode":1,"locale":"en"},"audienceHash":"${FeedVerifier.audienceHash(client.installId)}","complete":true,"items":[],"withdrawn":[{"id":"security-demo","revision":2}]}"""
        assertNull(repo.applyEnvelope(sign(withdrawn), "etag-3", client, Instant.parse("2026-08-28T12:00:00Z")))
        assertEquals(null, AnnouncementPresentation.modal(repo.records(client = client)))
        assertTrue(repo.records(client = client).any { it.withdrawn })

        val bad = envelope.replace("test-only-1", "other-key")
        assertEquals("unknown key", repo.applyEnvelope(bad, "etag-bad", client, Instant.parse("2026-08-28T12:00:00Z")))
        assertTrue(repo.records(client = client).any { it.withdrawn })
    }

    @Test
    fun dismissibleModalIsHiddenAfterDismissAndContextChangeRefetches() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = AnnouncementRepository(db)
        repo.setPublicKeyHex(publicKey.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
        repo.setKeyId("test-only-1")
        val payload = """{"feedVersion":1,"issuedAt":"2026-08-28T12:00:00Z","expiresAt":"2026-08-29T12:00:00Z","requestTarget":{"platform":"android","channel":"stable","versionCode":1,"locale":"en"},"audienceHash":"${FeedVerifier.audienceHash(client.installId)}","complete":true,"items":[{"id":"promo","revision":1,"category":"FEATURE","severity":"INFO","displayMode":"MODAL","title":"Promo","summary":"Optional","bodyMarkdown":"You can close this.","mustAcknowledge":false,"dismissible":true,"pinned":false,"actions":[{"type":"DISMISS","key":"d","label":"OK"}],"target":{"platform":"android","channel":"stable","rolloutPercent":100,"rolloutSalt":"stable-salt"}}],"withdrawn":[]}"""
        assertNull(repo.applyEnvelope(sign(payload), "etag-p", client, Instant.parse("2026-08-28T12:00:00Z")))
        val promo = repo.records(client = client).single()
        assertTrue(AnnouncementPresentation.modal(repo.records(client = client)) != null)
        repo.markDismissed(promo.item.id, promo.item.revision)
        assertEquals(null, AnnouncementPresentation.modal(repo.records(client = client)))
        val zh = client.copy(locale = "zh-CN")
        assertTrue(repo.needsFetch(zh, Instant.parse("2026-08-28T12:01:00Z")))
        val bumped = client.copy(versionCode = 2)
        assertTrue(repo.needsFetch(bumped, Instant.parse("2026-08-28T12:01:00Z")))
        assertEquals(false, repo.needsFetch(client, Instant.parse("2026-08-28T12:01:00Z")))
    }

    @Test
    fun automaticThrottleUsesLastSuccessfulFetchNotLastAttempt() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = AnnouncementRepository(db)
        repo.setPublicKeyHex(publicKey.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
        repo.setKeyId("test-only-1")
        val fetchedAt = Instant.parse("2026-08-28T12:00:00Z")
        assertNull(repo.applyEnvelope(sign(feedPayload(client, 10)), "etag-10", client, fetchedAt))

        // A later failed attempt must not move the six-hour automatic window.
        repo.markAttempt(client, fetchedAt.plusSeconds(5 * 60 * 60))
        assertTrue(repo.needsFetch(client, fetchedAt.plusSeconds(6 * 60 * 60 + 1)))
        assertEquals(fetchedAt.toString(), repo.lastFetchedAt(client))
    }

    @Test
    fun telemetryIdentityIsSeparateAndResetWhenStatsAreDisabled() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = AnnouncementRepository(db)
        val rolloutId = repo.installId()
        repo.setStatsEnabled(true)
        val firstTelemetryId = repo.telemetryIdentity()
        assertTrue(!firstTelemetryId.isNullOrBlank())
        assertNotEquals(rolloutId, firstTelemetryId)
        repo.recordInstallSeen(client, Instant.parse("2026-08-28T12:00:00Z"))
        assertTrue(repo.pendingTelemetryJson().orEmpty().contains("install_seen"))

        repo.setStatsEnabled(false)
        assertNull(repo.telemetryIdentity())
        assertNull(repo.pendingTelemetryJson())
        assertEquals(rolloutId, repo.installId())

        repo.setStatsEnabled(true)
        assertNotEquals(firstTelemetryId, repo.telemetryIdentity())
        assertEquals(rolloutId, repo.installId())
    }

    @Test
    fun installSeenIsOnceAndAppActiveDedupResetsOnVersionChange() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = AnnouncementRepository(db)
        repo.setStatsEnabled(true)
        val at = Instant.parse("2026-08-28T12:00:00Z")
        repo.recordInstallSeen(client, at)
        repo.recordInstallSeen(client, at.plusSeconds(1))
        repo.recordAppActive(client, at)
        repo.recordAppActive(client, at.plusSeconds(5 * 60 * 60))
        repo.recordAppActive(client, at.plusSeconds(6 * 60 * 60 + 1))
        repo.recordAppActive(client.copy(versionCode = 2), at.plusSeconds(6 * 60 * 60 + 2))

        val events = Json.parseToJsonElement(repo.pendingTelemetryJson()!!).jsonObject.getValue("events").jsonArray
        assertEquals(4, events.size)
        assertEquals(listOf("install_seen", "app_active", "app_active", "app_active"), events.map {
            it.jsonObject.getValue("type").jsonPrimitive.content
        })
        events.forEach { event ->
            assertTrue(event.jsonObject.keys.all { it in setOf(
                "eventId", "type", "installId", "platform", "channel", "versionCode", "locale",
                "announcementId", "revision", "actionId", "occurredAt",
            ) })
            assertTrue(event.jsonObject.values.none { value -> value.toString().contains("Body text") })
        }
    }

    @Test
    fun failedFetchKeepsPreviouslyVerifiedCacheUntouched() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = AnnouncementRepository(db)
        repo.setPublicKeyHex(publicKey.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
        repo.setKeyId("test-only-1")
        val at = Instant.parse("2026-08-28T12:00:00Z")
        assertNull(repo.applyEnvelope(sign(feedPayload(client, 20)), "etag-20", client, at))
        val before = repo.records(client = client).map { it.item.id to it.item.revision }
        repo.markAttempt(client, at.plusSeconds(60))
        repo.recordFetchFailure(client, at.plusSeconds(60), "network")
        assertEquals(before, repo.records(client = client).map { it.item.id to it.item.revision })
    }

    private fun feedPayload(client: ClientContext, version: Long): String =
        """{"feedVersion":$version,"issuedAt":"2026-08-28T12:00:00Z","expiresAt":"2026-08-29T12:00:00Z","requestTarget":{"platform":"android","channel":"stable","versionCode":1,"locale":"en"},"audienceHash":"${FeedVerifier.audienceHash(client.installId)}","complete":true,"items":[],"withdrawn":[]}"""

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
