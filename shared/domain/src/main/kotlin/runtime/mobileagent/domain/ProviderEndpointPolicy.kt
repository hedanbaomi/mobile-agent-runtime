// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import java.net.URI
import java.util.Locale

/** User-selected model destinations; this policy does not authorize tool or MCP URLs. */
object ProviderEndpointPolicy {
    const val INVALID_BASE_URL = "Enter a valid base URL without credentials, a query, or a fragment."
    const val INVALID_SCHEME = "The endpoint must use HTTP or HTTPS."
    const val INVALID_PORT = "The endpoint port must be between 1 and 65535."

    fun parseBaseUrl(raw: String): URI {
        val input = raw.trim().trimEnd('/')
        val uri = try { URI(input) } catch (_: Exception) {
            // URI parsing errors can contain userinfo or other private input.
            throw IllegalArgumentException(INVALID_BASE_URL)
        }
        require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            INVALID_BASE_URL
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { INVALID_SCHEME }
        require(uri.port == -1 || uri.port in 1..65535) { INVALID_PORT }
        return URI(scheme + input.substring(input.indexOf(':')))
    }
}
