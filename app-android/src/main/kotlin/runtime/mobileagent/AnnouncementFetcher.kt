// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import runtime.mobileagent.announcements.ClientContext
import java.util.UUID

import java.net.URLEncoder

/** Dedicated public transport: no Provider defaults, cookies or redirects. */
internal fun HttpClientConfig<*>.configurePublicAnnouncementTransport() {
    followRedirects = false
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 30_000
    }
}

class AnnouncementFetcher(private val http: HttpClient) : AnnouncementFetchPort {
    override suspend fun fetch(baseUrl: String, client: ClientContext, etag: String?): FetchOutcome {
        val url = baseUrl.trimEnd('/') +
            "/api/v1/announcements?platform=${enc(client.platform)}&channel=${enc(client.channel)}" +
            "&versionCode=${client.versionCode}&locale=${enc(client.locale)}"
        val response = http.request(url) {
            method = HttpMethod.Get
            header("X-Install-ID", client.installId)
            if (!etag.isNullOrBlank()) header(HttpHeaders.IfNoneMatch, etag)
        }
        return when (response.status.value) {
            200 -> FetchOutcome.Body(response.bodyAsText(), response.headers[HttpHeaders.ETag].orEmpty())
            304 -> FetchOutcome.NotModified
            else -> FetchOutcome.Failed("HTTP ${response.status.value}")
        }
    }

    /** Upload only the coordinator's already-whitelisted event envelope. */
    override suspend fun postEvents(baseUrl: String, consent: Boolean, eventsJson: String): Boolean {
        if (!consent) return false
        val response = http.request(baseUrl.trimEnd('/') + "/api/v1/events") {
            method = HttpMethod.Post
            header("X-Stats-Consent", "1")
            contentType(ContentType.Application.Json)
            setBody(eventsJson)
        }
        return response.status.value in 200..299
    }

    fun newEventId(): String = UUID.randomUUID().toString()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

sealed class FetchOutcome {
    data class Body(val envelopeJson: String, val etag: String) : FetchOutcome()
    data object NotModified : FetchOutcome()
    data class Failed(val message: String) : FetchOutcome()
}
