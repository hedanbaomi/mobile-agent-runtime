// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.provider.openai.RedactionBufferLimitExceeded
import runtime.mobileagent.provider.openai.StreamingSecretRedactor.Channel

class StreamingSecretRedactorEquivalenceTest {
    private fun streamAll(
        chunks: List<String>,
        secrets: List<String> = emptyList(),
        channel: Channel = Channel.TEXT,
    ): String {
        val redactor = StreamingSecretRedactor(secrets)
        val out = StringBuilder()
        for (chunk in chunks) {
            out.append(redactor.accept(chunk, channel))
        }
        for ((tailChannel, tail) in redactor.finish()) {
            if (tailChannel == channel) out.append(tail)
        }
        return out.toString()
    }

    private fun sliceBy(text: String, step: Int): List<String> = buildList {
        var index = 0
        while (index < text.length) {
            val next = (index + step).coerceAtMost(text.length)
            add(text.substring(index, next))
            index = next
        }
    }

    @Test
    fun splitSkKeywordAndCustomSecretsAcrossChunkBoundariesMatchFullRedactor() {
        val skFull = "Prefix sk-synthetic1234567890abcdef suffix"
        assertEquals(
            SecretRedactor.redact(skFull),
            streamAll(listOf("Prefix sk", "-synthetic1234567890abcdef suffix")),
        )
        assertEquals(
            SecretRedactor.redact(skFull),
            streamAll(listOf("Prefix s", "k", "-", "synthetic12", "34567890abcdef suffix")),
        )

        val customSecret = "synthetic-secret-ref-alpha-9999"
        val customFull = "Token=$customSecret and overlap tok-short-extended!"
        val secrets = listOf(customSecret, "tok-short", "tok-short-extended")
        assertEquals(
            SecretRedactor.redact(customFull, secrets),
            streamAll(
                listOf("Token=synthetic-secret-", "ref-alpha-9999 and overlap tok-short", "-extended!"),
                secrets = secrets,
            ),
        )
    }

    @Test
    fun splitCookieSetCookieAndJsonCookieAcrossChunkBoundariesMatchFullRedactor() {
        val headerCases = listOf(
            listOf("Header Cook", "ie: sid=synthetic_session_123; HttpOnly\r\nNext line"),
            listOf("Header Set-Cook", "ie", ": ", "auth=synthetic_cookie_456"),
            listOf("Payload {\"cook", "ie\": \"sid=synthetic_json_789\"}"),
            listOf("Payload {'set-cook", "ie': 'sid=synthetic_single_000'}"),
            listOf("Multiline Cookie:\n", "sid=synthetic_after_newline\nSafe tail"),
        )
        for (chunks in headerCases) {
            val full = chunks.joinToString("")
            val streamed = streamAll(chunks)
            assertEquals(SecretRedactor.redact(full), streamed, "Mismatch for chunks=$chunks")
            assertFalse(streamed.contains("synthetic_"), "Redacted stream leaked cookie value: $streamed")
        }
    }

    @Test
    fun splitBearerAndQuotedApiKeyAcrossChunkBoundariesMatchFullRedactor() {
        val cases = listOf(
            listOf("Authorization: Bear", "er synthetic-bearer-token-999 done"),
            listOf("Config x-a", "pi-k", "ey: \"synthetic spaced value\"", " after"),
            listOf("Config 'api_key", "' = 'synthetic single quoted'", "; next"),
        )
        for (chunks in cases) {
            val full = chunks.joinToString("")
            assertEquals(SecretRedactor.redact(full), streamAll(chunks))
        }
    }

    @Test
    fun arbitraryFixedSizeChunkingsMatchFullRedactorAndPreserveSafeText() {
        val secrets = listOf("synthetic-custom-secret-ref-2026", "synthetic-spaced secret")
        val document = buildString {
            append("Normal prose: sky is blue, Cooking is fun, api-key discussion, \"quoted text\", Do not stop.\n")
            append("OpenAI: sk-synthetic9876543210abcdef.\n")
            append("HTTP: Cookie: sid=synthetic_cookie_val; Path=/\r\n")
            append("Set-Cookie: token=synthetic_set_cookie\n")
            append("JSON: {\"cookie\": \"synthetic_json_cookie\", \"ok\": true}\n")
            append("Auth: Authorization: Bearer synthetic_bearer_xyz and x-api-key=\"synthetic quoted key\".\n")
            append("Custom: synthetic-custom-secret-ref-2026 and synthetic-spaced secret end")
        }
        val expected = SecretRedactor.redact(document, secrets)
        for (step in listOf(1, 2, 3, 5, 7, 13)) {
            assertEquals(expected, streamAll(sliceBy(document, step), secrets), "Failed for step=$step")
        }
    }

    @Test
    fun discardDropsUnconfirmedSplitKeywordAndCustomSecretPrefixes() {
        val customSecret = "synthetic-secret-ref-discard-8888"
        val cases = listOf(
            "Confirmed: sk" to "Confirmed: ",
            "Confirmed: Cook" to "Confirmed: ",
            "Confirmed: Set-Cook" to "Confirmed: ",
            "Confirmed: {\"cook" to "Confirmed: {",
            "Confirmed: synthetic-secret-ref-" to "Confirmed: ",
        )
        for ((input, expectedEmitted) in cases) {
            val redactor = StreamingSecretRedactor(listOf(customSecret))
            val emitted = redactor.accept(input, Channel.TEXT)
            assertTrue(redactor.hasPending(), "Expected withheld suffix for '$input'")
            redactor.discard()
            assertFalse(redactor.hasPending())
            assertEquals(0, redactor.pendingLength())
            assertTrue(redactor.finish().isEmpty())
            assertEquals(expectedEmitted, emitted)
        }
    }

    @Test
    fun unclosedProseQuotesAndNonCookieJsonFieldsNeverStallOrGrowPendingTail() {
        val prose = buildString {
            append("She said \"Hello world, this unclosed quote continues across many chunks ")
            append("and 'single quoted prose' plus {\"username\": \"alice_unclosed_field ")
            append("and api_key_note = \"not a header key ")
            while (length < 16_384) {
                append("ordinary streaming prose segment without closing quote; ")
            }
        }.substring(0, 16_384)
        val chunks = sliceBy(prose, 16)
        val redactor = StreamingSecretRedactor()
        val emitted = StringBuilder()
        for (chunk in chunks) {
            val part = redactor.accept(chunk, Channel.TEXT)
            emitted.append(part)
            assertTrue(
                redactor.pendingLength(Channel.TEXT) <= 16,
                "Prose with unclosed quotes must not accumulate pending tail: ${redactor.pendingLength(Channel.TEXT)}",
            )
        }
        for ((ch, tail) in redactor.finish()) {
            if (ch == Channel.TEXT) emitted.append(tail)
        }
        assertEquals(SecretRedactor.redact(prose), emitted.toString())
        assertEquals(prose, emitted.toString())
    }

    @Test
    fun longOpenSingleLinePatternsBoundPendingTailAndMatchFullRedactor() {
        val customSecret = "synthetic-extra-secret-token-2026"
        val openBodies = listOf(
            "Prefix line\nCookie: sid=" + "a".repeat(16_384),
            "Prefix line\nSet-Cookie: auth=" + "b".repeat(16_384),
            "Prefix line\nAuthorization: Bearer " + "c".repeat(16_384),
            "Prefix line\nx-api-key=" + "d".repeat(16_384),
            "Prefix line\nsk-" + "e".repeat(16_384),
        )
        for (full in openBodies) {
            val chunks = sliceBy(full, 16)
            val redactor = StreamingSecretRedactor(listOf(customSecret))
            val out = StringBuilder()
            var maxPending = 0
            for (chunk in chunks) {
                out.append(redactor.accept(chunk, Channel.TEXT))
                maxPending = maxOf(maxPending, redactor.pendingLength(Channel.TEXT))
            }
            assertTrue(
                maxPending <= 160,
                "Expected bounded O(1) pending tail for open pattern, got maxPending=$maxPending",
            )
            for ((ch, tail) in redactor.finish()) {
                if (ch == Channel.TEXT) out.append(tail)
            }
            assertEquals(SecretRedactor.redact(full, listOf(customSecret)), out.toString())

            val aborted = StreamingSecretRedactor(listOf(customSecret))
            val abortedOut = StringBuilder()
            for (chunk in chunks) {
                abortedOut.append(aborted.accept(chunk, Channel.TEXT))
            }
            aborted.discard()
            assertEquals("Prefix line\n".let { if ("Bearer" in full) "Prefix line\nAuthorization: " else it }, abortedOut.toString())
            assertEquals(0, aborted.pendingLength())
        }
    }

    @Test
    fun ambiguousQuotedTailsHaveAHardBoundForSingleAndFragmentedInput() {
        val head = "api_key: \""
        val atLimit = head + "z".repeat(32 * 1024 - head.length)
        for (chunks in listOf(listOf(atLimit), atLimit.chunked(113))) {
            val redactor = StreamingSecretRedactor()
            for (part in chunks) assertEquals("", redactor.accept(part))
            assertEquals(32 * 1024, redactor.pendingLength())
            assertEquals(SecretRedactor.redact(atLimit + "\"."), redactor.accept("\"."))
        }
        for (chunks in listOf(listOf(atLimit + "x"), (atLimit + "x").chunked(113))) {
            val redactor = StreamingSecretRedactor()
            redactor.accept("cook", Channel.REASONING)
            val error = assertThrows(RedactionBufferLimitExceeded::class.java) {
                chunks.forEach { redactor.accept(it) }
            }
            assertEquals("Provider redaction buffer exceeds limit", error.message)
            assertEquals(0, redactor.pendingLength())
            assertTrue(redactor.finish().isEmpty())
        }
        val prose = "normal sentence. ".repeat(8_000)
        assertEquals(prose, streamAll(listOf(prose)))
        val acrossBoundary = "x".repeat(32_760) + " api_key: \"sample-value\"." + prose
        assertEquals(SecretRedactor.redact(acrossBoundary), streamAll(listOf(acrossBoundary)))
        assertEquals(SecretRedactor.redact(acrossBoundary), streamAll(acrossBoundary.chunked(113)))
    }

    @Test
    fun unclosedQuotedCredentialCandidatesPreserveExactFinishAndDiscardSemantics() {
        val unclosedApiKey = "Intro text. api_key: \"" + "spaced_candidate_segment ".repeat(660)
        val closedApiKey = "$unclosedApiKey\" trailing safe"
        val newlineBrokenApiKey = "$unclosedApiKey\ntrailing safe"
        val unclosedJsonCookie = "Payload {\"cookie\": \"" + "cookie_fragment_".repeat(1_024)
        val closedJsonCookie = "$unclosedJsonCookie\"}"

        for (doc in listOf(unclosedApiKey, closedApiKey, newlineBrokenApiKey, unclosedJsonCookie, closedJsonCookie)) {
            assertEquals(SecretRedactor.redact(doc), streamAll(sliceBy(doc, 16)))
        }

        val discardRedactor = StreamingSecretRedactor()
        val visibleBeforeCancel = StringBuilder()
        for (chunk in sliceBy(unclosedJsonCookie, 16)) {
            visibleBeforeCancel.append(discardRedactor.accept(chunk, Channel.TEXT))
        }
        discardRedactor.discard()
        assertEquals("Payload {", visibleBeforeCancel.toString())
        assertFalse(visibleBeforeCancel.toString().contains("cookie_fragment_"))
    }
}
