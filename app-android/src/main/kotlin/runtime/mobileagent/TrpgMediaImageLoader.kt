// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of a bounded, sampled local image load. */
internal sealed interface TrpgMediaImageResult {
    data class Ready(val bitmap: Bitmap, val width: Int, val height: Int) : TrpgMediaImageResult

    data class RejectedTooLarge(val maxBytes: Long) : TrpgMediaImageResult

    data class Failed(val detail: String) : TrpgMediaImageResult
}

internal interface TrpgMediaImageLoader {
    suspend fun load(uri: String): TrpgMediaImageResult
}

/**
 * Reads at most the configured byte budget, decodes bounds first and samples
 * both edges down to the pixel limit off the main thread. The file is never
 * uploaded, never written to campaign storage and never fully read when it is
 * over budget.
 */
internal class TrpgMediaAndroidImageLoader(context: Context) : TrpgMediaImageLoader {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun load(uri: String): TrpgMediaImageResult = withContext(Dispatchers.IO) {
        if (!TrpgMediaPolicy.isAllowedContentUri(uri)) return@withContext TrpgMediaImageResult.Failed("invalid_uri")
        val mimeType = runCatching { resolver.getType(Uri.parse(uri)) }.getOrNull()
        if (mimeType != null && !mimeType.startsWith("image/")) {
            return@withContext TrpgMediaImageResult.Failed("not_an_image")
        }
        val bytes = try {
            resolver.openInputStream(Uri.parse(uri))?.use { stream -> TrpgMediaImageBudget.boundedRead(stream) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
        when (bytes) {
            null -> TrpgMediaImageResult.Failed("unreadable")
            TrpgMediaImageBytes.TooLarge -> TrpgMediaImageResult.RejectedTooLarge(TrpgMediaPolicy.MAX_IMAGE_BYTES)
            is TrpgMediaImageBytes.Loaded -> decode(bytes.bytes)
        }
    }

    private fun decode(bytes: ByteArray): TrpgMediaImageResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return TrpgMediaImageResult.Failed("decode_bounds")
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = TrpgMediaImageBudget.inSampleSize(bounds.outWidth, bounds.outHeight)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return TrpgMediaImageResult.Failed("decode_failed")
        if (!TrpgMediaImageBudget.isWithinPixelBudget(bitmap.width, bitmap.height)) {
            bitmap.recycle()
            return TrpgMediaImageResult.Failed("too_many_pixels")
        }
        return TrpgMediaImageResult.Ready(bitmap, bitmap.width, bitmap.height)
    }
}
