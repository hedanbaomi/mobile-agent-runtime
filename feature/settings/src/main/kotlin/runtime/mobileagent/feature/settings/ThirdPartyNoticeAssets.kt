// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.settings

import android.content.Context
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads only the signed-in-package notice assets. This object never opens a
 * network connection and never resolves paths supplied by a remote source.
 */
object ThirdPartyNoticeAssets {
    private const val AGPL_PATH = "AGPL-3.0-only.txt"
    private const val OVERVIEW_PATH = "THIRD_PARTY_NOTICES.md"
    private const val INDEX_PATH = "licenses/index.json"
    private const val MODEL_LICENSE_PATH = "modelpacks/all-MiniLM-L6-v2/LICENSES/Apache-2.0.txt"
    private const val LICENSE_PREFIX = "licenses/"
    private const val MAX_OVERVIEW_BYTES = 2L * 1024L * 1024L
    private const val MAX_AGPL_BYTES = 2L * 1024L * 1024L
    private const val MAX_INDEX_BYTES = 512L * 1024L
    private const val MAX_LICENSE_FILE_BYTES = 4L * 1024L * 1024L
    private const val MAX_CATALOG_BYTES = 4L * 1024L * 1024L
    private const val MAX_COMPONENT_BYTES = 16L * 1024L * 1024L
    private const val MAX_COMPONENTS = 512
    private const val MAX_FILES_PER_COMPONENT = 64

    /** Load the first-party license without blocking composition or exposing a path. */
    suspend fun loadAgplText(context: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            Result.success(readBytes(context, AGPL_PATH, MAX_AGPL_BYTES) { it == AGPL_PATH }.toString(Charsets.UTF_8))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    /** Load the overview and schema-versioned component index on a worker dispatcher. */
    suspend fun loadCatalog(context: Context): ThirdPartyNoticesUiState = withContext(Dispatchers.IO) {
        var overviewText = ""
        try {
            val overviewBytes = readBytes(context, OVERVIEW_PATH, MAX_OVERVIEW_BYTES) { it == OVERVIEW_PATH }
            overviewText = overviewBytes.toString(Charsets.UTF_8)
            val indexBytes = readBytes(context, INDEX_PATH, MAX_INDEX_BYTES) { it == INDEX_PATH }
            require(overviewBytes.size.toLong() + indexBytes.size.toLong() <= MAX_CATALOG_BYTES) {
                "第三方声明清单超过大小上限。"
            }
            val root = JSONObject(indexBytes.toString(Charsets.UTF_8))
            require(root.optInt("schemaVersion", -1) == 1) { "不支持的第三方声明清单版本。" }
            val items = root.optJSONArray("components") ?: JSONArray()
            require(items.length() <= MAX_COMPONENTS) { "第三方声明组件数量超过上限。" }
            val components = ArrayList<ThirdPartyNoticeUi>(items.length())
            val ids = HashSet<String>()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: error("第三方声明组件格式无效。")
                val id = item.optString("id").trim()
                val name = item.optString("name").trim()
                require(id.isNotBlank() && id.length <= 256) { "第三方声明组件 id 无效。" }
                require(name.isNotBlank() && name.length <= 512) { "第三方声明组件名称无效。" }
                require(ids.add(id)) { "第三方声明组件 id 重复。" }
                val filesJson = item.optJSONArray("files") ?: JSONArray()
                require(filesJson.length() in 1..MAX_FILES_PER_COMPONENT) {
                    "第三方声明文件清单无效。"
                }
                val files = ArrayList<ThirdPartyNoticeFileUi>(filesJson.length())
                val paths = HashSet<String>()
                for (fileIndex in 0 until filesJson.length()) {
                    val file = filesJson.optJSONObject(fileIndex) ?: error("第三方声明文件格式无效。")
                    val label = file.optString("label").trim()
                    val path = allowedLicensePath(file.optString("path"))
                    require(label.isNotBlank() && label.length <= 512) { "第三方声明文件标签无效。" }
                    require(paths.add(path)) { "第三方声明文件路径重复。" }
                    files += ThirdPartyNoticeFileUi(label = label, path = path)
                }
                components += ThirdPartyNoticeUi(
                    id = id,
                    name = name,
                    version = item.optString("version").trim().take(128),
                    license = item.optString("license").trim().take(256),
                    source = item.optString("source").trim().take(2048),
                    files = files,
                )
            }
            ThirdPartyNoticesUiState(
                overview = overviewText,
                components = components,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ThirdPartyNoticesUiState(overview = overviewText, error = safeError(failure, "第三方声明资产不可用。"))
        }
    }

    /**
     * Read every notice file for one indexed component. Each original body is
     * kept intact; generated separators only identify the source file.
     */
    suspend fun loadComponentText(context: Context, component: ThirdPartyNoticeUi): Result<String> = withContext(Dispatchers.IO) {
        try {
            var total = 0L
            val combined = component.files.joinToString("\n\n") { file ->
                val path = allowedLicensePath(file.path)
                val bytes = readBytes(context, path, MAX_LICENSE_FILE_BYTES) { isAllowedLicensePath(it) }
                total += bytes.size.toLong()
                require(total <= MAX_COMPONENT_BYTES) { "组件声明内容超过大小上限。" }
                "===== ${file.label} (${path}) =====\n${bytes.toString(Charsets.UTF_8)}"
            }
            Result.success(combined)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    private fun readBytes(
        context: Context,
        path: String,
        maxBytes: Long,
        allowed: (String) -> Boolean,
    ): ByteArray {
        require(allowed(path)) { "不允许读取该声明路径。" }
        context.assets.open(path).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count.toLong()
                require(total <= maxBytes) { "声明文件超过大小上限。" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun allowedLicensePath(raw: String): String {
        val path = raw.trim()
        require(isAllowedLicensePath(path)) { "第三方声明路径不在允许范围内。" }
        return path
    }

    private fun isAllowedLicensePath(path: String): Boolean {
        if (path.isBlank() || path.length > 1024) return false
        if (path.startsWith('/') || path.contains('\\') || path.contains("..")) return false
        if (path != MODEL_LICENSE_PATH && !path.startsWith(LICENSE_PREFIX)) return false
        return path.split('/').all { it.isNotBlank() && it != "." && it != ".." }
    }

    private fun safeError(failure: Throwable, fallback: String): String =
        failure.message?.replace(Regex("[\\r\\n\\t]+"), " ")?.take(256)?.ifBlank { fallback } ?: fallback
}
