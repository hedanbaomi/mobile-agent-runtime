// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.jvm.functions.Function1
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.WorkspaceApplyPatchRequest
import runtime.mobileagent.skills.tooling.WorkspaceResult
import runtime.mobileagent.workspace.WorkspaceVersionProjection

@RunWith(AndroidJUnit4::class)
class ShizukuVersionProjectionTest {
    @Test
    fun highBitOpaqueVersionsUseTheSharedProjectionInListingAndDeviceStat() {
        val bridge = ShizukuAuthorityBridge(ApplicationProvider.getApplicationContext<Context>())
        try {
            val raw = highBitVersion()
            val expected = WorkspaceVersionProjection.toPublic(raw)
            assertEquals(Long.MAX_VALUE, expected)

            val adapter = ShizukuWorkspaceBackendAdapter(bridge)
            val listing = adapter.parseListPayload(
                JSONObject()
                    .put("path", "")
                    .put(
                        "entries",
                        JSONArray().put(
                            JSONObject()
                                .put("path", "note.txt")
                                .put("type", "file")
                                .put("bytes", 3)
                                .put("version", raw.uppercase()),
                        ),
                    )
                    .put("truncated", false)
                    .put("skippedEntries", 0),
                "",
                16,
            )
            val listedVersion = listing?.entries?.single()?.version
            assertEquals(expected, listedVersion)
            val parsed = invokePrivate(adapter, "parseOpaqueVersion", raw.uppercase())
            val opaque = parsed?.javaClass?.getDeclaredField("opaqueVersion")?.also { it.isAccessible = true }?.get(parsed)
            assertEquals(raw, opaque)
            val patchRequest = WorkspaceApplyPatchRequest(
                workspaceId = ShizukuWorkspaceBackendAdapter.DEFAULT_WORKSPACE_ID,
                relativePath = "note.txt",
                patch = "replacement",
                expectedVersion = listedVersion ?: error("listing version missing"),
            )
            assertEquals(expected, patchRequest.expectedVersion)

            val tokenBackend = newTokenBackend(bridge)
            val stat = invokePrivate(
                tokenBackend,
                "parseStat",
                ShizukuDispatchResult.Success(
                    JSONObject()
                        .put("ok", true)
                        .put("operation", "stat")
                        .put("path", "note.txt")
                        .put("type", "file")
                        .put("bytes", 3)
                        .put("version", raw.uppercase())
                        .toString(),
                ),
                "note.txt",
            )
            assertEquals(expected, successVersion(stat))

            val patch = invokePrivate(
                tokenBackend,
                "parsePatchPayload",
                JSONObject()
                    .put("ok", true)
                    .put("operation", "apply_patch")
                    .put("path", "note.txt")
                    .put("type", "file")
                    .put("bytes", 3)
                    .put("created", false)
                    .put("version", raw.uppercase()),
                "note.txt",
            )
            assertEquals(expected, successVersion(patch))
        } finally {
            bridge.close()
        }
    }

    @Test
    fun successfulMutationWithUnusableVersionIsUnknownButReadMappingFailureIsProtocolError() {
        val bridge = ShizukuAuthorityBridge(ApplicationProvider.getApplicationContext<Context>())
        try {
            val tokenBackend = newTokenBackend(bridge)
            val badPatchPayload = JSONObject()
                .put("ok", true)
                .put("operation", "apply_patch")
                .put("path", "note.txt")
                .put("type", "file")
                .put("bytes", 3)
                .put("created", false)
                .put("version", "not-a-version")
            val patch = invokePrivate(tokenBackend, "parsePatchPayload", badPatchPayload, "note.txt")
            assertEquals(ToolErrorCode.UNKNOWN_OUTCOME, errorCode(patch))

            val badStatPayload = JSONObject()
                .put("ok", true)
                .put("operation", "stat")
                .put("path", "note.txt")
                .put("type", "file")
                .put("bytes", 3)
                .put("version", "not-a-version")
            val stat = invokePrivate(
                tokenBackend,
                "parseStat",
                ShizukuDispatchResult.Success(badStatPayload.toString()),
                "note.txt",
            )
            assertEquals(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH, errorCode(stat))

            val typedConflict = invokePrivate(
                tokenBackend,
                "dispatchFailure",
                ShizukuDispatchResult.Success(
                    JSONObject()
                        .put("ok", false)
                        .put("operation", "apply_patch")
                        .put("code", ShizukuWorkspaceFileStore.CONFLICT)
                        .toString(),
                ),
                true,
                "apply_patch",
            )
            assertEquals(ToolErrorCode.CONFLICT, errorCode(typedConflict))

            val adapter = ShizukuWorkspaceBackendAdapter(bridge)
            assertEquals(
                ToolErrorCode.UNKNOWN_OUTCOME,
                errorCode(invokeDispatchJson(adapter, "apply_patch", null)),
            )
            assertEquals(
                ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH,
                errorCode(invokeDispatchJson(adapter, "stat", null)),
            )
            assertEquals(
                ToolErrorCode.CONFLICT,
                errorCode(
                    invokeDispatchJson(
                        adapter,
                        "apply_patch",
                        JSONObject()
                            .put("ok", false)
                            .put("operation", "apply_patch")
                            .put("code", ShizukuWorkspaceFileStore.CONFLICT),
                    ),
                ),
            )
        } finally {
            bridge.close()
        }
    }

    private fun highBitVersion(): String = "ffffffffffffffff" + "0".repeat(48)

    @Test
    fun malformedMutationEnvelopeNeverClaimsKnownFailure() {
        val bridge = ShizukuAuthorityBridge(ApplicationProvider.getApplicationContext<Context>())
        try {
            val adapter = ShizukuWorkspaceBackendAdapter(bridge)
            val tokenBackend = newTokenBackend(bridge)
            val malformed = listOf(
                JSONObject(),
                JSONObject().put("ok", "true"),
                JSONObject().put("ok", "false").put("code", ShizukuWorkspaceFileStore.CONFLICT),
                JSONObject().put("ok", 0).put("code", ShizukuWorkspaceFileStore.CONFLICT),
                JSONObject().put("ok", false),
                JSONObject().put("ok", false).put("code", 42),
                JSONObject().put("ok", false).put("code", "UNKNOWN_TEST_CODE"),
            )
            malformed.forEach { response ->
                response.put("operation", "apply_patch")
                assertEquals(
                    ToolErrorCode.UNKNOWN_OUTCOME,
                    errorCode(invokeDispatchJson(adapter, "apply_patch", response)),
                )
                val dispatch = ShizukuDispatchResult.Success(response.toString())
                assertNull(invokePrivate(tokenBackend, "payload", dispatch, "apply_patch"))
                assertEquals(
                    ToolErrorCode.UNKNOWN_OUTCOME,
                    errorCode(invokePrivate(tokenBackend, "dispatchFailure", dispatch, true, "apply_patch")),
                )
            }
        } finally {
            bridge.close()
        }
    }

    private fun newTokenBackend(bridge: ShizukuAuthorityBridge): Any {
        val clazz = Class.forName("runtime.mobileagent.shizuku.ShizukuTokenWorkspaceBackend")
        val constructor = clazz.declaredConstructors.single().also { it.isAccessible = true }
        return constructor.newInstance(
            bridge,
            "test-handle",
            "test-workspace",
            "Test workspace",
            WorkspaceScope.SELECTED_DIRECTORY,
            Any(),
        )
    }

    private fun invokePrivate(target: Any, name: String, vararg arguments: Any?): Any? {
        val method = target.javaClass.declaredMethods.first { it.name == name }.also { it.isAccessible = true }
        return method.invoke(target, *arguments)
    }

    private fun invokeDispatchJson(
        adapter: ShizukuWorkspaceBackendAdapter,
        operation: String,
        payload: JSONObject?,
    ): Any? {
        val method = ShizukuWorkspaceBackendAdapter::class.java.declaredMethods
            .first { it.name == "dispatchJson" }
            .also { it.isAccessible = true }
        val response = payload ?: JSONObject()
            .put("ok", true)
            .put("operation", operation)
        val decoder = object : Function1<JSONObject, Any?> {
            override fun invoke(value: JSONObject): Any? = null
        }
        return method.invoke(adapter, operation, ShizukuDispatchResult.Success(response.toString()), decoder)
    }

    private fun successVersion(result: Any?): Long? =
        (result as? WorkspaceResult.Success<*>)?.value?.let { value ->
            value.javaClass.getDeclaredField("version").also { it.isAccessible = true }.get(value) as Long?
        }

    private fun errorCode(result: Any?): ToolErrorCode {
        val failure = result as? WorkspaceResult.Failure
        assertNotNull(failure)
        return failure!!.error.code
    }
}
