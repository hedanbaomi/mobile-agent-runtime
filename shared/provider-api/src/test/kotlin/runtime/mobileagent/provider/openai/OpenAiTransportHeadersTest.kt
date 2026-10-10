// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.provider.HeaderSecretResolver
import runtime.mobileagent.provider.RequestHeaderValue

class OpenAiTransportHeadersTest {
    @Test fun requestOverridesDefaultsWithoutDuplicateHeaderNamesOrCredentialDisclosure() = runBlocking {
        val chars = "synthetic-header-credential".toCharArray()
        val headers = resolveOpenAiHeaders("https://provider.example/v1", "synthetic-bearer",
            linkedMapOf("X-Custom" to RequestHeaderValue.Plain("first"), "x-custom" to RequestHeaderValue.Plain("second")),
            mapOf("X-Custom" to RequestHeaderValue.SecretRef("reference")),
            HeaderSecretResolver { host, ref ->
                assertEquals("provider.example", host)
                assertEquals("reference", ref)
                chars
            })
        assertEquals(1, headers.values.keys.count { it.equals("x-custom", true) })
        assertEquals("synthetic-header-credential", headers.values["X-Custom"])
        assertTrue(chars.all { it == '\u0000' })
        assertFalse(headers.toString().contains("synthetic"))
    }

    @Test fun invalidSecretHeaderStillClearsTheResolvedMutableBuffer() = runBlocking {
        val chars = "synthetic\nheader".toCharArray()
        assertThrows(InvalidHeaderException::class.java) {
            runBlocking {
                resolveOpenAiHeaders("https://provider.example", "synthetic-bearer", emptyMap(),
                    mapOf("X-Custom" to RequestHeaderValue.SecretRef("reference")), HeaderSecretResolver { _, _ -> chars })
            }
        }
        assertTrue(chars.all { it == '\u0000' })
    }
}
