// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.announcements

import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

sealed class FeedVerifyResult {
    data class Accepted(val payload: FeedPayload, val items: List<AnnouncementItem>) : FeedVerifyResult()
    data class Rejected(val reason: String) : FeedVerifyResult()
}

object FeedVerifier {
    private val json = Json { ignoreUnknownKeys = false }

    fun audienceHash(installId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(installId.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    fun verify(
        envelopeJson: String,
        publicKeys: Map<String, ByteArray>,
        client: ClientContext,
        now: Instant,
        previousFeedVersion: Long? = null,
    ): FeedVerifyResult {
        if (envelopeJson.length > FeedLimits.MAX_ENVELOPE_CHARS) return FeedVerifyResult.Rejected("envelope too large")
        val envelope = runCatching { json.decodeFromString<SignedEnvelope>(envelopeJson) }
            .getOrElse { return FeedVerifyResult.Rejected("invalid envelope") }
        if (envelope.schemaVersion != 1) return FeedVerifyResult.Rejected("unknown schema")
        val publicKey = publicKeys[envelope.keyId] ?: return FeedVerifyResult.Rejected("unknown key")
        val payloadBytes = runCatching { Base64.getDecoder().decode(envelope.payloadBase64) }
            .getOrElse { return FeedVerifyResult.Rejected("invalid payload") }
        if (payloadBytes.size > FeedLimits.MAX_PAYLOAD_BYTES) return FeedVerifyResult.Rejected("payload too large")
        val verified = runCatching {
            AnnouncementSignature.verify(envelope.payloadBase64, envelope.signatureBase64, publicKey)
        }.getOrDefault(false)
        if (!verified) return FeedVerifyResult.Rejected("bad signature")
        val payload = runCatching {
            json.decodeFromString<FeedPayload>(payloadBytes.toString(Charsets.UTF_8))
        }.getOrElse { return FeedVerifyResult.Rejected("invalid payload schema") }
        if (!payload.complete) return FeedVerifyResult.Rejected("incomplete snapshot")
        if (payload.items.size > FeedLimits.MAX_ITEMS) return FeedVerifyResult.Rejected("too many items")
        if (payload.audienceHash != audienceHash(client.installId)) return FeedVerifyResult.Rejected("audience mismatch")
        if (payload.requestTarget.platform != client.platform) return FeedVerifyResult.Rejected("platform mismatch")
        if (payload.requestTarget.channel != client.channel) return FeedVerifyResult.Rejected("channel mismatch")
        if (payload.requestTarget.versionCode != client.versionCode) return FeedVerifyResult.Rejected("version mismatch")
        if (payload.requestTarget.locale != client.locale) return FeedVerifyResult.Rejected("locale mismatch")
        val issued = runCatching { Instant.parse(payload.issuedAt) }.getOrElse { return FeedVerifyResult.Rejected("bad issuedAt") }
        val expires = runCatching { Instant.parse(payload.expiresAt) }.getOrElse { return FeedVerifyResult.Rejected("bad expiresAt") }
        if (now.isAfter(expires)) return FeedVerifyResult.Rejected("expired")
        if (now.isBefore(issued.minusSeconds(300))) return FeedVerifyResult.Rejected("not yet valid")
        if (previousFeedVersion != null && payload.feedVersion < previousFeedVersion) {
            return FeedVerifyResult.Rejected("feed version went backwards")
        }
        val items = payload.items.mapNotNull { item -> sanitize(item, client) }
        return FeedVerifyResult.Accepted(payload, items)
    }

    fun sanitize(item: AnnouncementItem, client: ClientContext): AnnouncementItem? {
        if (!AnnouncementContentGuard.allowedMarkdown(item.bodyMarkdown)) return null
        if (!AnnouncementContentGuard.allowedImage(item.image)) return null
        if (item.actions.size > FeedLimits.MAX_ACTIONS) return null
        if (item.actions.any { !AnnouncementActions.allowed(it) }) return null
        if (item.mustAcknowledge && (item.severity == Severity.INFO || item.displayMode != DisplayMode.MODAL)) return null
        if (!Targeting.matches(item.target.toTarget(), client, item.id)) return null
        return item
    }
}
