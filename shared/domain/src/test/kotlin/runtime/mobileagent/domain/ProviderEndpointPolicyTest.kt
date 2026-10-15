// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProviderEndpointPolicyTest {
    @Test fun acceptsProxyHostsPortsAndPrefixesInBothSchemes() {
        listOf(
            "http://proxy.example.invalid:8080/gateway/openai/v1",
            "http://192.168.1.20:3000/v1",
            "http://127.0.0.1:8765/v1",
            "http://[::1]:8080/v1",
            "https://proxy.example.invalid:8443/prefix/v1",
        ).forEach { assertEquals(it, ProviderEndpointPolicy.parseBaseUrl(it).toASCIIString()) }
        assertEquals("http://proxy.example.invalid:8080/v1", ProviderEndpointPolicy.parseBaseUrl(" HTTP://proxy.example.invalid:8080/v1/ ").toASCIIString())
    }

    @Test fun rejectsCredentialsAmbiguousDestinationsAndInvalidPorts() {
        listOf(
            "http://user:private-marker@proxy.example.invalid/v1",
            "http://proxy.example.invalid/v1?key=private-marker",
            "http://proxy.example.invalid/v1#private-marker",
            "http://proxy.example.invalid:0/v1",
            "http://proxy.example.invalid:65536/v1",
            "http://proxy.example.invalid:abc/v1",
            "http:///v1", "//proxy.example.invalid/v1", "file:///tmp/v1",
            "ftp://proxy.example.invalid/v1", "http://bad host/private-marker",
        ).forEach { raw ->
            val failure = assertThrows(IllegalArgumentException::class.java) { ProviderEndpointPolicy.parseBaseUrl(raw) }
            assertFalse(failure.message.orEmpty().contains("private-marker"))
        }
    }

    @Test fun outOfRangeAndNonNumericPortsAreReportedAsPortErrors() {
        listOf(
            "http://proxy.example.invalid:0/v1",
            "http://proxy.example.invalid:65536/v1",
            "http://proxy.example.invalid:99999999999/v1",
            "http://proxy.example.invalid:abc/v1",
            "http://[::1]:70000/v1",
        ).forEach { raw ->
            val failure = assertThrows(IllegalArgumentException::class.java) { ProviderEndpointPolicy.parseBaseUrl(raw) }
            assertEquals(ProviderEndpointPolicy.INVALID_PORT, failure.message, raw)
        }
        assertEquals("http://[::1]:65535/v1", ProviderEndpointPolicy.parseBaseUrl("http://[::1]:65535/v1").toASCIIString())
    }

    @Test fun schemePortAndPathChangesDetachAuxiliaryCredentials() {
        val old = ProviderProfile("proxy", "Proxy", ApiFormat.OPENAI_COMPATIBLE,
            "https://proxy.example.invalid/v1", mapOf("X-Token" to "secret-ref"), secretRef = "primary-ref", revision = 1)
        listOf("http://proxy.example.invalid/v1", "https://proxy.example.invalid:8443/v1", "https://proxy.example.invalid/other/v1")
            .forEach { assertTrue(ProviderDestinationBinding.headerSecretRefsForSave(old, it, old.headerSecretRefs).isEmpty()) }
        assertEquals(old.headerSecretRefs, ProviderDestinationBinding.headerSecretRefsForSave(old, old.baseUrl))
    }
}
