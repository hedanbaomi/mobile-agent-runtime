// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UnifiedTextPatchTest {
    @Test
    fun markedBlankContextAndGitHeadersUseTheSameParserInBothEngines() {
        val patch = "diff --git a/note b/note\nindex 111..222 100644\n--- a/note\n+++ b/note\n@@ -1,3 +1,3 @@\n first\n \n-last\n+changed\n"
        val source = "first\n\nlast\n"
        assertEquals("first\n\nchanged\n", UnifiedTextPatch.apply(source, patch))
        assertEquals("first\n\nchanged\n", stream(source, patch, "unified").second)
    }

    @Test
    fun insertionAfterAnExistingLineUsesZeroCountCoordinates() {
        assertEquals("a\nx\nb\n", UnifiedTextPatch.apply("a\nb\n", "@@ -1,0 +2,1 @@\n+x\n"))
    }

    @Test
    fun malformedCountsOverlappingHunksAndUnmarkedBlankRecordsReject() {
        for (patch in listOf("@@ -1,2 +1,1 @@\n-a\n+b\n", "@@ -1 +1 @@\n-a\n+b\n@@ -1 +1 @@\n-a\n+c\n", "@@ -1,2 +1,2 @@\n a\n\n+b\n")) {
            assertNull(UnifiedTextPatch.apply("a\nb\n", patch), patch)
            assertTrue(stream("a\nb\n", patch, "unified").first is RootStreamingPatchResult.Rejected, patch)
        }
    }

    @Test
    fun searchReplaceStreamingPreservesUniquenessAndUtf8AcrossChunkBoundary() {
        val prefix = "x".repeat(RootStreamingPatch.CHUNK_BYTES - 1)
        val source = prefix + "你好\n"
        val outcome = stream(source, """{"search":"你好","replace":"世界"}""", "search_replace")
        assertTrue(outcome.first is RootStreamingPatchResult.Succeeded)
        assertEquals(prefix + "世界\n", outcome.second)
        assertTrue(stream("aa aa", """{"search":"aa","replace":"b"}""", "search_replace").first is RootStreamingPatchResult.Rejected)
    }

    @Test
    fun searchReplaceRejectsNulSourceEvenAfterAnEarlierMatchOrChunk() {
        for (source in listOf("before\u0000after", "before" + "x".repeat(RootStreamingPatch.CHUNK_BYTES) + "\u0000")) {
            val outcome = stream(source, """{"search":"before","replace":"changed"}""", "search_replace")
            assertEquals(RootStreamingPatchResult.Rejected(RootFileOperationReason.PATCH_INVALID), outcome.first)
        }
    }

    @Test
    fun searchReplaceRemainsExactBytesWhileUnifiedDiffProvidesLineEndingEquivalence() {
        val source = "first\r\nsecond\r\n"
        val differentEndings = stream(source, """{"search":"first\nsecond","replace":"changed"}""", "search_replace")
        assertEquals(RootStreamingPatchResult.Rejected(RootFileOperationReason.PATCH_CONTEXT_MISMATCH), differentEndings.first)
        val exact = stream(source, """{"search":"first\r\nsecond","replace":"changed"}""", "search_replace")
        assertEquals("changed\r\n", exact.second)
        assertTrue(exact.first is RootStreamingPatchResult.Succeeded)
    }

    @Test
    fun crlfSourceMatchesLfPatchAndKeepsItsOwnLineEndings() {
        val patch = "@@ -1,3 +1,3 @@\n first\n \n-last\n+changed\n"
        assertEnginesAgree("first\r\n\r\nlast\r\n", patch, "first\r\n\r\nchanged\r\n")
    }

    @Test
    fun crlfPatchMatchesCrlfSource() {
        val patch = "--- a/note\r\n+++ b/note\r\n@@ -1,1 +1,1 @@\r\n-first\r\n+FIRST\r\n"
        assertEnginesAgree("first\r\nsecond\r\n", patch, "FIRST\r\nsecond\r\n")
    }

    @Test
    fun mixedLineEndingsArePreservedPerLineAndAddedLinesFollowTheirNeighbour() {
        assertEnginesAgree("a\r\nb\nc\r\n", "@@ -2,1 +2,1 @@\n-b\n+B\n", "a\r\nB\nc\r\n")
    }

    @Test
    fun insertedLinesInheritTheNeighbourSourceTerminator() {
        assertEnginesAgree("a\r\nb\r\n", "@@ -0,0 +1,1 @@\n+x\n", "x\r\na\r\nb\r\n")
        assertEnginesAgree("a\r\nb\r\n", "@@ -2,0 +3,1 @@\n+x\n", "a\r\nb\r\nx\r\n")
        assertEnginesAgree("a\nb\n", "@@ -1,0 +2,1 @@\n+x\n", "a\nx\nb\n")
    }

    @Test
    fun emptyFileFollowsTheStandardTrailingNewlineConvention() {
        val added = "@@ -0,0 +1,1 @@\n+hello\n"
        assertEnginesAgree("", added, "hello\n")
        val marked = "@@ -0,0 +1,1 @@\n+hello\n\\ No newline at end of file\n"
        assertEnginesAgree("", marked, "hello")
    }

    @Test
    fun emptySourceHasNoPhantomLineSoLaterAnchorsReject() {
        for (patch in listOf(
            "@@ -1,0 +2,1 @@\n+x\n",
            "@@ -2,0 +3,1 @@\n+x\n",
            "@@ -2,1 +2,1 @@\n-a\n+b\n",
        )) {
            assertRejectedByBoth("", patch)
        }
    }

    @Test
    fun insertionPastTheLastLineOfATerminatedFileRejects() {
        for (patch in listOf(
            "@@ -3,0 +4,1 @@\n+x\n",
            "@@ -3,1 +3,1 @@\n-a\n+b\n",
        )) {
            assertRejectedByBoth("a\nb\n", patch)
        }
        assertEnginesAgree("a\nb\n", "@@ -2,0 +3,1 @@\n+x\n", "a\nb\nx\n")
    }

    @Test
    fun deletingTheLastOrMiddleLineAdjustsTheTail() {
        assertEnginesAgree("a\nb\n", "@@ -2,1 +1,0 @@\n-b\n", "a\n")
        assertEnginesAgree("a\r\nb\r\n", "@@ -2,1 +1,0 @@\n-b\n", "a\r\n")
        assertEnginesAgree("a\nb\nc\n", "@@ -2,1 +1,0 @@\n-b\n", "a\nc\n")
        assertEnginesAgree("a\nb", "@@ -2,1 +1,0 @@\n-b\n\\ No newline at end of file\n", "a\n")
        assertEnginesAgree("a\n", "@@ -1,1 +0,0 @@\n-a\n", "")
        assertEnginesAgree("a", "@@ -1,1 +0,0 @@\n-a\n\\ No newline at end of file\n", "")
    }

    @Test
    fun unterminatedLastLineIsPreservedOrMarkedExplicitly() {
        assertEnginesAgree("a\nb", "@@ -1,1 +1,1 @@\n-a\n+A\n", "A\nb")
        assertEnginesAgree("a\nb", "@@ -2,1 +2,1 @@\n-b\n+B\n", "a\nB\n")
        assertEnginesAgree("a\nb", "@@ -2,1 +2,1 @@\n-b\n+B\n\\ No newline at end of file\n", "a\nB")
        assertEnginesAgree(
            "a\nb",
            "@@ -2,1 +2,1 @@\n-b\n\\ No newline at end of file\n+B\n\\ No newline at end of file\n",
            "a\nB",
        )
        assertEnginesAgree(
            "a",
            "@@ -1,1 +1,1 @@\n-a\n\\ No newline at end of file\n+B\n\\ No newline at end of file\n",
            "B",
        )
        assertEnginesAgree("a\r\nb", "@@ -2,1 +2,1 @@\n-b\n\\ No newline at end of file\n+B\n\\ No newline at end of file\n", "a\r\nB")
    }

    @Test
    fun appendingAfterAnUnterminatedLineWithoutRewritingItRejects() {
        assertRejectedByBoth("a\nb", "@@ -2,0 +3,1 @@\n+c\n")
    }

    @Test
    fun noNewlineMarkerIsValidatedAgainstTheRealLastLine() {
        assertRejectedByBoth("a\nb\n", "@@ -1,1 +1,1 @@\n-a\n\\ No newline at end of file\n+A\n")
        assertRejectedByBoth("a\n", "@@ -1,1 +1,2 @@\n-a\n+b\n\\ No newline at end of file\n+c\n")
        assertRejectedByBoth("a\n", "@@ -0,0 +1,1 @@\n\\ No newline at end of file\n")
    }

    @Test
    fun multipleHunksAccumulateOffsetsAcrossLines() {
        val patch = "@@ -1,2 +1,1 @@\n-one\n-two\n+ONE\n@@ -5,2 +4,2 @@\n five\n-six\n+SIX\n"
        assertEnginesAgree("one\ntwo\nthree\nfour\nfive\nsix\n", patch, "ONE\nthree\nfour\nfive\nSIX\n")
        assertEnginesAgree(
            "one\r\ntwo\r\nthree\r\nfour\r\nfive\r\nsix\r\n",
            patch,
            "ONE\r\nthree\r\nfour\r\nfive\r\nSIX\r\n",
        )
        val insertion = "@@ -1,1 +1,1 @@\n-a\n+A\n@@ -2,0 +3,1 @@\n+x\n"
        assertEnginesAgree("a\nb\nc\n", insertion, "A\nb\nx\nc\n")
    }

    @Test
    fun interiorCarriageReturnIsDataAndIsNeverTrimmed() {
        assertEnginesAgree("a\rb\n", "@@ -1,1 +1,1 @@\n-a\rb\n+a\rB\n", "a\rB\n")
    }

    @Test
    fun patchRecordTrailingCarriageReturnIsContentUnlessThePatchItselfUsesCrlf() {
        // LF patch: the CR in "-a\r" is the source's lone CR at EOF, not a record terminator.
        assertEnginesAgree("a\r", "@@ -1 +1 @@\n-a\r\n\\ No newline at end of file\n+b\n", "b\n")
        // LF patch against a CRLF file whose first line content ends with CR.
        assertEnginesAgree("a\r\r\nb\r\n", "@@ -1,1 +1,1 @@\n-a\r\n+X\n", "X\r\nb\r\n")
        // CRLF patch: one CR is the record terminator, the extra CR stays source content.
        assertEnginesAgree("a\r", "@@ -1 +1 @@\r\n-a\r\r\n\\ No newline at end of file\r\n+b\r\n", "b\n")
    }

    @Test
    fun difflibStylePatchesWithCrlfBodyRecordsApplyInBothEngines() {
        // Python difflib.unified_diff over CRLF lines: LF headers, body records keep each line's CR.
        assertEnginesAgree(
            "a\r\nb\r\n",
            "--- a\n+++ b\n@@ -1,2 +1,2 @@\n a\r\n-b\r\n+c\r\n",
            "a\r\nc\r\n",
        )
        // difflib keeps surrounding context instead of emitting a `@@ -0,0` pure-insertion hunk.
        assertEnginesAgree(
            "a\r\nb\r\n",
            "--- a\n+++ b\n@@ -1,2 +1,3 @@\n+x\r\n a\r\n b\r\n",
            "x\r\na\r\nb\r\n",
        )
        // Insertion between CRLF lines must not double the echoed CR.
        assertEnginesAgree(
            "a\r\nb\r\n",
            "--- a\n+++ b\n@@ -1,2 +1,3 @@\n a\r\n+x\r\n b\r\n",
            "a\r\nx\r\nb\r\n",
        )
        // Deletion keeps the surviving CRLF context untouched.
        assertEnginesAgree(
            "a\r\nb\r\n",
            "--- a\n+++ b\n@@ -1,2 +1 @@\n a\r\n-b\r\n",
            "a\r\n",
        )
        // Hand-written variant: the context content itself ends with CR.
        assertEnginesAgree(
            "a\r\r\nb\r\n",
            "--- a\n+++ b\n@@ -1,2 +1,2 @@\n a\r\r\n-b\r\n+c\r\n",
            "a\r\r\nc\r\n",
        )
        // Hand-written pure-insertion anchor at the start, with a CR-bearing added record.
        assertEnginesAgree(
            "a\r\nb\r\n",
            "--- a\n+++ b\n@@ -0,0 +1,1 @@\n+x\r\n",
            "x\r\na\r\nb\r\n",
        )
        // The same generator over LF lines stays LF.
        assertEnginesAgree("a\nb\n", "--- a\n+++ b\n@@ -1,2 +1,2 @@\n a\n-b\n+c\n", "a\nc\n")
    }

    @Test
    fun ambiguousPatchLineEndingsRejectInsteadOfGuessingContent() {
        assertRejectedByBoth("a\nb\n", "@@ -1 +1 @@\r\n-a\r\n+A\n@@ -2 +2 @@\n-b\n+B\n")
    }

    @Test
    fun hunkBodyRecordsNeverVoteOnPatchLineEndings() {
        // An added line "++ foo\r" renders as a "+++ ..." record ending in CR, but it is body
        // content; the patch is still an LF patch.
        assertEnginesAgree("a\n", "@@ -1,1 +1,2 @@\n-a\n+++ foo\r\n+A\n", "++ foo\r\nA\n")
        // The same content inside a CRLF patch: one CR is the record terminator and the echoed CR
        // is not doubled, so both patch flavours describe the same bytes.
        assertEnginesAgree("a\r\n", "@@ -1,1 +1,2 @@\r\n-a\r\n+++ foo\r\r\n+A\r\n", "++ foo\r\nA\r\n")
    }

    @Test
    fun nulInSourceIsRejectedByBothEnginesEvenOnUntouchedLines() {
        assertRejectedByBoth("a\u0000\nb\n", "@@ -2,1 +2,1 @@\n-b\n+B\n")
        assertRejectedByBoth("a\nb\u0000\n", "@@ -1,1 +1,1 @@\n-a\n+A\n")
        assertRejectedByBoth("a\nx\u0000\ny\n", "@@ -1,1 +1,1 @@\n-a\n+A\n")
        assertRejectedByBoth("a\u0000", "@@ -1,0 +2,1 @@\n+x\n")
    }

    @Test
    fun streamingUnifiedHandlesChunkBoundariesAndLongLines() {
        val boundaryLine = "y".repeat(RootStreamingPatch.CHUNK_BYTES - 1)
        assertEnginesAgree(
            boundaryLine + "\r\nsecond\r\n",
            "@@ -2,1 +2,1 @@\n-second\n+SECOND\n",
            boundaryLine + "\r\nSECOND\r\n",
        )
        val longLine = "x".repeat(RootStreamingPatch.CHUNK_BYTES + 17)
        assertEnginesAgree(
            longLine + "\r\nsecond\r\n",
            "@@ -2,1 +2,1 @@\n-second\n+SECOND\n",
            longLine + "\r\nSECOND\r\n",
        )
    }

    @Test
    fun outOfRangeContextAndInconsistentHeadersReject() {
        for (patch in listOf(
            "@@ -2,1 +2,1 @@\n-nope\n+x\n",
            "@@ -1,1 +1,1 @@\n-a\n+A\n@@ -3,1 +3,1 @@\n-b\n+B\n",
            "@@ -2,0 +1,1 @@\n+x\n",
            "@@ -1,2 +1,1 @@\n-a\n+b\n",
            "@@ -1 +1 @@\n?bad\n",
        )) {
            assertRejectedByBoth("a\nb\n", patch)
        }
    }

    @Test
    fun enginesAgreeOnTheWholeEdgeCaseMatrix() {
        val cases = listOf(
            "" to "@@ -0,0 +1,1 @@\n+hello\n",
            "" to "@@ -0,0 +1,1 @@\n+hello\n\\ No newline at end of file\n",
            "" to "@@ -1,0 +2,1 @@\n+x\n",
            "a" to "@@ -1,1 +1,1 @@\n-a\n+A\n",
            "a\n" to "@@ -1,1 +1,1 @@\n-a\n+A\n",
            "a\nb" to "@@ -2,1 +2,1 @@\n-b\n+B\n\\ No newline at end of file\n",
            "a\nb\n" to "@@ -2,1 +1,0 @@\n-b\n",
            "a\nb\n" to "@@ -3,0 +4,1 @@\n+x\n",
            "a\r\nb\r\n" to "@@ -1,1 +1,1 @@\n-a\n+A\n",
            "a\r\nb\nc\r\n" to "@@ -2,1 +2,1 @@\n-b\n+B\n",
            "one\ntwo\nthree\nfour\nfive\nsix\n" to
                "@@ -1,2 +1,1 @@\n-one\n-two\n+ONE\n@@ -5,2 +4,2 @@\n five\n-six\n+SIX\n",
            "a\nb\n" to "@@ -1,2 +1,2 @@\n a\n\n+b\n",
        )
        for ((source, patch) in cases) {
            assertEnginesMatch(source, patch)
        }
    }

    private fun assertEnginesAgree(source: String, patch: String, expected: String) {
        assertEquals(expected, UnifiedTextPatch.apply(source, patch), "in-memory result for patch=$patch")
        val (result, output) = stream(source, patch, "unified")
        assertTrue(result is RootStreamingPatchResult.Succeeded, "streaming rejected: $result patch=$patch")
        assertEquals(expected, output, "streaming result for patch=$patch")
    }

    private fun assertRejectedByBoth(source: String, patch: String) {
        assertNull(UnifiedTextPatch.apply(source, patch), "in-memory accepted patch=$patch")
        val (result, _) = stream(source, patch, "unified")
        assertTrue(result is RootStreamingPatchResult.Rejected, "streaming accepted patch=$patch")
    }

    private fun assertEnginesMatch(source: String, patch: String) {
        val plain = UnifiedTextPatch.apply(source, patch)
        val (result, output) = stream(source, patch, "unified")
        if (plain == null) {
            assertTrue(result is RootStreamingPatchResult.Rejected, "streaming accepted what memory rejected: $patch")
        } else {
            assertTrue(result is RootStreamingPatchResult.Succeeded, "streaming rejected what memory accepted: $result $patch")
            assertEquals(plain, output, "engines disagree on patch=$patch")
        }
    }

    private fun stream(source: String, patch: String, format: String): Pair<RootStreamingPatchResult, String> {
        val bytes = source.toByteArray()
        val output = ByteArrayOutputStream()
        val result = RootStreamingPatch.applyStreamed(bytes.size.toLong(), { offset, count ->
            bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
        }, format, patch.toByteArray()) { chunk, start, length -> output.write(chunk, start, length) }
        return result to output.toString(Charsets.UTF_8.name())
    }
}
