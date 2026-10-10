// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.knowledge.*

/** Local evidence preparation only: no SQL, consent, provider call or retry is reachable here. */
internal class KnowledgeVisualSource(private val rasterizer: PdfPageRasterizer?) {
    sealed interface Result {
        data class Ready(val asset: ExtractedAsset) : Result
        data class Failed(val code: String, val detail: String) : Result
    }

    fun prepare(bytes: ByteArray, parsed: ParsedPublication, sources: List<ExtractedAsset>, unit: ProcessingUnit): Result {
        val asset = if (parsed.format == SourceFormat.PDF && rasterizer != null && unit.sourceAssetId == null) {
            val rendered = (rasterizer as? PdfUnitRasterizer)?.renderUnit(bytes, unit)
                ?: if (unit.region == null) PdfParser.renderPage(bytes, rasterizer, unit.page) else null
            if (rendered == null) return Result.Failed("RENDER_FAILED", "PDF unit could not be rendered within local limits")
            ExtractedAsset("unit-${unit.unitId}", "IMAGE", unit.page,
                if (unit.region == null) "pdf-page-${unit.page}" else "pdf-unit-${unit.unitId}",
                rendered.bytes, rendered.mediaType, unit.effectiveRequestText())
        } else {
            val source = sources.firstOrNull { it.localId == unit.sourceAssetId }
                ?: sources.firstOrNull { it.page == unit.page }
                ?: return Result.Failed("MISSING_LOCAL_RASTER_SOURCE", "Unit has no local raster source")
            val payload = source.readBytes()
            val imageRenderer = rasterizer as? ImageUnitRasterizer
            val dimensions = imageRenderer?.imageDimensions(payload)
            val limits = UnitRenderLimits()
            val oversized = dimensions != null && (dimensions.first > limits.maxDimension ||
                dimensions.second > limits.maxDimension || dimensions.first.toLong() * dimensions.second > limits.maxPixels)
            if (parsed.format == SourceFormat.PDF && unit.sourceAssetId != null) {
                // A JPEG signature is not evidence of successful decoding. If an
                // illustration cannot decode, preserve complete page appearance.
                val rendered = imageRenderer?.renderImageUnit(payload, unit)
                if (rendered != null) source.copy(bytes = rendered.bytes, byteLength = rendered.bytes.size, byteSource = null,
                    mediaType = rendered.mediaType, section = if (unit.region == null) source.section else "image-unit-${unit.unitId}",
                    surroundingText = unit.effectiveRequestText())
                else {
                    val page = rasterizer?.let { PdfParser.renderPage(bytes, it, unit.page) }
                        ?: return Result.Failed("RENDER_FAILED", "PDF illustration and page could not be rendered within local limits")
                    ExtractedAsset("unit-${unit.unitId}", "IMAGE", unit.page, "pdf-page-${unit.page}",
                        page.bytes, page.mediaType, unit.effectiveRequestText())
                }
            } else if (imageRenderer != null && (unit.region != null || oversized || payload.size > limits.maxEncodedBytes)) {
                val rendered = imageRenderer.renderImageUnit(payload, unit)
                    ?: return Result.Failed("RENDER_FAILED", "Image unit could not be rendered within local limits")
                source.copy(bytes = rendered.bytes, byteLength = rendered.bytes.size, byteSource = null,
                    mediaType = rendered.mediaType, section = if (unit.region == null) source.section else "image-unit-${unit.unitId}",
                    surroundingText = unit.effectiveRequestText())
            } else {
                if (unit.region != null || payload.size > limits.maxEncodedBytes)
                    return Result.Failed("RENDER_LIMIT_EXCEEDED", "Region rendering is unavailable or image exceeds local byte limit")
                source.copy(bytes = payload, byteLength = payload.size, byteSource = null, surroundingText = unit.effectiveRequestText())
            }
        }
        // Office ordinals identify sections, never physical pages.
        return Result.Ready(if (parsed.format == SourceFormat.OFFICE_ARCHIVE) asset.copy(page = null) else asset)
    }
}
