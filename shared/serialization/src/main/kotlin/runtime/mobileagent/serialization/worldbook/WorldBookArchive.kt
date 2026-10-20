// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.worldbook

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import runtime.mobileagent.domain.worldbook.WorldBookFileSkip

/** 压缩包内一个 JSON 成员：规范化逻辑路径与完整解压内容（仅在回调期间有效）。 */
class WorldBookArchiveMember(val path: String, val bytes: ByteArray)

/** 没有作为候选读取的成员及原因。macOS 元数据（`__MACOSX/`、`._*`、`.DS_Store`）不计入。 */
data class WorldBookArchiveSkip(val path: String, val reason: WorldBookFileSkip)

/** 整个压缩包不可用（结构损坏、ZIP64/分卷）；[limit] 为真表示超出调用方给定的批量额度。 */
class WorldBookArchiveException(message: String, val limit: Boolean = false) : IllegalArgumentException(message)

/**
 * 世界书批量导入用的有界 ZIP 读取器。只读取 `.json` 成员，不解压到磁盘，也不展开嵌套压缩包。
 *
 * 安全边界：
 *  - 先完整校验中央目录：拒绝分卷、ZIP64、越界、重叠、条目数超限；
 *  - 每个候选成员校验本地头与中央目录一致、数据区不与下一成员重叠，按中央目录声明的大小
 *    有界解压并校验 CRC-32；单个成员失败只跳过该成员并报告原因；
 *  - 加密、未知压缩方式、链接、穿越路径、重复路径的成员一律跳过，不解释为可执行内容。
 *
 * 文件名编码：设置 UTF-8 标志时按 UTF-8；否则优先 Info-ZIP Unicode Path 扩展（0x7075，
 * 7-Zip/WinRAR 写入），再依次尝试严格 UTF-8 与 GB18030（Windows 中文系统自带压缩常见）。
 */
object WorldBookArchive {
    const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
    const val MAX_ENTRIES = 2_000
    const val MAX_MEMBER_BYTES = 16L * 1024 * 1024

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    private val ARCHIVE_EXTENSIONS = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "xz", "bz2")

    /**
     * @param maxJsonBytes 本压缩包内候选 JSON 的声明解压总量上限（调用方传入批次剩余额度）。
     * @param maxJsonMembers 本压缩包内候选 JSON 的数量上限（调用方传入批次剩余额度）。
     * @param checkpoint 在成员之间和解压循环中调用；抛出即中止读取（例如用户取消）。
     * @return 被跳过的成员；成功读取的 JSON 按中央目录顺序传给 [onMember]。
     * @throws WorldBookArchiveException 压缩包整体不可用或超出给定额度。
     */
    fun read(
        file: File,
        maxJsonBytes: Long,
        maxJsonMembers: Int,
        checkpoint: () -> Unit = {},
        onMember: (WorldBookArchiveMember) -> Unit,
    ): List<WorldBookArchiveSkip> {
        if (!file.isFile) throw WorldBookArchiveException("ZIP archive is not readable")
        if (file.length() > MAX_ARCHIVE_BYTES) throw WorldBookArchiveException("ZIP archive exceeds the 64 MiB limit")
        return RandomAccessFile(file, "r").use { raf ->
            val directory = WorldBookZipStructure.directory(raf, MAX_ENTRIES)
            val skips = ArrayList<WorldBookArchiveSkip>()
            val candidates = select(directory.entries, skips)
            if (candidates.size > maxJsonMembers) {
                throw WorldBookArchiveException("ZIP archive has ${candidates.size} JSON files; this import accepts at most $maxJsonMembers", limit = true)
            }
            if (candidates.sumOf { it.uncompressedSize } > maxJsonBytes) {
                throw WorldBookArchiveException("JSON files in the ZIP archive exceed the remaining import size limit", limit = true)
            }
            val offsets = directory.entries.map { it.localOffset }.sorted()
            for (entry in candidates) {
                checkpoint()
                val next = offsets.firstOrNull { it > entry.localOffset } ?: directory.centralOffset
                val bytes = WorldBookZipStructure.readMember(raf, entry, minOf(next, directory.centralOffset), checkpoint)
                if (bytes == null) skips += WorldBookArchiveSkip(checkNotNull(entry.path), WorldBookFileSkip.CORRUPT)
                else onMember(WorldBookArchiveMember(checkNotNull(entry.path), bytes))
            }
            skips
        }
    }

    private fun select(entries: List<WorldBookZipEntry>, skips: MutableList<WorldBookArchiveSkip>): List<WorldBookZipEntry> {
        val candidates = ArrayList<WorldBookZipEntry>()
        val seen = HashSet<String>()
        for (entry in entries) {
            val path = entry.path
            if (path == null) {
                skips += WorldBookArchiveSkip(String(entry.rawName, Charsets.UTF_8), WorldBookFileSkip.UNSAFE_PATH)
                continue
            }
            if (entry.directory || isSystemMetadata(path)) continue
            if (!seen.add(path.lowercase(Locale.ROOT))) {
                skips += WorldBookArchiveSkip(path, WorldBookFileSkip.DUPLICATE_PATH)
                continue
            }
            val reason = if (entry.link) WorldBookFileSkip.LINK else classifyName(path) ?: memberSkip(entry)
            if (reason != null) skips += WorldBookArchiveSkip(path, reason) else candidates += entry
        }
        return candidates
    }

    private fun memberSkip(entry: WorldBookZipEntry): WorldBookFileSkip? = when {
        entry.encrypted -> WorldBookFileSkip.ENCRYPTED
        entry.method != WorldBookZipStructure.METHOD_STORED && entry.method != WorldBookZipStructure.METHOD_DEFLATED ->
            WorldBookFileSkip.UNSUPPORTED_COMPRESSION
        entry.uncompressedSize > MAX_MEMBER_BYTES -> WorldBookFileSkip.TOO_LARGE
        else -> null
    }

    /** `PK\u0003\u0004` 或空包 `PK\u0005\u0006`。 */
    fun looksLikeZip(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
            ((head[2] == 0x03.toByte() && head[3] == 0x04.toByte()) || (head[2] == 0x05.toByte() && head[3] == 0x06.toByte()))

    /** 文件扩展名分类；返回 null 表示 JSON 候选。 */
    fun classifyName(name: String): WorldBookFileSkip? =
        when (name.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "json" -> null
            in ARCHIVE_EXTENSIONS -> WorldBookFileSkip.NESTED_ARCHIVE
            in IMAGE_EXTENSIONS -> WorldBookFileSkip.IMAGE
            else -> WorldBookFileSkip.UNSUPPORTED_TYPE
        }

    /** 系统生成的元数据文件，不作为用户内容报告。 */
    fun isSystemMetadata(path: String): Boolean {
        val segments = path.split('/').filter { it.isNotEmpty() }
        val base = segments.lastOrNull() ?: return false
        return segments.any { it == "__MACOSX" } || base.startsWith("._") || base == ".DS_Store" ||
            base.equals("Thumbs.db", ignoreCase = true) || base.equals("desktop.ini", ignoreCase = true)
    }
}
