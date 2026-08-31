// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkspaceAppToolsTest {
    @Test
    fun rejectsTraversalAndAbsolutePathsWithoutCreatingWorkspace() = runBlocking {
        val fixture = Fixture()
        try {
            val traversal = fixture.tools.invoke(call("traversal", "{\"path\":\"../escape.txt\",\"text\":\"x\"}"))
            val absolute = fixture.tools.invoke(call("absolute", "{\"path\":\"/escape.txt\",\"text\":\"x\"}"))
            val windows = fixture.tools.invoke(call("windows", "{\"path\":\"C:/escape.txt\",\"text\":\"x\"}"))

            assertTrue(traversal is ToolResult.Invalid)
            assertTrue(absolute is ToolResult.Invalid)
            assertTrue(windows is ToolResult.Invalid)
            assertFalse(fixture.workspace.toFile().exists())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun writeRequiresApprovalAndEachCallIdIsOneTime() = runBlocking {
        val fixture = Fixture()
        try {
            val first = fixture.tools.invoke(call("same", "{\"path\":\"note.txt\",\"text\":\"hello\"}"))
            assertEquals(ToolResult.NeedsApproval, first)
            assertFalse(fixture.workspace.toFile().exists())

            val duplicate = fixture.tools.invoke(call("same", "{\"path\":\"note.txt\",\"text\":\"hello\"}"))
            assertTrue(duplicate is ToolResult.Invalid)
            assertFalse(fixture.workspace.resolve("note.txt").toFile().exists())

            assertTrue(fixture.tools.approve("same") is ToolResult.Value)
            assertEquals("hello", fixture.workspace.resolve("note.txt").toFile().readText())
            assertTrue(fixture.tools.approve("same") is ToolResult.Invalid)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun atomicWritePublishesCompleteUtf8AndLeavesNoTemporaryFile() = runBlocking {
        val fixture = Fixture()
        try {
            assertTrue(fixture.tools.invoke(call("write-1", "{\"path\":\"data.txt\",\"text\":\"第一版\"}")) is ToolResult.NeedsApproval)
            assertTrue(fixture.tools.approve("write-1") is ToolResult.Value)
            assertTrue(fixture.tools.invoke(call("write-2", "{\"path\":\"data.txt\",\"text\":\"第二版\"}")) is ToolResult.NeedsApproval)
            assertTrue(fixture.tools.approve("write-2") is ToolResult.Value)

            assertEquals("第二版", fixture.workspace.resolve("data.txt").toFile().readText(Charsets.UTF_8))
            assertTrue(fixture.workspace.toFile().walkTopDown().none { it.name.startsWith(".mar-write-") })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun enforcesFileSizeBeforeApprovalAndRejectsSymlink(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val oversized = "x".repeat(256 * 1024 + 1)
            val tooLarge = fixture.tools.invoke(call("too-large", "{\"path\":\"large.txt\",\"text\":${jsonString(oversized)}}"))
            assertTrue(tooLarge is ToolResult.Invalid)
            assertFalse(fixture.workspace.resolve("large.txt").toFile().exists())

            assertTrue(fixture.tools.invoke(call("seed", "{\"path\":\"seed.txt\",\"text\":\"private\"}")) is ToolResult.NeedsApproval)
            assertTrue(fixture.tools.approve("seed") is ToolResult.Value)
            val outside = File(fixture.root.parentFile, "outside-${UUID.randomUUID()}.txt").apply { writeText("outside") }
            val link = fixture.workspace.resolve("link").toFile()
            try {
                Files.createSymbolicLink(link.toPath(), outside.toPath())
            } catch (error: Exception) {
                outside.delete()
                throw AssertionError("The Android app-private test filesystem must support symlink rejection", error)
            }
            val result = fixture.tools.invoke(call("symlink", "{\"path\":\"link\",\"maxBytes\":100}", "workspace_read"))
            assertEquals(ToolResult.NeedsApproval, result)
            val approved = fixture.tools.approve("symlink")
            assertTrue(approved is ToolResult.Invalid)
            assertFalse(approved.toString().contains(outside.absolutePath))
            outside.delete()
            Unit
        } finally {
            fixture.close()
        }
    }

    @Test
    fun deletesOnlyFilesAndEmptyDirectories() = runBlocking {
        val fixture = Fixture()
        try {
            assertTrue(fixture.tools.invoke(call("file-seed", "{\"path\":\"delete.txt\",\"text\":\"remove\"}")) is ToolResult.NeedsApproval)
            assertTrue(fixture.tools.approve("file-seed") is ToolResult.Value)
            assertTrue(fixture.tools.invoke(call("delete-file", "{\"path\":\"delete.txt\"}", "workspace_delete")) is ToolResult.NeedsApproval)
            assertTrue(fixture.workspace.resolve("delete.txt").toFile().exists())
            val deletedFile = fixture.tools.approve("delete-file")
            assertTrue(deletedFile is ToolResult.Value)
            assertFalse(fixture.workspace.resolve("delete.txt").toFile().exists())
            assertTrue((deletedFile as ToolResult.Value).json.contains("\"deleted\":true"))

            assertTrue(fixture.tools.invoke(call("empty-dir", "{\"path\":\"empty\"}", "workspace_create_directory")) is ToolResult.NeedsApproval)
            assertTrue(fixture.tools.approve("empty-dir") is ToolResult.Value)
            assertTrue(fixture.tools.invoke(call("delete-dir", "{\"path\":\"empty\"}", "workspace_delete")) is ToolResult.NeedsApproval)
            assertTrue(fixture.tools.approve("delete-dir") is ToolResult.Value)
            assertFalse(fixture.workspace.resolve("empty").toFile().exists())

            assertTrue(fixture.tools.invoke(call("nonempty-dir", "{\"path\":\"nonempty\"}", "workspace_create_directory")) is ToolResult.NeedsApproval)
            assertTrue(fixture.tools.approve("nonempty-dir") is ToolResult.Value)
            assertTrue(fixture.tools.invoke(call("nested-seed", "{\"path\":\"nonempty/child.txt\",\"text\":\"keep\"}")) is ToolResult.NeedsApproval)
            assertTrue(fixture.tools.approve("nested-seed") is ToolResult.Value)
            assertTrue(fixture.tools.invoke(call("reject-nonempty", "{\"path\":\"nonempty\"}", "workspace_delete")) is ToolResult.NeedsApproval)
            assertTrue(fixture.tools.approve("reject-nonempty") is ToolResult.Invalid)
            assertTrue(fixture.workspace.resolve("nonempty/child.txt").toFile().exists())

            assertTrue(fixture.tools.invoke(call("reject-root", "{\"path\":\"\"}", "workspace_delete")) is ToolResult.Invalid)
            assertTrue(fixture.workspace.toFile().exists())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun revokedSnapshotOrAgentFailsClosedAtApproval() = runBlocking {
        val fixture = Fixture()
        try {
            assertTrue(fixture.tools.invoke(call("revoked-snapshot", "{\"path\":\"snapshot.txt\",\"text\":\"x\"}")) is ToolResult.NeedsApproval)
            fixture.snapshotPresent = false
            assertTrue(fixture.tools.approve("revoked-snapshot") is ToolResult.Denied)
            assertFalse(fixture.workspace.resolve("snapshot.txt").toFile().exists())

            assertTrue(fixture.tools.invoke(call("revoked-agent", "{\"path\":\"agent.txt\",\"text\":\"x\"}")) is ToolResult.Denied)
            fixture.snapshotPresent = true
            fixture.agentPresent = false
            assertTrue(fixture.tools.invoke(call("revoked-agent-2", "{\"path\":\"agent.txt\",\"text\":\"x\"}")) is ToolResult.Denied)
            assertFalse(fixture.workspace.resolve("agent.txt").toFile().exists())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun readsAndListsAreAlsoApprovalGated() = runBlocking {
        val fixture = Fixture()
        try {
            assertTrue(fixture.tools.invoke(call("seed", "{\"path\":\"readme.md\",\"text\":\"内容\"}")) is ToolResult.NeedsApproval)
            assertTrue(fixture.tools.approve("seed") is ToolResult.Value)

            assertEquals(ToolResult.NeedsApproval, fixture.tools.invoke(call("read", "{\"path\":\"readme.md\"}", "workspace_read")))
            val read = fixture.tools.approve("read")
            assertTrue(read is ToolResult.Value)
            assertTrue((read as ToolResult.Value).json.contains("内容"))

            assertEquals(ToolResult.NeedsApproval, fixture.tools.invoke(call("list", "{\"path\":\"\"}", "workspace_list")))
            val list = fixture.tools.approve("list")
            assertTrue(list is ToolResult.Value)
            assertTrue((list as ToolResult.Value).json.contains("readme.md"))
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        val root: File
        val workspace: java.nio.file.Path
        var snapshotPresent = true
        var agentPresent = true
        val tools: WorkspaceAppTools

        init {
            val context = ApplicationProvider.getApplicationContext<Context>()
            root = File(context.cacheDir, "workspace-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
            val snapshot = SNAPSHOT
            workspace = File(root, "agent-workspaces/${WorkspaceAppTools.workspaceNamespace(snapshot)}").toPath()
            tools = WorkspaceAppTools(root, snapshot, { snapshotPresent }, { agentPresent })
        }

        fun close() {
            root.deleteRecursively()
        }
    }

    private companion object {
        val SNAPSHOT = AgentSnapshot(
            id = "snapshot-test",
            schemaVersion = 11,
            agentId = "agent-test",
            promptRevisionId = "prompt-test",
            chatModelId = "model-test",
            providerRevision = 1,
            knowledgeBaseIds = emptyList(),
            skillIds = emptyList(),
            createdAt = "2026-08-30T00:00:00Z",
        )

        fun call(id: String, args: String, name: String = "workspace_write") = ToolCall(id, name, args)

        fun jsonString(value: String): String =
            kotlinx.serialization.json.JsonPrimitive(value).toString()
    }
}
