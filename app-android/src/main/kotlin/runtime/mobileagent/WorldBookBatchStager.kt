// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import runtime.mobileagent.domain.worldbook.WorldBookFileSkip
import runtime.mobileagent.serialization.worldbook.WorldBookArchive
import runtime.mobileagent.serialization.worldbook.WorldBookArchiveException

/** One candidate JSON copied into private staging; [sha256] binds preview to confirm. */
internal data class WorldBookStagedFile(
    val key: String,
    val sourcePath: String,
    val fallbackName: String,
    val file: File,
    val bytes: Long,
    val sha256: String,
)

internal data class WorldBookStagedSkip(val sourcePath: String, val reason: WorldBookFileSkip)

internal class WorldBookStaging(val directory: File, val label: String) {
    val files = ArrayList<WorldBookStagedFile>()
    val skips = ArrayList<WorldBookStagedSkip>()
    var bytes = 0L
        private set

    fun add(file: WorldBookStagedFile) { files += file; bytes += file.bytes }
    fun discard() { directory.deleteRecursively() }
}

/** A whole selection was rejected; nothing from it may be imported. The message is user-facing. */
internal class WorldBookBatchException(message: String) : IllegalArgumentException(message)

/**
 * Copies picked files, a picked folder or ZIP archives into app-private staging before any
 * parsing. Preview and confirm both read the staged bytes, so a provider file that changes or
 * loses its grant in between cannot alter what the user approved. Sources are addressed by
 * opaque keys (document URI strings in production) so the boundaries are unit-testable;
 * classification and planning stay in the pure JVM layer.
 */
internal class WorldBookBatchStager(
    private val openSource: (String) -> InputStream?,
    private val sourceName: (String) -> String?,
    private val cacheRoot: File,
    private val chinese: Boolean,
    private val checkpoint: () -> Unit,
    private val progress: (String) -> Unit,
) {
    fun stageFiles(keys: List<String>): WorldBookStaging {
        if (keys.size > MAX_SELECTED) fail("一次最多选择 $MAX_SELECTED 个文件。", "Select at most $MAX_SELECTED files at once.")
        val staging = newStaging(if (chinese) "${keys.size} 个文件" else "${keys.size} files")
        return guarded(staging) {
            keys.forEachIndexed { index, key ->
                checkpoint()
                val name = nameOf(key) ?: "file-${index + 1}"
                progress(text("正在复制 ${index + 1}/${keys.size}：$name", "Copying ${index + 1}/${keys.size}: $name"))
                stagePicked(staging, name) { openSource(key) }
            }
        }
    }

    fun stageFolder(root: KnowledgeFolderNode?): WorldBookStaging {
        root ?: fail("无法打开所选文件夹。", "The selected folder could not be opened.")
        val label = root.name?.takeIf { it.isNotBlank() } ?: text("文件夹", "folder")
        progress(text("正在扫描文件夹：$label", "Scanning folder: $label"))
        val entries = when (val walked = KnowledgeFolderWalk.walk(root) { cancelledNow() }) {
            is KnowledgeFolderWalkResult.Files -> walked.files
            is KnowledgeFolderWalkResult.Rejected -> fail(walkMessage(walked.reason, true), walkMessage(walked.reason, false))
            KnowledgeFolderWalkResult.Cancelled -> throw CancellationException("folder scan cancelled")
        }.filterNot { WorldBookArchive.isSystemMetadata(it.relativePath) }.sortedBy { it.relativePath.lowercase(Locale.ROOT) }
        val staging = newStaging(label)
        return guarded(staging) {
            entries.forEachIndexed { index, entry ->
                checkpoint()
                val source = { openSource(entry.key) }
                when (val skip = typeSkip(entry.relativePath)) {
                    null -> {
                        progress(text("正在复制 ${index + 1}/${entries.size}：${entry.relativePath}",
                            "Copying ${index + 1}/${entries.size}: ${entry.relativePath}"))
                        if (extension(entry.relativePath) == "zip") stageZip(staging, entry.relativePath, source)
                        else stageJson(staging, entry.relativePath, source)
                    }
                    else -> staging.skips += WorldBookStagedSkip(entry.relativePath, skip)
                }
            }
        }
    }

    /** Explicitly picked files may lack an extension; sniff the bytes instead of trusting the name. */
    private fun stagePicked(staging: WorldBookStaging, name: String, open: () -> InputStream?) {
        when (val skip = typeSkip(name)) {
            null -> if (extension(name) == "zip") stageZip(staging, name, open) else stageJson(staging, name, open)
            WorldBookFileSkip.UNSUPPORTED_TYPE -> {
                val temporary = File(staging.directory, "sniff-${staging.files.size}.tmp")
                val copied = when (val result = copy(open, temporary, WorldBookArchive.MAX_ARCHIVE_BYTES)) {
                    is Copy.Done -> result
                    is Copy.Skipped -> return skip(staging, name, result.reason, temporary)
                }
                val head = temporary.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
                when {
                    WorldBookArchive.looksLikeZip(head) -> expand(staging, name, temporary)
                    looksLikeJson(temporary) && copied.bytes <= MAX_FILE_BYTES -> register(staging, name, temporary, copied)
                    looksLikeJson(temporary) -> skip(staging, name, WorldBookFileSkip.TOO_LARGE, temporary)
                    else -> skip(staging, name, WorldBookFileSkip.UNSUPPORTED_TYPE, temporary)
                }
            }
            else -> staging.skips += WorldBookStagedSkip(name, skip)
        }
    }

    private fun stageJson(staging: WorldBookStaging, path: String, open: () -> InputStream?) {
        val target = File(staging.directory, "c${staging.files.size}.json")
        when (val copied = copy(open, target, MAX_FILE_BYTES)) {
            is Copy.Done -> register(staging, path, target, copied)
            is Copy.Skipped -> skip(staging, path, copied.reason, target)
        }
    }

    private fun stageZip(staging: WorldBookStaging, path: String, open: () -> InputStream?) {
        val temporary = File(staging.directory, "archive-${staging.files.size}.zip")
        when (val copied = copy(open, temporary, WorldBookArchive.MAX_ARCHIVE_BYTES)) {
            is Copy.Done -> expand(staging, path, temporary)
            is Copy.Skipped -> skip(staging, path, copied.reason, temporary)
        }
    }

    private fun expand(staging: WorldBookStaging, path: String, archive: File) {
        try {
            val skips = WorldBookArchive.read(archive, MAX_TOTAL_BYTES - staging.bytes, MAX_CANDIDATES - staging.files.size, checkpoint) { member ->
                val target = File(staging.directory, "c${staging.files.size}.json")
                target.writeBytes(member.bytes)
                register(staging, "$path/${member.path}", target, Copy.Done(member.bytes.size.toLong(), sha256(member.bytes)))
            }
            skips.forEach { staging.skips += WorldBookStagedSkip("$path/${it.path}", it.reason) }
        } catch (error: WorldBookArchiveException) {
            if (error.limit) limitFailure()
            staging.skips += WorldBookStagedSkip(path, WorldBookFileSkip.UNSUPPORTED_ARCHIVE)
        } finally {
            archive.delete()
        }
    }

    private fun register(staging: WorldBookStaging, path: String, file: File, copied: Copy.Done) {
        if (staging.files.size >= MAX_CANDIDATES || staging.bytes + copied.bytes > MAX_TOTAL_BYTES) {
            file.delete()
            limitFailure()
        }
        val key = "c${staging.files.size}"
        val stored = File(staging.directory, "$key.json")
        if (file != stored) check(file.renameTo(stored)) { "World book staging could not be written" }
        staging.add(WorldBookStagedFile(key, path, fallbackName(path), stored, copied.bytes, copied.sha256))
    }

    private fun skip(staging: WorldBookStaging, path: String, reason: WorldBookFileSkip, partial: File) {
        partial.delete()
        staging.skips += WorldBookStagedSkip(path, reason)
    }

    private sealed interface Copy {
        data class Done(val bytes: Long, val sha256: String) : Copy
        data class Skipped(val reason: WorldBookFileSkip) : Copy
    }

    /** Bounded copy with SHA-256. A file the provider cannot open or read only skips that file. */
    private fun copy(open: () -> InputStream?, target: File, max: Long): Copy {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val stream = try {
            open()
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return Copy.Skipped(WorldBookFileSkip.UNREADABLE)
        // Write failures (for example a full disk) are not the source's fault and fail the selection.
        stream.use { input ->
            target.outputStream().use { output ->
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    checkpoint()
                    val count = try { input.read(chunk) } catch (_: IOException) { return Copy.Skipped(WorldBookFileSkip.UNREADABLE) }
                    if (count < 0) break
                    if (count == 0) return Copy.Skipped(WorldBookFileSkip.UNREADABLE)
                    total += count
                    if (total > max) return Copy.Skipped(WorldBookFileSkip.TOO_LARGE)
                    digest.update(chunk, 0, count)
                    output.write(chunk, 0, count)
                }
            }
        }
        return Copy.Done(total, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun looksLikeJson(file: File): Boolean = file.inputStream().use { input ->
        val head = ByteArray(64)
        val count = input.read(head).coerceAtLeast(0)
        val text = String(head, 0, count, Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
        text.startsWith("{")
    }

    private fun nameOf(key: String): String? = try {
        sourceName(key)?.takeIf { it.isNotBlank() }?.take(256)
    } catch (_: RuntimeException) {
        null
    }

    private fun newStaging(label: String): WorldBookStaging {
        val directory = File(stagingRoot(cacheRoot), "batch-" + UUID.randomUUID())
        check(directory.mkdirs()) { "World book staging could not be created" }
        return WorldBookStaging(directory, label)
    }

    private inline fun guarded(staging: WorldBookStaging, block: () -> Unit): WorldBookStaging {
        try { block() } catch (error: Throwable) { staging.discard(); throw error }
        return staging
    }

    private fun cancelledNow(): Boolean = try { checkpoint(); false } catch (_: CancellationException) { true }

    private fun limitFailure(): Nothing = fail(
        "一次最多导入 $MAX_CANDIDATES 个世界书 JSON（合计 ${MAX_TOTAL_BYTES / MIB} MiB），本次选择超出；未导入任何内容。请分几次选择子文件夹或部分文件。",
        "One import accepts at most $MAX_CANDIDATES world book JSON files (${MAX_TOTAL_BYTES / MIB} MiB in total) and this selection is larger. Nothing was imported; choose subfolders or fewer files in several imports.",
    )

    private fun walkMessage(reason: KnowledgeFolderWalkReason, zh: Boolean): String = when (reason) {
        KnowledgeFolderWalkReason.EMPTY -> if (zh) "所选文件夹是空的。" else "The selected folder is empty."
        KnowledgeFolderWalkReason.FILE_LIMIT -> if (zh) "所选文件夹超过 ${KnowledgeFolderWalk.MAX_FILES} 个文件；请选择只含世界书的文件夹（如酒馆的 worlds 目录），或改用 ZIP。"
            else "The folder has more than ${KnowledgeFolderWalk.MAX_FILES} files. Choose a folder that holds only world books (such as SillyTavern's worlds folder) or use a ZIP."
        KnowledgeFolderWalkReason.DEPTH_LIMIT -> if (zh) "所选文件夹层级超过 ${KnowledgeFolderWalk.MAX_DEPTH} 层。" else "The folder is nested deeper than ${KnowledgeFolderWalk.MAX_DEPTH} levels."
        KnowledgeFolderWalkReason.NODE_LIMIT -> if (zh) "所选文件夹条目过多，请缩小范围后重试。" else "The folder has too many items. Choose a smaller folder."
        KnowledgeFolderWalkReason.UNREADABLE -> if (zh) "无法完整读取所选文件夹；为避免只导入一部分，本次未导入任何内容。"
            else "The folder could not be read completely. Nothing was imported to avoid a partial import."
    }

    private fun text(zh: String, en: String) = if (chinese) zh else en
    private fun fail(zh: String, en: String): Nothing = throw WorldBookBatchException(text(zh, en))

    companion object {
        const val MAX_SELECTED = 128
        const val MAX_CANDIDATES = 128
        const val MAX_FILE_BYTES = WorldBookFileIO.MAX_BYTES.toLong()
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        private const val MIB = 1024L * 1024
        private const val STALE_MILLIS = 24L * 60 * 60 * 1000

        fun stagingRoot(cacheRoot: File) = File(cacheRoot, "worldbook-import")

        /** Production wiring: content:// documents only, names from the provider's display name. */
        fun forResolver(resolver: ContentResolver, cacheRoot: File, chinese: Boolean,
            checkpoint: () -> Unit, progress: (String) -> Unit) = WorldBookBatchStager(
            openSource = { key ->
                val uri = Uri.parse(key)
                require(uri.scheme == "content") { "Select world books with the system file picker" }
                resolver.openInputStream(uri)
            },
            sourceName = { key ->
                resolver.query(Uri.parse(key), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            },
            cacheRoot = cacheRoot, chinese = chinese, checkpoint = checkpoint, progress = progress,
        )

        /** Remove batches left by a killed process; current previews are newer than a day. */
        fun sweep(cacheRoot: File, now: Long = System.currentTimeMillis()) {
            stagingRoot(cacheRoot).listFiles()?.filter { it.name.startsWith("batch-") && now - it.lastModified() > STALE_MILLIS }
                ?.forEach { it.deleteRecursively() }
        }

        fun typeSkip(path: String): WorldBookFileSkip? = when (extension(path)) {
            "zip" -> null
            "7z", "rar", "tar", "gz", "tgz", "xz", "bz2" -> WorldBookFileSkip.UNSUPPORTED_ARCHIVE
            else -> WorldBookArchive.classifyName(path)
        }

        fun fallbackName(path: String): String {
            val base = path.substringAfterLast('/')
            val stem = if (base.contains('.')) base.substringBeforeLast('.') else base
            return stem.take(256).ifBlank { "Imported world book" }
        }

        private fun extension(path: String) = path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
