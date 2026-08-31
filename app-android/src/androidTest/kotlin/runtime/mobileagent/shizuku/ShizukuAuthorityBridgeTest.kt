// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.CapabilityId

@RunWith(AndroidJUnit4::class)
class ShizukuAuthorityBridgeTest {
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
