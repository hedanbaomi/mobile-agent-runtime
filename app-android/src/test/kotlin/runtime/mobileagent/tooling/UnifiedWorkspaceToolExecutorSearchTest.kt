// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.SnapshotGrantBinding
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.skills.tooling.WorkspaceBackendType
import runtime.mobileagent.skills.tooling.WorkspaceCreateDirectoryRequest
import runtime.mobileagent.skills.tooling.WorkspaceDescriptor
import runtime.mobileagent.skills.tooling.WorkspaceListRequest
import runtime.mobileagent.skills.tooling.WorkspaceListing
import runtime.mobileagent.skills.tooling.WorkspaceReadTextRequest
import runtime.mobileagent.skills.tooling.WorkspaceResult
import runtime.mobileagent.skills.tooling.WorkspaceStatRequest
import runtime.mobileagent.skills.tooling.WorkspaceText
import runtime.mobileagent.skills.tooling.WorkspaceWriteTextRequest
import runtime.mobileagent.workspace.InternalWorkspaceBackend
import runtime.mobileagent.workspace.SharedWorkspaceBackendAdapter

/**
 * Executor-level tests for the model-visible `file_glob` / `file_grep` surface and for the
 * deferred-disclosure hook.
 *
 * The suite is deliberately negative where it matters: whole-subtree authority, directory-range
 * scopes that must not widen, traversal-shaped inputs, mid-flight revocation, terminal versus
 * degraded read failures, cancellation, and the exact ONCE receipt rule that lets a first
 * batch/job page be disclosed while a repeated call id stays a non-replayable replay.
 */
class UnifiedWorkspaceToolExecutorSearchTest {

    @TempDir
    lateinit var tempDir: Path

    // ------------------------------------------------------------- exposure

    @Test
    fun searchToolsAreExposedOnlyForAuthorizedReadOnlyWorkspaces() {
        val listOnly = readFixture { workspaceId -> listOf(grant("l-$workspaceId", CapabilityId.FILE_LIST, workspaceId)) }
        val names = listOnly.executor.toolingSpecs.map { it.name }
        assertTrue(UnifiedWorkspaceToolExecutor.FILE_GLOB in names, "file.list is enough for a glob")
        assertFalse(UnifiedWorkspaceToolExecutor.FILE_GREP in names, "grep must not appear without read authority")

        val readOnly = readFixture { workspaceId ->
            listOf(grant("r-$workspaceId", CapabilityId.FILE_READ_TEXT, workspaceId))
        }
        val readNames = readOnly.executor.toolingSpecs.map { it.name }
        assertFalse(
            UnifiedWorkspaceToolExecutor.FILE_GLOB in readNames,
            "a glob needs enumeration authority, not only read authority",
        )
        assertFalse(UnifiedWorkspaceToolExecutor.FILE_GREP in readNames)

        val both = readFixture()
        val bothNames = both.executor.toolingSpecs.map { it.name }
        assertTrue(UnifiedWorkspaceToolExecutor.FILE_GLOB in bothNames)
        assertTrue(UnifiedWorkspaceToolExecutor.FILE_GREP in bothNames)

        val globSpec = both.executor.toolingSpecs.single { it.name == UnifiedWorkspaceToolExecutor.FILE_GLOB }
        val grepSpec = both.executor.toolingSpecs.single { it.name == UnifiedWorkspaceToolExecutor.FILE_GREP }
        assertFalse(globSpec.inputSchema.contains("regex"), "the glob schema must not advertise a regex")
        assertFalse(grepSpec.inputSchema.contains("regex"), "the grep schema must not advertise a regex")
        assertFalse(grepSpec.inputSchema.contains("\"pattern\""), "grep takes a literal query, not a pattern")
        assertTrue(grepSpec.description.contains("literal"), "the description must say the search is literal")
        assertTrue(grepSpec.inputSchema.contains("\"query\""))
        assertTrue(globSpec.inputSchema.contains("\"pattern\""))
    }

    @Test
    fun globAndGrepReturnBoundedResultsWithScanCounters(): Unit = runBlocking {
        val fixture = readFixture()
        val globCall = ToolCall(
            "glob-basic",
            UnifiedWorkspaceToolExecutor.FILE_GLOB,
            globArgs(fixture.workspaceId, "**/*.kt", root = "src"),
        )
        val glob = json(fixture.executor.invoke(globCall, fixture.context))
        assertEquals(1, glob.getValue("match_count").jsonPrimitive.int)
        assertFalse(glob.getValue("truncated").jsonPrimitive.boolean)
        assertEquals(
            listOf("src/Main.kt"),
            glob.getValue("matches").jsonArray.map { it.jsonObject.getValue("relative_path").jsonPrimitive.content },
        )
        val scanned = glob.getValue("scanned").jsonObject
        assertTrue(scanned.getValue("directories").jsonPrimitive.int >= 1)
        assertTrue(scanned.getValue("entries").jsonPrimitive.int >= 1)
        assertTrue(scanned.getValue("files").jsonPrimitive.int >= 1)

        val grep = json(
            fixture.executor.invoke(
                ToolCall(
                    "grep-basic",
                    UnifiedWorkspaceToolExecutor.FILE_GREP,
                    grepArgs(fixture.workspaceId, "main", root = "src"),
                ),
                fixture.context,
            ),
        )
        assertEquals(1, grep.getValue("match_count").jsonPrimitive.int)
        val hit = grep.getValue("matches").jsonArray.single().jsonObject
        assertEquals("src/Main.kt", hit.getValue("relative_path").jsonPrimitive.content)
        assertEquals(1, hit.getValue("line_number").jsonPrimitive.int)
        assertEquals(5, hit.getValue("column").jsonPrimitive.int)
        assertEquals("fun main() {}", hit.getValue("line").jsonPrimitive.content)
        assertEquals("main", grep.getValue("query").jsonPrimitive.content)
        assertFalse(grep.getValue("case_sensitive").jsonPrimitive.boolean)
    }

    @Test
    fun aSearchedSubtreeNeverDisclosesASiblingSubtree(): Unit = runBlocking {
        val fixture = readFixture()
        val result = json(
            fixture.executor.invoke(
                ToolCall(
                    "glob-src-only",
                    UnifiedWorkspaceToolExecutor.FILE_GLOB,
                    globArgs(fixture.workspaceId, "*", root = "src"),
                ),
                fixture.context,
            ),
        )
        val paths = result.getValue("matches").jsonArray.map { it.jsonObject.getValue("relative_path").jsonPrimitive.content }
        assertTrue(paths.isNotEmpty())
        assertTrue(paths.all { it.startsWith("src/") }, "no sibling subtree may be disclosed: $paths")
        assertFalse(paths.any { it.contains("docs") })
    }

    @Test
    fun truncationIsReportedInsteadOfBeingPresentedAsNoMatch(): Unit = runBlocking {
        val fixture = readFixture(
            files = mapOf("src/Main.kt" to "fun main() {}\n", "src/deep/Other.kt" to "fun other() {}\n"),
        )
        val result = json(
            fixture.executor.invoke(
                ToolCall(
                    "glob-depth-truncated",
                    UnifiedWorkspaceToolExecutor.FILE_GLOB,
                    globArgs(fixture.workspaceId, "nomatch-*", root = "src", extra = ",\"max_depth\":0"),
                ),
                fixture.context,
            ),
        )
        assertEquals(0, result.getValue("match_count").jsonPrimitive.int)
        assertTrue(result.getValue("truncated").jsonPrimitive.boolean, "an incomplete search is not 'no match'")
        assertTrue(
            result.getValue("truncation_reasons").jsonArray.any { it.jsonPrimitive.content == "max_depth" },
            "the reason must name the budget that stopped the scan",
        )
    }

    @Test
    fun aBackendWithoutListSupportNeverExposesSearchAndStaleCallsFailClosed() {
        val workspaceId = nextWorkspaceId()
        val adapter = adapter(workspaceId)
        runBlocking { writeFiles(adapter, workspaceId, mapOf("note.txt" to "body\n")) }
        val backend = NoListBackend(adapter)
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(backend.descriptor, backend))
        val grants = listOf(
            grant("nl-list-$workspaceId", CapabilityId.FILE_LIST, workspaceId),
            grant("nl-read-$workspaceId", CapabilityId.FILE_READ_TEXT, workspaceId),
        )
        val context = runContext(grants)
        val executor = executor(registry, context, grants)

        val names = executor.toolingSpecs.map { it.name }
        assertFalse(UnifiedWorkspaceToolExecutor.FILE_GLOB in names, "a backend without list() cannot serve a glob")
        assertFalse(UnifiedWorkspaceToolExecutor.FILE_GREP in names)
        assertTrue(UnifiedWorkspaceToolExecutor.FILE_READ_TEXT in names, "existing file tools keep working")

        // A stale model schema that still calls the search tool fails closed, not with a crash.
        val stale = runBlocking {
            executor.invoke(
                ToolCall("stale-glob", UnifiedWorkspaceToolExecutor.FILE_GLOB, globArgs(workspaceId, "*")),
                context,
            )
        }
        assertEquals(ToolErrorCode.CAPABILITY_DENIED, failureCode(stale))
        val read = runBlocking {
            executor.invoke(
                ToolCall(
                    "read-still-works",
                    UnifiedWorkspaceToolExecutor.FILE_READ_TEXT,
                    "{\"workspace_id\":\"$workspaceId\",\"relative_path\":\"note.txt\"}",
                ),
                context,
            )
        }
        assertTrue(read is ToolResult.Value, "plain reads must be unaffected by the new tools: $read")
    }

    @Test
    fun fileMoveStaysUnavailableAndOrdinaryReadsStillWork(): Unit = runBlocking {
        val fixture = readFixture()
        val names = fixture.executor.toolingSpecs.map { it.name }
        assertFalse(UnifiedWorkspaceToolExecutor.FILE_MOVE in names)
        val read = fixture.executor.invoke(
            ToolCall(
                "read-works",
                UnifiedWorkspaceToolExecutor.FILE_READ_TEXT,
                "{\"workspace_id\":\"${fixture.workspaceId}\",\"relative_path\":\"src/Main.kt\"}",
            ),
            fixture.context,
        )
        assertTrue(read is ToolResult.Value)
        val move = fixture.executor.invoke(
            ToolCall(
                "move-retired",
                UnifiedWorkspaceToolExecutor.FILE_MOVE,
                "{\"workspace_id\":\"${fixture.workspaceId}\",\"relative_path\":\"src/Main.kt\"," +
                    "\"destination_relative_path\":\"src/Copy.kt\"}",
            ),
            fixture.context,
        )
        assertEquals(ToolErrorCode.OPERATION_UNAVAILABLE, failureCode(move))
    }

    // ------------------------------------------------------------ input shape

    @Test
    fun traversalShapedRootsPatternsAndQueriesAreRejectedBeforeAnyBackendCall() {
        val fixture = readFixture()
        val workspaceId = fixture.workspaceId
        val rejected = listOf(
            globArgs(workspaceId, "*", root = "../outside"),
            globArgs(workspaceId, "*", root = "/etc"),
            globArgs(workspaceId, "*", root = "a/../../b"),
            globArgs(workspaceId, "../*"),
            globArgs(workspaceId, "/etc/*"),
            globArgs(workspaceId, "C:/windows/*"),
            globArgs(workspaceId, "a\\b"),
            globArgs(workspaceId, "a//b"),
            globArgs(workspaceId, "*", extra = ",\"unknown\":1"),
            globArgs(workspaceId, "*", extra = ",\"max_depth\":99"),
            globArgs(workspaceId, "*", extra = ",\"max_depth\":\"8\""),
            globArgs(workspaceId, "*", extra = ",\"max_results\":0"),
            globArgs(workspaceId, "*", extra = ",\"case_sensitive\":true"),
            grepArgs(workspaceId, ""),
            grepArgs(workspaceId, "a\nb"),
            grepArgs(workspaceId, "needle", extra = ",\"max_scanned_bytes\":0"),
            grepArgs(workspaceId, "needle", extra = ",\"pattern\":\"*\"", tool = "glob-extra"),
        )
        for ((index, arguments) in rejected.withIndex()) {
            val result = runBlocking {
                fixture.executor.invoke(ToolCall("reject-$index", UnifiedWorkspaceToolExecutor.FILE_GLOB, arguments), fixture.context)
            }
            assertTrue(result is ToolResult.Invalid, "arguments must be rejected: $arguments -> $result")
        }
        for ((index, arguments) in rejected.withIndex()) {
            val result = runBlocking {
                fixture.executor.invoke(ToolCall("reject-grep-$index", UnifiedWorkspaceToolExecutor.FILE_GREP, arguments), fixture.context)
            }
            assertTrue(result is ToolResult.Invalid || result is ToolResult.Failure, "must never execute: $arguments")
        }
        assertEquals(0, fixture.backend.listCalls, "a rejected search must not touch the provider")
        assertEquals(0, fixture.backend.readCalls)
    }

    @Test
    fun aSearchRootThatIsAFileFailsWithATypedError(): Unit = runBlocking {
        val fixture = readFixture()
        val result = fixture.executor.invoke(
            ToolCall(
                "glob-on-file",
                UnifiedWorkspaceToolExecutor.FILE_GLOB,
                globArgs(fixture.workspaceId, "*", root = "src/Main.kt"),
            ),
            fixture.context,
        )
        assertEquals(ToolErrorCode.UNSUPPORTED_ENTRY, failureCode(result))
    }

    // ------------------------------------------------------------- authority

    @Test
    fun grepRequiresBothCapabilitiesOnTheWholeSearchedSubtree(): Unit = runBlocking {
        val fixture = readFixture { workspaceId ->
            listOf(
                grant("s-list-$workspaceId", CapabilityId.FILE_LIST, workspaceId, pathScope = "src"),
                grant("s-read-$workspaceId", CapabilityId.FILE_READ_TEXT, workspaceId, pathScope = "src"),
            )
        }
        val inside = fixture.executor.invoke(
            ToolCall("grep-src", UnifiedWorkspaceToolExecutor.FILE_GREP, grepArgs(fixture.workspaceId, "main", root = "src")),
            fixture.context,
        )
        assertTrue(inside is ToolResult.Value, "the scoped subtree is authorized: $inside")

        val listsBefore = fixture.backend.listCalls
        val readsBefore = fixture.backend.readCalls
        val outside = fixture.executor.invoke(
            ToolCall("grep-root", UnifiedWorkspaceToolExecutor.FILE_GREP, grepArgs(fixture.workspaceId, "main")),
            fixture.context,
        )
        assertEquals(
            ToolErrorCode.CAPABILITY_DENIED,
            failureCode(outside),
            "a directory-range grant must not become whole-workspace authority",
        )
        assertEquals(listsBefore, fixture.backend.listCalls, "a denied search reads nothing entry by entry")
        assertEquals(readsBefore, fixture.backend.readCalls)
    }

    @Test
    fun aDescendantScopedGrantCannotBeWidenedIntoItsParent(): Unit = runBlocking {
        val fixture = readFixture(files = mapOf("src/sub/Inner.kt" to "class Inner\n")) { workspaceId ->
            listOf(
                grant("d-list-$workspaceId", CapabilityId.FILE_LIST, workspaceId, pathScope = "src/sub"),
                grant("d-read-$workspaceId", CapabilityId.FILE_READ_TEXT, workspaceId, pathScope = "src/sub"),
            )
        }
        val parent = fixture.executor.invoke(
            ToolCall("grep-parent", UnifiedWorkspaceToolExecutor.FILE_GREP, grepArgs(fixture.workspaceId, "Inner", root = "src")),
            fixture.context,
        )
        assertEquals(ToolErrorCode.CAPABILITY_DENIED, failureCode(parent))
        assertEquals(0, fixture.backend.listCalls, "the parent scope is denied before any I/O")

        val exact = fixture.executor.invoke(
            ToolCall("grep-exact", UnifiedWorkspaceToolExecutor.FILE_GREP, grepArgs(fixture.workspaceId, "Inner", root = "src/sub")),
            fixture.context,
        )
        assertTrue(exact is ToolResult.Value, "the granted subtree itself is searchable: $exact")
    }

    @Test
    fun midFlightRevocationStopsTheSearchWithoutDisclosingResults(): Unit = runBlocking {
        val fixture = readFixture(files = mapOf("a.txt" to "needle\n", "b.txt" to "needle\n"))
        fixture.backend.beforeList = {
            if (fixture.backend.listCalls == 1) fixture.authority.grants = emptyList()
        }
        val result = fixture.executor.invoke(
            ToolCall("revoke-mid", UnifiedWorkspaceToolExecutor.FILE_GREP, grepArgs(fixture.workspaceId, "needle")),
            fixture.context,
        )
        assertEquals(ToolErrorCode.CAPABILITY_DENIED, failureCode(result))
        assertEquals(0, fixture.backend.readCalls, "nothing may be read after the authority was lost")
    }

    @Test
    fun losingBackendCapabilitiesDuringSearchStopsFurtherReads(): Unit = runBlocking {
        val fixture = readFixture(files = mapOf("a.txt" to "needle\n", "b.txt" to "needle\n"))
        fixture.backend.beforeList = { fixture.backend.capabilitiesOverride = emptySet() }
        val result = fixture.executor.invoke(
            ToolCall("backend-lost", UnifiedWorkspaceToolExecutor.FILE_GREP, grepArgs(fixture.workspaceId, "needle")),
            fixture.context,
        )
        assertEquals(ToolErrorCode.CAPABILITY_DENIED, failureCode(result))
        assertEquals(0, fixture.backend.readCalls)
    }

    @Test
    fun replayAfterRevocationIsDeniedAndNeverRedispatches(): Unit = runBlocking {
        val fixture = readFixture()
        val call = ToolCall(
            "cached-glob",
            UnifiedWorkspaceToolExecutor.FILE_GLOB,
            globArgs(fixture.workspaceId, "**/*.kt", root = "src"),
        )
        assertTrue(fixture.executor.invoke(call, fixture.context) is ToolResult.Value)
        fixture.authority.grants = emptyList()
        assertFalse(fixture.executor.authorizeReplay(call), "the cached payload must not be disclosed")
        assertFalse(fixture.executor.authorizeDeferredResult(call), "and not through the deferred channel either")

        val listsBefore = fixture.backend.listCalls
        val fresh = fixture.executor.invoke(
            ToolCall(
                "fresh-after-revocation",
                UnifiedWorkspaceToolExecutor.FILE_GLOB,
                globArgs(fixture.workspaceId, "**/*.kt", root = "src"),
            ),
            fixture.context,
        )
        assertEquals(ToolErrorCode.CAPABILITY_DENIED, failureCode(fresh))
        assertEquals(listsBefore, fixture.backend.listCalls, "a revoked search must not be re-dispatched")
    }

    @Test
    fun aDeniedReadIsNeverReportedAsAPartialSuccess(): Unit = runBlocking {
        val fixture = readFixture(files = mapOf("a.txt" to "needle\n", "b.txt" to "needle\n"))
        fixture.backend.denyReadFor = setOf("a.txt")
        val result = fixture.executor.invoke(
            ToolCall("denied-read", UnifiedWorkspaceToolExecutor.FILE_GREP, grepArgs(fixture.workspaceId, "needle")),
            fixture.context,
        )
        assertEquals(
            ToolErrorCode.PERMISSION_DENIED,
            failureCode(result),
            "a revoked/denied read must terminate the search, not become an unreadable-file counter",
        )
    }

    @Test
    fun aCancelledSearchIsAnUnknownOutcomeAndIsNeverReplayed(): Unit = runBlocking {
        val fixture = readFixture()
        fixture.backend.cancelOnList = true
        val call = ToolCall(
            "cancelled-glob",
            UnifiedWorkspaceToolExecutor.FILE_GLOB,
            globArgs(fixture.workspaceId, "**/*.kt", root = "src"),
        )
        val result = fixture.executor.invoke(call, fixture.context)
        assertTrue(result is ToolResult.UnknownOutcome, "a cancelled dispatch is externally ambiguous: $result")
        assertFalse(fixture.executor.authorizeReplay(call), "an unknown outcome is never replayed")
        assertFalse(fixture.executor.authorizeDeferredResult(call))
    }

    // -------------------------------------------------- deferred disclosure

    /**
     * A mixed-lifetime request: one half is ONCE (consumed at dispatch) and the other half is a
     * standing grant.  Each capability must be satisfied from its own source — a live standing
     * scope *or* its own exact consumed receipt — rather than demanding a receipt for both halves.
     */
    @Test
    fun aMixedLifetimeRequestExecutesWhenEachHalfHasItsOwnAuthority(): Unit = runBlocking {
        val onceList = onceLikeFixture(onceCapabilities = setOf(CapabilityId(CapabilityId.FILE_LIST)))
        val listOnceCall = ToolCall(
            "mixed-list-once",
            UnifiedWorkspaceToolExecutor.FILE_GREP,
            grepArgs(onceList.workspaceId, "main", root = "src"),
        )
        assertTrue(
            onceList.executor.invoke(listOnceCall, onceList.context) is ToolResult.Value,
            "ONCE list + standing read must run rather than be denied by its own consumption",
        )
        assertEquals(1, onceList.consumed.size, "exactly the ONCE half is consumed")
        assertFalse(
            onceList.executor.authorizeReplay(listOnceCall),
            "the consumed half still makes a repeated call id a non-disclosable replay",
        )

        val onceRead = onceLikeFixture(onceCapabilities = setOf(CapabilityId(CapabilityId.FILE_READ_TEXT)))
        val readOnceCall = ToolCall(
            "mixed-read-once",
            UnifiedWorkspaceToolExecutor.FILE_GREP,
            grepArgs(onceRead.workspaceId, "main", root = "src"),
        )
        assertTrue(
            onceRead.executor.invoke(readOnceCall, onceRead.context) is ToolResult.Value,
            "standing list + ONCE read must run rather than be denied by its own consumption",
        )
        assertEquals(1, onceRead.consumed.size, "exactly the ONCE half is consumed")
    }

    @Test
    fun deferredDisclosureAdmitsTheExactConsumedOncePageButReplayStillDenies(): Unit = runBlocking {
        val fixture = onceFixture()
        val call = ToolCall(
            "deferred-once",
            UnifiedWorkspaceToolExecutor.FILE_GREP,
            grepArgs(fixture.workspaceId, "main", root = "src"),
        )
        val result = fixture.executor.invoke(call, fixture.context)
        assertTrue(result is ToolResult.Value, "the ONCE-authorized search must succeed: $result")
        assertTrue(
            fixture.authority.grants.all { it.consumedAt != null },
            "the durable CAS must have advanced both ONCE rows",
        )

        assertTrue(
            fixture.executor.authorizeDeferredResult(call),
            "the first page of the same execution may still be disclosed from its exact receipt",
        )
        assertFalse(
            fixture.executor.authorizeReplay(call),
            "repeating a call id stays a replay and must keep rejecting a consumed ONCE grant",
        )
    }

    @Test
    fun deferredDisclosureDeniesEveryPostConsumptionChangeAndStaysDenied(): Unit = runBlocking {
        val mutations: List<Pair<String, (LiveAuthority) -> Unit>> = listOf(
            "revocation" to { authority ->
                authority.grants = authority.grants.map { it.copy(revision = it.revision + 1, revokedAt = REVOKED_AT) }
            },
            "expiry" to { authority ->
                authority.grants = authority.grants.map { it.copy(expiresAt = "2000-01-01T00:00:00Z") }
            },
            "scope edit" to { authority ->
                authority.grants = authority.grants.map { it.copy(pathScope = "docs") }
            },
            "capability edit" to { authority ->
                authority.grants = authority.grants.map {
                    it.copy(capability = CapabilityId(CapabilityId.FILE_WRITE_TEXT))
                }
            },
            "workspace edit" to { authority ->
                authority.grants = authority.grants.map { it.copy(workspaceId = "other-workspace") }
            },
            "binding removal" to { authority -> authority.bindings = emptyList() },
            "policy change" to { authority -> authority.policyVersion = POLICY + 1 },
        )

        for ((name, mutate) in mutations) {
            val fixture = onceFixture()
            val call = ToolCall(
                "deferred-$name",
                UnifiedWorkspaceToolExecutor.FILE_GREP,
                grepArgs(fixture.workspaceId, "main", root = "src"),
            )
            assertTrue(
                fixture.executor.invoke(call, fixture.context) is ToolResult.Value,
                "$name: the ONCE search must run first",
            )
            mutate(fixture.authority)
            assertFalse(fixture.executor.authorizeDeferredResult(call), "$name must deny the deferred page")
            assertFalse(
                fixture.executor.authorizeDeferredResult(call),
                "$name must stay denied: a failed revalidation is not revived",
            )
            assertFalse(fixture.executor.authorizeReplay(call), "$name must also deny ordinary replay")
        }
    }

    @Test
    fun deferredDisclosureDeniesUnknownResultsMutationsAndClosedExecutors(): Unit = runBlocking {
        // A cancelled search leaves an unknown outcome, which is never disclosed deferred.
        val fixture = readFixture()
        fixture.backend.cancelOnList = true
        val unknownCall = ToolCall(
            "deferred-unknown",
            UnifiedWorkspaceToolExecutor.FILE_GLOB,
            globArgs(fixture.workspaceId, "**/*.kt", root = "src"),
        )
        assertTrue(fixture.executor.invoke(unknownCall, fixture.context) is ToolResult.UnknownOutcome)
        assertFalse(fixture.executor.authorizeDeferredResult(unknownCall))

        // A mutation result is not a deferred page.
        val write = readFixture(writable = true)
        val writeCall = ToolCall(
            "deferred-write",
            UnifiedWorkspaceToolExecutor.FILE_WRITE_TEXT,
            "{\"workspace_id\":\"${write.workspaceId}\",\"relative_path\":\"note.txt\",\"text\":\"body\"}",
        )
        assertTrue(write.executor.invoke(writeCall, write.context) is ToolResult.Value)
        assertFalse(
            write.executor.authorizeDeferredResult(writeCall),
            "the deferred channel is restricted to read-only operations",
        )

        // A closed executor denies both channels.
        val closed = readFixture()
        val closedCall = ToolCall(
            "deferred-closed",
            UnifiedWorkspaceToolExecutor.FILE_GLOB,
            globArgs(closed.workspaceId, "**/*.kt", root = "src"),
        )
        assertTrue(closed.executor.invoke(closedCall, closed.context) is ToolResult.Value)
        closed.executor.close()
        assertFalse(closed.executor.authorizeDeferredResult(closedCall), "close() clears the bound call")
        assertFalse(closed.executor.authorizeReplay(closedCall), "close() clears the replay cache too")
    }

    // ------------------------------------------------------------- fixtures

    private class LiveAuthority(
        var grants: List<CapabilityGrant>,
        var bindings: List<SnapshotGrantBinding>,
        var policyVersion: Long,
    )

    private class Fixture(
        val workspaceId: String,
        val executor: UnifiedWorkspaceToolExecutor,
        val context: ToolExecutionContext,
        val authority: LiveAuthority,
        val backend: CountingBackend,
        /** The pre-consumption ONCE rows this fixture's consumer actually consumed. */
        val consumed: MutableList<CapabilityGrant>,
    )

    private var fixtureCounter = 0

    private fun nextWorkspaceId(): String {
        fixtureCounter += 1
        return "ws-search-$fixtureCounter"
    }

    private fun adapter(workspaceId: String): SharedWorkspaceBackendAdapter = SharedWorkspaceBackendAdapter(
        InternalWorkspaceBackend(tempDir.resolve(workspaceId), workspaceId = workspaceId),
    )

    private fun readFixture(
        files: Map<String, String> = mapOf("src/Main.kt" to "fun main() {}\n", "docs/readme.md" to "text\n"),
        writable: Boolean = false,
        grantsFor: (String) -> List<CapabilityGrant> = { workspaceId ->
            val capabilities = if (writable) {
                listOf(CapabilityId.FILE_LIST, CapabilityId.FILE_STAT, CapabilityId.FILE_READ_TEXT, CapabilityId.FILE_WRITE_TEXT)
            } else {
                listOf(CapabilityId.FILE_LIST, CapabilityId.FILE_STAT, CapabilityId.FILE_READ_TEXT)
            }
            capabilities.mapIndexed { index, capability ->
                grant("g$index-$workspaceId", capability, workspaceId)
            }
        },
    ): Fixture {
        val workspaceId = nextWorkspaceId()
        val adapter = adapter(workspaceId)
        runBlocking { writeFiles(adapter, workspaceId, files) }
        val counting = CountingBackend(adapter)
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(counting.descriptor, counting))
        val grants = grantsFor(workspaceId)
        val context = runContext(grants)
        val authority = LiveAuthority(grants, context.snapshotGrantBindings, POLICY)
        val executor = executor(registry, context, grants, authority)
        return Fixture(workspaceId, executor, context, authority, counting, mutableListOf())
    }

    /** A workspace whose read and list authority are both ONCE, consumed at dispatch. */
    private fun onceFixture(): Fixture = onceLikeFixture(
        onceCapabilities = setOf(CapabilityId(CapabilityId.FILE_LIST), CapabilityId(CapabilityId.FILE_READ_TEXT)),
    )

    /**
     * A workspace where only the listed capabilities are ONCE and the rest are standing grants.
     * The consumer models the durable CAS exactly: revision + 1 with the consumption timestamp.
     */
    private fun onceLikeFixture(onceCapabilities: Set<CapabilityId>): Fixture {
        val workspaceId = nextWorkspaceId()
        val adapter = adapter(workspaceId)
        runBlocking { writeFiles(adapter, workspaceId, mapOf("src/Main.kt" to "fun main() {}\n")) }
        val counting = CountingBackend(adapter)
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(counting.descriptor, counting))
        val grants = listOf(
            grant(
                "life-list-$workspaceId",
                CapabilityId.FILE_LIST,
                workspaceId,
                lifetime = lifetimeFor(CapabilityId(CapabilityId.FILE_LIST), onceCapabilities),
            ),
            grant(
                "life-read-$workspaceId",
                CapabilityId.FILE_READ_TEXT,
                workspaceId,
                lifetime = lifetimeFor(CapabilityId(CapabilityId.FILE_READ_TEXT), onceCapabilities),
            ),
        )
        val context = runContext(grants)
        val authority = LiveAuthority(grants, context.snapshotGrantBindings, POLICY)
        val consumed = mutableListOf<CapabilityGrant>()
        val executor = UnifiedWorkspaceToolExecutor(
            registry = registry,
            approvalEngine = ApprovalEngine(),
            resolver = liveResolver(authority),
            contextProvider = { context },
            auditSink = ALLOWING_AUDIT,
            clock = SYSTEM_TOOLING_CLOCK,
            onceGrantConsumer = { row ->
                authority.grants = authority.grants.map { live ->
                    if (live.grantId == row.grantId) {
                        row.copy(revision = row.revision + 1, consumedAt = CONSUMED_AT)
                    } else {
                        live
                    }
                }
                consumed += row
                true
            },
        )
        return Fixture(workspaceId, executor, context, authority, counting, consumed)
    }

    private fun lifetimeFor(capability: CapabilityId, onceCapabilities: Set<CapabilityId>): GrantLifetime =
        if (capability in onceCapabilities) GrantLifetime.ONCE else GrantLifetime.PERSISTENT

    private fun liveResolver(authority: LiveAuthority): EffectiveCapabilityResolver = EffectiveCapabilityResolver(
        grants = CapabilityGrantReader { _, _ -> authority.grants },
        bindings = SnapshotGrantBindingReader { authority.bindings },
        currentPolicyVersionReader = { authority.policyVersion },
    )

    private fun executor(
        registry: WorkspaceRegistry,
        context: ToolExecutionContext,
        grants: List<CapabilityGrant>,
        authority: LiveAuthority? = null,
        approvalRequired: Boolean = false,
    ): UnifiedWorkspaceToolExecutor {
        val effectiveAuthority = authority ?: LiveAuthority(grants, context.snapshotGrantBindings, POLICY)
        return UnifiedWorkspaceToolExecutor(
            registry = registry,
            approvalEngine = ApprovalEngine(),
            resolver = liveResolver(effectiveAuthority),
            contextProvider = { context },
            auditSink = ALLOWING_AUDIT,
            clock = SYSTEM_TOOLING_CLOCK,
            dangerousModeProvider = { runtime.mobileagent.domain.DangerousMode.DISABLED },
            onceGrantConsumer = { !approvalRequired && false },
        )
    }

    private suspend fun writeFiles(
        adapter: SharedWorkspaceBackendAdapter,
        workspaceId: String,
        files: Map<String, String>,
    ) {
        for ((path, text) in files) {
            val directories = path.split('/').dropLast(1)
            for (depth in directories.indices) {
                val directory = directories.take(depth + 1).joinToString("/")
                adapter.createDirectory(
                    WorkspaceCreateDirectoryRequest(workspaceId = workspaceId, relativePath = directory),
                )
            }
            val written = adapter.writeText(
                WorkspaceWriteTextRequest(workspaceId = workspaceId, relativePath = path, text = text, replace = true),
            )
            assertTrue(written is WorkspaceResult.Success, "fixture write failed for $path: $written")
        }
    }

    private fun runContext(grants: List<CapabilityGrant>): ToolExecutionContext = ToolExecutionContext(
        agentId = AGENT,
        snapshotId = SNAPSHOT,
        modelCallId = "model-search",
        sessionIdentity = SESSION,
        configSnapshotHash = "config-search",
        policyVersion = POLICY,
        effectiveCapabilities = grants.map { it.capability }.toSet(),
        canonicalGrants = grants,
        snapshotGrantBindings = grants.map { grant ->
            SnapshotGrantBinding(
                snapshotId = SNAPSHOT,
                grantId = grant.grantId,
                capability = grant.capability,
                workspaceId = grant.workspaceId,
                pathScope = grant.pathScope,
                policyVersion = grant.policyVersion,
            )
        },
    )

    private fun grant(
        grantId: String,
        capability: String,
        workspaceId: String,
        pathScope: String? = null,
        lifetime: GrantLifetime = GrantLifetime.PERSISTENT,
    ): CapabilityGrant = CapabilityGrant(
        grantId = grantId,
        agentId = AGENT,
        capability = CapabilityId(capability),
        workspaceId = workspaceId,
        pathScope = pathScope,
        lifetime = lifetime,
        policyVersion = POLICY,
        revision = 1,
    )

    private fun globArgs(workspaceId: String, pattern: String, root: String? = null, extra: String = ""): String {
        val rootPart = if (root == null) "" else ",\"relative_path\":\"$root\""
        return "{\"workspace_id\":\"$workspaceId\",\"pattern\":\"$pattern\"$rootPart$extra}"
    }

    private fun grepArgs(
        workspaceId: String,
        query: String,
        root: String? = null,
        extra: String = "",
        tool: String = "grep",
    ): String {
        require(tool.isNotEmpty())
        val rootPart = if (root == null) "" else ",\"relative_path\":\"$root\""
        return "{\"workspace_id\":\"$workspaceId\",\"query\":\"$query\"$rootPart$extra}"
    }

    private fun failureCode(result: ToolResult): ToolErrorCode? = (result as? ToolResult.Failure)?.error?.code

    private fun json(result: ToolResult): JsonObject {
        assertTrue(result is ToolResult.Value, "expected a successful search, got $result")
        return Json.parseToJsonElement((result as ToolResult.Value).json).jsonObject
    }

    private companion object {
        const val AGENT = "agent-search"
        const val SNAPSHOT = "snapshot-search"
        const val SESSION = "session-search"
        const val POLICY = 1L
        const val CONSUMED_AT = "2026-10-10T00:00:00Z"
        const val REVOKED_AT = "2026-10-10T00:00:01Z"

        val ALLOWING_AUDIT: WorkspaceAuditSink = object : WorkspaceAuditSink {
            override suspend fun record(event: WorkspaceAuditEvent): Boolean = true
        }
    }
}

/**
 * Counts provider calls and can inject a cancellation or a typed read denial, so a test can prove
 * that a denied or revoked search dispatched nothing.
 */
private class CountingBackend(private val delegate: WorkspaceBackend) : WorkspaceBackend {
    var listCalls = 0
    var readCalls = 0
    var beforeList: (() -> Unit)? = null
    var cancelOnList = false
    var denyReadFor: Set<String> = emptySet()
    var capabilitiesOverride: Set<CapabilityId>? = null

    override val descriptor: WorkspaceDescriptor get() = delegate.descriptor
    override val capabilities: Set<CapabilityId> get() = capabilitiesOverride ?: delegate.capabilities
    override val expectedVersionCapabilities: Set<CapabilityId> get() = delegate.expectedVersionCapabilities

    override suspend fun list(request: WorkspaceListRequest): WorkspaceResult<WorkspaceListing> {
        listCalls += 1
        beforeList?.invoke()
        if (cancelOnList) throw CancellationException("cancelled by the run")
        return delegate.list(request)
    }

    override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> {
        readCalls += 1
        if (request.relativePath in denyReadFor) {
            return WorkspaceResult.Failure(ToolError(ToolErrorCode.PERMISSION_DENIED))
        }
        return delegate.readText(request)
    }

    override suspend fun stat(request: WorkspaceStatRequest) = delegate.stat(request).also { statCalls += 1 }

    override suspend fun writeText(request: WorkspaceWriteTextRequest) =
        delegate.writeText(request).also { writeCalls += 1 }

    var statCalls = 0
    var writeCalls = 0
}

/**
 * A read-only backend that does not advertise or implement directory listing.
 */
private class NoListBackend(private val delegate: WorkspaceBackend) : WorkspaceBackend {
    override val descriptor: WorkspaceDescriptor get() = delegate.descriptor

    override val capabilities: Set<CapabilityId> = setOf(
        CapabilityId(CapabilityId.FILE_STAT),
        CapabilityId(CapabilityId.FILE_READ_TEXT),
    )

    override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> =
        delegate.readText(request)

    override suspend fun stat(request: WorkspaceStatRequest) = delegate.stat(request)
}
