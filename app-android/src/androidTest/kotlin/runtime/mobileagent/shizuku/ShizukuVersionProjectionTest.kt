// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
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
import kotlinx.coroutines.runBlocking
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.skills.tooling.WorkspaceWriteTextRequest
import runtime.mobileagent.skills.tooling.WorkspaceCreateDirectoryRequest
import runtime.mobileagent.skills.tooling.WorkspaceMoveRequest
import runtime.mobileagent.skills.tooling.WorkspaceDeleteRequest
import runtime.mobileagent.skills.tooling.WorkspaceStatRequest
import runtime.mobileagent.skills.tooling.WorkspaceReadTextRequest
import runtime.mobileagent.workspace.WorkspaceVersionProjection

@RunWith(AndroidJUnit4::class)
class ShizukuVersionProjectionTest {
    @Test
    fun bothTypedAdaptersCreateUpdateReadAndDeleteDespiteSiblingChanges() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "shizuku-adapter-crud-${UUID.randomUUID()}")
        check(File(root, "Download/MobileAgentRuntime-Shizuku").mkdirs())
        val store = ShizukuWorkspaceFileStore(File(root, "Download/MobileAgentRuntime-Shizuku"))
        val bridge = Proxy.newProxyInstance(DeviceServiceBridge::class.java.classLoader,
            arrayOf(DeviceServiceBridge::class.java)) { _, method, original ->
            val arguments = original.orEmpty().let { if (method.name.startsWith("dispatchWorkspace")) it.drop(1) else it.toList() }
            val name = method.name.replace("dispatchWorkspace", "dispatch")
            if (name == "dispatchReadChunk") {
                val chunk = store.readChunk(arguments[0] as String, arguments[2] as Int, arguments[1] as Long)
                when (chunk) {
                    is ShizukuWorkspaceFileStore.ReadChunkResult.Failure -> ShizukuWorkspaceReadDispatchResult.Success(
                        ShizukuWorkspaceReadResponse.rejected(chunk.code))
                    is ShizukuWorkspaceFileStore.ReadChunkResult.Success -> {
                        val pipe = ParcelFileDescriptor.createPipe()
                        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(chunk.bytes) }
                        val metadata = JSONObject().put("ok", true).put("operation", "read").put("path", chunk.path)
                            .put("bytes", chunk.bytes.size).put("version", chunk.version).put("offsetBytes", chunk.offsetBytes)
                            .put("totalBytes", chunk.totalBytes).put("eof", chunk.eof).toString()
                        ShizukuWorkspaceReadDispatchResult.Success(ShizukuWorkspaceReadResponse.accepted(metadata, pipe[0]))
                    }
                }
            } else {
                val payload = when (name) {
                    "dispatchWrite" -> store.write(arguments[0] as String, arguments[1] as ByteArray, arguments[2] as Boolean)
                    "dispatchMkdir" -> store.mkdir(arguments[0] as String)
                    "dispatchDelete" -> store.delete(arguments[0] as String)
                    "dispatchStat" -> store.stat(arguments[0] as String)
                    "dispatchApplyPatch" -> store.applyPatch(arguments[0] as String, arguments[1] as String,
                        arguments[2] as String, arguments[3] as String)
                    else -> error("Unexpected typed RPC: $name")
                }
                ShizukuDispatchResult.Success(payload)
            }
        } as DeviceServiceBridge
        try {
            listOf(ShizukuWorkspaceBackendAdapter(bridge), newTokenBackend(bridge) as WorkspaceBackend).forEachIndexed { index, backend ->
                val id = backend.descriptor.id
                val a = "a-$index.txt"
                val b = "b-$index.txt"
                val dir = "directory-$index"
                fun success(result: WorkspaceResult<*>) = org.junit.Assert.assertTrue("Typed RPC failed: $result", result is WorkspaceResult.Success)
                success(backend.writeText(WorkspaceWriteTextRequest(id, a, "before")))
                success(backend.writeText(WorkspaceWriteTextRequest(id, b, "keep")))
                val observed = (backend.stat(WorkspaceStatRequest(id, b)) as WorkspaceResult.Success).value.version!!
                success(backend.createDirectory(WorkspaceCreateDirectoryRequest(id, dir)))
                success(backend.writeText(WorkspaceWriteTextRequest(id, a, "after", replace = true)))
                assertEquals("after", (backend.readText(WorkspaceReadTextRequest(id, a, 4096)) as WorkspaceResult.Success).value.text)
                success(backend.delete(WorkspaceDeleteRequest(id, a)))
                success(backend.applyPatch(WorkspaceApplyPatchRequest(id, b, "updated after sibling delete", observed,
                    format = runtime.mobileagent.skills.tooling.WorkspacePatchFormat.REPLACE)))
                assertEquals("updated after sibling delete", (backend.readText(WorkspaceReadTextRequest(id, b, 4096)) as WorkspaceResult.Success).value.text)
                success(backend.delete(WorkspaceDeleteRequest(id, b)))
                success(backend.delete(WorkspaceDeleteRequest(id, dir)))
            }
            assertEquals(0, File(root, "Download/MobileAgentRuntime-Shizuku").listFiles()!!.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ordinaryVersionPreconditionsAreUnsupportedForBothShizukuAdapters() = runBlocking {
        val bridge = ShizukuAuthorityBridge(ApplicationProvider.getApplicationContext<Context>())
        try {
            val adapters = listOf(ShizukuWorkspaceBackendAdapter(bridge), newTokenBackend(bridge) as WorkspaceBackend)
            adapters.forEach { backend ->
                assertEquals(setOf(CapabilityId("file.apply_patch")), backend.expectedVersionCapabilities)
                val id = backend.descriptor.id
                val results = listOf(
                    backend.writeText(WorkspaceWriteTextRequest(id, "note.txt", "body", expectedVersion = 123L)),
                    backend.createDirectory(WorkspaceCreateDirectoryRequest(id, "directory", 123L)),
                    backend.move(WorkspaceMoveRequest(id, "note.txt", "moved.txt", 123L)),
                    backend.delete(WorkspaceDeleteRequest(id, "note.txt", 123L)),
                )
                results.forEach { result ->
                    assertEquals(ToolErrorCode.WORKSPACE_VERSION_UNSUPPORTED, (result as WorkspaceResult.Failure).error.code)
                }
            }
        } finally {
            bridge.close()
        }
    }

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

    private fun newTokenBackend(bridge: DeviceServiceBridge): Any {
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
