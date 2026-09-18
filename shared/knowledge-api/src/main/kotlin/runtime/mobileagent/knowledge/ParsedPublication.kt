// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

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
)

data class ParsedPublication(
    val format: SourceFormat,
    val text: String,
    val pages: List<ExtractedPage>,
    val assets: List<ExtractedAsset>,
    val needsVision: Boolean,
    val parserFingerprint: String,
)
