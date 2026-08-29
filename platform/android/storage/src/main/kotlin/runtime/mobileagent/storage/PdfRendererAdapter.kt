// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import runtime.mobileagent.knowledge.PdfPageRasterizer
import runtime.mobileagent.knowledge.RenderedPdfPage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Android PdfRenderer backed implementation of the shared PDF rasterizer.
 *
 * PdfRenderer requires a seekable file descriptor and only permits one open
 * page at a time.  The adapter therefore writes the already-copied CAS bytes
 * to a private cache file, opens and closes each requested page serially, and
 * removes the temporary file in all cases.  It never follows a path supplied
 * by the user and does not retain the PDF after rendering.
 */
class AndroidPdfRendererAdapter(
    context: Context,
    private val maxDimension: Int = DEFAULT_MAX_DIMENSION,
    private val maxPixels: Long = DEFAULT_MAX_PIXELS,
) : PdfPageRasterizer {
    private val cacheDir: File = File(context.cacheDir, "pdf-render")

    init {
        require(maxDimension > 0) { "maxDimension must be positive" }
        require(maxPixels > 0) { "maxPixels must be positive" }
        cacheDir.mkdirs()
    }

    override fun render(pdfBytes: ByteArray, pages: List<Int>): List<RenderedPdfPage> {
        val requested = pages.asSequence().filter { it > 0 }.distinct().toList()
        if (requested.isEmpty()) return emptyList()

        val source = File.createTempFile("pdf-", ".pdf", cacheDir)
        return try {
            FileOutputStream(source).use { output ->
                var offset = 0
                while (offset < pdfBytes.size) {
                    val length = min(COPY_BUFFER_SIZE, pdfBytes.size - offset)
                    output.write(pdfBytes, offset, length)
                    offset += length
                }
                output.fd.sync()
            }
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    requested.mapNotNull { pageNumber ->
                        if (pageNumber > renderer.pageCount) return@mapNotNull null
                        renderPage(renderer, pageNumber)
                    }
                }
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            source.delete()
        }
    }

    private fun renderPage(renderer: PdfRenderer, pageNumber: Int): RenderedPdfPage? {
        return runCatching {
            renderer.openPage(pageNumber - 1).use { page ->
                val (width, height) = outputSize(page.width, page.height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(BitmapColor.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val encoded = ByteArrayOutputStream().use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            "PdfRenderer could not encode page $pageNumber"
                        }
                        output.toByteArray()
                    }
                    RenderedPdfPage(
                        page = pageNumber,
                        bytes = encoded,
                        mediaType = "image/png",
                        width = width,
                        height = height,
                    )
                } finally {
                    bitmap.recycle()
                }
            }
        }.getOrNull()
    }

    private fun outputSize(pageWidth: Int, pageHeight: Int): Pair<Int, Int> {
        val width = max(1, pageWidth)
        val height = max(1, pageHeight)
        val dimensionScale = min(1.0, maxDimension.toDouble() / max(width, height).toDouble())
        val pixelScale = min(1.0, sqrt(maxPixels.toDouble() / (width.toDouble() * height.toDouble())))
        val scale = min(dimensionScale, pixelScale)
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    private object BitmapColor {
        const val WHITE: Int = -1
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val DEFAULT_MAX_DIMENSION = 2048
        const val DEFAULT_MAX_PIXELS = 4_000_000L
    }
}
