// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.memory

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult

/**
 * Multi-Skill memory identity: the runtime binds each Skill to its own
 * opaque tool namespace (`memory_<opaque>.<operation>`).  Skill A can only
 * address A memory, Skill B only B memory; revoking A leaves B working; the
 * model never supplies the identity (tool names and handles are
 * runtime-issued, and a handle from another namespace is denied).
 */
class MultiSkillMemoryIdentityTest {
    private val allCaps = setOf(
        SKILL_MEMORY_READ_CAPABILITY,
        SKILL_MEMORY_SEARCH_CAPABILITY,
        SKILL_MEMORY_APPEND_CAPABILITY,
        SKILL_MEMORY_REPLACE_CAPABILITY,
    )

    private fun binding(skill: String, revision: Int = 1, enabled: Boolean = true) = SkillMemoryBinding(
        installId = skill,
        packageHash = "hash-$skill",
        memorySpaceId = "default",
        agentId = "agent-1",
        snapshotId = "snapshot-1",
        capabilities = allCaps,
        enabled = enabled,
        grantId = "grant-$skill",
        grantRevision = revision,
    )

    private class FakePort(var live: List<SkillMemoryBinding>) : SkillMemoryRepositoryPort {
        val files = linkedMapOf<String, MutableMap<String, String>>()
        val versions = linkedMapOf<String, MutableMap<String, Int>>()

        private fun namespace(binding: SkillMemoryBinding): String =
            SkillMemoryHandle.namespaceFor(binding.installId, binding.packageHash, binding.memorySpaceId)

        override fun bindings(
            agentId: String,
            snapshotId: String,
            trustedSkillId: String?,
            effectiveCapabilities: Set<String>?,
        ): List<SkillMemoryBinding> = live
            .filter { it.agentId == agentId && it.snapshotId == snapshotId }
            .filter { trustedSkillId == null || it.installId == trustedSkillId }
            .map { if (effectiveCapabilities == null) it else it.copy(capabilities = it.capabilities intersect effectiveCapabilities) }

        override fun bindings(agentId: String, snapshotId: String): List<SkillMemoryBinding> =
            bindings(agentId, snapshotId, null, null)

        override fun current(agentId: String, snapshotId: String, original: SkillMemoryBinding): SkillMemoryBinding? =
            bindings(agentId, snapshotId).singleOrNull { it == original }

        override fun current(
            agentId: String,
            snapshotId: String,
            original: SkillMemoryBinding,
            effectiveCapabilities: Set<String>?,
        ): SkillMemoryBinding? = current(agentId, snapshotId, original)
            ?.let { if (effectiveCapabilities == null) it else it.copy(capabilities = it.capabilities intersect effectiveCapabilities) }

        override fun list(binding: SkillMemoryBinding): SkillMemoryListResult =
            SkillMemoryListResult((files[namespace(binding)] ?: emptyMap()).map { (path, text) ->
                SkillMemoryEntry(path, text.toByteArray().size.toLong(), "v${versions[namespace(binding)]?.get(path) ?: 1}")
            })

        override fun read(binding: SkillMemoryBinding, path: String, maxBytes: Int): SkillMemoryReadResult {
            val text = files[namespace(binding)]?.get(path) ?: throw SkillMemoryException(SkillMemoryFailureCode.NOT_FOUND)
            return SkillMemoryReadResult(path, text, text.toByteArray().size, "v${versions[namespace(binding)]?.get(path) ?: 1}")
        }

        override fun search(binding: SkillMemoryBinding, query: String, maxResults: Int): SkillMemorySearchResult {
            val hits = (files[namespace(binding)] ?: emptyMap())
                .filter { (_, text) -> query in text }
                .map { (path, text) -> SkillMemorySearchHit(path, 1, text.take(80)) }
                .take(maxResults)
            return SkillMemorySearchResult(hits, false)
        }

        override fun append(binding: SkillMemoryBinding, path: String, text: String, expectedVersion: String?): SkillMemoryWriteResult {
            val store = files.getOrPut(namespace(binding)) { linkedMapOf() }
            val vers = versions.getOrPut(namespace(binding)) { linkedMapOf() }
            val current = "v${vers[path] ?: 0}"
            if (expectedVersion != null && expectedVersion != current && store.containsKey(path)) {
                throw SkillMemoryException(SkillMemoryFailureCode.CONFLICT)
            }
            store[path] = (store[path] ?: "") + text
            vers[path] = (vers[path] ?: 0) + 1
            val value = store.getValue(path)
            return SkillMemoryWriteResult(path, value.toByteArray().size, "v${vers.getValue(path)}", vers.getValue(path) == 1 && expectedVersion == null)
        }

        override fun replace(binding: SkillMemoryBinding, path: String, text: String, expectedVersion: String?): SkillMemoryWriteResult {
            val store = files.getOrPut(namespace(binding)) { linkedMapOf() }
            val vers = versions.getOrPut(namespace(binding)) { linkedMapOf() }
            val current = "v${vers[path] ?: 0}"
            if (expectedVersion != null && expectedVersion != current && store.containsKey(path)) {
                throw SkillMemoryException(SkillMemoryFailureCode.CONFLICT)
            }
            val created = !store.containsKey(path)
            store[path] = text
            vers[path] = (vers[path] ?: 0) + 1
            return SkillMemoryWriteResult(path, text.toByteArray().size, "v${vers.getValue(path)}", created)
        }
    }

    private fun executor(port: FakePort, skills: Set<String>) = SkillMemoryToolExecutor(
        repository = port,
        agentId = "agent-1",
        snapshotId = "snapshot-1",
        trustedSkillIds = skills,
    )

    private fun handleFor(skill: String): String = SkillMemoryHandle.forBinding(skill, "hash-$skill", "default")

    private fun toolNames(executor: SkillMemoryToolExecutor): List<String> = executor.specs.map { it.name }

    private fun appendArgs(handle: String, path: String = "MEMORY.md", text: String = "note"): String = buildJsonObject {
        put("memoryHandle", handle)
        put("path", path)
        put("text", text)
    }.toString()

    private fun readArgs(handle: String, path: String = "MEMORY.md"): String = buildJsonObject {
        put("memoryHandle", handle)
        put("path", path)
    }.toString()

    private fun approvedAppend(exec: SkillMemoryToolExecutor, tool: String, args: String, callId: String): ToolResult {
        val pending = runBlocking { exec.invoke(ToolCall(callId, tool, args)) }
        assertTrue(pending is ToolResult.NeedsApproval, "expected NeedsApproval but got $pending")
        return runBlocking { exec.approve(callId) }
    }

    @Test
    fun twoSkillsGetIsolatedNamespacedTools(): Unit = runBlocking {
        val port = FakePort(listOf(binding("skill-a"), binding("skill-b")))
        val exec = executor(port, setOf("skill-a", "skill-b"))
        val names = toolNames(exec)
        // No shared union names remain once two Skills are present.
        assertTrue(names.none { it == SkillMemoryToolExecutor.MEMORY_READ }, names.toString())
        assertEquals(8, names.size)

        val handleA = handleFor("skill-a")
        val handleB = handleFor("skill-b")
        val toolA = exec.specs.single { it.name.endsWith(".append") && handleA in it.parametersJson }.name
        val toolB = exec.specs.single { it.name.endsWith(".append") && handleB in it.parametersJson }.name
        assertTrue(toolA != toolB)

        assertTrue(approvedAppend(exec, toolA, appendArgs(handleA, text = "alpha-note"), "call-a-1") is ToolResult.Value)
        assertTrue(approvedAppend(exec, toolB, appendArgs(handleB, text = "beta-note"), "call-b-1") is ToolResult.Value)

        val readA = exec.specs.single { it.name.endsWith(".read") && handleA in it.parametersJson }.name
        val readB = exec.specs.single { it.name.endsWith(".read") && handleB in it.parametersJson }.name
        val pendingA = exec.invoke(ToolCall("call-a-2", readA, readArgs(handleA)))
        assertTrue(pendingA is ToolResult.NeedsApproval)
        val doneA = exec.approve("call-a-2")
        assertTrue(doneA is ToolResult.Value && (doneA as ToolResult.Value).json.contains("alpha-note"), doneA.toString())
        assertFalse((doneA as ToolResult.Value).json.contains("beta-note"))
        val pendingB = exec.invoke(ToolCall("call-b-2", readB, readArgs(handleB)))
        assertTrue(pendingB is ToolResult.NeedsApproval)
        val doneB = exec.approve("call-b-2")
        assertTrue(doneB is ToolResult.Value && (doneB as ToolResult.Value).json.contains("beta-note"), doneB.toString())
        assertFalse((doneB as ToolResult.Value).json.contains("alpha-note"))
    }

    @Test
    fun crossSkillHandleIsDeniedWithoutDisclosure(): Unit = runBlocking {
        val port = FakePort(listOf(binding("skill-a"), binding("skill-b")))
        val exec = executor(port, setOf("skill-a", "skill-b"))
        val handleA = handleFor("skill-a")
        val handleB = handleFor("skill-b")
        val toolA = exec.specs.single { it.name.endsWith(".append") && handleA in it.parametersJson }.name
        // Skill B's handle presented to Skill A's tool: denied, nothing written.
        val denied = exec.invoke(ToolCall("call-x-1", toolA, appendArgs(handleB, text = "smuggled")))
        assertTrue(denied is ToolResult.Denied, denied.toString())
        assertTrue(port.files.values.all { "smuggled" !in it.values })
    }

    @Test
    fun revokeADoesNotAffectBAndDeniesReplay(): Unit = runBlocking {
        val port = FakePort(listOf(binding("skill-a"), binding("skill-b")))
        val exec = executor(port, setOf("skill-a", "skill-b"))
        val handleA = handleFor("skill-a")
        val handleB = handleFor("skill-b")
        val toolA = exec.specs.single { it.name.endsWith(".append") && handleA in it.parametersJson }.name
        val toolB = exec.specs.single { it.name.endsWith(".append") && handleB in it.parametersJson }.name
        assertTrue(approvedAppend(exec, toolA, appendArgs(handleA, text = "alpha"), "call-a-1") is ToolResult.Value)
        assertTrue(approvedAppend(exec, toolB, appendArgs(handleB, text = "beta"), "call-b-1") is ToolResult.Value)

        // Revoke Skill A: its binding disappears from the live port.
        port.live = listOf(binding("skill-b"))
        assertFalse(exec.authorizeReplay(ToolCall("call-a-1", toolA, appendArgs(handleA))))
        assertTrue(exec.authorizeReplay(ToolCall("call-b-1", toolB, appendArgs(handleB))))

        // New calls on A's frozen tools fail closed; B keeps working.
        val deniedNew = exec.invoke(ToolCall("call-a-2", toolA, appendArgs(handleA, text = "more")))
        assertTrue(deniedNew is ToolResult.Denied, deniedNew.toString())
        assertTrue(approvedAppend(exec, toolB, appendArgs(handleB, text = "more-beta"), "call-b-2") is ToolResult.Value)
    }

    @Test
    fun restartPreservesIsolatedMemoryAndRevocation() {
        val port = FakePort(listOf(binding("skill-a"), binding("skill-b")))
        val first = executor(port, setOf("skill-a", "skill-b"))
        val handleA = handleFor("skill-a")
        val toolA = first.specs.single { it.name.endsWith(".append") && handleA in it.parametersJson }.name
        assertTrue(approvedAppend(first, toolA, appendArgs(handleA, text = "alpha-across-restart"), "call-a-1") is ToolResult.Value)

        // Simulated process restart: a new executor over the same durable
        // port rediscovers both isolated namespaces with their content.
        val restarted = executor(port, setOf("skill-a", "skill-b"))
        assertEquals(8, restarted.specs.size)
        val readA = restarted.specs.single { it.name.endsWith(".read") && handleA in it.parametersJson }.name
        val pending = runBlocking { restarted.invoke(ToolCall("call-a-2", readA, readArgs(handleA))) }
        assertTrue(pending is ToolResult.NeedsApproval)
        val done = runBlocking { restarted.approve("call-a-2") }
        assertTrue(done is ToolResult.Value && (done as ToolResult.Value).json.contains("alpha-across-restart"), done.toString())

        // Revocation survives the restart too: a post-restart executor
        // exposes only Skill B's namespaced tools.
        port.live = listOf(binding("skill-b"))
        val afterRevoke = executor(port, setOf("skill-a", "skill-b"))
        val names = toolNames(afterRevoke)
        assertEquals(4, names.size)
        val handleB = handleFor("skill-b")
        assertTrue(names.all { name ->
            afterRevoke.specs.single { it.name == name }.parametersJson.contains(handleB)
        })
        assertFalse(names.any { name ->
            afterRevoke.specs.single { it.name == name }.parametersJson.contains(handleA)
        })
    }

    @Test
    fun singleSkillKeepsLegacyToolNames(): Unit = runBlocking {        val port = FakePort(listOf(binding("skill-a")))
        val exec = executor(port, setOf("skill-a"))
        val names = toolNames(exec).sorted()
        assertEquals(
            listOf(
                SkillMemoryToolExecutor.MEMORY_APPEND,
                SkillMemoryToolExecutor.MEMORY_READ,
                SkillMemoryToolExecutor.MEMORY_REPLACE,
                SkillMemoryToolExecutor.MEMORY_SEARCH,
            ).sorted(),
            names,
        )
    }

    @Test
    fun forgedNamespaceAndHandleAreRejected(): Unit = runBlocking {
        val port = FakePort(listOf(binding("skill-a"), binding("skill-b")))
        val exec = executor(port, setOf("skill-a", "skill-b"))
        val forged = exec.invoke(ToolCall("call-f-1", "memory_deadbeef1234.search", readArgs(handleFor("skill-a")).replace("MEMORY.md", "MEMORY.md")))
        assertTrue(forged is ToolResult.Invalid, forged.toString())
        val handleA = handleFor("skill-a")
        val toolA = exec.specs.single { it.name.endsWith(".search") && handleA in it.parametersJson }.name
        val forgedHandle = exec.invoke(
            ToolCall("call-f-2", toolA, buildJsonObject {
                put("memoryHandle", "0".repeat(64))
                put("query", "x")
            }.toString()),
        )
        assertTrue(forgedHandle is ToolResult.Invalid, forgedHandle.toString())
    }
}
