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
data class UnitCoverage(val page: Int, val region: UnitRegion, val nativeTextSource: String = "page-extraction")

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
)

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
                regions.forEach { area ->
                    val kind = if (regions.size == 1) ProcessingUnitKind.PAGE else ProcessingUnitKind.REGION
                    val order = size
                    val id = identity(contentHash, page.page.toString(), kind.name, area.toString(), order.toString(), version, inputIdentity)
                    add(ProcessingUnit(contentHash, page.page, kind, id, parent, order,
                        area.takeIf { kind == ProcessingUnitKind.REGION }, UnitCoverage(page.page, area),
                        page.nativeText, page.needsVision, version, group, continuation, page.tableHeader,
                        sourceInputIdentity = inputIdentity))
                }
            }
        }
    }

    private fun split(page: PlanningPage): List<UnitRegion> {
        val parts = mutableListOf(UnitRegion.FULL)
        var index = 0
        while (index < parts.size) {
            val region = parts[index]
            val width = page.width.toDouble() * (region.right - region.left) / UnitRegion.SCALE
            val height = page.height.toDouble() * (region.bottom - region.top) / UnitRegion.SCALE
            val densityParts = if (page.dense || page.complexLayout || page.tableHeader != null) 2 else 1
            val overLimit = width > MAX_REGION_DIMENSION || height > MAX_REGION_DIMENSION || width * height > MAX_REGION_PIXELS
            if ((overLimit || parts.size < densityParts) && parts.size < MAX_UNITS_PER_PAGE) {
                // Horizontal bands preserve table columns and reading order; exceptionally wide pages split vertically.
                val horizontal = height >= width || (!overLimit && page.tableHeader != null)
                val middle = if (horizontal) (region.top + region.bottom) / 2 else (region.left + region.right) / 2
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
        private fun identity(vararg fields: String): String {
            val canonical = fields.joinToString("") { "${it.length}:$it" }
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
