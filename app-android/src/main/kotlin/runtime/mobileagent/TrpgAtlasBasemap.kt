// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Optional local-only base map for the atlas. The picker hands us a user-chosen `content://` URI;
 * we keep only that URI string in this device's SharedPreferences for one conversation and never
 * upload, copy or delete the picked file. Backup carries atlas pins, not this local image.
 */
internal object TrpgAtlasBasemap {
    /** Hard cap before any decode: an oversized file is rejected, never buffered without bound. */
    const val MAX_BYTES = 8 * 1024 * 1024

    /** Decoded pixels are sampled down to this longest edge, keeping bitmaps small in memory. */
    const val MAX_EDGE = 2048

    private const val PREFERENCES = "trpg-atlas-basemap"
    private const val PREFIX = "uri:"

    fun loadUri(context: Context, conversationId: String): String? =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(PREFIX + conversationId, null)

    fun saveUri(context: Context, conversationId: String, uri: String?) {
        val editor = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
        if (uri == null) editor.remove(PREFIX + conversationId) else editor.putString(PREFIX + conversationId, uri)
        editor.apply()
    }

    /** Persistable read access is best effort: without it the current session can still preview. */
    fun takeReadPermission(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (_: SecurityException) {
        false
    }

    /** Reads at most [maxBytes]; null means the file is larger and was not buffered in full. */
    fun readCapped(stream: InputStream, maxBytes: Int = MAX_BYTES): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Bounds are read before any pixel allocation; failure (stale URI, bad data) returns null. */
    fun decode(resolver: ContentResolver, uri: Uri): Bitmap? = try {
        require(uri.scheme == "content")
        resolver.openInputStream(uri)?.use { stream -> readCapped(stream) }?.let(::decodeSampled)
    } catch (_: Exception) {
        null
    }

    internal fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = TrpgMediaImageBudget.inSampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (bitmap.width > MAX_EDGE || bitmap.height > MAX_EDGE ||
            !TrpgMediaImageBudget.isWithinPixelBudget(bitmap.width, bitmap.height)) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }
}
