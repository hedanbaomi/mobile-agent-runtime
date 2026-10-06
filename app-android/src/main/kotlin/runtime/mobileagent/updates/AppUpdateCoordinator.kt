// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

internal enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, READY, ERROR }

internal data class AppUpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: AppRelease? = null,
    val downloaded: Long = 0,
    val message: String = "",
    val prompt: Boolean = false,
    val compatible: Boolean = true,
) {
    val busy: Boolean get() = phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)
}

internal interface UpdateCheckStore {
    var checkedDay: String
    var cachedRelease: AppRelease?
    var dismissedPrompt: String
}

/** Process lifetime, shared by foreground, settings and announcement links. No autonomous downloads. */
internal class AppUpdateCoordinator(
    private val source: ReleaseSource,
    private val store: UpdateCheckStore,
    private val scope: CoroutineScope,
    private val directory: File,
    private val installedVersion: String,
    private val compatible: Boolean,
    private val verify: (File, AppRelease) -> Unit,
    private val day: () -> String = { LocalDate.now().toString() },
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutable = MutableStateFlow(AppUpdateState(compatible = compatible))
    val state = mutable.asStateFlow()
    private val operation = Mutex()
    private val downloader = ReleaseDownloader(directory, source)
    private var lastFailure: Long? = null
    private var download: Deferred<File?>? = null

    fun check(manual: Boolean = false) = scope.launch {
        if (!operation.tryLock()) return@launch
        try {
            val today = day()
            val cached = store.cachedRelease
            if (mutable.value.phase == UpdatePhase.IDLE && cached != null) showRelease(cached, today, false)
            if (!manual && store.checkedDay == today && cached != null) {
                return@launch
            }
            val failureAt = lastFailure
            if (!manual && failureAt != null && elapsed() - failureAt in 0 until 15 * 60 * 1000) return@launch
            mutable.update { it.copy(phase = UpdatePhase.CHECKING, message = "正在检查正式版本…") }
            val release = withContext(Dispatchers.IO) { source.latest().validate() }
            // A failed request never records a successful day or overwrites the last valid candidate.
            store.cachedRelease = release
            store.checkedDay = today
            lastFailure = null
            showRelease(release, today, manual)
        } catch (cancelled: CancellationException) {
            mutable.update { it.copy(phase = if (it.release != null) UpdatePhase.AVAILABLE else UpdatePhase.IDLE) }
            throw cancelled
        } catch (_: Exception) {
            lastFailure = elapsed()
            mutable.update { it.copy(phase = UpdatePhase.ERROR, message = "检查失败，请检查网络后重试。", prompt = manual) }
        } finally { operation.unlock() }
    }

    private fun showRelease(release: AppRelease?, today: String, manual: Boolean) {
        val candidate = release?.validate()?.takeIf { ReleaseVersion.parse(it.version) > ReleaseVersion.parse(installedVersion) }
        if (candidate == null) {
            mutable.value = AppUpdateState(message = "当前已是最新正式版本。", prompt = manual, compatible = compatible)
            return
        }
        val ready = downloader.file(candidate).isFile
        mutable.value = AppUpdateState(
            phase = if (ready) UpdatePhase.READY else UpdatePhase.AVAILABLE,
            release = candidate,
            message = if (compatible) "发现新版本 ${candidate.version}" else "发现 ${candidate.version}；当前设备或验证版签名无法覆盖升级。",
            prompt = manual || store.dismissedPrompt != "$today:${candidate.version}", compatible = compatible,
        )
    }

    fun dismiss() {
        mutable.value.release?.let { store.dismissedPrompt = "${day()}:${it.version}" }
        mutable.update { it.copy(prompt = false) }
    }

    /** Concurrent clicks reuse one task. The captured candidate cannot be replaced mid-download. */
    @Synchronized
    fun downloadOrReady(): Deferred<File?> {
        download?.takeIf { it.isActive }?.let { return it }
        val task = scope.async {
            if (!operation.tryLock()) return@async null
            try {
                val release = mutable.value.release?.validate() ?: return@async null
                if (!compatible) return@async null
                mutable.update { it.copy(phase = UpdatePhase.DOWNLOADING, downloaded = 0, prompt = true, message = "正在下载…") }
                val file = withContext(Dispatchers.IO) {
                    val existing = downloader.file(release)
                    val file = if (existing.isFile && existing.length() == release.size && sha256(existing) == release.sha256) existing
                    else downloader.download(release, currentCoroutineContext()) { bytes ->
                        mutable.update { it.copy(downloaded = bytes) }
                    }
                    mutable.update { it.copy(phase = UpdatePhase.VERIFYING, message = "正在验证安装包…") }
                    verify(file, release)
                    file
                }
                mutable.update { it.copy(phase = UpdatePhase.READY, downloaded = release.size, message = "下载完成，等待系统安装确认。") }
                file
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(phase = UpdatePhase.AVAILABLE, downloaded = 0, message = "下载已取消。") }
                throw cancelled
            } catch (_: Exception) {
                mutable.value.release?.let { downloader.file(it).delete() }
                mutable.update { it.copy(phase = UpdatePhase.ERROR, message = "下载或安全校验失败，请重试。") }
                null
            } finally { operation.unlock() }
        }
        download = task
        return task
    }

    fun cancelDownload() { download?.cancel() }

    fun installationMessage(message: String) { mutable.update { it.copy(message = message, prompt = true) } }

    /** Verify again immediately before granting the installer a read-only URI. */
    suspend fun readyForInstall(): File? = withContext(Dispatchers.IO) {
        val release = mutable.value.release ?: return@withContext null
        if (mutable.value.phase != UpdatePhase.READY || !compatible) return@withContext null
        val file = downloader.file(release)
        try { verify(file, release); file } catch (_: Exception) {
            file.delete()
            mutable.update { it.copy(phase = UpdatePhase.ERROR, message = "安装包校验失败，请重新下载。") }
            null
        }
    }
}
