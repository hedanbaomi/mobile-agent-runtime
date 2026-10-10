// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecretRedactorTest {
    @Test
    fun redactsBearerAndSkTokens() {
        val out = SecretRedactor.redact(
            "Authorization: Bearer tokensecret sk-abcdefghijklmn leftover",
            listOf("leftover"),
        )
        assertTrue(out.contains("***"))
        assertFalse(out.contains("tokensecret"))
        assertFalse(out.contains("sk-abcdefghijklmn"))
        assertFalse(out.contains("leftover"))
    }

    @Test
    fun extraSecretIsRequiredForArbitraryTokens() {
        val token = "synthetic-provider-token-12345"
        val raw = SecretRedactor.redact("Invalid credential: $token")
        assertTrue(raw.contains(token))
        val redacted = SecretRedactor.redact("Invalid credential: $token", listOf(token))
        assertFalse(redacted.contains(token))
    }

    @Test
    fun modernProviderKeysAndCredentialHeadersAreFullyRedacted() {
        val keys = listOf("sk-proj-example_credential-with-hyphens", "sk-ant-api03-example_credential-more")
        val input = keys.joinToString(" ") +
            "\nx-api-key: synthetic-header-value" +
            "\nCookie: session=synthetic-session; auth=synthetic-auth" +
            "\n{\"cookie\":\"synthetic-json-cookie\",\"x-api-key\":\"synthetic-json-key\"}"
        val out = SecretRedactor.redact(input)
        keys.forEach { assertFalse(out.contains(it)) }
        listOf("synthetic-header-value", "synthetic-session", "synthetic-auth", "synthetic-json-cookie", "synthetic-json-key")
            .forEach { assertFalse(out.contains(it), out) }
    }

    @Test
    fun overlappingRuntimeSecretsAreRemovedLongestFirst() {
        assertFalse(SecretRedactor.redact("synthetic-long-credential", listOf("synthetic", "synthetic-long-credential"))
            .contains("long-credential"))
    }
}
