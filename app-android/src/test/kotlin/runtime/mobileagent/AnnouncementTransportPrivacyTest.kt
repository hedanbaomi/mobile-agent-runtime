// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.announcements.ClientContext

class AnnouncementTransportPrivacyTest {
    @Test
    fun actualPublicTransportDoesNotSendProviderCredentialsOrEventsWithoutConsent() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        HttpClient(MockEngine) {
            configurePublicAnnouncementTransport()
            engine { addHandler { request -> requests += request; respond("", HttpStatusCode.NoContent) } }
        }.use { http ->
            val fetcher = AnnouncementFetcher(http)
            assertFalse(fetcher.postEvents("https://notice.invalid", false, "{}"))
            assertTrue(requests.isEmpty())
            fetcher.fetch("https://notice.invalid", ClientContext("android", "stable", 15, "en-US", "fixture-install"), "fixture-etag")
            assertEquals("fixture-install", requests.single().headers["X-Install-ID"])
            assertEquals("fixture-etag", requests.single().headers[HttpHeaders.IfNoneMatch])
            assertTrue(fetcher.postEvents("https://notice.invalid", true, "{}"))
            assertEquals("1", requests.last().headers["X-Stats-Consent"])
            for (request in requests) for (header in listOf(HttpHeaders.Authorization, HttpHeaders.Cookie, "X-Api-Key", "api-key")) {
                assertNull(request.headers[header], header)
            }
        }
    }
}
