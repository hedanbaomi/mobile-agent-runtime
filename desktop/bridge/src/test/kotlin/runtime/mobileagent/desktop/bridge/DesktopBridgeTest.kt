// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.desktop.bridge

import com.sun.jna.Platform
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import runtime.mobileagent.bridge.BridgeCodec
import runtime.mobileagent.bridge.BridgeErrorCodes
import runtime.mobileagent.bridge.BridgeOperation
import runtime.mobileagent.bridge.BridgeRequestEnvelope
import runtime.mobileagent.bridge.BridgeResponseEnvelope
import runtime.mobileagent.bridge.BridgeProtocol

class DesktopBridgeTest {
    @Test
    fun typedFileOperationsRoundTripThroughFixedAdbHelper() {
        val serial = "typed-device"
        val (configuration, report) = testConfiguration(serial, 42_001)
        val runner = FakeTypedAdbRunner()
        val manager = AdbProcessManager.validated(configuration, runner, report, WinTrustVerifier { true })
        val executor = WiredAdbTypedFileExecutorImpl(manager)
        val handler = DesktopTypedBridgeRequestHandler(
            shell = { error("shell is not used by typed operation test") },
            typedFiles = { executor },
        )

        val list = handler.handle(
            typed(
                "typed-list",
                BridgeOperation.FILE_LIST,
                buildJsonObject { put("workspace_id", "wired-adb") },
            ),
            BridgeCancellation(),
        )
        assertTrue(list.success)
        assertEquals("file_list", list.payload?.get("operation")?.toString()?.trim('"'))

        val stat = handler.handle(
            typed(
                "typed-stat",
                BridgeOperation.FILE_STAT,
                buildJsonObject {
                    put("workspace_id", "wired-adb")
                    put("relative_path", "seed.txt")
                },
            ),
            BridgeCancellation(),
        )
        assertTrue(stat.success)

        val read = handler.handle(
            typed(
                "typed-read",
                BridgeOperation.FILE_READ_TEXT,
                buildJsonObject {
                    put("workspace_id", "wired-adb")
                    put("relative_path", "seed.txt")
                    put("max_bytes", 24 * 1024)
                },
            ),
            BridgeCancellation(),
        )
        assertTrue(read.success)
        assertEquals("seed", read.payload?.get("text")?.toString()?.trim('"'))

        val write = handler.handle(
            typed(
                "typed-write",
                BridgeOperation.FILE_WRITE_TEXT,
                buildJsonObject {
                    put("workspace_id", "wired-adb")
                    put("relative_path", "new.txt")
                    put("content", "hello")
                    put("overwrite", false)
                },
            ),
            BridgeCancellation(),
        )
        assertTrue(write.success)

        val mkdir = handler.handle(
            typed(
                "typed-mkdir",
                BridgeOperation.FILE_CREATE_DIRECTORY,
                buildJsonObject {
                    put("workspace_id", "wired-adb")
                    put("relative_path", "dir")
                    put("recursive", false)
                },
            ),
            BridgeCancellation(),
        )
        assertTrue(mkdir.success)

        val move = handler.handle(
            typed(
                "typed-move",
                BridgeOperation.FILE_MOVE,
                buildJsonObject {
                    put("workspace_id", "wired-adb")
                    put("relative_path", "new.txt")
                    put("destination_relative_path", "dir/moved.txt")
                    put("overwrite", false)
                },
            ),
            BridgeCancellation(),
        )
        assertTrue(move.success)

        val delete = handler.handle(
            typed(
                "typed-delete",
                BridgeOperation.FILE_DELETE,
                buildJsonObject {
                    put("workspace_id", "wired-adb")
                    put("relative_path", "dir/moved.txt")
                    put("recursive", false)
                },
            ),
            BridgeCancellation(),
        )
        assertTrue(delete.success)
        assertEquals(7, runner.requests.size)
        runner.requests.forEach { processRequest ->
            assertEquals(
                listOf(
                    configuration.adbPath.toString(), "-s", serial,
                    "shell", "-T", "sh", "-c", WIRED_ADB_TYPED_HELPER_COMMAND,
                ),
                processRequest.argv,
            )
            assertTrue(processRequest.stdin!!.size >= 5)
        }
    }

    @Test
    fun typedFileValidationRejectsTraversalAndDoesNotDispatch() {
        val (configuration, report) = testConfiguration("typed-device", 42_001)
        val runner = FakeTypedAdbRunner()
        val executor = WiredAdbTypedFileExecutorImpl(
            AdbProcessManager.validated(configuration, runner, report, WinTrustVerifier { true }),
        )
        val handler = DesktopTypedBridgeRequestHandler(
            shell = { error("shell is not used by typed validation test") },
            typedFiles = { executor },
        )
        val response = handler.handle(
            typed(
                "typed-invalid",
                BridgeOperation.FILE_STAT,
                buildJsonObject {
                    put("workspace_id", "wired-adb")
                    put("relative_path", "../outside")
                },
            ),
            BridgeCancellation(),
        )
        assertFalse(response.success)
        assertEquals(BridgeErrorCodes.REQUEST_INVALID, response.errorCode)
        assertFalse(response.errorMessage.orEmpty().contains("outside"))
        assertEquals(0, runner.requests.size)
    }

    @Test
    fun typedFileDisconnectAndUnknownOutcomeNeverBecomeSuccess() {
        val (configuration, report) = testConfiguration("typed-device", 42_001)
        val runner = FakeTypedAdbRunner()
        val executor = WiredAdbTypedFileExecutorImpl(
            AdbProcessManager.validated(configuration, runner, report, WinTrustVerifier { true }),
        )
        val handler = DesktopTypedBridgeRequestHandler(
            shell = { error("shell is not used by typed outcome test") },
            typedFiles = { executor },
        )
        val request = typed(
            "typed-unknown",
            BridgeOperation.FILE_WRITE_TEXT,
            buildJsonObject {
                put("workspace_id", "wired-adb")
                put("relative_path", "unknown.txt")
                put("content", "side effect uncertain")
                put("overwrite", false)
            },
        )
        runner.unknown = true
        val unknown = handler.handle(request, BridgeCancellation())
        assertFalse(unknown.success)
        assertEquals(BridgeErrorCodes.UNKNOWN_OUTCOME, unknown.errorCode)

        runner.unknown = false
        runner.disconnected = true
        val disconnected = handler.handle(
            request.copy(requestId = "typed-disconnected"),
            BridgeCancellation(),
        )
        assertFalse(disconnected.success)
        assertEquals(BridgeErrorCodes.UNKNOWN_OUTCOME, disconnected.errorCode)
    }

    @Test
    fun devicesParserHandlesMultipleAndNeverSelectsFirst() {
        val devices = AdbDevicesParser.parse(
            """
            List of devices attached
            first	device product:foo model:Foo
            second	unauthorized
            third	offline
            """.trimIndent(),
        )
        assertEquals(3, devices.size)
        assertEquals("first", AdbDevicesParser.selectExplicit(devices, "first").serial)
        assertThrows<IllegalArgumentException> { AdbDevicesParser.selectExplicit(devices, "second") }
        assertThrows<IllegalArgumentException> { AdbDevicesParser.selectExplicit(devices, "missing") }
    }

    @Test
    fun shellCommandSpecialCharactersStayInUtf8Stdin() {
        val serial = "USB&|><^%!\u0027\"-serial"
        val (configuration, report) = testConfiguration(serial, 42_001)
        val runner = RecordingRunner(
            ProcessCapture(ProcessOutcome.COMPLETE, 0, "shell_v2\n".toByteArray(), ByteArray(0), false, false, 1),
            ProcessCapture(ProcessOutcome.COMPLETE, 0, "ok\n".toByteArray(), ByteArray(0), false, false, 2),
        )
        val executor = WiredAdbShellExecutor(AdbProcessManager.validated(configuration, runner, report, WinTrustVerifier { true }))
        val command = "printf '%s' '&|><^%!\u0027\"'"
        val result = executor.execute(WiredAdbShellRequest(command, cwd = "/sdcard/a'b"))
        assertEquals(ProcessOutcome.COMPLETE, result.outcome)
        assertTrue(result.stderrMayContainAdbDiagnostics)
        val shell = runner.requests[1]
        assertEquals(
            listOf(
                configuration.adbPath.toString(), "-s", serial,
                "shell", "-T", "sh", "-s",
            ),
            shell.argv,
        )
        assertTrue(shell.argv.drop(3).none { it.contains('&') || it.contains('|') || it.contains('>') })
        val script = shell.stdin!!.toString(Charsets.UTF_8)
        assertTrue(script.contains(command))
        assertTrue(script.contains("a'\"'\"'b"))
    }

    @Test
    fun adbDiagnosticStderrIsDiscardedBeforeBridgePayload() {
        val serial = "serial-in-adb-diagnostic"
        val (configuration, report) = testConfiguration(serial, 42_001)
        val rawDiagnostics = """
            adb: device $serial is offline
            platform-tools path: ${configuration.canonicalAdbPath}
            java.lang.IllegalStateException: raw-adb-process-exception
        """.trimIndent().toByteArray()
        val runner = RecordingRunner(
            ProcessCapture(
                outcome = ProcessOutcome.COMPLETE,
                exitCode = 0,
                stdout = "shell_v2\n".toByteArray(),
                stderr = ByteArray(0),
                stdoutTruncated = false,
                stderrTruncated = false,
                durationMs = 1,
            ),
            ProcessCapture(
                outcome = ProcessOutcome.COMPLETE,
                exitCode = 17,
                stdout = "remote stdout\n".toByteArray(),
                stderr = rawDiagnostics,
                stdoutTruncated = false,
                stderrTruncated = true,
                durationMs = 2,
            ),
        )
        val executor = WiredAdbShellExecutor(
            AdbProcessManager.validated(configuration, runner, report, WinTrustVerifier { true }),
        )
        val handler = DesktopTypedBridgeRequestHandler(shell = { executor })
        val request = typed(
            "shell-adb-diagnostics",
            BridgeOperation.SHELL_EXEC,
            buildJsonObject {
                put("command", "printf 'remote stdout\\n'")
                put("cwd", "/sdcard")
                put("timeout_ms", 30_000)
                put("max_output_bytes", 1 * 1024 * 1024)
            },
        )

        val response = handler.handle(request, BridgeCancellation())

        assertTrue(response.success)
        assertFalse(response.stderrMayContainAdbDiagnostics)
        val payload = response.payload ?: error("shell response payload is missing")
        assertEquals(17, payload["exit_code"]?.toString()?.toInt())
        assertEquals("remote stdout\n", java.util.Base64.getDecoder().decode(
            payload["stdout_base64"]?.toString()?.trim('"') ?: error("stdout missing"),
        ).toString(Charsets.UTF_8))
        assertEquals(
            ByteArray(0).toList(),
            java.util.Base64.getDecoder().decode(payload["stderr_base64"]?.toString()?.trim('"') ?: error("stderr missing")).toList(),
        )
        // The safe truncation statistic remains available without exposing the
        // bytes that caused it.
        assertEquals("true", payload["stderr_truncated"]?.toString())

        val encoded = BridgeCodec.encodeResponse(response).toString(Charsets.UTF_8)
        assertFalse(encoded.contains(serial))
        assertFalse(encoded.contains(configuration.canonicalAdbPath.toString()))
        assertFalse(encoded.contains("platform-tools"))
        assertFalse(encoded.contains("raw-adb-process-exception"))
        assertFalse(encoded.contains("java.lang.IllegalStateException"))
    }

    @Test
    fun reverseManagerRemovesOnlyExactOwnMapping() {
        val serial = "serial-1"
        val (configuration, report) = testConfiguration(serial, 42_001)
        val runner = RecordingRunner(
            ProcessCapture(ProcessOutcome.COMPLETE, 0, ByteArray(0), ByteArray(0), false, false, 1),
            ProcessCapture(ProcessOutcome.COMPLETE, 0, "$serial tcp:38765 tcp:42001\nother tcp:38765 tcp:9999\n".toByteArray(), ByteArray(0), false, false, 1),
            ProcessCapture(ProcessOutcome.COMPLETE, 0, "$serial tcp:38765 tcp:42001\n".toByteArray(), ByteArray(0), false, false, 1),
            ProcessCapture(ProcessOutcome.COMPLETE, 0, ByteArray(0), ByteArray(0), false, false, 1),
        )
        val reverse = AdbReverseManager(AdbProcessManager.validated(configuration, runner, report, WinTrustVerifier { true }))
        reverse.ensure()
        assertTrue(reverse.removeOwn())
        assertTrue(runner.requests[0].argv.containsAll(listOf("reverse", "--no-rebind", "tcp:38765", "tcp:42001")))
        assertTrue(runner.requests.last().argv.containsAll(listOf("reverse", "--remove", "tcp:38765")))
        assertFalse(runner.requests.last().argv.any { it.contains("all") })
    }

    @Test
    fun cliRequiresExplicitAdbAndSerialForTargetedCommands() {
        val parsed = BridgeCliParser.parse(
            arrayOf(
                "run", "--adb", "C:\\adb.exe", "--serial", "device-1",
                "--desktop-id", "desktop", "--app-instance-id", "app",
                "--trust-dir", "C:\\trust",
            ),
        )
        assertTrue(parsed is BridgeCliCommand.Run)
        assertThrows<IllegalArgumentException> {
            BridgeCliParser.parse(arrayOf("run", "--adb", "C:\\adb.exe"))
        }
        assertThrows<IllegalArgumentException> {
            BridgeCliParser.parse(arrayOf("connect", "--adb", "C:\\adb.exe", "--serial", "x"))
        }
    }

    @Test
    fun pairCliRequiresSerialButLearnsAppIdentityFromAndroid() {
        val parsed = BridgeCliParser.parse(
            arrayOf("pair", "--adb", "C:\\adb.exe", "--serial", "device-1", "--trust-dir", "C:\\trust"),
        )
        assertTrue(parsed is BridgeCliCommand.Pair)
        assertTrue((parsed as BridgeCliCommand.Pair).desktopId == null)
        assertThrows<IllegalArgumentException> {
            BridgeCliParser.parse(arrayOf("pair", "--adb", "C:\\adb.exe"))
        }
    }

    @Test
    fun desktopIdentityStoreIsStableAcrossReads() {
        val store = InMemoryDesktopIdentityStore()
        val first = store.loadOrCreate()
        assertEquals(first, store.loadOrCreate())
        assertTrue(first.startsWith("desktop-"))
    }

    @Test
    fun dpapiRoundTripRunsOnlyOnWindows() {
        if (!Platform.isWindows()) return
        val directory = Files.createTempDirectory("mar-bridge-trust")
        val identity = runtime.mobileagent.bridge.BridgeIdentity.forSerial("desktop", "app", "serial")
        val record = DesktopTrustRecord(
            identity,
            directory.resolve("adb.exe").toAbsolutePath().toString(),
            ByteArray(32) { 1 },
            ByteArray(32) { 2 },
            runtime.mobileagent.bridge.SecretBytes.from(ByteArray(32) { 3 }),
        )
        val store = DpapiDesktopTrustStore(directory)
        store.save(record)
        val loaded = store.load(identity)!!
        assertArrayEquals(ByteArray(32) { 3 }, loaded.copyTrust().copyBytes())
        loaded.close()
        record.close()
        store.forget(identity)
    }

    private class RecordingRunner(private val responses: MutableList<ProcessCapture>) : ProcessRunner {
        val requests = mutableListOf<ProcessRequest>()

        constructor(vararg responses: ProcessCapture) : this(responses.toMutableList())

        override fun run(request: ProcessRequest): ProcessCapture {
            requests += request.copy(stdin = request.stdin?.copyOf())
            return responses.removeFirst()
        }
    }

    private class FakeTypedAdbRunner : ProcessRunner {
        val requests = mutableListOf<ProcessRequest>()
        var disconnected = false
        var unknown = false
        private val files = linkedMapOf("seed.txt" to "seed")
        private val directories = linkedSetOf("dir")

        override fun run(request: ProcessRequest): ProcessCapture {
            requests += request.copy(stdin = request.stdin?.copyOf())
            if (unknown) return ProcessCapture(
                outcome = ProcessOutcome.UNKNOWN_OUTCOME,
                exitCode = null,
                stdout = ByteArray(0),
                stderr = ByteArray(0),
                stdoutTruncated = false,
                stderrTruncated = false,
                durationMs = 1,
            )
            if (disconnected) return ProcessCapture(
                outcome = ProcessOutcome.FAILED,
                exitCode = null,
                stdout = ByteArray(0),
                stderr = ByteArray(0),
                stdoutTruncated = false,
                stderrTruncated = false,
                durationMs = 1,
            )
            val frame = request.stdin ?: error("typed helper request did not have stdin")
            val length = ((frame[0].toInt() and 0xff) shl 24) or
                ((frame[1].toInt() and 0xff) shl 16) or
                ((frame[2].toInt() and 0xff) shl 8) or
                (frame[3].toInt() and 0xff)
            val decoded = BridgeCodec.decodeRequest(frame.copyOfRange(4, 4 + length))
            val response = respond(decoded)
            val body = BridgeCodec.encodeResponse(response)
            val output = byteArrayOf(
                (body.size ushr 24).toByte(),
                (body.size ushr 16).toByte(),
                (body.size ushr 8).toByte(),
                body.size.toByte(),
            ) + body
            return ProcessCapture(
                outcome = ProcessOutcome.COMPLETE,
                exitCode = 0,
                stdout = output,
                stderr = ByteArray(0),
                stdoutTruncated = false,
                stderrTruncated = false,
                durationMs = 1,
            )
        }

        private fun respond(request: BridgeRequestEnvelope): BridgeResponseEnvelope {
            val path = request.payload.string("relative_path")
            val operation = BridgeOperation.parse(request.operation)
            val payload = when (operation) {
                BridgeOperation.FILE_LIST -> buildJsonObject {
                    put("operation", "file_list")
                    put("relative_path", "")
                    put("entries", buildJsonArray {
                        files.forEach { (name, value) ->
                            add(buildJsonObject {
                                put("relative_path", name)
                                put("type", "file")
                                put("bytes", value.toByteArray().size)
                            })
                        }
                        directories.forEach { name ->
                            add(buildJsonObject {
                                put("relative_path", name)
                                put("type", "directory")
                            })
                        }
                    })
                }
                BridgeOperation.FILE_STAT -> buildJsonObject {
                    put("operation", "file_stat")
                    put("relative_path", path)
                    put("entries", buildJsonArray {
                        add(buildJsonObject {
                            put("relative_path", path)
                            put("type", if (files.containsKey(path)) "file" else "directory")
                            files[path]?.let { put("bytes", it.toByteArray().size) }
                        })
                    })
                    files[path]?.let { put("bytes", it.toByteArray().size) }
                }
                BridgeOperation.FILE_READ_TEXT -> buildJsonObject {
                    put("operation", "file_read_text")
                    put("relative_path", path)
                    put("text", files[path] ?: "")
                    put("bytes", (files[path] ?: "").toByteArray().size)
                }
                BridgeOperation.FILE_WRITE_TEXT -> {
                    val content = request.payload.string("content") ?: error("fake write content missing")
                    files[path!!] = content
                    buildJsonObject {
                        put("operation", "file_write_text")
                        put("relative_path", path)
                        put("bytes", content.toByteArray().size)
                        put("created", true)
                        put("replaced", false)
                    }
                }
                BridgeOperation.FILE_CREATE_DIRECTORY -> {
                    directories += path!!
                    buildJsonObject {
                        put("operation", "file_create_directory")
                        put("relative_path", path)
                        put("created", true)
                    }
                }
                BridgeOperation.FILE_MOVE -> {
                    val destination = request.payload.string("destination_relative_path") ?: error("fake move destination missing")
                    val content = files.remove(path!!)
                    if (content != null) files[destination] = content
                    buildJsonObject {
                        put("operation", "file_move")
                        put("relative_path", path)
                        put("bytes", content?.toByteArray()?.size ?: 0)
                        put("replaced", false)
                    }
                }
                BridgeOperation.FILE_DELETE -> {
                    files.remove(path!!)
                    buildJsonObject {
                        put("operation", "file_delete")
                        put("relative_path", path)
                        put("deleted", true)
                    }
                }
                else -> error("fake only serves typed files")
            }
            return BridgeResponseEnvelope(
                protocolVersion = BridgeProtocol.VERSION,
                requestId = request.requestId,
                success = true,
                payload = payload,
            )
        }

        private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
            (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.content
    }

    private fun typed(
        requestId: String,
        operation: BridgeOperation,
        payload: kotlinx.serialization.json.JsonObject,
    ): BridgeRequestEnvelope = BridgeRequestEnvelope(
        protocolVersion = BridgeProtocol.VERSION,
        requestId = requestId,
        operation = operation.wireName,
        payload = payload,
    )

    private fun testConfiguration(serial: String, hostPort: Int): Pair<AdbConfiguration, AdbDoctorReport> {
        val directory = Files.createTempDirectory("mar-bridge-adb")
        val executable = directory.resolve("adb.exe")
        Files.write(executable, byteArrayOf(0x41, 0x44, 0x42))
        val configuration = AdbConfiguration.create(executable, serial, 38_765, hostPort)
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(configuration.canonicalAdbPath))
        val identity = AdbExecutableFileIdentity.read(configuration.canonicalAdbPath)
        val report = AdbDoctorReport(
            configuration.canonicalAdbPath,
            "Android Debug Bridge version 1",
            runtime.mobileagent.bridge.BridgeEncoding.hex(digest),
            true,
            identity.fileKey,
            identity.fileSize,
            identity.lastModifiedMillis,
        )
        return configuration to report
    }
}
