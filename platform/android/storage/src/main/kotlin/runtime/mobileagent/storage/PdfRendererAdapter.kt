// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import runtime.mobileagent.knowledge.PdfPageRasterizer
import runtime.mobileagent.knowledge.RenderedPdfPage
import runtime.mobileagent.knowledge.PdfUnitRasterizer
import runtime.mobileagent.knowledge.ProcessingUnit
import runtime.mobileagent.knowledge.UnitRegion
import runtime.mobileagent.knowledge.UnitRenderLimits
import runtime.mobileagent.knowledge.ImageUnitRasterizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
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
) : PdfPageRasterizer, PdfUnitRasterizer, ImageUnitRasterizer {
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

    override fun renderUnit(pdfBytes: ByteArray, unit: ProcessingUnit, limits: UnitRenderLimits): RenderedPdfPage? {
        require(unit.page > 0)
        val source = File.createTempFile("pdf-unit-", ".pdf", cacheDir)
        return try {
            FileOutputStream(source).use { it.write(pdfBytes) }
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (unit.page > renderer.pageCount) null else renderPage(renderer, unit.page,
                        unit.region ?: UnitRegion.FULL, limits)
                }
            }
        } finally { source.delete() }
    }

    override fun imageDimensions(bytes: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    @Suppress("DEPRECATION")
    override fun renderImageUnit(bytes: ByteArray, unit: ProcessingUnit, limits: UnitRenderLimits): RenderedPdfPage? = runCatching {
        val dimensions = imageDimensions(bytes) ?: return@runCatching null
        val area = unit.region ?: UnitRegion.FULL
        val left = (dimensions.first.toLong() * area.left / UnitRegion.SCALE).toInt()
        val top = (dimensions.second.toLong() * area.top / UnitRegion.SCALE).toInt()
        val right = ((dimensions.first.toLong() * area.right + UnitRegion.SCALE - 1) / UnitRegion.SCALE).toInt()
        val bottom = ((dimensions.second.toLong() * area.bottom + UnitRegion.SCALE - 1) / UnitRegion.SCALE).toInt()
        val width = right - left; val height = bottom - top
        var sample = 1
        while ((width.toLong() + sample - 1) / sample > min(maxDimension, limits.maxDimension) ||
            (height.toLong() + sample - 1) / sample > min(maxDimension, limits.maxDimension) ||
            ((width.toLong() + sample - 1) / sample) * ((height.toLong() + sample - 1) / sample) > min(maxPixels, limits.maxPixels)) {
            check(sample < (1 shl 29))
            sample *= 2
        }
        val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) ?: return@runCatching null
        try {
            val bitmap = decoder.decodeRegion(Rect(left, top, right, bottom), BitmapFactory.Options().apply {
                inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: return@runCatching null
            try {
                check(bitmap.width.toLong() * bitmap.height <= min(maxPixels, limits.maxPixels))
                val encoded = BoundedImageOutput(limits.maxEncodedBytes).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
                }
                RenderedPdfPage(unit.page, encoded, "image/png", bitmap.width, bitmap.height)
            } finally { bitmap.recycle() }
        } finally { decoder.recycle() }
    }.getOrNull()

    private fun renderPage(
        renderer: PdfRenderer,
        pageNumber: Int,
        region: UnitRegion = UnitRegion.FULL,
        limits: UnitRenderLimits = UnitRenderLimits(maxDimension, maxPixels),
    ): RenderedPdfPage? {
        return runCatching {
            renderer.openPage(pageNumber - 1).use { page ->
                val left = page.width.toDouble() * region.left / UnitRegion.SCALE
                val top = page.height.toDouble() * region.top / UnitRegion.SCALE
                val cropWidth = page.width.toDouble() * (region.right - region.left) / UnitRegion.SCALE
                val cropHeight = page.height.toDouble() * (region.bottom - region.top) / UnitRegion.SCALE
                val (width, height) = outputSize(cropWidth, cropHeight, limits)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(BitmapColor.WHITE)
                    val transform = Matrix().apply {
                        setValues(floatArrayOf((width / cropWidth).toFloat(), 0f, (-left * width / cropWidth).toFloat(),
                            0f, (height / cropHeight).toFloat(), (-top * height / cropHeight).toFloat(), 0f, 0f, 1f))
                    }
                    page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val encoded = BoundedImageOutput(limits.maxEncodedBytes).use { output ->
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

    private fun outputSize(pageWidth: Double, pageHeight: Double, limits: UnitRenderLimits): Pair<Int, Int> {
        val width = max(1.0, pageWidth)
        val height = max(1.0, pageHeight)
        val dimensionScale = min(1.0, min(maxDimension, limits.maxDimension).toDouble() / max(width, height))
        val pixelScale = min(1.0, sqrt(min(maxPixels, limits.maxPixels).toDouble() / (width * height)))
        val scale = min(dimensionScale, pixelScale)
        val outputWidth = min(max(1, (width * scale).toInt()).toLong(), min(maxPixels, limits.maxPixels)).toInt()
        val outputHeight = min(max(1, (height * scale).toInt()), (min(maxPixels, limits.maxPixels) / outputWidth).coerceAtLeast(1).toInt())
        return outputWidth to outputHeight
    }

    /** Abort encoding before ByteArrayOutputStream can grow beyond the configured payload budget. */
    private class BoundedImageOutput(private val limit: Int) : ByteArrayOutputStream(min(limit, 64 * 1024)) {
        override fun write(value: Int) { check(count < limit); super.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(length >= 0 && count.toLong() + length <= limit); super.write(bytes, offset, length)
        }
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
