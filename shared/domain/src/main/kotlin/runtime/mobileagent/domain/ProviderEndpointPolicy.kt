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

    /** Port text after a bracketed IPv6 literal or a plain host. */
    private val PORT_SUFFIX = Regex("""(?:\[[^\]]*]|[^:\[\]]*):(.*)""")

    fun parseBaseUrl(raw: String): URI {
        val input = raw.trim().trimEnd('/')
        val uri = try { URI(input) } catch (_: Exception) {
            // URI parsing errors can contain userinfo or other private input.
            throw IllegalArgumentException(INVALID_BASE_URL)
        }
        // An out-of-range port makes java.net.URI drop the host, so name the port
        // before the generic check would report the whole URL as invalid.
        val portText = uri.rawAuthority?.substringAfterLast('@')?.let { PORT_SUFFIX.matchEntire(it)?.groupValues?.get(1) }
        require(portText.isNullOrEmpty() || portText.toIntOrNull() in 1..65535) { INVALID_PORT }
        require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            INVALID_BASE_URL
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { INVALID_SCHEME }
        require(uri.port == -1 || uri.port in 1..65535) { INVALID_PORT }
        return URI(scheme + input.substring(input.indexOf(':')))
    }
}
