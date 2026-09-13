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
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.W32APIOptions
import com.sun.jna.win32.StdCallLibrary
import java.nio.charset.StandardCharsets
import java.nio.file.LinkOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
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

/**
 * JNA requires these structures to be JVM-visible (not private nested classes)
 * so field reflection on JDK 16+ does not throw IllegalAccessException before
 * WinVerifyTrust runs.
 */
@Structure.FieldOrder("cbStruct", "pcwszFilePath", "hFile", "pgKnownSubject")
internal class WinTrustFileInfo() : Structure() {
    @JvmField var cbStruct: Int = 0
    @JvmField var pcwszFilePath: Pointer? = Pointer.NULL
    @JvmField var hFile: Pointer? = Pointer.NULL
    @JvmField var pgKnownSubject: Pointer? = Pointer.NULL

    constructor(path: Pointer) : this() {
        pcwszFilePath = path
        cbStruct = size()
        write()
    }
}

@Structure.FieldOrder("cbStruct", "pPolicyCallbackData", "pSIPClientData", "dwUIChoice",
    "fdwRevocationChecks", "dwUnionChoice", "pFile", "dwStateAction",
    "hWVTStateData", "pwszURLReference", "dwProvFlags", "dwUIContext")
internal class WinTrustData() : Structure() {
    @JvmField var cbStruct: Int = 0
    @JvmField var pPolicyCallbackData: Pointer? = Pointer.NULL
    @JvmField var pSIPClientData: Pointer? = Pointer.NULL
    @JvmField var dwUIChoice: Int = 2
    @JvmField var fdwRevocationChecks: Int = 0
    @JvmField var dwUnionChoice: Int = 1
    @JvmField var pFile: Pointer? = Pointer.NULL
    @JvmField var dwStateAction: Int = 0
    @JvmField var hWVTStateData: Pointer? = Pointer.NULL
    @JvmField var pwszURLReference: Pointer? = Pointer.NULL
    @JvmField var dwProvFlags: Int = 0x80
    @JvmField var dwUIContext: Int = 0

    constructor(fileInfo: Pointer) : this() {
        pFile = fileInfo
        cbStruct = size()
        write()
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

/**
 * Portable executable identity for the validated adb.exe.
 *
 * `BasicFileAttributes.fileKey()` is optional by contract and the JDK's Windows provider
 * returns `null` on current builds, which previously made the spawn guard fail closed with
 * "adb.exe file identity is unavailable" and blocked `devices`/`pair`/`run` on an otherwise
 * trusted host. Prefer the provider key, then the Win32 handle identity (volume serial + file
 * index), and keep a bounded path/size/mtime tuple as the last resort so the guard can still
 * run. The content SHA-256 re-check in [AdbExecutableGuard] remains the authoritative
 * replacement check in every case.
 */
internal object AdbFileIdentity {
    fun read(path: Path, fileSize: Long, lastModifiedMillis: Long): String {
        providerFileKey(path)?.let { return it }
        if (Platform.isWindows()) windowsFileKey(path)?.let { return it }
        return "fallback:$path|$fileSize|$lastModifiedMillis"
    }

    private fun providerFileKey(path: Path): String? = runCatching {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            .fileKey()
            ?.toString()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Win32 identity (volume serial + 128-bit file id) read through the handle API. The JDK's
     * Windows provider can return a null `fileKey`, but the OS handle identity is stable across
     * metadata changes and detects a replacement file.
     */
    private fun windowsFileKey(path: Path): String? = runCatching {
        val handle = Kernel32.INSTANCE.CreateFile(
            path.toString(),
            WinNT.GENERIC_READ,
            WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE or WinNT.FILE_SHARE_DELETE,
            null,
            WinNT.OPEN_EXISTING,
            WinNT.FILE_ATTRIBUTE_NORMAL,
            null,
        )
        if (handle == null || handle == WinBase.INVALID_HANDLE_VALUE) return@runCatching null
        try {
            val information = WinBase.FILE_ID_INFO()
            val read = Kernel32.INSTANCE.GetFileInformationByHandleEx(
                handle,
                FILE_ID_INFO_CLASS,
                information.pointer,
                WinDef.DWORD(information.size().toLong()),
            )
            if (!read) return@runCatching null
            information.read()
            val identifier = information.FileId.Identifier.joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
            val volume = java.lang.Long.toUnsignedString(information.VolumeSerialNumber, 16)
            "win32:$volume:$identifier"
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }.getOrNull()

    private const val FILE_ID_INFO_CLASS = 18
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
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            val fileSize = attrs.size()
            val lastModifiedMillis = attrs.lastModifiedTime().toMillis()
            return AdbExecutableFileIdentity(
                canonical,
                AdbFileIdentity.read(canonical, fileSize, lastModifiedMillis),
                fileSize,
                lastModifiedMillis,
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
            // Production Windows validation must keep a real file identity; only the
            // degraded path/size/mtime tuple is rejected as unavailable.
            require(current.fileKey != null && !current.fileKey.startsWith("fallback:")) {
                "adb.exe file identity is unavailable"
            }
        }
        require(current.fileKey != null && current.fileKey == report.fileKey) {
            "adb.exe file identity changed"
        }
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
