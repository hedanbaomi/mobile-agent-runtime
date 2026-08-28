// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.announcements

import java.security.MessageDigest

object Rollout {
    fun bucket(announcementId: String, rolloutSalt: String, installId: String): Int {
        require(TOKEN.matches(announcementId)) { "invalid announcementId" }
        require(TOKEN.matches(rolloutSalt)) { "invalid rolloutSalt" }
        require(UUID.matches(installId)) { "invalid installId" }
        val compact = """["$announcementId","$rolloutSalt","$installId"]"""
        val digest = MessageDigest.getInstance("SHA-256").digest(compact.toByteArray(Charsets.UTF_8))
        var acc = 0
        for (i in 0..7) {
            acc = (acc * 256 + (digest[i].toInt() and 0xFF)) % 100
        }
        return acc
    }

    fun hits(announcementId: String, rolloutSalt: String, installId: String, percent: Int): Boolean {
        require(percent in 0..100)
        return bucket(announcementId, rolloutSalt, installId) < percent
    }

    private val TOKEN = Regex("^[A-Za-z0-9._-]+$")
    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
}
