// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import android.os.Build
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal data class RootSupervisedCommandSpec(
    val command: String,
    val cwd: String?,
    val environment: Map<String, String>,
    val timeoutMs: Long,
    val graceMs: Long,
    val maxOutputBytes: Int,
)

internal data class RootSupervisedCommandReport(
    val startStatus: Int,
    val leaderExited: Boolean,
    val exitCode: Int?,
    val timedOut: Boolean,
    val cancelled: Boolean,
    val leakedDescendants: Boolean,
    val settled: Boolean,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val processGroupId: Int = 0,
    val unreapedPid: Int = 0,
    val unreapedStartTicks: Long = 0L,
)

internal interface RootNativeSupervisorHandle {
    fun requestCancel(): Boolean

    fun close()
}

internal interface RootNativeSupervisorBackend {
    fun isKernelSubreaperSupported(): Boolean

    fun createCancelHandle(): RootNativeSupervisorHandle?

    fun runSupervised(
        spec: RootSupervisedCommandSpec,
        cancelHandle: RootNativeSupervisorHandle,
    ): RootSupervisedCommandReport
}

internal object RootNativeSupervisorJni {
    private const val LIB_NAME = "mar_root_supervisor"
    private val loadAttempted = AtomicBoolean(false)

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (loadAttempted.get()) return loaded
        loadAttempted.set(true)
        loaded = tryLoadStandard() || tryLoadFromClassPathApk()
        return loaded
    }

    private fun tryLoadStandard(): Boolean =
        runCatching {
            System.loadLibrary(LIB_NAME)
            true
        }.getOrDefault(false)

    private fun tryLoadFromClassPathApk(): Boolean = runCatching {
        val loaderPaths = Regex("""/data/app/[^"\s\],]+\.apk""")
            .findAll(RootNativeSupervisorJni::class.java.classLoader?.toString().orEmpty())
            .map { it.value }
        val apkPath = (
            System.getProperty("java.class.path").orEmpty().split(File.pathSeparatorChar).asSequence() +
                loaderPaths
            ).firstOrNull { it.startsWith("/data/app/") && it.endsWith(".apk") && File(it).isFile }
            ?: return@runCatching false
        val abis = runCatching { Build.SUPPORTED_64_BIT_ABIS?.toList() }.getOrNull()
            .orEmpty()
            .ifEmpty { listOf("arm64-v8a", "x86_64") }
        val apkDir = File(apkPath).parentFile
        for (abi in abis) {
            val archDir = if (abi == "arm64-v8a") "arm64" else abi
            val extracted = apkDir?.resolve("lib/$archDir/lib$LIB_NAME.so")
            if (extracted != null && extracted.isFile && runCatching { System.load(extracted.absolutePath); true }.getOrDefault(false)) {
                return@runCatching true
            }
            if (runCatching { System.load("$apkPath!/lib/$abi/lib$LIB_NAME.so"); true }.getOrDefault(false)) {
                return@runCatching true
            }
        }
        false
    }.getOrDefault(false)

    @JvmStatic
    external fun nativeProbeKernelSupport(): Boolean

    @JvmStatic
    external fun nativeCreateCancelToken(): Long

    @JvmStatic
    external fun nativeSignalCancelToken(writeFd: Int): Boolean

    @JvmStatic
    external fun nativeCloseCancelToken(readFd: Int, writeFd: Int)

    /**
     * `linkat(AT_EMPTY_PATH)` for the FD-pinned content commit. Returns 0 on success, or a
     * negative errno (`-EOPNOTSUPP` when the kernel/ABI does not provide it).
     */
    @JvmStatic
    external fun nativeLinkAtEmptyPath(parentFd: Int, sourceFd: Int, name: String): Int

    /** Atomic no-clobber entry move on retained parent FDs. Returns 0 or negative errno. */
    @JvmStatic
    external fun nativeRenameAtNoReplace(srcParentFd: Int, srcName: String, dstParentFd: Int, dstName: String): Int

    /** `unlinkat(AT_REMOVEDIR)` for the protected staging directory. 0 or negative errno. */
    @JvmStatic
    external fun nativeRemoveDirectoryAt(parentFd: Int, name: String): Int

    @JvmStatic
    external fun nativeRunSupervised(
        command: String,
        cwd: String?,
        envVars: Array<String>,
        timeoutMs: Long,
        graceMs: Long,
        maxOutputBytes: Int,
        cancelReadFd: Int,
        outMeta: LongArray,
    ): Array<ByteArray>?
}

internal class JniCancelHandle(
    val readFd: Int,
    private val writeFd: Int,
) : RootNativeSupervisorHandle {
    private val closed = AtomicBoolean(false)

    override fun requestCancel(): Boolean {
        if (closed.get()) return false
        return runCatching { RootNativeSupervisorJni.nativeSignalCancelToken(writeFd) }.getOrDefault(false)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { RootNativeSupervisorJni.nativeCloseCancelToken(readFd, writeFd) }
        }
    }
}

internal object JniRootNativeSupervisorBackend : RootNativeSupervisorBackend {
    override fun isKernelSubreaperSupported(): Boolean =
        RootNativeSupervisorJni.ensureLoaded() &&
            runCatching { RootNativeSupervisorJni.nativeProbeKernelSupport() }.getOrDefault(false)

    override fun createCancelHandle(): RootNativeSupervisorHandle? {
        if (!RootNativeSupervisorJni.ensureLoaded()) return null
        val packed = runCatching { RootNativeSupervisorJni.nativeCreateCancelToken() }.getOrDefault(-1L)
        if (packed < 0L) return null
        val readFd = (packed ushr 32).toInt()
        val writeFd = (packed and 0xffffffffL).toInt()
        return JniCancelHandle(readFd = readFd, writeFd = writeFd)
    }

    override fun runSupervised(
        spec: RootSupervisedCommandSpec,
        cancelHandle: RootNativeSupervisorHandle,
    ): RootSupervisedCommandReport {
        val jniHandle = cancelHandle as? JniCancelHandle ?: return failedPreSpawnReport()
        val meta = LongArray(11)
        val envArray = spec.environment.entries
            .map { (k, v) -> "$k=$v" }
            .toTypedArray()
        val streams = runCatching {
            RootNativeSupervisorJni.nativeRunSupervised(
                command = spec.command,
                cwd = spec.cwd,
                envVars = envArray,
                timeoutMs = spec.timeoutMs,
                graceMs = spec.graceMs,
                maxOutputBytes = spec.maxOutputBytes,
                cancelReadFd = jniHandle.readFd,
                outMeta = meta,
            )
        }.getOrNull()
        val startStatus = meta[0].toInt()
        if (streams == null && startStatus != -3) {
            return failedPreSpawnReport(if (startStatus < 0) startStatus else -1)
        }
        val outBytes = streams?.getOrNull(0) ?: ByteArray(0)
        val errBytes = streams?.getOrNull(1) ?: ByteArray(0)
        val pgidAndPid = meta[9]
        return RootSupervisedCommandReport(
            startStatus = startStatus,
            leaderExited = meta[1] == 1L,
            exitCode = meta[2].toInt().takeIf { it >= 0 },
            timedOut = meta[3] == 1L,
            cancelled = meta[4] == 1L,
            leakedDescendants = meta[5] == 1L,
            settled = meta[6] == 1L,
            stdout = outBytes,
            stderr = errBytes,
            stdoutTruncated = meta[7] == 1L,
            stderrTruncated = meta[8] == 1L,
            processGroupId = (pgidAndPid ushr 32).toInt(),
            unreapedPid = (pgidAndPid and 0xffffffffL).toInt(),
            unreapedStartTicks = meta[10],
        )
    }

    private fun failedPreSpawnReport(code: Int = -1) = RootSupervisedCommandReport(
        startStatus = code,
        leaderExited = false,
        exitCode = null,
        timedOut = false,
        cancelled = false,
        leakedDescendants = false,
        settled = true,
        stdout = ByteArray(0),
        stderr = ByteArray(0),
        stdoutTruncated = false,
        stderrTruncated = false,
    )
}
