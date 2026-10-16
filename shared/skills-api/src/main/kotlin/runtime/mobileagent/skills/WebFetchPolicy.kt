// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import java.net.URI

/** Validate a remote page identifier without resolving or contacting the page from the device. */
object WebFetchPolicy {
    const val MAX_URL_CHARS = 8192

    fun isAllowedUrl(url: String): Boolean {
        if (url.isEmpty() || url.length > MAX_URL_CHARS || url != url.trim() || url.any(Char::isISOControl)) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val defaultPort = when (uri.scheme?.lowercase()) {
            "https" -> 443
            // This is a remote page identifier; the credential-bearing API connection stays HTTPS.
            "http" -> 80
            else -> return false
        }
        if (uri.rawUserInfo != null || uri.rawFragment != null || (uri.port != -1 && uri.port != defaultPort)) return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        if (!host.contains('.') || HttpPolicy.isIpLiteral(host) || HttpPolicy.isForbiddenHost(host)) return false
        if (listOf(".localhost", ".internal", ".lan", ".home").any(host::endsWith)) return false
        return true
    }
}
