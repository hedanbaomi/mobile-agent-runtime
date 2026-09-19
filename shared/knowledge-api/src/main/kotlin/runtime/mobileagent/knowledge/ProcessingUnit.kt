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

    /** True when [inner] lies completely inside this region. */
    fun contains(inner: UnitRegion): Boolean =
        left <= inner.left && top <= inner.top && right >= inner.right && bottom >= inner.bottom
}

/**
 * Explicit text-to-image relation carried by a [ProcessingUnit].
 *
 * [PAGE_CONTEXT] is the honest default: the text is page-level context and the
 * planner makes **no** claim that it was extracted from this crop. The old
 * planner fabricated that claim by pairing text slice `i` with image stripe `i`
 * purely by list position; that ordinal pairing is gone.
 *
 * [PROVEN_LAYOUT] is only used when a parser supplied [LayoutTextEvidence]
 * whose rectangle is fully contained in the unit's crop, so the attached offset
 * range provably belongs to that image.
 */
object TextImageAssociation {
    const val PAGE_CONTEXT = "PAGE_CONTEXT"
    const val PROVEN_LAYOUT = "PROVEN_LAYOUT"
    val values: Set<String> = setOf(PAGE_CONTEXT, PROVEN_LAYOUT)
}

/**
 * Explicit fidelity note for a layout feature the planner could not preserve
 * faithfully. A null note means no known degradation for the unit.
 */
object LayoutDegradation {
    /** Page text could not be fully contained in one request, so only local chunks carry it. */
    const val PAGE_TEXT_LOCAL_ONLY = "PAGE_TEXT_LOCAL_ONLY"

    /** Two/multi-column reading order is not provable from extraction alone. */
    const val COMPLEX_LAYOUT_TEXT_ORDER_UNVERIFIED = "COMPLEX_LAYOUT_TEXT_ORDER_UNVERIFIED"

    /** Parser layout evidence exists, but none of it falls inside this crop. */
    const val TEXT_NOT_LOCATED_IN_CROP = "TEXT_NOT_LOCATED_IN_CROP"
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
    /** [TextImageAssociation.PAGE_CONTEXT] unless parser-proven layout evidence backs [requestText]. */
    val textImageAssociation: String = TextImageAssociation.PAGE_CONTEXT,
    /** Serialized evidence for a [TextImageAssociation.PROVEN_LAYOUT] association; empty otherwise. */
    val textLayoutEvidence: List<LayoutTextEvidence> = emptyList(),
    /** Non-null when a layout feature was explicitly degraded; null is the default. */
    val layoutDegradation: String? = null,
) {
    init {
        require(textImageAssociation in TextImageAssociation.values) {
            "PIPELINE_INVALID_ASSOCIATION: unknown text/image association"
        }
        if (textImageAssociation == TextImageAssociation.PROVEN_LAYOUT) {
            require(textLayoutEvidence.isNotEmpty()) {
                "PIPELINE_INVALID_ASSOCIATION: PROVEN_LAYOUT requires serialized layout evidence"
            }
        }
    }

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
    /**
     * Parser-proven rectangle/text offset mappings for this page. Empty means no
     * layout coordinates are available, so no crop mapping may be fabricated.
     */
    val textRegions: List<LayoutTextEvidence> = emptyList(),
)

/**
 * Pure metadata planner. Limits concern image fidelity and request admission,
 * never a guessed model context window.
 *
 * ### Text-to-image provenance
 * A Vision unit only carries text that is either (a) proven by parser
 * [LayoutTextEvidence] to lie inside its crop ([TextImageAssociation.PROVEN_LAYOUT]),
 * or (b) the whole page text sent once as explicit page context
 * ([TextImageAssociation.PAGE_CONTEXT]). It never pairs text slice `i` with
 * image stripe `i`. The full native text always stays on
 * [ProcessingUnit.nativeText] and is published locally; an over-budget page is
 * marked [LayoutDegradation.PAGE_TEXT_LOCAL_ONLY] instead of being truncated.
 */
class DocumentUnitPlanner(val version: String = VERSION) {
    init { require(version.isNotBlank()) }

    fun planPublication(
        contentHash: String,
        publication: ParsedPublication,
        imageDimensions: Map<String, Pair<Int, Int>> = emptyMap(),
        budget: VisionRequestBudget = VisionRequestBudget(),
    ): List<ProcessingUnit> {
        if (publication.format != SourceFormat.PDF) {
            val nativePages = publication.pages.ifEmpty { listOf(ExtractedPage(1, publication.text, false)) }
            val native = plan(contentHash, nativePages.filter { it.text.isNotBlank() }.map {
                PlanningPage(it.page, it.text, false, parserFingerprint = publication.parserFingerprint,
                    textRegions = it.textRegions)
            }, budget)
            return native + publication.assets.filter { it.kind == "IMAGE" || it.kind == "PAGE" }.flatMapIndexed { assetIndex, asset ->
                val dimensions = imageDimensions[asset.localId] ?: (612 to 792)
                plan(contentHash, listOf(PlanningPage(asset.page ?: 1, asset.surroundingText, true, dimensions.first, dimensions.second,
                    parserFingerprint = publication.parserFingerprint)), budget).map { unit ->
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
                parserFingerprint = publication.parserFingerprint,
                textRegions = page.textRegions)
        }, budget)
    }

    fun plan(
        contentHash: String,
        pages: List<PlanningPage>,
        budget: VisionRequestBudget = VisionRequestBudget(),
    ): List<ProcessingUnit> {
        require(contentHash.isNotBlank())
        require(pages.map { it.page }.distinct().size == pages.size)
        val continuationOrdinals = mutableMapOf<String, Int>()
        return buildList {
            pages.sortedBy { it.page }.forEach { page ->
                require(page.page > 0 && page.width > 0 && page.height > 0)
                validatePage(page)
                val parent = identity(contentHash, page.page.toString(), "page")
                val group = page.continuationKey?.let { identity(contentHash, "continuation", it) }
                val continuation = group?.let { continuationOrdinals.getOrDefault(it, 0).also { n -> continuationOrdinals[it] = n + 1 } }
                val inputIdentity = identity("source-input-v2", page.parserFingerprint, page.nativeText,
                    (page.tableHeader != null).toString(), page.tableHeader.orEmpty(),
                    group.orEmpty(), continuation?.toString().orEmpty(), page.needsVision.toString(),
                    page.width.toString(), page.height.toString())
                val regions = if (!page.needsVision) listOf(UnitRegion.FULL) else split(page)
                val assignments = if (!page.needsVision) {
                    // Native-only pages never produce a Vision request; local chunking has
                    // its own limits and must not inherit the Vision admission bound.
                    listOf(RegionTextAssignment(page.nativeText, 0, page.nativeText.length,
                        TextImageAssociation.PAGE_CONTEXT, emptyList(), null))
                } else {
                    assign(page, regions, budget)
                }
                regions.forEachIndexed { rIndex, area ->
                    val kind = if (regions.size == 1) ProcessingUnitKind.PAGE else ProcessingUnitKind.REGION
                    val order = size
                    val assignment = assignments[rIndex]
                    val unitInputIdentity = identity("source-input-v3", inputIdentity, assignment.slice,
                        assignment.coverageStart.toString(), assignment.coverageEnd.toString(),
                        assignment.association, assignment.evidence.joinToString { it.toString() })
                    val id = identity(contentHash, page.page.toString(), kind.name, area.toString(), order.toString(), version, unitInputIdentity)
                    add(ProcessingUnit(
                        documentContentHash = contentHash,
                        page = page.page,
                        kind = kind,
                        unitId = id,
                        parentPageId = parent,
                        readingOrder = order,
                        region = area.takeIf { kind == ProcessingUnitKind.REGION },
                        coverage = UnitCoverage(
                            page = page.page,
                            region = area,
                            nativeTextSource = assignment.evidence.firstOrNull()?.evidenceSource ?: "page-extraction",
                            textStart = assignment.coverageStart,
                            textEnd = assignment.coverageEnd,
                        ),
                        nativeText = page.nativeText,
                        requiresVision = page.needsVision,
                        plannerVersion = version,
                        continuationGroupId = group,
                        continuationIndex = continuation,
                        tableHeader = page.tableHeader,
                        sourceInputIdentity = unitInputIdentity,
                        requestText = assignment.slice,
                        textImageAssociation = assignment.association,
                        textLayoutEvidence = assignment.evidence,
                        layoutDegradation = assignment.degradation,
                    ))
                }
            }
        }
    }

    /**
     * Decide which text each crop request may carry.
     *
     * Admission baseline: one image plus the header/continuation hints must fit
     * on its own, otherwise the plan fails locally before any render or dispatch.
     */
    private fun assign(page: PlanningPage, regions: List<UnitRegion>, budget: VisionRequestBudget): List<RegionTextAssignment> {
        val hints = page.hintText()
        val baseline = budget.requestUnits("", hints, 1)
        require(baseline <= budget.effectiveInputUnits) {
            "PIPELINE_VISION_BUDGET_EXCEEDED: one image request needs $baseline conservative input units before any " +
                "text, but the effective budget is ${budget.effectiveInputUnits}; no request was sent"
        }
        if (page.textRegions.isEmpty()) {
            return pageContextAssignments(page, regions, budget, hints, evidenceUnusable = false)
        }

        val proven = regions.map { crop ->
            val contained = page.textRegions
                .filter { crop.contains(it.region) }
                .sortedWith(compareBy({ it.textStart }, { it.region.top }, { it.region.left }))
            val merged = mergeRanges(contained.map { it.textStart to it.textEnd })
            if (merged.isEmpty()) {
                RegionTextAssignment("", 0, 0, TextImageAssociation.PAGE_CONTEXT, emptyList(),
                    LayoutDegradation.TEXT_NOT_LOCATED_IN_CROP)
            } else {
                val slice = merged.joinToString("\n") { page.nativeText.substring(it.first, it.second) }
                if (slice.length <= MAX_REQUEST_TEXT_CHARS && budget.requestUnits(slice, hints, 1) <= budget.effectiveInputUnits) {
                    RegionTextAssignment(slice, merged.first().first, merged.last().second,
                        TextImageAssociation.PROVEN_LAYOUT, contained, null)
                } else {
                    RegionTextAssignment("", 0, 0, TextImageAssociation.PAGE_CONTEXT, emptyList(),
                        LayoutDegradation.PAGE_TEXT_LOCAL_ONLY)
                }
            }
        }
        // Evidence that maps to no crop is not usable as a crop mapping: fall back
        // to explicit whole-page PAGE_CONTEXT instead of pretending a proven relation.
        return if (proven.any { it.association == TextImageAssociation.PROVEN_LAYOUT }) proven
            else pageContextAssignments(page, regions, budget, hints, evidenceUnusable = true)
    }

    /**
     * No usable layout evidence: send the whole page text once, labelled
     * PAGE_CONTEXT, only when it fits entirely (8500 UTF-16 plus real budget).
     * Otherwise send no text and say the original is local-only. There is no
     * partial/fabricated slice and no silent truncation.
     */
    private fun pageContextAssignments(
        page: PlanningPage,
        regions: List<UnitRegion>,
        budget: VisionRequestBudget,
        hints: String,
        evidenceUnusable: Boolean,
    ): List<RegionTextAssignment> {
        val full = page.nativeText
        val sendFull = full.isNotBlank() &&
            full.length <= MAX_REQUEST_TEXT_CHARS &&
            budget.requestUnits(full, hints, 1) <= budget.effectiveInputUnits
        val degradation = when {
            full.isBlank() -> null
            !sendFull -> LayoutDegradation.PAGE_TEXT_LOCAL_ONLY
            evidenceUnusable -> LayoutDegradation.TEXT_NOT_LOCATED_IN_CROP
            page.complexLayout -> LayoutDegradation.COMPLEX_LAYOUT_TEXT_ORDER_UNVERIFIED
            else -> null
        }
        return regions.mapIndexed { index, _ ->
            // Attach the whole-page context to one request only, so a multi-crop
            // page is never billed the same long text once per crop.
            val attach = sendFull && index == 0
            RegionTextAssignment(
                slice = if (attach) full else "",
                coverageStart = 0,
                coverageEnd = if (attach) full.length else 0,
                association = TextImageAssociation.PAGE_CONTEXT,
                evidence = emptyList(),
                degradation = degradation,
            )
        }
    }

    /** Image-fidelity splitting only; text length never drives crop count any more. */
    private fun split(page: PlanningPage): List<UnitRegion> {
        val densityParts = if (page.dense || page.complexLayout || page.tableHeader != null) 2 else 1
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
        require(parts.all { area ->
            val width = page.width.toDouble() * (area.right-area.left) / UnitRegion.SCALE
            val height = page.height.toDouble() * (area.bottom-area.top) / UnitRegion.SCALE
            width <= MAX_REGION_DIMENSION && height <= MAX_REGION_DIMENSION && width*height <= MAX_REGION_PIXELS
        }) { "PIPELINE_REGION_LIMIT_EXCEEDED: page exceeds the bounded crop plan; no request was sent" }
        return parts.sortedWith(compareBy<UnitRegion> { it.top }.thenBy { it.left })
    }

    private fun validatePage(page: PlanningPage) {
        // Malformed UTF-16 must never silently become replacement characters in
        // published provenance, even when the text is only kept locally.
        if (page.needsVision && page.nativeText.isNotEmpty()) validateUnicodeScalars(page.nativeText)
        page.textRegions.forEach { evidence ->
            require(evidence.textStart in 0..page.nativeText.length &&
                evidence.textEnd in evidence.textStart..page.nativeText.length) {
                "PIPELINE_INVALID_TEXT_COVERAGE: parser layout evidence offsets fall outside the page text"
            }
            require(listOf(evidence.textStart, evidence.textEnd).none { offset ->
                offset > 0 && offset < page.nativeText.length &&
                    page.nativeText[offset-1].isHighSurrogate() && page.nativeText[offset].isLowSurrogate()
            }) { "PIPELINE_INVALID_TEXT_COVERAGE: layout evidence splits a Unicode scalar" }
        }
    }

    private fun PlanningPage.hintText(): String = buildString {
        tableHeader?.let { append(it) }
        continuationKey?.let { append(it) }
    }

    private fun mergeRanges(ranges: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val sorted = ranges.filter { it.second > it.first }.sortedBy { it.first }
        if (sorted.isEmpty()) return emptyList()
        val merged = mutableListOf<Pair<Int, Int>>()
        var start = sorted.first().first
        var end = sorted.first().second
        for (index in 1 until sorted.size) {
            val next = sorted[index]
            if (next.first <= end) {
                if (next.second > end) end = next.second
            } else {
                merged += start to end
                start = next.first
                end = next.second
            }
        }
        merged += start to end
        return merged
    }

    private data class RegionTextAssignment(
        val slice: String,
        val coverageStart: Int,
        val coverageEnd: Int,
        val association: String,
        val evidence: List<LayoutTextEvidence>,
        val degradation: String?,
    )

    companion object {
        const val VERSION = "document-units-v3"
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
            val codePoints = validateUnicodeScalars(text)
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

/**
 * Reject unpaired UTF-16 surrogates and return the code point count.
 *
 * Extracted text must stay a valid Unicode scalar sequence so every published
 * slice re-encodes to exactly the bytes it came from.
 */
private fun validateUnicodeScalars(text: String): Int {
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
    return codePoints
}
