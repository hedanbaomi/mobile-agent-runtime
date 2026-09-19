// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import kotlinx.serialization.Serializable

/**
 * Parser-proven mapping between one page rectangle and the exact native text
 * offset range extracted from that rectangle.
 *
 * Both the [region] and the `[textStart, textEnd)` offsets come from the
 * parser's own layout data. They are **never** inferred from string length,
 * list position, reading-order index or the size of a generated image stripe;
 * a caller that has no coordinates must leave the evidence list empty and let
 * the planner mark the unit as `PAGE_CONTEXT`.
 *
 * Offsets are UTF-16 indices into the page's extracted `text` (the same text
 * the planner stores as `ProcessingUnit.nativeText`).
 *
 * @param region normalized page rectangle that contained this text.
 * @param textStart inclusive UTF-16 offset into the page text.
 * @param textEnd exclusive UTF-16 offset into the page text.
 * @param evidenceSource short label of the producing parser/layout step.
 */
@Serializable
data class LayoutTextEvidence(
    val region: UnitRegion,
    val textStart: Int,
    val textEnd: Int,
    val evidenceSource: String = "parser-layout",
) {
    init {
        require(textStart >= 0 && textEnd >= textStart) {
            "PIPELINE_INVALID_TEXT_COVERAGE: layout evidence offsets must be ordered"
        }
        require(evidenceSource.isNotBlank()) {
            "PIPELINE_INVALID_EVIDENCE: layout evidence source must not be blank"
        }
    }
}

data class ExtractedAsset(
    val localId: String,
    val kind: String,
    val page: Int?,
    val section: String?,
    val bytes: ByteArray,
    val mediaType: String,
    val surroundingText: String,
)

data class ExtractedPage(
    val page: Int,
    val text: String,
    val needsVision: Boolean,
    val width: Int = 612,
    val height: Int = 792,
    val complexLayout: Boolean = false,
    /**
     * Parser-proven text-to-region mappings for this page.
     *
     * Empty means the parser supplied no layout coordinates, so the planner
     * must not fabricate a crop association. The whole page text is then sent
     * at most once as `PAGE_CONTEXT` and only when it fits both the hard 8500
     * UTF-16 bound and the admission budget; it is always published locally as
     * page provenance and is never sliced across image stripes.
     */
    val textRegions: List<LayoutTextEvidence> = emptyList(),
)

data class ParsedPublication(
    val format: SourceFormat,
    val text: String,
    val pages: List<ExtractedPage>,
    val assets: List<ExtractedAsset>,
    val needsVision: Boolean,
    val parserFingerprint: String,
)
