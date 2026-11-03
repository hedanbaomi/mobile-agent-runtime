// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*
import runtime.mobileagent.serialization.TransferCodec
import runtime.mobileagent.serialization.TransferOptions

/** Exercises the production portable export/import boundary, using synthetic local Root facts. */
class RootTransferIsolationTest {
    private val fixture = RootApprovalFixture()
    private fun createSource(db: SqlConnection): RootApprovalBinding {
        Migrations.apply(db)
        val profiles = ProfileRepository(db)
        profiles.createProvider(ProviderProfile("provider-exported","Fixture",ApiFormat.OPENAI_COMPATIBLE,
            "https://example.invalid/v1",secretRef="synthetic-source-ref",revision=1))
        profiles.createModel(ModelProfile("model-exported","provider-exported",ModelRole.CHAT,"synthetic",
            capabilities=emptySet(),contextLimit=4096,outputLimit=512,revision=1))
        val agents = AgentRepository(db)
        agents.saveWithPrompt(AgentProfile("agent-exported","Fixture","pending","model-exported",revision=0),
            "Synthetic portable prompt")
        AuthorityPolicyRepository(db).updatePolicy(0,Authority.ROOT_DIRECT,DangerousMode.DISABLED)
        val roots = RootAuthorityRepository(db)
        val consent = roots.confirmRoot(Authority.ROOT_DIRECT,0,1)!!
        roots.advanceIdentityEpoch(Authority.ROOT_DIRECT,consent.revision)
        val execution = fixture.execution()
        val workspaces = RootWorkspaceRepository(db) { "exported-root-workspace" }
        workspaces.createNew("Fixture",true,execution)
        RootWorkspaceLocatorRepository(db).attachNew(RootWorkspaceLocator(
            workspaces.getOrigin("exported-root-workspace")!!,ByteArray(72) { 9 },
        ),execution)
        RootGrantRepository(db) { fixture.now }.create(CapabilityGrant(
            "exported-root-grant","agent-exported",CapabilityId(CapabilityId.FILE_READ_TEXT),
            workspaceId="exported-root-workspace",policyVersion=1,pathScope="private-scope-marker",
        ),execution)
        RootSnapshotGrantRepository(db) { fixture.now }.createNewSnapshot(
            "snapshot-exported-root","agent-exported",setOf("exported-root-grant"),execution,
        ) { agents.createSnapshot("agent-exported","snapshot-exported-root") }
        val conversations = ConversationRepository(db)
        conversations.create("snapshot-exported-root","Fixture history","conversation-exported-root")
        conversations.append("conversation-exported-root",MessageRole.USER,"Synthetic portable history")
        val run = RootRunAdmission(db) { fixture.now }.create(
            RunRecord("run-exported-root", "snapshot-exported-root", "conversation-exported-root",
                budgetJson = runBudgetJson(1,1,1), createdAt = fixture.now,
                manifestJson = "{\"providerRevision\":17}"), execution,
        )
        RunRepository(db).save(run.copy(state = RunStatus.WAITING_TOOL_APPROVAL))
        db.execute("INSERT INTO tool_invocations(invocation_id,run_id,call_id,name,arguments_json,permission_decision,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
            listOf("invocation-exported-root","run-exported-root","model-call","file_read_text","{}",
                "PENDING","PENDING",fixture.now,fixture.now))
        val approvals = RootApprovalRepository(db,clock={fixture.now})
        val approval = approvals.approve(approvals.createPending(RootApprovalRequest(
            "run-exported-root","invocation-exported-root","snapshot-exported-root","exported-root-grant",
            "b".repeat(64),RootReceiptDigests.text("{}"),execution,
        )).approval.approvalId)
        val audit = ToolAuditDetail.builder("audit-exported-root",approval.approval.requestId,"agent-exported",
            approval.capability,"DENIED",fixture.now)
            .authority(Authority.ROOT_DIRECT).workspaceHash(approval.workspaceId)
            .approval(approval.approval.approvalId).dangerousMode(DangerousMode.DISABLED).policyVersion(1).build()
        RootAuditRepository(db).append(audit,approval)
        val dispatch=RootDispatchRepository(db) { fixture.now }
        val permit=dispatch.claim(approval.approval.approvalId,approval.request)
        assertNotNull(dispatch.getReceipt(permit.requestId))
        return approval
    }

    private fun assertNoImportedTrust(db: SqlConnection, approval: RootApprovalBinding) {
        assertNull(RootWorkspaceRepository(db).getOrigin("exported-root-workspace"))
        assertNull(RootWorkspaceLocatorRepository(db).get("exported-root-workspace"))
        assertNull(RootGrantRepository(db).get("exported-root-grant"))
        assertNull(CapabilityGrantRepository(db).get("exported-root-grant"))
        assertTrue(db.query("SELECT snapshot_id FROM root_snapshot_seals WHERE snapshot_id='snapshot-exported-root'").isEmpty())
        assertNull(RootSnapshotGrantRepository(db) { fixture.now }.revalidate(
            "snapshot-exported-root","exported-root-grant",fixture.execution()))
        assertNull(RootApprovalRepository(db,clock={fixture.now})
            .getCurrent(approval.approval.approvalId,approval.request))
        assertNull(RootAuditRepository(db).get("audit-exported-root"))
        assertNull(RootDispatchRepository(db).getReceipt(approval.approval.requestId))
    }

    @Test fun portableAgentConfigurationDoesNotCarryLocalRootAuthorityOrGrants() {
        JdbcSqlConnection().use { source ->
            val approval = createSource(source)
            val generation = RootWorkspaceRepository(source).getOrigin(approval.workspaceId)!!.generationId
            val controller=RootDispatchRepository(source).getReceipt(approval.approval.requestId)!!.claim.controllerId
            val payload = TransferRepository(source).exportAgent("agent-exported")
            assertFalse(payload.contains("app-instance-000001"))
            assertFalse(payload.contains(generation))
            assertFalse(payload.contains(controller))
            assertFalse(payload.contains("private-scope-marker"))
            JdbcSqlConnection().use { target ->
                Migrations.apply(target)
                assertEquals("agent-exported",TransferRepository(target).importBundle(payload).agentId)
                assertFalse(RootAuthorityRepository(target).get(Authority.ROOT_DIRECT).consent.enabled)
                assertNoImportedTrust(target,approval)
                // Even fresh local platform consent cannot revive a source-device grant.
                AuthorityPolicyRepository(target).updatePolicy(0,Authority.ROOT_DIRECT,DangerousMode.DISABLED)
                val roots = RootAuthorityRepository(target)
                val consent = roots.confirmRoot(Authority.ROOT_DIRECT,0,1)!!
                roots.advanceIdentityEpoch(Authority.ROOT_DIRECT,consent.revision)
                assertNoImportedTrust(target,approval)
            }
        }
    }

    @Test fun archiveKeepsHistoryAndTargetRootStateWithoutRestoringSourceTrust() {
        JdbcSqlConnection().use { source ->
            val approval = createSource(source)
            val generation = RootWorkspaceRepository(source).getOrigin(approval.workspaceId)!!.generationId
            val controller=RootDispatchRepository(source).getReceipt(approval.approval.requestId)!!.claim.controllerId
            val out = ByteArrayOutputStream()
            TransferRepository(source).exportArchive("agent-exported",TransferOptions(includeConversations=true),out)
            val bytes = out.toByteArray()
            var entries = 0
            var sawAgentPayload = false
            var sawPortableRunManifest = false
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (zip.nextEntry != null) {
                    entries++
                    val content = zip.readBytes().toString(Charsets.UTF_8)
                    if (content.contains("agent-exported")) sawAgentPayload = true
                    if (content.contains("providerRevision")) sawPortableRunManifest = true
                    assertFalse(content.contains("rootExecutionStamp"))
                    assertFalse(content.contains("service-instance-001"))
                    assertFalse(content.contains("session-instance-001"))
                    assertFalse(content.contains("app-instance-000001"))
                    assertFalse(content.contains(generation))
                    assertFalse(content.contains(controller))
                    assertFalse(content.contains("private-scope-marker"))
                    zip.closeEntry()
                }
            }
            assertTrue(entries >= 2)
            assertTrue(sawAgentPayload)
            assertTrue(sawPortableRunManifest)
            JdbcSqlConnection().use { target ->
                fixture.setup(target)
                val targetApprovals=RootApprovalRepository(target,clock={fixture.now})
                val targetApproval=targetApprovals.approve(targetApprovals.createPending(fixture.request()).approval.approvalId)
                val targetDispatch=RootDispatchRepository(target) { fixture.now }
                val targetPermit=targetDispatch.claim(targetApproval.approval.approvalId,targetApproval.request)
                val existingReceipt=targetDispatch.getReceipt(targetPermit.requestId)
                assertNotNull(existingReceipt)
                val existingRoot = RootAuthorityRepository(target).get(Authority.ROOT_DIRECT)
                val existingGrant = RootGrantRepository(target).get("root-grant")
                val existingSnapshot = RootSnapshotGrantRepository(target) { fixture.now }
                    .revalidate("snapshot-root","root-grant",fixture.execution())
                assertTrue(existingRoot.consent.enabled)
                assertNotNull(existingGrant)
                assertNotNull(existingSnapshot)
                TransferRepository(target).importArchive(bytes)
                assertEquals(existingRoot,RootAuthorityRepository(target).get(Authority.ROOT_DIRECT))
                assertEquals(existingReceipt,targetDispatch.getReceipt(targetPermit.requestId))
                assertEquals(existingGrant,RootGrantRepository(target).get("root-grant"))
                assertEquals(existingSnapshot,RootSnapshotGrantRepository(target) { fixture.now }
                    .revalidate("snapshot-root","root-grant",fixture.execution()))
                assertNoImportedTrust(target,approval)
                assertNotNull(AgentRepository(target).getSnapshot("snapshot-exported-root"))
                assertEquals("{}", RunRepository(target).get("run-exported-root")!!.manifestJson)
                assertEquals("snapshot-exported-root",ConversationRepository(target).get("conversation-exported-root")!!.snapshotId)
                assertEquals("Synthetic portable history",
                    ConversationRepository(target).messages("conversation-exported-root").single().text)
                assertNotNull(AuditRepository(target).get("audit-exported-root"))
                assertEquals("run-exported-root",AuditRepository(target).get("audit-exported-root")!!.runId)
                assertTrue(target.query("PRAGMA foreign_key_check").isEmpty())
            }
        }
    }

    @Test fun rootInvocationObservationAndConversationExportScrubPrivatePathsCommandsAndCanaries() {
        val secretPath = "/data/user/0/com.victim.secret/files/canary.key"
        val secretCmd = "cat /data/user/0/com.victim.secret/files/canary.key"
        val canary = "ROOT_SECRET_CANARY_98765"
        JdbcSqlConnection().use { source ->
            createSource(source)
            val runs = RunRepository(source)
            // Observe runtime invocation on Root-stamped run before dispatch receipt exists: must not persist resultJson
            runs.observeRuntimeInvocation(
                ToolInvocation(
                    invocationId = "invocation-unreceipted-root",
                    runId = "run-exported-root",
                    callId = "call-unreceipted",
                    name = "shell_exec",
                    argumentsJson = """{"command":"$secretCmd","cwd":"$secretPath"}""",
                    permissionDecision = "ALLOWED",
                    state = "COMPLETED",
                    resultJson = """{"stdout":"$canary","cwd":"$secretPath"}""",
                    createdAt = fixture.now,
                    updatedAt = fixture.now,
                ),
            )
            val savedUnreceipted = runs.invocations("run-exported-root").first { it.invocationId == "invocation-unreceipted-root" }
            assertNull(savedUnreceipted.resultJson)

            // Even if legacy/raw rows or message parts contain private Root arguments/results, portable export scrubs them
            source.execute(
                "UPDATE tool_invocations SET arguments_json=?, result_json=?, state=? WHERE invocation_id=?",
                listOf(
                    """{"path":"$secretPath","command":"$secretCmd"}""",
                    """{"content":"$canary"}""",
                    "ROOT_DISPATCH_ATTEMPTING",
                    "invocation-exported-root",
                ),
            )
            val conversations = ConversationRepository(source)
            conversations.appendMessage(
                Message(
                    id = "msg-root-call",
                    conversationId = "conversation-exported-root",
                    role = MessageRole.ASSISTANT,
                    status = "COMPLETED",
                    parts = listOf(
                        ToolCallPart(
                            callId = "model-call",
                            name = "shell_exec",
                            argumentsJson = """{"command":"$secretCmd","cwd":"$secretPath"}""",
                        ),
                    ),
                    createdAt = fixture.now,
                ),
            )
            conversations.appendMessage(
                Message(
                    id = "msg-root-result",
                    conversationId = "conversation-exported-root",
                    role = MessageRole.TOOL,
                    text = """{"stdout":"$canary","path":"$secretPath"}""",
                    status = "COMPLETED",
                    parts = listOf(
                        TextPart("""{"stdout":"$canary","path":"$secretPath"}"""),
                        ToolResultPart(
                            callId = "model-call",
                            resultJson = """{"stdout":"$canary","path":"$secretPath"}""",
                        ),
                    ),
                    createdAt = fixture.now,
                ),
            )
            // Unlinked streaming ToolCallPart in a Root-stamped conversation must also be scrubbed
            conversations.appendMessage(
                Message(
                    id = "msg-root-unlinked-call",
                    conversationId = "conversation-exported-root",
                    role = MessageRole.ASSISTANT,
                    status = "COMPLETED",
                    parts = listOf(
                        ToolCallPart(
                            callId = "unlinked-streaming-call",
                            name = "file_read_text",
                            argumentsJson = """{"relative_path":"$secretPath"}""",
                        ),
                    ),
                    createdAt = fixture.now,
                ),
            )

            val out = ByteArrayOutputStream()
            TransferRepository(source).exportArchive("agent-exported", TransferOptions(includeConversations = true), out)
            val archiveBytes = out.toByteArray()
            val conversationJson = extractConversationJson(archiveBytes)
            assertFalse(conversationJson.contains(secretPath))
            assertFalse(conversationJson.contains(secretCmd))
            assertFalse(conversationJson.contains(canary))
            assertFalse(conversationJson.contains("ROOT_DISPATCH_"))
            assertFalse(conversationJson.contains("rootExecutionStamp"))

            ZipInputStream(archiveBytes.inputStream()).use { zip ->
                while (zip.nextEntry != null) {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    assertFalse(text.contains(secretPath))
                    assertFalse(text.contains(secretCmd))
                    assertFalse(text.contains(canary))
                    assertFalse(text.contains("ROOT_DISPATCH_"))
                    assertFalse(text.contains("rootExecutionStamp"))
                    zip.closeEntry()
                }
            }

            JdbcSqlConnection().use { target ->
                Migrations.apply(target)
                TransferRepository(target).importArchive(archiveBytes)
                val importedInvocations = RunRepository(target).invocations("run-exported-root")
                assertTrue(
                    importedInvocations.all {
                        it.argumentsJson == "{}" && it.resultJson == null && !it.state.startsWith("ROOT_DISPATCH_")
                    },
                )
                assertEquals(
                    "CANCELLED",
                    importedInvocations.first { it.invocationId == "invocation-exported-root" }.state,
                )
            }
        }
    }

    @Test fun transferCodecRejectsTamperedRootExecutionStampAndRootDispatchState() {
        JdbcSqlConnection().use { source ->
            createSource(source)
            val out = ByteArrayOutputStream()
            TransferRepository(source).exportArchive("agent-exported", TransferOptions(includeConversations = true), out)
            val cleanJson = extractConversationJson(out.toByteArray())
            val stampedJson = cleanJson.replace(
                "\"{\\\"providerRevision\\\":17}\"",
                "\"{\\\"rootExecutionStamp\\\":{}}\"",
            )
            assertTrue(stampedJson.contains("rootExecutionStamp"))
            assertThrows(Exception::class.java) {
                TransferCodec.decodeConversation(stampedJson)
            }
            val dispatchStateJson = cleanJson.replace(
                "\"CANCELLED\"",
                "\"ROOT_DISPATCH_CLAIMED\"",
            )
            assertTrue(dispatchStateJson.contains("ROOT_DISPATCH_CLAIMED"))
            assertThrows(Exception::class.java) {
                TransferCodec.decodeConversation(dispatchStateJson)
            }
        }
    }

    private fun extractConversationJson(archiveBytes: ByteArray): String {
        ZipInputStream(archiveBytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val text = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                if (entry.name.startsWith("conversations/") && entry.name.endsWith(".json")) {
                    return text
                }
            }
        }
        error("Missing conversation entry in archive")
    }
}
