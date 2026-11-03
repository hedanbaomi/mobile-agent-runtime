// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import android.content.Context
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.MobileAgentApp
import runtime.mobileagent.RunCoordinator
import runtime.mobileagent.WorkspacePickerTarget
import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.CapabilityGrantRepository
import runtime.mobileagent.data.ProfileRepository
import runtime.mobileagent.data.RootAuthorityRepository
import runtime.mobileagent.data.RunRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.domain.ToolInvocation
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.integration.RootWorkspaceUiController
import runtime.mobileagent.integration.WorkspaceAccessResult
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.tooling.AuthorityManager
import runtime.mobileagent.tooling.InMemoryAuthorityStateStore
import runtime.mobileagent.tooling.ToolExecutionContext

/**
 * W7 targeted acceptance draft: the real App process, the real default `su` connector, and the
 * real production approval/receipt seam, with typed file operations only.
 *
 * What this exercises (no test seam is injected):
 * - `RootRuntimeCoordinator(context, app.container.db)` with every default:
 *   `AndroidRootSessionConnector(context)`, `AndroidOsPosixFdOperations`, the real shell runner and
 *   the real control-plane observer. No `directTransportFactory`, no TCP/FIFO daemon, no
 *   `attachVerifiedRootSession` call and no manual transport.
 * - The connector starts the pinned MagiskSU `su` binary and the app's own UID0
 *   `RootPrivilegedProcessMain`; acquisition only succeeds after a bound Manager `GRANTED`
 *   observation (`lastManagerObservation` starts with `GRANTED:`).
 * - The foreground workspace attach (`RootWorkspaceUiController.attach`) with
 *   `writable=true, metadata=true, shell=false`: Root Shell consent stays off while the typed
 *   metadata/read/write capabilities are granted.
 * - `RootToolExecutorAdmission.create` -> `RootRuntimeToolBackend` -> `invoke` (NeedsApproval) ->
 *   the persisted original Run invocation -> `approve` -> `approveFileInvocation` ->
 *   `claimApprovedFileDispatch` receipt -> typed `RootServiceFileDispatch`. The test never
 *   invents an approval, receipt, pin or grant.
 * - `file_get_metadata`, `file_read_text`, unconditional `file_write_text`, conditional
 *   `file_write_text` and conditional `file_apply_patch` on a UID0-exclusive fixture. A stale
 *   `expected_version` must fail with `CONFLICT` and leave the bytes/version unchanged.
 *
 * The fixture is created by the device harness, never by this test:
 * `/data/adb/root-typed-acceptance` (UID0:UID0, 0700) containing `payload.txt`
 * (UID0:UID0, 0600, bytes `TYPED_ROOT_SEED_V1` without a newline). The harness must also verify
 * the ancestor chain and the writable `/data` mount; see `integration.md`.
 *
 * NOT compiled and NOT executed in the drafting workspace. Do not treat this file as evidence.
 */
@RunWith(AndroidJUnit4::class)
class RootDirectTypedProductionAcceptanceTest {
    @Test
    fun defaultConnectorRunsTypedFileAndConditionalOpsOnUid0ExclusiveFixture() {
        withinBudget(OVERALL_BUDGET_MS) { runAcceptance() }
    }

    private fun runAcceptance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as MobileAgentApp
        app.ensureHostInitialized()
        val db = app.container.db

        val arguments = InstrumentationRegistry.getArguments()
        val fixtureDir = requireNotNull(arguments.getString(ARG_FIXTURE_DIR)) {
            "Missing -e $ARG_FIXTURE_DIR=/data/adb/root-typed-acceptance"
        }.trim()
        require(fixtureDir.startsWith("/") && !fixtureDir.endsWith("/") && !fixtureDir.contains("..")) {
            "$ARG_FIXTURE_DIR must be a normalized absolute path: $fixtureDir"
        }
        require(!fixtureDir.startsWith(context.dataDir.absolutePath + "/")) {
            "The fixture must not be inside the host app's own dataDir"
        }
        val seedName = arguments.getString(ARG_FIXTURE_FILE) ?: DEFAULT_SEED_NAME
        require(
            seedName.matches(Regex("[A-Za-z0-9._-]{1,128}")) && seedName != "." && seedName != "..",
        ) { "Invalid $ARG_FIXTURE_FILE" }
        val expectedSeed = arguments.getString(ARG_SEED_TEXT) ?: DEFAULT_SEED_TEXT

        val seedPath = "$fixtureDir/$seedName"
        val hostDenial = runCatching { Os.stat(seedPath) }.exceptionOrNull()
        val hostErrno = (hostDenial as? ErrnoException)?.errno
        assertTrue(
            "Fixture '$seedPath' must be unreadable by the host app UID ${Process.myUid()} " +
                "(expected EACCES/EPERM/ENOENT, got ${hostDenial ?: "stat succeeded"})",
            hostErrno != null &&
                hostErrno in setOf(OsConstants.EACCES, OsConstants.EPERM, OsConstants.ENOENT),
        )

        val coordinator = RootRuntimeCoordinator(context, db)
        var executor: RootToolExecutor? = null
        try {
            // Fresh-fixture precondition only: this test must not erase durable authorization,
            // approval or receipt facts. The device owner resets a fresh install outside the test;
            // this is explicitly not a product recovery/degraded-recovery acceptance.
            val freshRoot = RootAuthorityRepository(db).get(Authority.ROOT_DIRECT)
            assertFalse(
                "Fresh fixture precondition: Root consent must not already be enabled",
                freshRoot.consent.enabled,
            )
            assertFalse(
                "Fresh fixture precondition: Root Shell consent must be off",
                freshRoot.consent.shellEnabled,
            )
            assertFalse(
                "Fresh fixture precondition: the channel must not be degraded",
                freshRoot.degraded,
            )
            assertEquals(
                "Fresh fixture precondition: no prior Root identity epoch",
                0L,
                freshRoot.identityEpoch,
            )
            assertEquals(
                "Fresh fixture precondition: no prior approval bindings",
                0L,
                db.query("SELECT COUNT(*) AS n FROM root_approval_bindings").single().long("n"),
            )
            assertEquals(
                "Fresh fixture precondition: no prior dispatch receipts",
                0L,
                db.query("SELECT COUNT(*) AS n FROM root_dispatch_receipts").single().long("n"),
            )

            val policy = AuthorityPolicyRepository(db)
            policy.updatePolicy(
                expectedPolicyVersion = policy.getPolicy().policyVersion,
                selectedAuthority = Authority.ROOT_DIRECT,
                dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
            )

            val manager = AuthorityManager(InMemoryAuthorityStateStore())
            val acquisitionStarted = System.nanoTime()
            coordinator.setUserIntent(
                authority = Authority.ROOT_DIRECT,
                enabled = true,
                authorityManager = manager,
                dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
                shizukuState = null,
            )
            val acquisitionMs = (System.nanoTime() - acquisitionStarted) / 1_000_000L
            assertTrue(
                "Default `su` acquisition must stay bounded, took ${acquisitionMs}ms " +
                    "(observation=${coordinator.lastManagerObservation})",
                acquisitionMs in 1L..ACQUISITION_BUDGET_MS,
            )
            assertTrue(
                "The real default connector did not become ready: " +
                    "observation=${coordinator.lastManagerObservation} " +
                    "record=${RootAuthorityRepository(db).get(Authority.ROOT_DIRECT)}",
                coordinator.isAuthorityReadyForDispatch(Authority.ROOT_DIRECT),
            )
            val observation = coordinator.lastManagerObservation.orEmpty()
            assertTrue(
                "A live direct session must be armed by a bound Manager GRANTED observation, got '$observation'",
                observation.startsWith("GRANTED:"),
            )

            val execution = requireNotNull(
                coordinator.currentExecutionStamp(Authority.ROOT_DIRECT, requireShellConsent = false),
            ) { "A ready direct session must produce a non-shell execution stamp" }
            assertEquals(0, execution.identity.serviceUid)
            assertEquals(Process.myUid(), execution.identity.appUid)
            assertEquals(Authority.ROOT_DIRECT, execution.identity.authority)
            assertEquals(PrivilegeTier.ROOT, execution.identity.privilegeTier)
            assertEquals(
                PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION,
                execution.identity.protocolVersion,
            )
            assertTrue(execution.identity.sessionId.isNotBlank())
            assertTrue(execution.identity.serviceInstanceId.isNotBlank())

            // Root Shell consent is deliberately not requested: typed file capabilities must not
            // depend on it, and shell_exec must remain absent from the frozen surface.
            assertNull(
                "Root Shell consent must stay off for this acceptance",
                coordinator.currentExecutionStamp(Authority.ROOT_DIRECT, requireShellConsent = true),
            )
            assertFalse(
                "The channel record must not have Root Shell consent enabled",
                RootAuthorityRepository(db).get(Authority.ROOT_DIRECT).consent.shellEnabled,
            )

            val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
            val agentId = "agent-root-typed-$suffix"
            val providerId = "provider-root-typed-$suffix"
            val modelId = "model-root-typed-$suffix"
            val snapshotId = "snapshot-root-typed-$suffix"
            val conversationId = "conversation-root-typed-$suffix"
            val runId = "run-root-typed-$suffix"

            val profiles = ProfileRepository(db)
            profiles.createProvider(
                ProviderProfile(
                    providerId,
                    "Root Typed Provider",
                    ApiFormat.OPENAI_COMPATIBLE,
                    "https://example.invalid/v1",
                    revision = 1,
                ),
            )
            profiles.createModel(
                ModelProfile(
                    modelId,
                    providerId,
                    ModelRole.CHAT,
                    "synthetic",
                    emptySet(),
                    contextLimit = 16_384,
                    outputLimit = 512,
                    revision = 1,
                ),
            )
            AgentRepository(db).saveWithPrompt(
                AgentProfile(agentId, "Root Typed Agent", "pending", modelId, revision = 0),
                "Synthetic prompt",
            )

            val attached = RootWorkspaceUiController(db, coordinator).attach(
                authority = Authority.ROOT_DIRECT,
                path = fixtureDir,
                writable = true,
                metadata = true,
                shell = false,
                target = WorkspacePickerTarget(agentId),
            )
            assertTrue("Foreground Root attach failed: $attached", attached is WorkspaceAccessResult.Success)
            val workspaceId = (attached as WorkspaceAccessResult.Success).workspace.workspaceId
            val workspace = requireNotNull(WorkspaceRepository(db).get(workspaceId))

            val grants = CapabilityGrantRepository(db).forAgent(agentId, includeRevoked = false)
            val expectedCapabilities = setOf(
                CapabilityId.FILE_METADATA_READ,
                CapabilityId.FILE_READ_TEXT,
                CapabilityId.FILE_WRITE_TEXT,
            )
            expectedCapabilities.forEach { capability ->
                assertTrue(
                    "Foreground attach must grant $capability",
                    grants.any { it.capability == CapabilityId(capability) },
                )
            }
            assertFalse(
                "Root Shell must not be granted by a shell=false attach",
                grants.any { it.capability == CapabilityId(CapabilityId.SHELL_EXECUTE) },
            )

            val snapshot = coordinator.sealNewSnapshot(
                authority = Authority.ROOT_DIRECT,
                snapshotId = snapshotId,
                agentId = agentId,
                grantIds = grants.mapTo(mutableSetOf()) { it.grantId },
            ) {
                AgentRepository(db).createSnapshot(agentId, snapshotId)
            }
            val now = Utc.nowIso()
            db.execute(
                "INSERT INTO conversations(id,snapshot_id,agent_snapshot_id,title,created_at,updated_at) " +
                    "VALUES(?,?,?,?,?,?)",
                listOf(conversationId, snapshot.id, snapshot.id, "Root Typed Acceptance", now, now),
            )

            val runs = RunRepository(db)
            RunCoordinator(runs, coordinator::admitNewRun).prepare(
                RunRecord(runId, snapshot.id, conversationId, createdAt = now),
                "owner",
            )
            db.execute(
                "UPDATE runs SET state=? WHERE run_id=?",
                listOf(RunStatus.WAITING_TOOL_APPROVAL.name, runId),
            )
            val executionContext = ToolExecutionContext(
                agentId,
                snapshot.id,
                "model-context-root-typed",
                conversationId,
                runId,
                "config-root-typed",
                execution.policyVersion,
                canonicalGrants = grants,
                snapshotGrantBindings = CapabilityGrantRepository(db).listSnapshotBindings(snapshot.id),
            )
            val productionExecutor = requireNotNull(
                RootToolExecutorAdmission.create(db, coordinator, executionContext),
            ) { "Production Root tool admission refused the stamped Run" }
            executor = productionExecutor

            val exposed = productionExecutor.exposedToolNames
            setOf(
                "file_get_metadata",
                "file_read_text",
                "file_write_text",
                "file_apply_patch",
            ).forEach { tool ->
                assertTrue("$tool must be exposed for a writable+metadata Root workspace", tool in exposed)
            }
            assertFalse(
                "shell_exec must remain withheld because Root Shell consent is off",
                "shell_exec" in exposed,
            )
            assertFalse(
                "The production Root catalog does not contain workspace_list",
                "workspace_list" in exposed,
            )

            val metadataArgs = buildJsonObject {
                put("workspace_id", workspace.id)
                put("relative_path", seedName)
            }.toString()
            val readArgs = buildJsonObject {
                put("workspace_id", workspace.id)
                put("relative_path", seedName)
                put("max_bytes", 4096)
            }.toString()

            // 1. Real UID0 metadata for the UID0-exclusive fixture.
            val seedMetadata = successJson(
                productionExecutor, db, runs, runId,
                toolName = "file_get_metadata",
                argumentsJson = metadataArgs,
                label = "seed-metadata",
            )
            assertUid0Metadata(seedMetadata, expectedSeed.toByteArray(Charsets.UTF_8).size)
            val seedVersion = metadataVersion(seedMetadata)
            assertSha256Version(seedVersion, "seed metadata version")

            // 2. The typed read returns the exact expected bytes; no shell is involved.
            val seedRead = successJson(
                productionExecutor, db, runs, runId,
                toolName = "file_read_text",
                argumentsJson = readArgs,
                label = "seed-read",
            )
            assertEquals(expectedSeed, seedRead.string("text"))
            assertEquals(seedVersion, seedRead.string("version"))
            assertEquals("true", seedRead.string("eof"))

            // 3. Unconditional write (replace=true, no expected_version).
            val unconditional = successJson(
                productionExecutor, db, runs, runId,
                toolName = "file_write_text",
                argumentsJson = writeArgs(workspace.id, seedName, UNCONDITIONAL_TEXT, expectedVersion = null),
                label = "unconditional-write",
            )
            assertUid0Metadata(unconditional, UNCONDITIONAL_TEXT.toByteArray(Charsets.UTF_8).size)
            val unconditionalVersion = metadataVersion(unconditional)
            assertSha256Version(unconditionalVersion, "unconditional write version")
            assertNotEquals(seedVersion, unconditionalVersion)
            assertEquals(UNCONDITIONAL_TEXT, readText(productionExecutor, db, runs, runId, readArgs, "unconditional-read"))

            // 4. Conditional write on the proven UID0-exclusive parent. This is the smallest
            //    version-checked write; an app-private parent would be refused with
            //    WORKSPACE_VERSION_UNSUPPORTED instead.
            val conditional = successJson(
                productionExecutor, db, runs, runId,
                toolName = "file_write_text",
                argumentsJson = writeArgs(
                    workspace.id,
                    seedName,
                    CONDITIONAL_TEXT,
                    expectedVersion = unconditionalVersion,
                ),
                label = "conditional-write",
            )
            assertUid0Metadata(conditional, CONDITIONAL_TEXT.toByteArray(Charsets.UTF_8).size)
            val conditionalVersion = metadataVersion(conditional)
            assertSha256Version(conditionalVersion, "conditional write version")
            assertNotEquals(unconditionalVersion, conditionalVersion)
            assertEquals(CONDITIONAL_TEXT, readText(productionExecutor, db, runs, runId, readArgs, "conditional-read"))

            // 5. Conditional patch (search_replace) with the observed version.
            val patched = successJson(
                productionExecutor, db, runs, runId,
                toolName = "file_apply_patch",
                argumentsJson = patchArgs(
                    workspaceId = workspace.id,
                    relativePath = seedName,
                    search = CONDITIONAL_TEXT,
                    replace = PATCHED_TEXT,
                    expectedVersion = conditionalVersion,
                ),
                label = "conditional-patch",
            )
            assertUid0Metadata(patched, PATCHED_TEXT.toByteArray(Charsets.UTF_8).size)
            val patchedVersion = metadataVersion(patched)
            assertSha256Version(patchedVersion, "patched version")
            assertNotEquals(conditionalVersion, patchedVersion)
            assertEquals(PATCHED_TEXT, readText(productionExecutor, db, runs, runId, readArgs, "patched-read"))

            // The negative version must be shape-valid for the real format (the service reports a
            // 64-hex SHA-256 version here) but different from the current value. A placeholder
            // such as "stale-version-000" would not prove that the production comparison ran;
            // this value is rejected only by the real version gate and leaves the file untouched.
            assertSha256Version(patchedVersion, "current version")
            val wrongVersion = differentSha256Version(patchedVersion)
            assertSha256Version(wrongVersion, "wrong expected_version")
            assertNotEquals(patchedVersion, wrongVersion)
            // Prove the wrong value is accepted by the production argument parser: the negative
            // below can only fail at the real typed version gate, not at schema parsing.
            // RootToolArguments.normalize returns RootToolRequest? and returns null on parse failure
            // (RootToolArguments.kt:58-65), so a non-null result is a real parser positive control.
            assertNotNull(
                "shape-valid wrong version must pass the file_write_text parser",
                RootToolArguments.normalize(
                    "file_write_text",
                    writeArgs(workspace.id, seedName, MUST_NOT_APPLY, expectedVersion = wrongVersion),
                ),
            )
            assertNotNull(
                "shape-valid wrong version must pass the file_apply_patch parser",
                RootToolArguments.normalize(
                    "file_apply_patch",
                    patchArgs(
                        workspaceId = workspace.id,
                        relativePath = seedName,
                        search = PATCHED_TEXT,
                        replace = MUST_NOT_APPLY,
                        expectedVersion = wrongVersion,
                    ),
                ),
            )

            // 6. Negative: a shape-valid but wrong expected_version is a definite conflict and must
            //    not mutate the bytes or the version. This covers conditional write.
            val staleWrite = settle(
                productionExecutor, db, runs, runId,
                toolName = "file_write_text",
                argumentsJson = writeArgs(
                    workspace.id,
                    seedName,
                    MUST_NOT_APPLY,
                    expectedVersion = wrongVersion,
                ),
                label = "stale-write",
            )
            assertConflict(staleWrite)
            assertApprovalBinding(db, staleWrite.invocationId)
            assertReceipt(db, staleWrite.invocationId, expectSuccess = false)
            assertEquals(PATCHED_TEXT, readText(productionExecutor, db, runs, runId, readArgs, "stale-write-read"))
            val afterStaleWrite = successJson(
                productionExecutor, db, runs, runId,
                toolName = "file_get_metadata",
                argumentsJson = metadataArgs,
                label = "stale-write-metadata",
            )
            assertEquals(patchedVersion, metadataVersion(afterStaleWrite))

            // 7. Negative: the same shape-valid wrong version on conditional patch must also be
            //    refused with CONFLICT and leave the current bytes intact. This is the patch-side
            //    real version gate, not a schema/parameter rejection.
            val stalePatch = settle(
                productionExecutor, db, runs, runId,
                toolName = "file_apply_patch",
                argumentsJson = patchArgs(
                    workspaceId = workspace.id,
                    relativePath = seedName,
                    search = PATCHED_TEXT,
                    replace = MUST_NOT_APPLY,
                    expectedVersion = wrongVersion,
                ),
                label = "stale-patch",
            )
            assertConflict(stalePatch)
            assertApprovalBinding(db, stalePatch.invocationId)
            assertReceipt(db, stalePatch.invocationId, expectSuccess = false)
            assertEquals(PATCHED_TEXT, readText(productionExecutor, db, runs, runId, readArgs, "stale-patch-read"))
            val afterStalePatch = successJson(
                productionExecutor, db, runs, runId,
                toolName = "file_get_metadata",
                argumentsJson = metadataArgs,
                label = "stale-patch-metadata",
            )
            assertEquals(patchedVersion, metadataVersion(afterStalePatch))
            assertTrue(
                "A rejected version must not degrade the live direct session",
                coordinator.isAuthorityReadyForDispatch(Authority.ROOT_DIRECT),
            )
        } finally {
            executor?.let { runCatching { runBlocking { it.close() } } }
            runCatching { coordinator.close() }
        }
    }

    private fun successJson(
        executor: RootToolExecutor,
        db: SqlConnection,
        runs: RunRepository,
        runId: String,
        toolName: String,
        argumentsJson: String,
        label: String,
    ): JsonObject {
        val settled = settle(executor, db, runs, runId, toolName, argumentsJson, label)
        assertTrue("$label must succeed, got ${settled.result}", settled.result is ToolResult.Value)
        assertApprovalBinding(db, settled.invocationId)
        assertReceipt(db, settled.invocationId, expectSuccess = true)
        return valueObject(settled.result)
    }

    private fun settle(
        executor: RootToolExecutor,
        db: SqlConnection,
        runs: RunRepository,
        runId: String,
        toolName: String,
        argumentsJson: String,
        label: String,
    ): Settled {
        val invocationId = "root-typed-${sequence++}-$label"
        val call = ToolCall(invocationId, toolName, argumentsJson)
        val first = runBlocking { executor.invoke(call) }
        assertEquals("$label must first request approval, got $first", ToolResult.NeedsApproval, first)

        // This is the production persistence step that follows a pending model call. The backend
        // itself never manufactures an owner; the real Run/invocation row must exist before the
        // approval can be bound.
        db.execute(
            "UPDATE runs SET state=? WHERE run_id=?",
            listOf(RunStatus.WAITING_TOOL_APPROVAL.name, runId),
        )
        runs.addInvocation(
            ToolInvocation(
                invocationId = invocationId,
                runId = runId,
                callId = invocationId,
                name = toolName,
                argumentsJson = argumentsJson,
                state = "WAITING_APPROVAL",
                permissionDecision = "PENDING",
                createdAt = Utc.nowIso(),
            ),
        )
        val result = runBlocking { executor.approve(invocationId) }
        val stored = runs.invocations(runId).single { it.invocationId == invocationId }
        runs.observeRuntimeInvocation(
            stored.copy(
                state = if (result is ToolResult.Value) "SUCCEEDED" else "FAILED",
                permissionDecision = "APPROVED",
                resultJson = (result as? ToolResult.Value)?.json,
            ),
        )
        return Settled(invocationId, result)
    }

    private fun readText(
        executor: RootToolExecutor,
        db: SqlConnection,
        runs: RunRepository,
        runId: String,
        readArgs: String,
        label: String,
    ): String = successJson(
        executor, db, runs, runId,
        toolName = "file_read_text",
        argumentsJson = readArgs,
        label = label,
    ).string("text")

    private fun assertConflict(settled: Settled) {
        val result = settled.result
        assertTrue("stale expected_version must fail, got $result", result is ToolResult.Failure)
        assertEquals(
            "stale expected_version must map to CONFLICT",
            ToolErrorCode.CONFLICT,
            (result as ToolResult.Failure).error.code,
        )
    }

    private fun assertUid0Metadata(metadata: JsonObject, expectedBytes: Int) {
        assertEquals("regular_file", metadata.string("type"))
        assertEquals(0, metadata.string("owner_uid").toInt())
        assertEquals(0, metadata.string("owner_gid").toInt())
        assertEquals("600", metadata.string("mode_octal"))
        assertEquals(expectedBytes.toLong(), metadata.string("size_bytes").toLong())
        assertEquals(1L, metadata.string("hardlink_count").toLong())
        assertTrue(metadata.string("device_id").toLong() > 0L)
        assertTrue(metadata.string("inode").toLong() > 0L)
        assertTrue(metadata.string("mount_id").toLong() > 0L)
        assertTrue(metadata.string("version").isNotBlank())
    }

    private fun metadataVersion(metadata: JsonObject): String {
        val version = metadata.string("version")
        assertTrue("Metadata version must not be blank", version.isNotBlank())
        return version
    }

    private fun assertSha256Version(value: String, label: String) {
        assertTrue(
            "$label must use the real 64-hex version shape, got '$value'",
            value.matches(Regex("[0-9a-f]{64}")),
        )
    }

    /**
     * A shape-valid 64-hex value that is guaranteed different from [value]. This is deliberately
     * not a "stale-version-000" placeholder: it exercises the production version comparison, not
     * schema/parameter rejection.
     */
    private fun differentSha256Version(value: String): String {
        assertSha256Version(value, "current version")
        val first = if (value[0] == '0') '1' else '0'
        return first + value.substring(1)
    }

    private fun assertApprovalBinding(db: SqlConnection, invocationId: String) {
        val rows = db.query(
            "SELECT binding_json FROM root_approval_bindings WHERE invocation_id=?",
            listOf(invocationId),
        )
        assertEquals("Expected one real approval binding for $invocationId", 1L, rows.size.toLong())
        val binding = rows.single().string("binding_json")
        assertTrue("Approval binding must be persisted", binding.isNotBlank())
        assertTrue(
            "Approval binding must name the original model call id",
            binding.contains("\"callId\":\"$invocationId\""),
        )
    }

    private fun assertReceipt(db: SqlConnection, invocationId: String, expectSuccess: Boolean) {
        val rows = db.query(
            "SELECT stage,outcome,closed FROM root_dispatch_receipts WHERE invocation_id=?",
            listOf(invocationId),
        )
        assertEquals("Expected one dispatch receipt for $invocationId", 1L, rows.size.toLong())
        val receipt = rows.single()
        assertEquals("TERMINAL", receipt.string("stage"))
        assertEquals(1L, receipt.long("closed"))
        if (expectSuccess) {
            assertEquals("SUCCEEDED", receipt.string("outcome"))
        } else {
            assertNotEquals("SUCCEEDED", receipt.string("outcome"))
        }
    }

    private fun valueObject(result: ToolResult): JsonObject {
        val value = result as? ToolResult.Value ?: error("Expected ToolResult.Value, got $result")
        return Json.parseToJsonElement(value.json).jsonObject
    }

    private fun JsonObject.string(key: String): String =
        requireNotNull(this[key]) { "Missing JSON field '$key'" }.jsonPrimitive.content

    private fun writeArgs(
        workspaceId: String,
        relativePath: String,
        text: String,
        expectedVersion: String?,
    ): String = buildJsonObject {
        put("workspace_id", workspaceId)
        put("relative_path", relativePath)
        put("text", text)
        put("replace", true)
        expectedVersion?.let { put("expected_version", it) }
    }.toString()

    private fun patchArgs(
        workspaceId: String,
        relativePath: String,
        search: String,
        replace: String,
        expectedVersion: String,
    ): String = buildJsonObject {
        put("workspace_id", workspaceId)
        put("relative_path", relativePath)
        put("expected_version", expectedVersion)
        put("format", "search_replace")
        put("patch", buildJsonObject {
            put("search", search)
            put("replace", replace)
        }.toString())
    }.toString()

    private data class Settled(val invocationId: String, val result: ToolResult)

    /**
     * Keeps the device test bounded and rethrows the worker's failure on the instrumentation
     * thread, so a real assertion failure is never converted into a silent "no result".
     */
    private fun <T> withinBudget(timeoutMs: Long, block: () -> T): T {
        val pool = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "root-typed-acceptance").apply { isDaemon = true }
        }
        val future = pool.submit(Callable { block() })
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (failure: ExecutionException) {
            when (val cause = failure.cause ?: failure) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw RuntimeException(cause)
            }
        } catch (timeout: TimeoutException) {
            future.cancel(true)
            throw AssertionError("Root typed acceptance exceeded its ${timeoutMs}ms budget")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AssertionError("Root typed acceptance was interrupted")
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val ARG_FIXTURE_DIR = "rootTypedFixtureDir"
        const val ARG_FIXTURE_FILE = "rootTypedFixtureFile"
        const val ARG_SEED_TEXT = "rootTypedSeedText"
        const val DEFAULT_SEED_NAME = "payload.txt"
        const val DEFAULT_SEED_TEXT = "TYPED_ROOT_SEED_V1"
        const val UNCONDITIONAL_TEXT = "ROOT_UNCONDITIONAL_V2"
        const val CONDITIONAL_TEXT = "ROOT_CONDITIONAL_V3"
        const val PATCHED_TEXT = "ROOT_PATCHED_V4"
        const val MUST_NOT_APPLY = "MUST_NOT_APPLY"
        const val OVERALL_BUDGET_MS = 300_000L
        const val ACQUISITION_BUDGET_MS = 120_000L
        var sequence = 0
    }
}
