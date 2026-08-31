// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.desktop.bridge

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.W32APIOptions
import com.sun.jna.win32.StdCallLibrary
import java.nio.charset.StandardCharsets
import java.nio.file.LinkOption
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Arrays
import runtime.mobileagent.bridge.BridgeEncoding

data class AdbDoctorReport(
    val canonicalPath: Path,
    val versionOutput: String,
    val sha256Hex: String,
    val signatureVerified: Boolean,
    val fileKey: String?,
    val fileSize: Long,
    val lastModifiedMillis: Long,
)

class AdbSignatureException(message: String) : RuntimeException(message)
class AdbHashChangedRequiresConfirmation(message: String) : RuntimeException(message)

fun interface WinTrustVerifier {
    fun verify(path: Path): Boolean

    /** Real WinVerifyTrust is Windows-only; injected test verifiers are not. */
    val requiresWindows: Boolean get() = false
}

/** Uses WinVerifyTrust with UI disabled; no trust decision is persisted here. */
class JnaWinTrustVerifier : WinTrustVerifier {
    override val requiresWindows: Boolean get() = true

    override fun verify(path: Path): Boolean {
        if (!Platform.isWindows()) return false
        val canonical = path.toAbsolutePath().normalize()
        val widePath = Memory((canonical.toString().length + 1L) * 2L)
        widePath.setWideString(0, canonical.toString())
        val fileInfo = WinTrustFileInfo(widePath)
        val data = WinTrustData(fileInfo.pointer)
        fileInfo.write()
        data.write()
        return try {
            WinTrust.INSTANCE.WinVerifyTrust(
                Pointer.NULL,
                WINTRUST_ACTION_GENERIC_VERIFY_V2,
                data.pointer,
            ) == 0
        } finally {
            widePath.clear()
        }
    }

    private interface WinTrust : StdCallLibrary {
        fun WinVerifyTrust(hwnd: Pointer?, action: Guid.GUID, data: Pointer): Int

        companion object {
            val INSTANCE: WinTrust = Native.load("wintrust", WinTrust::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    private class WinTrustFileInfo(private val path: Memory) : Structure() {
        @JvmField var cbStruct: Int = 0
        @JvmField var pcwszFilePath: Pointer = path
        @JvmField var hFile: Pointer? = Pointer.NULL
        @JvmField var pgKnownSubject: Pointer? = Pointer.NULL

        override fun getFieldOrder(): List<String> = listOf(
            "cbStruct", "pcwszFilePath", "hFile", "pgKnownSubject",
        )

        init {
            cbStruct = size()
        }
    }

    private class WinTrustData(private val fileInfo: Pointer) : Structure() {
        @JvmField var cbStruct: Int = 0
        @JvmField var pPolicyCallbackData: Pointer? = Pointer.NULL
        @JvmField var pSIPClientData: Pointer? = Pointer.NULL
        @JvmField var dwUIChoice: Int = WTD_UI_NONE
        @JvmField var fdwRevocationChecks: Int = WTD_REVOKE_NONE
        @JvmField var dwUnionChoice: Int = WTD_CHOICE_FILE
        @JvmField var pFile: Pointer = fileInfo
        @JvmField var dwStateAction: Int = WTD_STATEACTION_IGNORE
        @JvmField var hWVTStateData: Pointer? = Pointer.NULL
        @JvmField var pwszURLReference: Pointer? = Pointer.NULL
        @JvmField var dwProvFlags: Int = WTD_REVOCATION_CHECK_END_CERT
        @JvmField var dwUIContext: Int = WTD_UICONTEXT_EXECUTE

        override fun getFieldOrder(): List<String> = listOf(
            "cbStruct", "pPolicyCallbackData", "pSIPClientData", "dwUIChoice",
            "fdwRevocationChecks", "dwUnionChoice", "pFile", "dwStateAction",
            "hWVTStateData", "pwszURLReference", "dwProvFlags", "dwUIContext",
        )

        init {
            cbStruct = size()
        }
    }

    companion object {
        private val WINTRUST_ACTION_GENERIC_VERIFY_V2 = Guid.GUID("00AAC56B-CD44-11d0-8CC2-00C04FC295EE")
        private const val WTD_UI_NONE = 2
        private const val WTD_REVOKE_NONE = 0
        private const val WTD_CHOICE_FILE = 1
        private const val WTD_STATEACTION_IGNORE = 0
        private const val WTD_REVOCATION_CHECK_END_CERT = 0x80
        private const val WTD_UICONTEXT_EXECUTE = 0
    }
}

class AdbDoctor internal constructor(
    private val configuration: AdbConfiguration,
    private val runner: ProcessRunner,
    private val verifier: WinTrustVerifier = JnaWinTrustVerifier(),
) {
    fun inspect(): AdbDoctorReport {
        if (!Platform.isWindows() && verifier.requiresWindows) {
            throw AdbSignatureException("production desktop companion requires Windows")
        }
        val canonical = configuration.adbPath.toRealPath()
        require(canonical.fileName.toString().equals("adb.exe", ignoreCase = true)) {
            "configured executable is not adb.exe"
        }
        require(Files.isRegularFile(canonical)) { "configured adb.exe is not a regular file" }
        // This is the first child spawn, so establish path, file identity,
        // content and publisher trust before invoking `adb version`.  Repeat
        // the identity/hash check afterwards to detect a replacement while
        // the version process was running.
        val before = AdbExecutableFileIdentity.read(canonical)
        var beforeDigest = sha256(canonical)
        try {
            if (!verifier.verify(canonical)) throw AdbSignatureException("WinVerifyTrust rejected adb.exe")
            val versionProcess = runner.run(
                ProcessRequest(listOf(canonical.toString(), "version"), timeoutMs = 10_000),
            )
            require(versionProcess.outcome == ProcessOutcome.COMPLETE && versionProcess.exitCode == 0) {
                "adb version check failed"
            }
            val version = versionProcess.stdout.toUtf8Strict()
            require(version.contains("Android Debug Bridge", ignoreCase = true)) {
                "configured executable is not official adb"
            }
            val after = AdbExecutableFileIdentity.read(canonical)
            var afterDigest = sha256(canonical)
            try {
                require(after == before) { "adb.exe changed during doctor validation" }
                require(MessageDigest.isEqual(afterDigest, beforeDigest)) {
                    "adb.exe hash changed during doctor validation"
                }
                return AdbDoctorReport(
                    canonical,
                    version,
                    BridgeEncoding.hex(afterDigest),
                    true,
                    after.fileKey,
                    after.fileSize,
                    after.lastModifiedMillis,
                )
            } finally {
                Arrays.fill(afterDigest, 0)
            }
        } finally {
            Arrays.fill(beforeDigest, 0)
        }
    }

    fun requireTrustHash(record: DesktopTrustRecord, report: AdbDoctorReport, userConfirmedHashChange: Boolean = false) {
        require(Path.of(record.canonicalAdbPath).toAbsolutePath().normalize() == report.canonicalPath) {
            "configured adb.exe path changed"
        }
        val expected = BridgeEncoding.hex(record.adbSha256)
        if (!expected.equals(report.sha256Hex, ignoreCase = true) && !userConfirmedHashChange) {
            throw AdbHashChangedRequiresConfirmation("adb.exe hash changed; explicit user confirmation is required")
        }
    }

    private fun sha256(path: Path): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            } finally {
                Arrays.fill(buffer, 0)
            }
        }
        return digest.digest()
    }
}

/** File identity bound to the validated executable, not just its pathname. */
internal data class AdbExecutableFileIdentity(
    val canonicalPath: Path,
    val fileKey: String?,
    val fileSize: Long,
    val lastModifiedMillis: Long,
) {
    companion object {
        fun read(path: Path): AdbExecutableFileIdentity {
            rejectReparsePoint(path)
            val canonical = path.toRealPath()
            require(!Files.isSymbolicLink(path)) { "adb.exe may not be a symlink" }
            require(!Files.isSymbolicLink(canonical)) { "adb.exe may not be a symlink" }
            rejectReparsePoint(canonical)
            require(Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS)) {
                "adb.exe is not a regular file"
            }
            val attrs = Files.readAttributes(
                canonical,
                java.nio.file.attribute.BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            return AdbExecutableFileIdentity(
                canonical,
                attrs.fileKey()?.toString(),
                attrs.size(),
                attrs.lastModifiedTime().toMillis(),
            )
        }

        private fun rejectReparsePoint(path: Path) {
            if (!Platform.isWindows()) return
            val attributes = Kernel32.INSTANCE.GetFileAttributes(path.toString())
            require(attributes != -1) { "cannot inspect adb.exe file attributes" }
            require(attributes and WinNT.FILE_ATTRIBUTE_REPARSE_POINT == 0) {
                "adb.exe may not be a reparse point"
            }
        }
    }
}

/** Re-checks path, file identity, publisher trust and content before every child spawn. */
internal class AdbExecutableGuard(
    private val report: AdbDoctorReport,
    private val verifier: WinTrustVerifier,
) {
    fun verifyBeforeSpawn() {
        if (!Platform.isWindows() && verifier.requiresWindows) {
            throw AdbSignatureException("production desktop companion requires Windows")
        }
        val current = AdbExecutableFileIdentity.read(report.canonicalPath)
        require(current.canonicalPath == report.canonicalPath) { "adb.exe canonical path changed" }
        if (verifier.requiresWindows) {
            require(report.fileKey != null && current.fileKey != null) { "adb.exe file identity is unavailable" }
        }
        require(current.fileKey == report.fileKey) { "adb.exe file identity changed" }
        require(current.fileSize == report.fileSize && current.lastModifiedMillis == report.lastModifiedMillis) {
            "adb.exe file metadata changed"
        }
        val digest = sha256File(report.canonicalPath)
        try {
            require(BridgeEncoding.hex(digest).equals(report.sha256Hex, ignoreCase = true)) {
                "adb.exe hash changed"
            }
        } finally {
            Arrays.fill(digest, 0)
        }
        if (!verifier.verify(report.canonicalPath)) {
            throw AdbSignatureException("WinVerifyTrust rejected adb.exe")
        }
    }

    private fun sha256File(path: Path): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            } finally {
                Arrays.fill(buffer, 0)
            }
        }
        return digest.digest()
    }
}
