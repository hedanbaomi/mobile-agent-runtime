// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.privileged
import android.content.Context
import android.content.pm.ApplicationInfo
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.MobileAgentApp
import runtime.mobileagent.RunCoordinator
import runtime.mobileagent.WorkspacePickerTarget
import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.AuthorityPreferencesRepository
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
import runtime.mobileagent.integration.SqliteAuthorityStateStore
import runtime.mobileagent.integration.WorkspaceAccessResult
import runtime.mobileagent.shizuku.ShizukuAuthorityState
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.ElevatedAuthority
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.tooling.AuthorityManager
import runtime.mobileagent.tooling.ToolExecutionContext
@RunWith(AndroidJUnit4::class)
class ForeignTypedChannelAcceptanceTest {
    @Test
    fun rootDirectUsesSqliteManagerAndDefaultConnectorOnForeignFixture() {
        runChannel(Authority.ROOT_DIRECT, null)
    }
    @Test
    fun shizukuRootUsesRealSdkUid0AndUserServiceHandshakeOnForeignFixture() {
        val facts = requireNotNull(RootShizukuLiveSampler.sample(SHIZUKU_SAMPLE_TIMEOUT_MS)) {
            "bounded fresh IShizukuService sample failed (timeout/unknown); " +
                "check the real UID0 Shizuku server and permission"
        }
        assertTrue("fresh Shizuku binder is not alive", facts.binderAlive)
        assertFalse("Shizuku v11+ is required", facts.preV11)
        assertTrue("fresh IShizukuService permission is not granted", facts.permissionGranted)
        assertEquals("fresh IShizukuService server UID must be 0", 0, facts.serverUid)
        assertTrue(
            "fresh IShizukuService version is unsupported: ${facts.serverVersion}",
            (facts.serverVersion ?: -1) >= RootShizukuBridgePolicy.MIN_SERVER_VERSION,
        )
        // Build the state only from the bounded production sampler facts. This does not rely on
        // the SDK 13.1.5 checkSelfPermission cache, and the real default UserService handshake below
        // still proves serviceUid=0.
        val state = ShizukuAuthorityState(
            installedHint = true,
            binderAlive = facts.binderAlive,
            permissionGranted = facts.permissionGranted,
            apiVersion = facts.serverVersion,
            serverUid = facts.serverUid,
            userServiceAlive = false,
            ready = false,
            errorCode = null,
            preV11 = facts.preV11,
        )
        runChannel(Authority.SHIZUKU_ROOT, state)
    }
    private fun runChannel(authority: Authority, shizukuState: ShizukuAuthorityState?) {
        val context = appContext()
        val app = context as MobileAgentApp
        app.ensureHostInitialized()
        val db = app.container.db
        val fixture = Fixture(context)
        fixture.assertHostCannotOpen()
        fixture.assertFreshChannel(db, authority)
        // Real SQLite-backed AuthorityManager, not the in-memory test store.
        val authorityManager = AuthorityManager(SqliteAuthorityStateStore(AuthorityPolicyRepository(db), AuthorityPreferencesRepository(db)))
        // Formal foreground order: select -> setIntent.
        assertTrue(
            "SQLite AuthorityManager could not select $authority",
            authorityManager.selectAuthority(authority.toElevatedRoot()),
        )
        val coordinator = RootRuntimeCoordinator(context, db)
        var executor: RootToolExecutor? = null
        try {
            withChannelBudget(authority, coordinator) {
            coordinator.setUserIntent(
                authority = authority,
                enabled = true,
                authorityManager = authorityManager,
                dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
                shizukuState = shizukuState,
            )
            if (authority == Authority.ROOT_DIRECT) {
                val observation = coordinator.lastManagerObservation.orEmpty()
                assertTrue(
                    "ROOT_DIRECT must be armed by a real Manager GRANTED observation, got '$observation'",
                    observation.startsWith("GRANTED:"),
                )
            }
            val record = RootAuthorityRepository(db).get(authority)
            assertFalse("Root Shell must remain off", record.consent.shellEnabled)
            assertNull(
                "Root Shell execution stamp must be absent",
                coordinator.currentExecutionStamp(authority, requireShellConsent = true),
            )
            val policy = AuthorityPolicyRepository(db).getPolicy()
            assertEquals(
                "F14: RootConsent must bind the current policyVersion after select+setIntent; " +
                    "a stale binding makes the execution stamp null",
                policy.policyVersion,
                record.consent.policyVersion,
            )
            val execution = requireNotNull(
                coordinator.currentExecutionStamp(authority, requireShellConsent = false),
            ) {
                "The real $authority session produced no execution stamp; " +
                    "check the F14 policyVersion fix and the real connector identity"
            }
            assertEquals(0, execution.identity.serviceUid)
            assertEquals(Process.myUid(), execution.identity.appUid)
            assertEquals(authority, execution.identity.authority)
            assertEquals(PrivilegeTier.ROOT, execution.identity.privilegeTier)
            assertEquals(
                PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION,
                execution.identity.protocolVersion,
            )
            assertTrue(execution.identity.sessionId.isNotBlank())
            assertTrue(coordinator.isAuthorityReadyForDispatch(authority))
            val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
            val agentId = "agent-foreign-$suffix"
            val providerId = "provider-foreign-$suffix"
            val modelId = "model-foreign-$suffix"
            val snapshotId = "snapshot-foreign-$suffix"
            val conversationId = "conversation-foreign-$suffix"
            val runId = "run-foreign-$suffix"
            val profiles = ProfileRepository(db)
            profiles.createProvider(ProviderProfile(providerId, "Foreign Fixture Provider", ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1", revision = 1))
            profiles.createModel(ModelProfile(modelId, providerId, ModelRole.CHAT, "synthetic", emptySet(), contextLimit = 16_384, outputLimit = 512, revision = 1))
            AgentRepository(db).saveWithPrompt(AgentProfile(agentId, "Foreign Fixture Agent", "pending", modelId, revision = 0), "Synthetic prompt")
            val attached = RootWorkspaceUiController(db, coordinator).attach(
                authority = authority,
                path = fixture.directory,
                writable = true,
                metadata = true,
                shell = false,
                target = WorkspacePickerTarget(agentId),
            )
            assertTrue("workspace attach failed: $attached", attached is WorkspaceAccessResult.Success)
            val workspaceId = (attached as WorkspaceAccessResult.Success).workspace.workspaceId
            val workspace = requireNotNull(WorkspaceRepository(db).get(workspaceId))
            val grants = CapabilityGrantRepository(db).forAgent(agentId, includeRevoked = false)
            assertTrue(grants.any { it.capability == CapabilityId(CapabilityId.FILE_METADATA_READ) })
            assertTrue(grants.any { it.capability == CapabilityId(CapabilityId.FILE_READ_TEXT) })
            assertTrue(grants.any { it.capability == CapabilityId(CapabilityId.FILE_WRITE_TEXT) })
            assertFalse(grants.any { it.capability == CapabilityId(CapabilityId.SHELL_EXECUTE) })
            val snapshot = coordinator.sealNewSnapshot(
                authority = authority,
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
                listOf(conversationId, snapshot.id, snapshot.id, "Foreign Fixture", now, now),
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
                "model-foreign-typed",
                conversationId,
                runId,
                "config-foreign-typed",
                execution.policyVersion,
                canonicalGrants = grants,
                snapshotGrantBindings = CapabilityGrantRepository(db).listSnapshotBindings(snapshot.id),
            )
            executor = requireNotNull(
                RootToolExecutorAdmission.create(db, coordinator, executionContext),
            ) { "production Root tool admission refused the stamped Run" }
            val exposed = executor.exposedToolNames
            setOf("file_get_metadata", "file_read_text", "file_write_text", "file_apply_patch")
                .forEach { assertTrue("$it must be exposed", it in exposed) }
            assertFalse("Root Shell must not be exposed", "shell_exec" in exposed)
            val directoryMetaArgs = buildJsonObject {
                put("workspace_id", workspace.id); put("relative_path", "")
            }.toString()
            val fileMetaArgs = buildJsonObject {
                put("workspace_id", workspace.id); put("relative_path", SEED_NAME)
            }.toString()
            val readArgs = buildJsonObject {
                put("workspace_id", workspace.id); put("relative_path", SEED_NAME); put("max_bytes", 4096)
            }.toString()
            val directoryMeta = successJson(
                executor, db, runs, runId, "file_get_metadata", directoryMetaArgs, "directory-meta",
            )
            assertDirectoryMetadata(directoryMeta, fixture.uid)
            val seedMeta = successJson(
                executor, db, runs, runId, "file_get_metadata", fileMetaArgs, "seed-meta",
            )
            assertFileMetadata(seedMeta, fixture.uid, SEED_TEXT.toByteArray(Charsets.UTF_8).size)
            val seedVersion = metadataVersion(seedMeta)
            assertSha256(seedVersion)
            val seedRead = successJson(executor, db, runs, runId, "file_read_text", readArgs, "seed-read")
            assertEquals(SEED_TEXT, seedRead.string("text"))
            assertEquals(seedVersion, seedRead.string("version"))
            assertEquals("true", seedRead.string("eof"))
            val unconditional = successJson(
                executor, db, runs, runId,
                "file_write_text",
                writeArgs(workspace.id, SEED_NAME, UNCONDITIONAL_TEXT, expectedVersion = null),
                "unconditional-write",
            )
            assertFileMetadata(
                unconditional,
                fixture.uid,
                UNCONDITIONAL_TEXT.toByteArray(Charsets.UTF_8).size,
            )
            val unconditionalVersion = metadataVersion(unconditional)
            assertSha256(unconditionalVersion)
            assertNotEquals(seedVersion, unconditionalVersion)
            assertEquals(
                UNCONDITIONAL_TEXT,
                readText(executor, db, runs, runId, readArgs, "unconditional-read"),
            )
            // App-private domain: conditional write must be refused before mutation.
            val conditionalWrite = settle(
                executor, db, runs, runId,
                "file_write_text",
                writeArgs(
                    workspace.id,
                    SEED_NAME,
                    MUST_NOT_APPLY,
                    expectedVersion = unconditionalVersion,
                ),
                "app-private-conditional-write",
            )
            assertUnsupported(db, conditionalWrite)
            val afterWrite = successJson(
                executor, db, runs, runId, "file_read_text", readArgs, "after-conditional-write",
            )
            assertEquals(UNCONDITIONAL_TEXT, afterWrite.string("text"))
            assertEquals(unconditionalVersion, afterWrite.string("version"))
            // App-private domain: conditional patch must be refused before mutation.
            val conditionalPatch = settle(
                executor, db, runs, runId,
                "file_apply_patch",
                patchArgs(
                    workspace.id,
                    SEED_NAME,
                    UNCONDITIONAL_TEXT,
                    MUST_NOT_APPLY,
                    unconditionalVersion,
                ),
                "app-private-conditional-patch",
            )
            assertUnsupported(db, conditionalPatch)
            val afterPatch = successJson(
                executor, db, runs, runId, "file_read_text", readArgs, "after-conditional-patch",
            )
            assertEquals(UNCONDITIONAL_TEXT, afterPatch.string("text"))
            assertEquals(unconditionalVersion, afterPatch.string("version"))
            assertTrue(
                "a refused app-private conditional operation must not degrade the session",
                coordinator.isAuthorityReadyForDispatch(authority),
            )
            }
        } finally {
            executor?.let { ex -> closeBounded("executor") { runBlocking { ex.close() } } }
            closeBounded("coordinator") { coordinator.close() }
        }
    }
    private fun withChannelBudget(
        authority: Authority,
        coordinator: RootRuntimeCoordinator,
        block: () -> Unit,
    ) {
        val pool = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "foreign-typed-${authority.name}").apply { isDaemon = true }
        }
        val future = pool.submit(Callable { block() })
        try {
            future.get(CHANNEL_BUDGET_MS, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            future.cancel(true)
            closeBounded("coordinator") { coordinator.close() }
            throw AssertionError(
                "$authority exceeded ${CHANNEL_BUDGET_MS}ms; coordinator.close() was invoked",
                timeout,
            )
        } catch (failure: ExecutionException) {
            when (val cause = failure.cause ?: failure) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw RuntimeException(cause)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            closeBounded("coordinator") { coordinator.close() }
            throw AssertionError("$authority test was interrupted", interrupted)
        } finally {
            pool.shutdownNow()
        }
    }
    private fun closeBounded(label: String, close: () -> Unit) {
        val closer = Thread({ runCatching { close() } }, "foreign-typed-$label").apply {
            isDaemon = true
        }
        closer.start()
        closer.join(CLOSE_BUDGET_MS)
    }
    private fun appContext(): Context = ApplicationProvider.getApplicationContext()
    private fun assertDirectoryMetadata(meta: JsonObject, fixtureUid: Int) {
        assertEquals("directory", meta.string("type"))
        assertEquals(fixtureUid, meta.string("owner_uid").toInt())
        assertEquals("700", meta.string("mode_octal"))
        assertTrue(meta.string("version").isNotBlank())
    }
    private fun assertFileMetadata(meta: JsonObject, fixtureUid: Int, expectedBytes: Int) {
        assertEquals("regular_file", meta.string("type"))
        assertEquals(fixtureUid, meta.string("owner_uid").toInt())
        assertEquals("600", meta.string("mode_octal"))
        assertEquals(expectedBytes.toLong(), meta.string("size_bytes").toLong())
        assertEquals(1L, meta.string("hardlink_count").toLong())
        assertSha256(meta.string("version"))
    }
    private fun assertSha256(value: String) {
        assertTrue("expected a real 64-hex version, got '$value'", value.matches(Regex("[0-9a-f]{64}")))
    }
    private fun metadataVersion(meta: JsonObject): String {
        val version = meta.string("version")
        assertTrue("metadata version is blank", version.isNotBlank())
        return version
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
        val invocationId = "foreign-typed-${sequence++}-$label"
        val call = ToolCall(invocationId, toolName, argumentsJson)
        val first = runBlocking { executor.invoke(call) }
        assertEquals("$label must first request approval, got $first", ToolResult.NeedsApproval, first)
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
    private fun assertUnsupported(db: SqlConnection, settled: Settled) {
        val result = settled.result
        assertTrue("expected a definite failure, got $result", result is ToolResult.Failure)
        assertEquals(
            "app-private conditional operations must map to WORKSPACE_VERSION_UNSUPPORTED",
            ToolErrorCode.WORKSPACE_VERSION_UNSUPPORTED,
            (result as ToolResult.Failure).error.code,
        )
        assertApprovalBinding(db, settled.invocationId)
        assertReceipt(db, settled.invocationId, expectSuccess = false)
    }
    private fun assertApprovalBinding(db: SqlConnection, invocationId: String) {
        val rows = db.query(
            "SELECT binding_json FROM root_approval_bindings WHERE invocation_id=?",
            listOf(invocationId),
        )
        assertEquals("expected one approval binding", 1L, rows.size.toLong())
        assertTrue(rows.single().string("binding_json").contains("\"callId\":\"$invocationId\""))
    }
    private fun assertReceipt(db: SqlConnection, invocationId: String, expectSuccess: Boolean) {
        val rows = db.query(
            "SELECT stage,outcome,closed FROM root_dispatch_receipts WHERE invocation_id=?",
            listOf(invocationId),
        )
        assertEquals("expected one dispatch receipt", 1L, rows.size.toLong())
        val receipt = rows.single()
        assertEquals("TERMINAL", receipt.string("stage"))
        assertEquals(1L, receipt.long("closed"))
        if (expectSuccess) assertEquals("SUCCEEDED", receipt.string("outcome"))
        else assertNotEquals("SUCCEEDED", receipt.string("outcome"))
    }
    private fun readText(
        executor: RootToolExecutor,
        db: SqlConnection,
        runs: RunRepository,
        runId: String,
        readArgs: String,
        label: String,
    ): String = successJson(executor, db, runs, runId, "file_read_text", readArgs, label).string("text")
    private fun valueObject(result: ToolResult): JsonObject {
        val value = result as? ToolResult.Value
            ?: throw AssertionError("expected ToolResult.Value, got $result")
        return Json.parseToJsonElement(value.json).jsonObject
    }
    private fun JsonObject.string(key: String): String =
        requireNotNull(this[key]) { "missing JSON field '$key'" }.jsonPrimitive.content
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
        put("patch", buildJsonObject { put("search", search); put("replace", replace) }.toString())
    }.toString()
    private fun Authority.toElevatedRoot(): ElevatedAuthority = when (this) {
        Authority.ROOT_DIRECT -> ElevatedAuthority.ROOT_DIRECT
        Authority.SHIZUKU_ROOT -> ElevatedAuthority.SHIZUKU_ROOT
        else -> error("not a Root authority: $this")
    }
    private class Fixture(context: Context) {
        private val info: ApplicationInfo? = runCatching {
            context.packageManager.getApplicationInfo(FIXTURE_PACKAGE, 0)
        }.getOrNull()
        private val providedUid: Int? = InstrumentationRegistry.getArguments()
            .getString(ARG_FIXTURE_UID)?.toIntOrNull()
        val uid: Int = when (val observed = info?.uid) {
            null -> providedUid ?: throw AssertionError(
                "fixture $FIXTURE_PACKAGE is not visible via PackageManager (Android 11+ package " +
                    "visibility) or is not installed; add a debug/review androidTest <queries> " +
                    "overlay for the fixture package or pass -e $ARG_FIXTURE_UID <actual uid>; " +
                    "do not add QUERY_ALL_PACKAGES",
            )
            else -> {
                if (providedUid != null) {
                    assertEquals("fixture UID argument differs from PackageManager", observed, providedUid)
                }
                observed
            }
        }
        private val userId: Int = uid / 100_000
        val directory: String = "/data/user/$userId/$FIXTURE_PACKAGE/files/root-acceptance"
        private val payload: String = "$directory/$SEED_NAME"
        init {
            assertTrue("fixture UID must be a real app UID, got $uid", uid > 0)
            assertNotEquals("fixture must not share the host UID", Process.myUid(), uid)
            assertFalse("fixture must not be UID 0", uid == 0)
            val observedFlags = info?.flags
            if (observedFlags != null) {
                assertFalse(
                    "fixture APK must be non-debuggable",
                    (observedFlags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                )
            }
        }
        fun assertHostCannotOpen() {
            assertDenied("stat fixture directory", runCatching { Os.stat(directory) }.exceptionOrNull())
            assertDenied("stat fixture payload", runCatching { Os.stat(payload) }.exceptionOrNull())
            val opened = runCatching { Os.open(payload, OsConstants.O_RDONLY, 0) }
            if (opened.isSuccess) {
                runCatching { Os.close(opened.getOrThrow()) }
                fail("host UID ${Process.myUid()} must not open the fixture payload")
            }
            assertDenied("open fixture payload", opened.exceptionOrNull())
        }
        fun assertFreshChannel(db: SqlConnection, authority: Authority) {
            val record = RootAuthorityRepository(db).get(authority)
            assertFalse("fresh fixture: $authority consent already enabled", record.consent.enabled)
            assertFalse("fresh fixture: $authority shell consent already enabled", record.consent.shellEnabled)
            assertFalse("fresh fixture: $authority is degraded", record.degraded)
            assertEquals("fresh fixture: $authority has an old identity epoch", 0L, record.identityEpoch)
            val receipts = db.query(
                "SELECT COUNT(*) AS n FROM root_dispatch_receipts WHERE authority=?",
                listOf(authority.name),
            ).single().long("n")
            assertEquals("fresh fixture: $authority has old dispatch receipts", 0L, receipts)
        }
        private fun assertDenied(action: String, failure: Throwable?) {
            assertTrue(
                "$action must be denied for host UID ${Process.myUid()}, got ${failure ?: "success"}",
                failure is ErrnoException &&
                    failure.errno in setOf(OsConstants.EACCES, OsConstants.EPERM, OsConstants.ENOENT),
            )
        }
    }
    private data class Settled(
        val invocationId: String,
        val result: ToolResult,
    )
    private companion object {
        const val FIXTURE_PACKAGE = "runtime.mobileagent.fixture.root0700"
        const val SEED_NAME = "payload"
        const val SEED_TEXT = "FD_PROBE_V1"
        const val UNCONDITIONAL_TEXT = "ROOT_FIXTURE_UNCONDITIONAL_OK"
        const val MUST_NOT_APPLY = "MUST_NOT_APPLY"
        const val ARG_FIXTURE_UID = "foreignFixtureUid"
        const val SHIZUKU_SAMPLE_TIMEOUT_MS = 1_500L
        const val CHANNEL_BUDGET_MS = 240_000L
        const val CLOSE_BUDGET_MS = 15_000L
        var sequence = 0
    }
}
