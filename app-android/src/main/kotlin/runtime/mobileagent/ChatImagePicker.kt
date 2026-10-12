// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream
import runtime.mobileagent.domain.ChatImageUpload

/** Picker output: validated uploads with optional previews, plus per-file user-facing errors. */
internal data class ChatImagePickResult(
    val uploads: List<Pair<ChatImageUpload, Bitmap?>>,
    val rejected: List<String>,
)

/** One picked file, decoupled from ContentResolver so the reader can be tested on the JVM. */
internal class ChatImageSource(
    val declaredType: String?,
    val declaredSize: Long?,
    val metadataUnavailable: Boolean = false,
    val open: () -> InputStream?,
)

/**
 * Reads picker results on the caller's IO thread. URIs are used only to open the stream here;
 * neither the URI nor a file name reaches the ViewModel, logs or storage.
 */
internal object ChatImagePicker {
    private const val THUMBNAIL_EDGE = 192
    private const val THUMBNAIL_MAX_EDGE = 1024

    fun read(resolver: ContentResolver, uris: List<Uri>, thumbnails: Boolean = true): ChatImagePickResult {
        val sources = uris.take(ChatImageUpload.MAX_IMAGES_PER_MESSAGE).map { uri ->
            val type = runCatching { resolver.getType(uri) }
            ChatImageSource(type.getOrNull(), declaredSize(resolver, uri), type.isFailure) { resolver.openInputStream(uri) }
        }
        return withOverflow(read(sources, if (thumbnails) ::thumbnail else { _ -> null }), uris.size)
    }

    fun read(sources: List<ChatImageSource>, preview: (ByteArray) -> Bitmap?): ChatImagePickResult {
        val uploads = mutableListOf<Pair<ChatImageUpload, Bitmap?>>()
        val rejected = mutableListOf<String>()
        sources.take(ChatImageUpload.MAX_IMAGES_PER_MESSAGE).forEachIndexed { index, source ->
            val label = "第 ${index + 1} 张图片"
            val type = source.declaredType?.lowercase()
            when {
                source.metadataUnavailable -> rejected += "$label 无法读取，未添加。"
                type !in SUPPORTED_TYPES -> rejected += "$label 格式不受支持，仅支持 PNG、JPEG、GIF、WebP。"
                (source.declaredSize ?: 0L) > ChatImageUpload.MAX_IMAGE_BYTES ->
                    rejected += "$label 超过 2 MiB，未添加。"
                else -> try {
                    val upload = (source.open() ?: error("unavailable")).use { ChatImageUpload.read(it, checkNotNull(type)) }
                    uploads += upload to runCatching { preview(upload.bytes()) }.getOrNull()
                } catch (failure: IllegalArgumentException) {
                    rejected += if (failure.message.orEmpty().startsWith("INPUT_OVERFLOW")) "$label 超过 2 MiB，未添加。"
                        else "$label 内容与声明格式不符，未添加。"
                } catch (_: Exception) {
                    rejected += "$label 无法读取，未添加。"
                }
            }
        }
        return withOverflow(ChatImagePickResult(uploads, rejected), sources.size)
    }

    private fun withOverflow(result: ChatImagePickResult, count: Int): ChatImagePickResult =
        if (count <= ChatImageUpload.MAX_IMAGES_PER_MESSAGE) result else result.copy(
            rejected = result.rejected + "每条消息最多 ${ChatImageUpload.MAX_IMAGES_PER_MESSAGE} 张图片，另有 ${count - ChatImageUpload.MAX_IMAGES_PER_MESSAGE} 张未读取。",
        )

    private fun declaredSize(resolver: ContentResolver, uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else null
        }
    }.getOrNull()

    /** Bounds are read before any pixel allocation; the preview is sampled to a small edge. */
    private fun thumbnail(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while ((bounds.outWidth / (sample * 2) >= THUMBNAIL_EDGE && bounds.outHeight / (sample * 2) >= THUMBNAIL_EDGE) ||
            maxOf(bounds.outWidth, bounds.outHeight) / sample > THUMBNAIL_MAX_EDGE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private val SUPPORTED_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
}
