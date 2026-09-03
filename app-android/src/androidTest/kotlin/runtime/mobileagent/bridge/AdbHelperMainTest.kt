// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.bridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import runtime.mobileagent.wired.PrivilegedFileEngine
import runtime.mobileagent.wired.WiredAdbEntryType
import runtime.mobileagent.wired.WiredAdbFileEngineResult
import runtime.mobileagent.wired.WiredAdbFileOperation
import runtime.mobileagent.wired.WiredAdbFileRequest
import runtime.mobileagent.wired.WiredAdbFileResult
import runtime.mobileagent.wired.WiredAdbRequestId
import runtime.mobileagent.wired.NioPrivilegedFileEngine
import runtime.mobileagent.wired.WIRED_MAX_READ_BYTES

class AdbHelperMainTest {
    @Test
    fun shellOperationIsRejectedWithoutEngineDispatch() {
        var calls = 0
        val input = requestFrame(
            BridgeRequestEnvelope(
                BridgeProtocol.VERSION,
                "helper-shell-1",
                BridgeOperation.SHELL_EXEC.wireName,
                buildJsonObject { put("command", "touch outside") },
            ),
        )
        val output = ByteArrayOutputStream()
        val server = AdbHelperServer(
            ByteArrayInputStream(input),
            output,
            engine = PrivilegedFileEngine {
                calls++
                WiredAdbFileEngineResult.Failure("UNEXPECTED_DISPATCH")
            },
            uidProvider = { android.os.Process.SHELL_UID },
        )
        assertEquals(AdbHelperServer.EXIT_OK, server.run())
        assertEquals(0, calls)
        val response = decodeResponses(output.toByteArray()).single()
        assertEquals(false, response.success)
        assertEquals(AdbHelperServer.ERR_TYPED_OPERATION_REQUIRED, response.errorCode)
        assertEquals(null, response.errorMessage)
    }

    @Test
    fun traversalIsRejectedBeforeBackend() {
        var calls = 0
        val input = requestFrame(
            BridgeRequestEnvelope(
                BridgeProtocol.VERSION,
                "helper-file-1",
                BridgeOperation.FILE_STAT.wireName,
                buildJsonObject { put("relative_path", "../outside") },
            ),
        )
        val output = ByteArrayOutputStream()
        val server = AdbHelperServer(
            ByteArrayInputStream(input),
            output,
            engine = PrivilegedFileEngine {
                calls++
                WiredAdbFileEngineResult.Failure("UNEXPECTED_DISPATCH")
            },
            uidProvider = { android.os.Process.SHELL_UID },
        )
        server.run()
        assertEquals(0, calls)
        assertEquals(AdbHelperServer.ERR_REQUEST_INVALID, decodeResponses(output.toByteArray()).single().errorCode)
    }

    @Test
    fun fileRequestIsTheOnlyDispatchedTypedOperation() {
        var received: WiredAdbFileRequest? = null
        val input = requestFrame(
            BridgeRequestEnvelope(
                BridgeProtocol.VERSION,
                "helper-file-2",
                BridgeOperation.FILE_WRITE_TEXT.wireName,
                buildJsonObject {
                    put("workspace_id", "wired-adb")
                    put("relative_path", "notes.txt")
                    put("content", "hello")
                    put("overwrite", true)
                },
            ),
        )
        val output = ByteArrayOutputStream()
        val server = AdbHelperServer(
            ByteArrayInputStream(input),
            output,
            engine = PrivilegedFileEngine {
                received = it
                WiredAdbFileEngineResult.Success(
                    WiredAdbFileResult(
                        WiredAdbFileOperation.WRITE_TEXT,
                        "notes.txt",
                        bytes = 5,
                        replaced = true,
                    ),
                )
            },
            uidProvider = { android.os.Process.SHELL_UID },
        )
        assertEquals(AdbHelperServer.EXIT_OK, server.run())
        assertEquals(WiredAdbFileOperation.WRITE_TEXT, received?.operation)
        assertEquals("notes.txt", received?.relativePath)
        assertEquals("hello", received?.contentUtf8?.toString(Charsets.UTF_8))
        assertTrue(decodeResponses(output.toByteArray()).single().success)
    }

    @Test
    fun existingFileErrorPrefixIsNotDuplicated() {
        val input = requestFrame(
            BridgeRequestEnvelope(
                BridgeProtocol.VERSION,
                "helper-file-too-large",
                BridgeOperation.FILE_READ_TEXT.wireName,
                buildJsonObject {
                    put("workspace_id", "wired-adb")
                    put("relative_path", "large.bin")
                    put("max_bytes", WIRED_MAX_READ_BYTES)
                    put("offset_bytes", 0L)
                },
            ),
        )
        val output = ByteArrayOutputStream()
        val server = AdbHelperServer(
            ByteArrayInputStream(input),
            output,
            engine = PrivilegedFileEngine { WiredAdbFileEngineResult.Failure("FILE_TOO_LARGE") },
            uidProvider = { android.os.Process.SHELL_UID },
        )

        assertEquals(AdbHelperServer.EXIT_OK, server.run())
        assertEquals("FILE_TOO_LARGE", decodeResponses(output.toByteArray()).single().errorCode)
    }

    @Test
    fun helperUsesStrictLengthLimit() {
        val bytes = byteArrayOf(0x7f, 0x7f, 0x7f, 0x7f)
        val output = ByteArrayOutputStream()
        val server = AdbHelperServer(
            ByteArrayInputStream(bytes),
            output,
            engine = PrivilegedFileEngine { WiredAdbFileEngineResult.Failure("UNEXPECTED") },
            uidProvider = { android.os.Process.SHELL_UID },
            maxFrameBytes = 64,
        )
        assertEquals(AdbHelperServer.EXIT_PROTOCOL_ERROR, server.run())
        assertTrue(output.size() == 0)
    }

    @Test
    fun symlinkIsRejectedByNioBackendWhenSupported() {
        val root = Files.createTempDirectory("mar-wired-helper")
        try {
            val target = root.resolve("target.txt")
            Files.write(target, "secret".toByteArray())
            val link = root.resolve("link.txt")
            try {
                Files.createSymbolicLink(link, target.fileName)
            } catch (_: UnsupportedOperationException) {
                assumeTrue("symlinks unsupported", false)
            } catch (_: java.nio.file.FileSystemException) {
                assumeTrue("symlinks unavailable", false)
            }
            val request = WiredAdbFileRequest(
                WiredAdbRequestId("helper-symlink-1"),
                WiredAdbFileOperation.STAT,
                "link.txt",
            )
            val result = NioPrivilegedFileEngine(root).execute(request)
            assertTrue(result is WiredAdbFileEngineResult.Failure)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun shellUidGateFailsClosed() {
        val output = ByteArrayOutputStream()
        val server = AdbHelperServer(
            ByteArrayInputStream(ByteArray(0)),
            output,
            engine = PrivilegedFileEngine { WiredAdbFileEngineResult.Failure("UNEXPECTED") },
            uidProvider = { 10_000 },
        )
        assertEquals(AdbHelperServer.EXIT_UNAUTHORIZED, server.run())
        assertEquals(AdbHelperServer.ERR_SHELL_UID_REQUIRED, decodeResponses(output.toByteArray()).single().errorCode)
    }

    private fun requestFrame(request: BridgeRequestEnvelope): ByteArray {
        val body = BridgeCodec.encodeRequest(request)
        return byteArrayOf(
            (body.size ushr 24).toByte(),
            (body.size ushr 16).toByte(),
            (body.size ushr 8).toByte(),
            body.size.toByte(),
        ) + body
    }

    private fun decodeResponses(bytes: ByteArray): List<BridgeResponseEnvelope> {
        val result = mutableListOf<BridgeResponseEnvelope>()
        var offset = 0
        while (offset < bytes.size) {
            assertTrue(offset + 4 <= bytes.size)
            val length = ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)
            offset += 4
            assertTrue(offset + length <= bytes.size)
            result += BridgeCodec.decodeResponse(bytes.copyOfRange(offset, offset + length))
            offset += length
        }
        return result
    }
}
