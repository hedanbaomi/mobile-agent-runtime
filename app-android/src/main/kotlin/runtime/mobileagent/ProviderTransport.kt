// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import runtime.mobileagent.provider.ProviderHttpResponseException

/** Shared production transport for Chat, Vision, Embedding and provider probes. */
internal fun createProviderHttpClient(timeoutMillis: Long = 180_000): HttpClient = HttpClient(OkHttp) {
    followRedirects = false
    engine {
        config {
            // A proxy redirect must never move credentials/content to another destination.
            followRedirects(false)
            followSslRedirects(false)
            retryOnConnectionFailure(false)
            addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 503) {
                    response.close()
                    throw ProviderHttpResponseException(503)
                }
                response
            }
        }
    }
    install(HttpTimeout) {
        requestTimeoutMillis = timeoutMillis
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = timeoutMillis
    }
}
