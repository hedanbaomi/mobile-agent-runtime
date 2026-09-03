// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.skills.tooling.WorkspaceListingWarningCode

@RunWith(AndroidJUnit4::class)
class ShizukuAuthorityBridgeTest {
    @Test
    fun productionAdapterPreservesAndValidatesListingWarnings() {
        val bridge = ShizukuAuthorityBridge(ApplicationProvider.getApplicationContext<Context>())
        try {
            val backend = ShizukuWorkspaceBackendAdapter(bridge)
            val payload = JSONObject()
                .put("path", "")
                .put("entries", JSONArray())
                .put("truncated", false)
                .put("nextCursor", JSONObject.NULL)
                .put("skippedEntries", 1)
                .put(
                    "warnings",
                    JSONArray().put(
                        JSONObject()
                            .put("code", WorkspaceListingWarningCode.SYMLINK_SKIPPED.name)
                            .put("count", 1),
                    ),
                )

            val listing = backend.parseListPayload(payload, "", 16)
            assertEquals(1, listing?.skippedEntries)
            assertEquals(WorkspaceListingWarningCode.SYMLINK_SKIPPED, listing?.warnings?.single()?.code)
            assertNull(backend.parseListPayload(JSONObject(payload.toString()).put("skippedEntries", 2), "", 16))
            assertNull(
                backend.parseListPayload(
                    JSONObject(payload.toString())
                        .put("truncated", true)
                        .put("nextCursor", "cursor\nwith-path-like-lines"),
                    "",
                    16,
                ),
            )
        } finally {
            bridge.close()
        }
    }

    @Test
    fun notReadyBridgeExposesNoToolsAndNeverDispatchesImplicitly() {
        val bridge = ShizukuAuthorityBridge(ApplicationProvider.getApplicationContext<Context>())
        try {
            val workspaceBackend = ShizukuBackendFactory.createWorkspaceBackend(bridge)
            assertTrue(workspaceBackend.capabilities.contains(CapabilityId(CapabilityId.FILE_STAT)))
            assertTrue(workspaceBackend.capabilities.contains(CapabilityId(CapabilityId.FILE_MOVE)))
            val snapshot = AgentSnapshot(
                id = "shizuku-test-snapshot",
                schemaVersion = 11,
                agentId = "shizuku-test-agent",
                promptRevisionId = "prompt",
                chatModelId = "model",
                providerRevision = 1,
                knowledgeBaseIds = emptyList(),
                skillIds = emptyList(),
                createdAt = "2026-08-30T00:00:00Z",
            )
            val executor = bridge.createToolExecutor(snapshot, { true }, { true })
            val state = bridge.refresh()
            if (!state.ready) {
                assertTrue(executor.specs.isEmpty())
            } else {
                // A real installed Shizuku service may be present on a dedicated test device;
                // even then only the five typed workspace tools may be discoverable.
                assertTrue(executor.specs.all { it.name.startsWith("shizuku_workspace_") })
            }
        } finally {
            bridge.close()
        }
    }
}
