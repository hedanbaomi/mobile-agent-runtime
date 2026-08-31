// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SkillMemoryToolExecutorTest {
    @Test
    fun exposesOpaqueHandlesOnlyForBoundEnabledSkillsWithMemoryCapability() = runBlocking {
        val root = temporaryRoot()
        try {
            assertEquals(CapabilityId.MEMORY_READ, SKILL_MEMORY_READ_CAPABILITY)
            assertEquals(CapabilityId.MEMORY_SEARCH, SKILL_MEMORY_SEARCH_CAPABILITY)
            assertEquals(CapabilityId.MEMORY_APPEND, SKILL_MEMORY_APPEND_CAPABILITY)
            assertEquals(CapabilityId.MEMORY_REPLACE, SKILL_MEMORY_REPLACE_CAPABILITY)
            val eligible = binding(
                installId = "install-a",
                packageHash = "hash-a",
                capabilities = setOf(
                    SKILL_MEMORY_READ_CAPABILITY,
                    SKILL_MEMORY_SEARCH_CAPABILITY,
                    SKILL_MEMORY_APPEND_CAPABILITY,
                    SKILL_MEMORY_REPLACE_CAPABILITY,
                ),
            )
            val noCapability = binding(installId = "install-b", packageHash = "hash-b", capabilities = emptySet())
            val appendOnly = binding(
                installId = "install-c",
                packageHash = "hash-c",
                capabilities = setOf(SKILL_MEMORY_APPEND_CAPABILITY),
            )
            val executor = executor(root, listOf(eligible, noCapability, appendOnly))
            assertEquals(
                setOf(
                    SkillMemoryToolExecutor.MEMORY_READ,
                    SkillMemoryToolExecutor.MEMORY_SEARCH,
                    SkillMemoryToolExecutor.MEMORY_APPEND,
                    SkillMemoryToolExecutor.MEMORY_REPLACE,
                ),
                executor.specs.map { it.name }.toSet(),
            )
            assertTrue(executor.specs.none { it.name in setOf("memory_list", "memory_write", "memory_delete") })
            val schema = executor.specs.single { it.name == SkillMemoryToolExecutor.MEMORY_REPLACE }.parametersJson
            assertTrue(schema.contains(SkillMemoryBackend.opaqueHandleFor("install-a", "hash-a", "default")))
            assertFalse(schema.contains("install-a"))
            assertFalse(schema.contains("hash-a"))
            assertFalse(schema.contains(root.absolutePath))
            assertFalse(executor.specs.any { it.parametersJson.contains("packageHash") || it.parametersJson.contains("installId") })
            assertTrue(executor.specs.all { it.sideEffect })
            assertTrue(executor.specs.all { it.capability in setOf(
                SKILL_MEMORY_READ_CAPABILITY,
                SKILL_MEMORY_SEARCH_CAPABILITY,
                SKILL_MEMORY_APPEND_CAPABILITY,
                SKILL_MEMORY_REPLACE_CAPABILITY,
            ) })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exposesOnlyTheTrustedSkillAndFailsClosedWithoutItsIdentity() = runBlocking {
        val root = temporaryRoot()
        try {
            val first = binding(installId = "install-a", packageHash = "package-a")
            val second = binding(installId = "install-b", packageHash = "package-b")
            val executor = executor(root, listOf(first, second))
            val firstHandle = handle(first)
            val secondHandle = handle(second)
            val schemas = executor.specs.joinToString("\n") { it.parametersJson }
            assertTrue(schemas.contains(firstHandle))
            assertFalse(schemas.contains(secondHandle))

            val crossSkill = call("cross-skill", SkillMemoryToolExecutor.MEMORY_REPLACE, buildJsonObject {
                put("memoryHandle", secondHandle)
                put("path", "MEMORY.md")
                put("text", "must-not-cross")
            })
            assertTrue(executor.invoke(crossSkill) is ToolResult.Invalid)
            assertFalse(root.walkTopDown().any { it.isFile && it.name == "MEMORY.md" })

            val noTrustedIdentity = SkillMemoryToolExecutor(
                backend = SkillMemoryBackend(root),
                agentId = first.agentId,
                snapshotId = first.snapshotId,
                bindingPort = StaticSkillMemoryBindingPort { listOf(first, second) },
            )
            assertTrue(noTrustedIdentity.specs.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun snapshotAndAgentBindingIntersectionDoesNotExposeAnotherScope() = runBlocking {
        val root = temporaryRoot()
        try {
            val trusted = binding(installId = "install-trusted", packageHash = "package-trusted")
            val otherAgent = trusted.copy(installId = "install-other-agent", packageHash = "package-other-agent", agentId = "agent-other")
            val otherSnapshot = trusted.copy(installId = "install-other-snapshot", packageHash = "package-other-snapshot", snapshotId = "snapshot-other")
            val port = StaticSkillMemoryBindingPort { listOf(trusted, otherAgent, otherSnapshot) }
            val executor = SkillMemoryToolExecutor(
                backend = SkillMemoryBackend(root),
                agentId = trusted.agentId,
                snapshotId = trusted.snapshotId,
                bindingPort = port,
                trustedSkillId = trusted.installId,
            )
            val schema = executor.specs.joinToString("\n") { it.parametersJson }
            assertTrue(schema.contains(handle(trusted)))
            assertFalse(schema.contains(handle(otherAgent)))
            assertFalse(schema.contains(handle(otherSnapshot)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun frozenEffectiveCapabilitiesCanOnlyNarrowTheCanonicalBinding() = runBlocking {
        val root = temporaryRoot()
        try {
            val binding = binding(capabilities = setOf(
                SKILL_MEMORY_READ_CAPABILITY,
                SKILL_MEMORY_APPEND_CAPABILITY,
            ))
            val executor = SkillMemoryToolExecutor(
                backend = SkillMemoryBackend(root),
                agentId = binding.agentId,
                snapshotId = binding.snapshotId,
                bindingPort = StaticSkillMemoryBindingPort { listOf(binding) },
                trustedSkillId = binding.installId,
                effectiveCapabilities = setOf(SKILL_MEMORY_READ_CAPABILITY),
            )
            assertEquals(setOf(SkillMemoryToolExecutor.MEMORY_READ), executor.specs.map { it.name }.toSet())
            val unsupportedWrite = call("narrowed-write", SkillMemoryToolExecutor.MEMORY_APPEND, buildJsonObject {
                put("memoryHandle", handle(binding)); put("path", "MEMORY.md"); put("text", "must-not-write")
            })
            assertTrue(executor.invoke(unsupportedWrite) is ToolResult.Invalid)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun readSearchAppendAndReplaceAreAllApprovalGatedAndCallIdsAreOneShot() = runBlocking {
        val root = temporaryRoot()
        try {
            val binding = binding()
            val executor = executor(root, listOf(binding))
            val handle = handle(binding)
            val replace = call("replace-1", SkillMemoryToolExecutor.MEMORY_REPLACE, buildJsonObject {
                put("memoryHandle", handle); put("path", "MEMORY.md"); put("text", "hello\nregex .* literal")
            })
            assertEquals(ToolResult.NeedsApproval, executor.invoke(replace))
            assertFalse(root.walkTopDown().any { it.isFile && it.name == "MEMORY.md" })
            val replaceResult = executor.approve(replace.callId) as ToolResult.Value
            assertTrue(replaceResult.json.contains("MEMORY.md"))
            assertTrue(executor.approve(replace.callId) is ToolResult.Invalid)
            val duplicate = executor.invoke(replace)
            assertTrue(duplicate is ToolResult.Invalid)

            val read = call("read-1", SkillMemoryToolExecutor.MEMORY_READ, buildJsonObject {
                put("memoryHandle", handle); put("path", "MEMORY.md")
            })
            assertEquals(ToolResult.NeedsApproval, executor.invoke(read))
            assertTrue((executor.approve(read.callId) as ToolResult.Value).json.contains("hello"))

            val search = call("search-1", SkillMemoryToolExecutor.MEMORY_SEARCH, buildJsonObject {
                put("memoryHandle", handle); put("query", ".*")
            })
            assertEquals(ToolResult.NeedsApproval, executor.invoke(search))
            val searchResult = executor.approve(search.callId) as ToolResult.Value
            val searchJson = Json.parseToJsonElement(searchResult.json).jsonObject
            assertEquals("MEMORY.md", searchJson["hits"]!!.jsonArray.single().jsonObject["path"]!!.jsonPrimitive.content)
            assertEquals(2, searchJson["hits"]!!.jsonArray.single().jsonObject["line"]!!.jsonPrimitive.int)
            assertTrue(searchJson["hits"]!!.jsonArray.single().jsonObject["snippet"]!!.jsonPrimitive.content.contains(".*"))

            val version = Json.parseToJsonElement(replaceResult.json).jsonObject["version"]!!.jsonPrimitive.content
            val append = call("append-1", SkillMemoryToolExecutor.MEMORY_APPEND, buildJsonObject {
                put("memoryHandle", handle); put("path", "MEMORY.md"); put("text", "\nappended"); put("expectedVersion", version)
            })
            assertEquals(ToolResult.NeedsApproval, executor.invoke(append))
            val appendResult = executor.approve(append.callId) as ToolResult.Value
            assertFalse(Json.parseToJsonElement(appendResult.json).jsonObject["created"]!!.jsonPrimitive.content.toBoolean())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun diagnosticsAreTypedBoundedAndUseOnlyLoggerSuppliedHmacReferences() = runBlocking {
        val root = temporaryRoot()
        try {
            val binding = binding()
            val events = mutableListOf<SkillMemoryDiagnosticEvent>()
            val requests = mutableListOf<SkillMemoryDiagnosticRefRequest>()
            val sink = SkillMemoryDiagnosticSink { event -> events += event }
            val refs = SkillMemoryDiagnosticRefProvider { request ->
                requests += request
                SkillMemoryDiagnosticReferences(
                    skillRef = "a".repeat(32),
                    agentRef = "b".repeat(32),
                    requestRef = "c".repeat(32),
                )
            }
            val executor = SkillMemoryToolExecutor(
                backend = SkillMemoryBackend(root),
                agentId = binding.agentId,
                snapshotId = binding.snapshotId,
                bindingPort = StaticSkillMemoryBindingPort { listOf(binding) },
                trustedSkillId = binding.installId,
                diagnosticSink = sink,
                diagnosticRefProvider = refs,
            )
            val handle = handle(binding)
            val replace = call("replace-secret-call", SkillMemoryToolExecutor.MEMORY_REPLACE, buildJsonObject {
                put("memoryHandle", handle)
                put("path", "journal/2026-08-30.md")
                put("text", "secret-content")
            })
            assertEquals(ToolResult.NeedsApproval, executor.invoke(replace))
            assertEquals(SkillMemoryDiagnosticOperation.REPLACE, events.single().operation)
            assertEquals(SkillMemoryDiagnosticState.STARTED, events.single().state)
            assertTrue(events.single().references.skillRef == "a".repeat(32))
            assertTrue((executor.approve(replace.callId) as ToolResult.Value).json.contains("journal/2026-08-30.md"))
            assertEquals(SkillMemoryDiagnosticState.SUCCEEDED, events.last().state)
            assertEquals("none", events.last().errorCode)
            assertEquals("secret-content".toByteArray(Charsets.UTF_8).size, events.last().count)

            val search = call("search-secret-call", SkillMemoryToolExecutor.MEMORY_SEARCH, buildJsonObject {
                put("memoryHandle", handle)
                put("query", "secret-content")
            })
            assertEquals(ToolResult.NeedsApproval, executor.invoke(search))
            assertTrue(executor.approve(search.callId) is ToolResult.Value)
            assertEquals(SkillMemoryDiagnosticOperation.SEARCH, events.last().operation)
            assertEquals(1, events.last().count)

            val invalid = call("invalid-secret-call", SkillMemoryToolExecutor.MEMORY_REPLACE, buildJsonObject {
                put("memoryHandle", handle)
                put("path", "../secret-path")
                put("text", "secret-invalid-content")
            })
            assertTrue(executor.invoke(invalid) is ToolResult.Invalid)
            assertEquals(SkillMemoryDiagnosticState.FAILED, events.last().state)
            assertEquals("invalid_arguments", events.last().errorCode)

            val eventText = events.joinToString()
            assertFalse(eventText.contains("secret-content"))
            assertFalse(eventText.contains("secret-path"))
            assertFalse(eventText.contains("2026-08-30"))
            assertTrue(eventText.contains("a".repeat(32)))
            assertTrue(eventText.contains("b".repeat(32)))
            assertTrue(eventText.contains("c".repeat(32)))
            assertTrue(requests.all { it.memoryHandle == handle })
            assertTrue(requests.all { it.callId !in eventText })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun namespaceIncludesPackageHashAndMemorySpaceSoUpgradeDoesNotInherit() {
        val root = temporaryRoot()
        try {
            val backend = SkillMemoryBackend(root)
            val old = binding(packageHash = "old-package")
            val upgraded = old.copy(packageHash = "new-package")
            val otherSpace = old.copy(memorySpaceId = "separate-space")
            val otherInstall = old.copy(installId = "separate-install")
            backend.space(old).replace("MEMORY.md", "old")
            assertEquals(1, backend.space(old).list().entries.size)
            backend.space(old).replace("MEMORY.md", "replaced-without-expected-version")
            assertEquals("replaced-without-expected-version", backend.space(old).read("MEMORY.md").text)
            assertTrue(backend.space(upgraded).list().entries.isEmpty())
            assertTrue(backend.space(otherSpace).list().entries.isEmpty())
            assertTrue(backend.space(otherInstall).list().entries.isEmpty())
            assertTrue(SkillMemoryBackend.namespaceFor(old.installId, old.packageHash, old.memorySpaceId) !=
                SkillMemoryBackend.namespaceFor(upgraded.installId, upgraded.packageHash, upgraded.memorySpaceId))
            assertFalse(SkillMemoryBackend.namespaceFor(old.installId, old.packageHash, old.memorySpaceId).contains(old.installId))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun appendIsAtomicWithExpectedVersionAndSearchIsLiteralAndBounded() {
        val root = temporaryRoot()
        try {
            val limits = SkillMemoryLimits(
                maxTotalBytes = 128,
                maxFileBytes = 128,
                maxEntries = 4,
                maxReadBytes = 128,
                maxSearchResults = 1,
                maxSearchSnippetBytes = 16,
                maxSearchOutputBytes = 256,
                maxOutputBytes = 2048,
            )
            val space = SkillMemoryBackend(root, limits).space(binding())
            val first = space.replace("MEMORY.md", "literal .* one\nliteral .* two")
            val appended = space.append("MEMORY.md", "\nthird", first.version)
            assertEquals("literal .* one\nliteral .* two\nthird", space.read("MEMORY.md").text)
            assertFalse(appended.created)
            try {
                space.append("MEMORY.md", "stale", first.version)
                throw AssertionError("stale append must conflict")
            } catch (error: SkillMemoryException) {
                assertEquals(SkillMemoryFailureCode.CONFLICT, error.code)
            }
            val result = space.search(".*", maxResults = 1)
            assertEquals(1, result.hits.size)
            assertTrue(result.truncated)
            assertEquals("MEMORY.md", result.hits.single().path)
            assertEquals(1, result.hits.single().line)
            assertTrue(result.hits.single().snippet.contains(".*"))
            assertTrue(result.hits.single().snippet.toByteArray(Charsets.UTF_8).size <= limits.maxSearchSnippetBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun traversalRootAndInvalidNamesAreRejectedBeforeApproval() = runBlocking {
        val root = temporaryRoot()
        try {
            val binding = binding()
            val executor = executor(root, listOf(binding))
            val handle = handle(binding)
            listOf("../escape", "/escape", "journal/../escape.md", "journal/today.md", "journal/2024-02-30.md", "journal", "MEMORY.md/child")
                .forEachIndexed { index, path ->
                    val result = executor.invoke(call("bad-$index", SkillMemoryToolExecutor.MEMORY_REPLACE, buildJsonObject {
                        put("memoryHandle", handle); put("path", path); put("text", "x")
                    }))
                    assertTrue("path=$path result=$result", result is ToolResult.Invalid)
                }
            assertFalse(root.walkTopDown().any { it.isFile })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun symlinkAndUnexpectedRootEntriesFailClosed() = runBlocking {
        val root = temporaryRoot()
        val outside = temporaryRoot()
        try {
            val binding = binding()
            val executor = executor(root, listOf(binding))
            val handle = handle(binding)
            val seed = call("seed", SkillMemoryToolExecutor.MEMORY_REPLACE, buildJsonObject {
                put("memoryHandle", handle); put("path", "MEMORY.md"); put("text", "safe")
            })
            assertEquals(ToolResult.NeedsApproval, executor.invoke(seed))
            assertTrue(executor.approve(seed.callId) is ToolResult.Value)
            val journal = File(root, "skill-memory/${SkillMemoryBackend.namespaceFor(binding.installId, binding.packageHash, binding.memorySpaceId)}/journal")
            outside.resolve("2024-01-01.md").writeText("outside")
            Files.createSymbolicLink(journal.toPath(), outside.toPath())
            val read = call("symlink", SkillMemoryToolExecutor.MEMORY_READ, buildJsonObject {
                put("memoryHandle", handle); put("path", "journal/2024-01-01.md")
            })
            assertEquals(ToolResult.NeedsApproval, executor.invoke(read))
            val result = executor.approve(read.callId)
            assertTrue(result is ToolResult.Invalid)
            assertFalse(result.toString().contains(outside.absolutePath))
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun quotaAndExpectedVersionConflictAreEnforced() = runBlocking {
        val root = temporaryRoot()
        try {
            val binding = binding()
            val limits = SkillMemoryLimits(maxTotalBytes = 8, maxFileBytes = 8, maxEntries = 2, maxReadBytes = 8, maxOutputBytes = 2048)
            val executor = SkillMemoryToolExecutor(
                backend = SkillMemoryBackend(root, limits),
                agentId = binding.agentId,
                snapshotId = binding.snapshotId,
                bindings = listOf(binding),
                trustedSkillId = binding.installId,
                limits = limits,
            )
            val handle = handle(binding)
            val first = call("first", SkillMemoryToolExecutor.MEMORY_REPLACE, buildJsonObject {
                put("memoryHandle", handle); put("path", "MEMORY.md"); put("text", "1234")
            })
            assertEquals(ToolResult.NeedsApproval, executor.invoke(first))
            val firstResult = executor.approve(first.callId) as ToolResult.Value
            val version = Json.parseToJsonElement(firstResult.json).jsonObject["version"]!!.jsonPrimitive.content
            val stale = call("stale", SkillMemoryToolExecutor.MEMORY_REPLACE, buildJsonObject {
                put("memoryHandle", handle); put("path", "MEMORY.md"); put("text", "5678"); put("expectedVersion", "0")
            })
            assertEquals(ToolResult.NeedsApproval, executor.invoke(stale))
            assertTrue((executor.approve(stale.callId) as ToolResult.Invalid).reason.contains("CONFLICT"))
            val quota = call("quota", SkillMemoryToolExecutor.MEMORY_REPLACE, buildJsonObject {
                put("memoryHandle", handle); put("path", "journal/2024-01-01.md"); put("text", "12345"); put("expectedVersion", "0")
            })
            assertEquals(ToolResult.NeedsApproval, executor.invoke(quota))
            assertTrue((executor.approve(quota.callId) as ToolResult.Invalid).reason.contains("QUOTA_EXCEEDED"))
            assertTrue(version.isNotBlank())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun staleApprovalAfterGrantOrPackageChangeIsDeniedAndNoCapabilityIsHidden() = runBlocking {
        val root = temporaryRoot()
        try {
            var current = binding()
            val port = StaticSkillMemoryBindingPort { listOf(current) }
            val executor = SkillMemoryToolExecutor(
                backend = SkillMemoryBackend(root),
                agentId = current.agentId,
                snapshotId = current.snapshotId,
                bindingPort = port,
                trustedSkillId = current.installId,
            )
            val handle = handle(current)
            val pending = call("pending", SkillMemoryToolExecutor.MEMORY_REPLACE, buildJsonObject {
                put("memoryHandle", handle); put("path", "MEMORY.md"); put("text", "must not write")
            })
            assertEquals(ToolResult.NeedsApproval, executor.invoke(pending))
            current = current.copy(grantRevision = current.grantRevision + 1)
            assertTrue(executor.approve(pending.callId) is ToolResult.Denied)
            assertFalse(root.walkTopDown().any { it.isFile })

            current = binding()
            val packagePending = call("pending-package", SkillMemoryToolExecutor.MEMORY_REPLACE, buildJsonObject {
                put("memoryHandle", handle); put("path", "MEMORY.md"); put("text", "must not write package")
            })
            assertEquals(ToolResult.NeedsApproval, executor.invoke(packagePending))
            current = current.copy(packageHash = "different-package")
            assertTrue(executor.approve(packagePending.callId) is ToolResult.Denied)

            current = binding()
            val metadataPending = call("pending-metadata", SkillMemoryToolExecutor.MEMORY_SEARCH, buildJsonObject {
                put("memoryHandle", handle); put("query", "must not search")
            })
            assertEquals(ToolResult.NeedsApproval, executor.invoke(metadataPending))
            current = current.copy(memoryMetadataRevision = current.memoryMetadataRevision + 1)
            assertTrue(executor.approve(metadataPending.callId) is ToolResult.Denied)

            val noMemory = executor(root, listOf(current.copy(capabilities = setOf("workspace.read"))))
            assertTrue(noMemory.specs.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun approvalRejectExpireCancelAndUnknownEachHaveTerminalDiagnostics() = runBlocking {
        val root = temporaryRoot()
        try {
            val binding = binding()
            val events = mutableListOf<SkillMemoryDiagnosticEvent>()
            val executor = SkillMemoryToolExecutor(
                backend = SkillMemoryBackend(root),
                agentId = binding.agentId,
                snapshotId = binding.snapshotId,
                bindingPort = StaticSkillMemoryBindingPort { listOf(binding) },
                trustedSkillId = binding.installId,
                diagnosticSink = SkillMemoryDiagnosticSink { events += it },
            )
            fun request(id: String) = call(id, SkillMemoryToolExecutor.MEMORY_REPLACE, buildJsonObject {
                put("memoryHandle", handle(binding)); put("path", "MEMORY.md"); put("text", id)
            })

            val rejected = request("reject")
            assertEquals(ToolResult.NeedsApproval, executor.invoke(rejected))
            assertTrue(executor.reject(rejected.callId) is ToolResult.Denied)
            assertEquals(SkillMemoryDiagnosticState.DENIED, events.last().state)

            val expired = request("expire")
            assertEquals(ToolResult.NeedsApproval, executor.invoke(expired))
            assertTrue(executor.expire(expired.callId) is ToolResult.Denied)
            assertEquals(SkillMemoryDiagnosticState.EXPIRED, events.last().state)

            val cancelled = request("cancel")
            assertEquals(ToolResult.NeedsApproval, executor.invoke(cancelled))
            assertTrue(executor.cancel(cancelled.callId) is ToolResult.Denied)
            assertEquals(SkillMemoryDiagnosticState.CANCELLED, events.last().state)

            assertTrue(executor.approve("missing") is ToolResult.Invalid)
            assertEquals(SkillMemoryDiagnosticState.UNKNOWN, events.last().state)
            assertTrue(events.filter { it.state != SkillMemoryDiagnosticState.STARTED }.all { it.state.terminal })
            assertFalse(root.walkTopDown().any { it.isFile })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedUtf8IsRejectedAndTemporaryFilesDoNotRemain() {
        val root = temporaryRoot()
        try {
            val binding = binding()
            val backend = SkillMemoryBackend(root)
            try {
                backend.space(binding).replace("MEMORY.md", "\uD800")
                throw AssertionError("unpaired UTF-16 surrogate must not be encoded as replacement UTF-8")
            } catch (error: SkillMemoryException) {
                assertEquals(SkillMemoryFailureCode.INVALID_CONTENT, error.code)
            }
            backend.space(binding).replace("MEMORY.md", "第一版")
            assertTrue(root.walkTopDown().none { it.name.startsWith(".mar-memory-") })
            val memoryFile = File(root, "skill-memory/${SkillMemoryBackend.namespaceFor(binding.installId, binding.packageHash, binding.memorySpaceId)}/MEMORY.md")
            memoryFile.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
            try {
                backend.space(binding).list()
                throw AssertionError("malformed UTF-8 must not be listed")
            } catch (error: SkillMemoryException) {
                assertEquals(SkillMemoryFailureCode.INVALID_CONTENT, error.code)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun executor(root: File, bindings: List<SkillMemoryBinding>): SkillMemoryToolExecutor =
        SkillMemoryToolExecutor(
            SkillMemoryBackend(root),
            bindings.first().agentId,
            bindings.first().snapshotId,
            bindings,
            bindings.first().installId,
        )

    private fun binding(
        installId: String = "install-a",
        packageHash: String = "package-a",
        capabilities: Set<String> = setOf(
            SKILL_MEMORY_READ_CAPABILITY,
            SKILL_MEMORY_SEARCH_CAPABILITY,
            SKILL_MEMORY_APPEND_CAPABILITY,
            SKILL_MEMORY_REPLACE_CAPABILITY,
        ),
    ): SkillMemoryBinding = SkillMemoryBinding(
        installId = installId,
        packageHash = packageHash,
        memorySpaceId = "default",
        agentId = "agent-a",
        snapshotId = "snapshot-a",
        capabilities = capabilities,
        enabled = true,
        grantId = "grant-a",
        grantRevision = 1,
    )

    private fun handle(binding: SkillMemoryBinding): String =
        SkillMemoryBackend.opaqueHandleFor(binding.installId, binding.packageHash, binding.memorySpaceId)

    private fun call(id: String, name: String, args: kotlinx.serialization.json.JsonObject): ToolCall =
        ToolCall(id, name, args.toString())

    private fun temporaryRoot(): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Files.createTempDirectory(context.cacheDir.toPath(), "skill-memory-${UUID.randomUUID()}-").toFile()
    }
}
