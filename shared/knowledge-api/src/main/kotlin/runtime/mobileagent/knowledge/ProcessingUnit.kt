// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
enum class ProcessingUnitKind { PAGE, REGION, LOGICAL }

/** Integer normalized coordinates avoid platform-dependent floating-point identity. */
@Serializable
data class UnitRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init { require(left >= 0 && top >= 0 && right <= SCALE && bottom <= SCALE && left < right && top < bottom) }
    companion object { const val SCALE = 1_000_000; val FULL = UnitRegion(0, 0, SCALE, SCALE) }
}

@Serializable
data class UnitCoverage(
    val page: Int,
    val region: UnitRegion,
    val nativeTextSource: String = "page-extraction",
    val textStart: Int = 0,
    val textEnd: Int = 0,
)

@Serializable
data class ProcessingUnit(
    val documentContentHash: String,
    val page: Int,
    val kind: ProcessingUnitKind,
    val unitId: String,
    val parentPageId: String,
    val readingOrder: Int,
    val region: UnitRegion?,
    val coverage: UnitCoverage,
    /** Page-native text is provenance, never falsely attributed to a particular crop. */
    val nativeText: String,
    val requiresVision: Boolean,
    val plannerVersion: String,
    val continuationGroupId: String? = null,
    val continuationIndex: Int? = null,
    val tableHeader: String? = null,
    val sourceAssetId: String? = null,
    /** Canonical hash of extraction/input provenance; independent from a provider attempt. */
    val sourceInputIdentity: String = "",
    /** Null means a legacy row without an explicit request slice; empty/blank is valid text. */
    val requestText: String? = null,
) {
    fun effectiveRequestText(): String {
        requestText?.let { return it }
        // Earlier serializers omitted empty requestText values. Nonzero offsets still
        // prove an explicit slice, including an empty trailing slice at end-of-page.
        if (coverage.textStart != 0 || coverage.textEnd != 0) {
            require(coverage.textStart >= 0 && coverage.textEnd in coverage.textStart..nativeText.length) {
                "PIPELINE_INVALID_TEXT_COVERAGE: invalid persisted text offsets"
            }
            return nativeText.substring(coverage.textStart, coverage.textEnd)
        }
        return nativeText
    }
}

data class PlanningPage(
    val page: Int,
    val nativeText: String,
    val needsVision: Boolean,
    val width: Int = 612,
    val height: Int = 792,
    val dense: Boolean = false,
    val complexLayout: Boolean = false,
    val tableHeader: String? = null,
    val continuationKey: String? = null,
    val parserFingerprint: String = "",
)

/** Pure metadata planner. Limits concern image fidelity, not guessed model context windows. */
class DocumentUnitPlanner(val version: String = VERSION) {
    init { require(version.isNotBlank()) }

    fun planPublication(contentHash: String, publication: ParsedPublication, imageDimensions: Map<String, Pair<Int, Int>> = emptyMap()): List<ProcessingUnit> {
        if (publication.format != SourceFormat.PDF) {
            val nativePages = publication.pages.ifEmpty { listOf(ExtractedPage(1, publication.text, false)) }
            val native = plan(contentHash, nativePages.filter { it.text.isNotBlank() }.map {
                PlanningPage(it.page, it.text, false, parserFingerprint = publication.parserFingerprint)
            })
            return native + publication.assets.filter { it.kind == "IMAGE" || it.kind == "PAGE" }.flatMapIndexed { assetIndex, asset ->
                val dimensions = imageDimensions[asset.localId] ?: (612 to 792)
                plan(contentHash, listOf(PlanningPage(asset.page ?: 1, asset.surroundingText, true, dimensions.first, dimensions.second,
                    parserFingerprint = publication.parserFingerprint))).map { unit ->
                    val order = native.size + assetIndex * MAX_UNITS_PER_PAGE + unit.readingOrder
                    unit.copy(unitId = identity(contentHash, asset.localId, unit.unitId, order.toString()),
                        kind = if (unit.region == null) ProcessingUnitKind.LOGICAL else ProcessingUnitKind.REGION,
                        readingOrder = order, sourceAssetId = asset.localId)
                }
            }
        }
        val pages = publication.pages.sortedBy { it.page }
        // Only explicit delimited text is a table cue. Vector drawings alone do not prove a table.
        val headers = pages.map { page ->
            page.text.lineSequence().map { it.trim() }.firstOrNull { line ->
                line.length in 3..1024 && (line.count { it == '|' } >= 2 || line.count { it == '\t' } >= 2)
            }
        }
        var groupStart = 0
        return plan(contentHash, pages.mapIndexed { index, page ->
            val header = headers[index]
            val previousMatches = index > 0 && pages[index - 1].page + 1 == page.page && header != null && header == headers[index - 1]
            val nextMatches = index + 1 < pages.size && page.page + 1 == pages[index + 1].page && header != null && header == headers[index + 1]
            if (!previousMatches) groupStart = page.page
            PlanningPage(page.page, page.text, page.needsVision, page.width, page.height,
                dense = page.text.length > DENSE_CHARACTERS, complexLayout = page.complexLayout,
                tableHeader = header,
                continuationKey = if (previousMatches || nextMatches) "explicit-table:$groupStart:$header" else null,
                parserFingerprint = publication.parserFingerprint)
        })
    }

    fun plan(contentHash: String, pages: List<PlanningPage>): List<ProcessingUnit> {
        require(contentHash.isNotBlank())
        require(pages.map { it.page }.distinct().size == pages.size)
        val continuationOrdinals = mutableMapOf<String, Int>()
        return buildList {
            pages.sortedBy { it.page }.forEach { page ->
                require(page.page > 0 && page.width > 0 && page.height > 0)
                val parent = identity(contentHash, page.page.toString(), "page")
                val group = page.continuationKey?.let { identity(contentHash, "continuation", it) }
                val continuation = group?.let { continuationOrdinals.getOrDefault(it, 0).also { n -> continuationOrdinals[it] = n + 1 } }
                val inputIdentity = identity("source-input-v1", page.parserFingerprint, page.nativeText,
                    (page.tableHeader != null).toString(), page.tableHeader.orEmpty(),
                    group.orEmpty(), continuation?.toString().orEmpty(), page.needsVision.toString(),
                    page.width.toString(), page.height.toString())
                val regions = if (!page.needsVision) listOf(UnitRegion.FULL) else split(page)
                // Native-only pages never produce a Vision request; local chunking has
                // its own limits and must not inherit the Vision dispatch bound.
                val textSlices = if (page.needsVision) splitTextSlices(page.nativeText, regions.size)
                    else listOf(0 to page.nativeText.length)
                regions.forEachIndexed { rIndex, area ->
                    val kind = if (regions.size == 1) ProcessingUnitKind.PAGE else ProcessingUnitKind.REGION
                    val order = size
                    val sliceRange = textSlices[rIndex]
                    val slice = if (page.nativeText.isNotEmpty()) page.nativeText.substring(sliceRange.first, sliceRange.second) else ""
                    val unitInputIdentity = identity("source-input-v2", inputIdentity, slice, sliceRange.first.toString(), sliceRange.second.toString())
                    val id = identity(contentHash, page.page.toString(), kind.name, area.toString(), order.toString(), version, unitInputIdentity)
                    add(ProcessingUnit(contentHash, page.page, kind, id, parent, order,
                        area.takeIf { kind == ProcessingUnitKind.REGION },
                        UnitCoverage(page.page, area, textStart = sliceRange.first, textEnd = sliceRange.second),
                        page.nativeText, page.needsVision, version, group, continuation, page.tableHeader,
                        sourceInputIdentity = unitInputIdentity,
                        requestText = slice))
                }
            }
        }
    }

    private fun split(page: PlanningPage): List<UnitRegion> {
        require(page.nativeText.length.toLong() <= MAX_REQUEST_TEXT_CHARS.toLong() * MAX_UNITS_PER_PAGE) {
            "PIPELINE_TEXT_LIMIT_EXCEEDED: page text cannot fit in $MAX_UNITS_PER_PAGE bounded requests; no text was truncated"
        }
        val textParts = ((page.nativeText.length.toLong() + DENSE_CHARACTERS - 1) / DENSE_CHARACTERS)
            .coerceIn(1L, MAX_UNITS_PER_PAGE.toLong()).toInt()
        val densityParts = maxOf(if (page.dense || page.complexLayout || page.tableHeader != null) 2 else 1, textParts)
        val parts = if (densityParts <= 1) {
            mutableListOf(UnitRegion.FULL)
        } else {
            val n = densityParts.coerceIn(1, MAX_UNITS_PER_PAGE)
            (0 until n).map { i ->
                UnitRegion(
                    0,
                    (i.toLong() * UnitRegion.SCALE / n).toInt(),
                    UnitRegion.SCALE,
                    ((i + 1).toLong() * UnitRegion.SCALE / n).toInt()
                )
            }.toMutableList()
        }
        var index = 0
        while (index < parts.size && parts.size < MAX_UNITS_PER_PAGE) {
            val region = parts[index]
            val width = page.width.toDouble() * (region.right - region.left) / UnitRegion.SCALE
            val height = page.height.toDouble() * (region.bottom - region.top) / UnitRegion.SCALE
            val overLimit = width > MAX_REGION_DIMENSION || height > MAX_REGION_DIMENSION || width * height > MAX_REGION_PIXELS
            if (overLimit) {
                // A table header is a preference, never a reason to keep splitting
                // height while the unchanged width is the dimension over the limit.
                val horizontal = when {
                    width > MAX_REGION_DIMENSION -> false
                    height > MAX_REGION_DIMENSION -> true
                    else -> height >= width || page.tableHeader != null
                }
                val start = if (horizontal) region.top else region.left
                val end = if (horizontal) region.bottom else region.right
                val middle = start + (end - start) / 2
                require(middle > start && middle < end) {
                    "PIPELINE_REGION_LIMIT_EXCEEDED: region cannot be subdivided without losing coverage"
                }
                val first = if (horizontal) region.copy(bottom = middle) else region.copy(right = middle)
                val second = if (horizontal) region.copy(top = middle) else region.copy(left = middle)
                parts[index] = first
                parts.add(index + 1, second)
            } else index++
        }
        return parts.sortedWith(compareBy<UnitRegion> { it.top }.thenBy { it.left })
    }

    companion object {
        const val VERSION = "document-units-v2"
        const val DENSE_CHARACTERS = 8_000
        const val MAX_REGION_DIMENSION = 2048
        const val MAX_REGION_PIXELS = 4_000_000
        const val MAX_UNITS_PER_PAGE = 64
        /** Local UTF-16 character bound, not a claim about a provider's token window. */
        const val MAX_REQUEST_TEXT_CHARS = 8_500

        fun splitTextSlices(text: String, parts: Int): List<Pair<Int, Int>> {
            require(parts in 1..MAX_UNITS_PER_PAGE)
            require(text.length.toLong() <= parts.toLong() * MAX_REQUEST_TEXT_CHARS) {
                "PIPELINE_TEXT_LIMIT_EXCEEDED: text cannot fit in $parts bounded requests; no text was truncated"
            }
            // Validate before any request. A malformed source must not silently turn
            // into replacement characters when each independently sent slice is encoded.
            var offset = 0
            var codePoints = 0
            while (offset < text.length) {
                val c = text[offset]
                require(!Character.isLowSurrogate(c) &&
                    (!Character.isHighSurrogate(c) ||
                        (offset + 1 < text.length && Character.isLowSurrogate(text[offset + 1])))) {
                    "PIPELINE_INVALID_TEXT: unpaired UTF-16 surrogate in page text"
                }
                offset += if (Character.isHighSurrogate(c)) 2 else 1
                codePoints++
            }
            if (text.isEmpty()) return List(parts) { 0 to 0 }
            if (parts == 1) return listOf(0 to text.length)
            if (codePoints <= parts) {
                var cursor = 0
                return List(parts) {
                    val start = cursor
                    if (cursor < text.length) cursor += Character.charCount(text.codePointAt(cursor))
                    start to cursor
                }
            }
            val length = text.length
            val boundaries = mutableListOf(0)
            for (i in 1 until parts) {
                val ideal = (i.toLong() * length / parts).toInt()
                val windowStart = maxOf(boundaries.last() + 1, ideal - 200)
                val windowEnd = minOf(length - (parts - i), ideal + 200)
                var best = ideal
                // Preserve existing safe boundaries and therefore paid unit identities.
                if (windowStart < windowEnd) {
                    val nlIndex = text.lastIndexOf('\n', minOf(ideal + 100, windowEnd))
                    if (nlIndex in windowStart..windowEnd) {
                        best = nlIndex + 1
                    } else {
                        val punctIndex = text.lastIndexOf('。', minOf(ideal + 50, windowEnd))
                        if (punctIndex in maxOf(boundaries.last() + 1, ideal - 50)..windowEnd) {
                            best = punctIndex + 1
                        } else {
                            val spIndex = text.lastIndexOf(' ', minOf(ideal + 25, windowEnd))
                            if (spIndex in maxOf(boundaries.last() + 1, ideal - 50)..windowEnd) {
                                best = spIndex + 1
                            }
                        }
                    }
                }
                val remainingParts = parts - i
                val lower = maxOf(boundaries.last(), length - remainingParts * MAX_REQUEST_TEXT_CHARS)
                val upper = minOf(length, boundaries.last() + MAX_REQUEST_TEXT_CHARS)
                var boundary = best.coerceIn(lower, upper)
                if (!isCharacterBoundary(text, boundary)) {
                    boundary = when {
                        boundary + 1 <= upper -> boundary + 1
                        boundary - 1 >= lower -> boundary - 1
                        else -> throw IllegalArgumentException(
                            "PIPELINE_TEXT_LIMIT_EXCEEDED: Unicode-safe slices need more request capacity; no text was truncated")
                    }
                }
                boundaries.add(boundary)
            }
            boundaries.add(length)
            return (0 until parts).map { boundaries[it] to boundaries[it + 1] }.also { slices ->
                require(slices.all { (start, end) ->
                    end - start <= MAX_REQUEST_TEXT_CHARS &&
                        isCharacterBoundary(text, start) && isCharacterBoundary(text, end)
                }) { "PIPELINE_TEXT_LIMIT_EXCEEDED: unable to make bounded Unicode-safe requests" }
            }
        }

        private fun isCharacterBoundary(text: String, offset: Int): Boolean =
            offset == 0 || offset == text.length ||
                !(Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset]))

        private fun identity(vararg fields: String): String {
            val canonical = fields.joinToString("") { "${it.length}:$it" }
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
