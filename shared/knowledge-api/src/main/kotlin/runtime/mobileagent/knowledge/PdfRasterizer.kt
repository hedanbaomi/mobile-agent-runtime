// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

/**
 * A page image produced by a PDF renderer.
 *
 * The shared parser deliberately does not depend on Android.  Android uses
 * [android.graphics.pdf.PdfRenderer] through the platform adapter, while JVM
 * tests and other hosts can provide a deterministic renderer of their own.
 */
data class RenderedPdfPage(
    val page: Int,
    val bytes: ByteArray,
    val mediaType: String = "image/png",
    val width: Int = 0,
    val height: Int = 0,
)

/**
 * Renders the requested one-based PDF pages into complete page images.
 *
 * Implementations must return at most one image for each requested page and
 * should return no image for a page that could not be rendered.  The parser
 * retains an explicit empty PAGE asset in that case, so a renderer failure
 * cannot silently turn visual content into a READY text-only import.
 */
fun interface PdfPageRasterizer {
    fun render(pdfBytes: ByteArray, pages: List<Int>): List<RenderedPdfPage>
}
