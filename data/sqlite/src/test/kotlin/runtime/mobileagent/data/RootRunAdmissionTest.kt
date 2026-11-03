// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

class RootRunAdmissionTest {
    private val fixture = RootApprovalFixture()
    private fun newRun() = RunRecord("run-new", "snapshot-root", "conversation-root", createdAt = fixture.now,
        manifestJson = """{"providerRevision":17,"retrievalScope":{"searched":["kb-1"]},"futureField":[true,null]}""")
    private fun stamp(manifest: String) = Json.parseToJsonElement(manifest).jsonObject["rootExecutionStamp"]
    private fun replaceStamp(manifest: String, value: JsonElement?) =
        JsonObject(Json.parseToJsonElement(manifest).jsonObject.toMutableMap().apply {
            if (value == null) remove("rootExecutionStamp") else put("rootExecutionStamp", value)
        }).toString()

    @Test fun admissionCreatesOnlyANewRunAndPreservesOtherManifestFields() {
        JdbcSqlConnection().use { db ->
            fixture.setup(db)
            val input = newRun()
            val run = RootRunAdmission(db).create(input, fixture.execution())
            assertEquals(run, RunRepository(db).get(run.runId))
            assertEquals(fixture.execution(), Json.decodeFromJsonElement<RootExecutionStamp>(stamp(run.manifestJson)!!))
            assertEquals(Json.parseToJsonElement(input.manifestJson),
                JsonObject(Json.parseToJsonElement(run.manifestJson).jsonObject - "rootExecutionStamp"))
            assertThrows(Exception::class.java) { RootRunAdmission(db).create(input, fixture.execution()) }
            assertEquals(run, RunRepository(db).get(run.runId))
        }
    }

    @Test fun admissionCannotUpgradeAnExistingUnstampedRun() {
        JdbcSqlConnection().use { db ->
            fixture.setup(db)
            val ordinary = RunRepository(db).create(newRun())
            assertThrows(Exception::class.java) { RootRunAdmission(db).create(ordinary, fixture.execution()) }
            assertEquals(ordinary, RunRepository(db).get(ordinary.runId))
            assertThrows(AppException::class.java) {
                RunRepository(db).save(ordinary.copy(manifestJson = RootRunManifest.freeze(ordinary.manifestJson, fixture.execution())))
            }
            assertEquals(ordinary, RunRepository(db).get(ordinary.runId))
        }
    }

    @Test fun saveCannotRemoveReplaceOrNullTheFrozenStampButCanUpdateOtherManifestFields() {
        JdbcSqlConnection().use { db ->
            fixture.setup(db)
            val runs = RunRepository(db)
            val run = runs.get("run-root")!!
            val original = stamp(run.manifestJson)!!
            val changed = fixture.execution().let { it.copy(identity = it.identity.copy(identityEpoch = 2)) }
            listOf(null, JsonNull, Json.encodeToJsonElement(changed)).forEach { value ->
                assertThrows(AppException::class.java) { runs.save(run.copy(manifestJson = replaceStamp(run.manifestJson, value))) }
                assertEquals(run, runs.get(run.runId))
            }
            val manifest = JsonObject(Json.parseToJsonElement(run.manifestJson).jsonObject +
                ("retrievalScope" to buildJsonObject { put("partial", true) })).toString()
            val updated = runs.save(run.copy(modelRounds = 1, manifestJson = manifest))
            assertEquals(updated, runs.get(run.runId))
            assertEquals(original, stamp(updated.manifestJson))
            assertEquals(true, Json.parseToJsonElement(updated.manifestJson).jsonObject["retrievalScope"]!!.jsonObject["partial"]!!.jsonPrimitive.boolean)
        }
    }

    @Test fun ordinaryRunManifestUpdatesRemainSupportedWithoutAddingRootAdmission() {
        JdbcSqlConnection().use { db ->
            fixture.setup(db)
            val runs = RunRepository(db)
            val run = runs.create(newRun())
            val updated = run.copy(manifestJson = """{"providerRevision":18,"futureField":{"nested":[1,2]}}""")
            assertEquals(updated, runs.save(updated))
            assertEquals(updated, runs.get(run.runId))
            assertNull(stamp(updated.manifestJson))
        }
    }

    @Test fun staleConsentPolicyEpochOrShellRevisionCannotCreateARootRun() {
        val valid = fixture.execution()
        listOf(valid.copy(consentRevision = 2), valid.copy(policyVersion = 2), valid.copy(shellConsentRevision = 2),
            valid.copy(identity = valid.identity.copy(identityEpoch = 2))).forEach { execution ->
            JdbcSqlConnection().use { db ->
                fixture.setup(db)
                assertThrows(IllegalArgumentException::class.java) { RootRunAdmission(db).create(newRun(), execution) }
                assertNull(RunRepository(db).get("run-new"))
            }
        }
    }

    @Test fun admissionRejectsStartedRunsAndCallerSuppliedStampInsteadOfOverwritingThem() {
        JdbcSqlConnection().use { db ->
            fixture.setup(db)
            val admission = RootRunAdmission(db)
            assertThrows(IllegalArgumentException::class.java) {
                admission.create(newRun().copy(state = RunStatus.WAITING_TOOL_APPROVAL), fixture.execution())
            }
            listOf(JsonNull, Json.encodeToJsonElement(fixture.execution())).forEach { value ->
                assertThrows(IllegalArgumentException::class.java) {
                    admission.create(newRun().copy(manifestJson = replaceStamp(newRun().manifestJson, value)), fixture.execution())
                }
            }
            assertNull(RunRepository(db).get("run-new"))
        }
    }

    @Test fun ignoredAdmissionInsertCannotReturnAnApparentlyFrozenRun() {
        JdbcSqlConnection().use { db ->
            fixture.setup(db)
            db.execute("CREATE TRIGGER ignore_new_run BEFORE INSERT ON runs BEGIN SELECT RAISE(IGNORE); END")
            assertThrows(IllegalStateException::class.java) { RootRunAdmission(db).create(newRun(), fixture.execution()) }
            assertNull(RunRepository(db).get("run-new"))
        }
    }

    @Test fun newEpochCanAdmitANewRunWithTheExistingPersistentGrant() {
        JdbcSqlConnection().use { db ->
            fixture.setup(db)
            val roots = RootAuthorityRepository(db)
            val state = roots.advanceIdentityEpoch(Authority.ROOT_DIRECT, roots.get(Authority.ROOT_DIRECT).revision)!!
            val old = fixture.execution()
            val execution = old.copy(identity = old.identity.copy(identityEpoch = state.identityEpoch,
                serviceInstanceId = "service-instance-002", sessionId = "session-instance-002"))
            val admitted = RootRunAdmission(db).create(newRun(), execution)
            val runs = RunRepository(db)
            runs.save(admitted.copy(state = RunStatus.WAITING_TOOL_APPROVAL))
            runs.addInvocation(ToolInvocation("invocation-new", admitted.runId, "call-new", "file_read_text", createdAt = fixture.now))
            val request = fixture.request().copy(runId = admitted.runId, invocationId = "invocation-new", execution = execution)
            val approvals = RootApprovalRepository(db, clock = { fixture.now })
            val approved = approvals.approve(approvals.createPending(request).approval.approvalId)
            val dispatch = RootDispatchRepository(db) { fixture.now }
            val permit = dispatch.claim(approved.approval.approvalId, request)
            assertEquals(RootDispatchStage.CLAIMED, dispatch.getReceipt(permit.requestId)!!.stage)
            assertEquals(old, Json.decodeFromJsonElement<RootExecutionStamp>(stamp(runs.get("run-root")!!.manifestJson)!!))
        }
    }
}
