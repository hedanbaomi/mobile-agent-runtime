// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder

/**
 * A streamed chat reply that is still arriving must be received in full, however long it
 * takes.  Lift only the client's total-request ceiling for this call; the connect timeout and
 * the socket inactivity timeout stay in force, so a stalled connection still fails.  Clients
 * without [HttpTimeout] (tests, custom engines) are left untouched.
 */
internal fun HttpRequestBuilder.receiveStreamedReplyInFull(http: HttpClient) {
    if (http.pluginOrNull(HttpTimeout) == null) return
    timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
}
