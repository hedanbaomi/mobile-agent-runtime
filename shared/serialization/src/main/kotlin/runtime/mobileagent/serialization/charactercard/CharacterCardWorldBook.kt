// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.charactercard

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import runtime.mobileagent.domain.charactercard.CharacterCardFieldNames
import runtime.mobileagent.domain.charactercard.CharacterCardFormat
import runtime.mobileagent.domain.worldbook.WorldBookDocument
import runtime.mobileagent.domain.worldbook.WorldBookFieldNames
import runtime.mobileagent.serialization.worldbook.WorldBookCodec
import runtime.mobileagent.serialization.worldbook.WorldBookImportException

/**
 * 内嵌 `character_book` 的桥接：**复用既有 [WorldBookCodec]**（含其严格校验与有界限额），不重复实现。
 *
 * 契约：
 *  - 没有 `character_book`（或显式 null）→ 返回 null，属正常情况；
 *  - 有书但结构损坏（不是对象、`entries` 不是数组、条目非法、重复 uid、超限）→ 明确失败，
 *    绝不静默丢弃或返回空书；
 *  - 世界书仅保留带格式标识的书子树；完整原始卡由 CharacterCardDocument 保留，避免每轮读书重复解码整卡。
 */
internal object CharacterCardWorldBook {

    fun read(
        detection: CharacterCardDetection,
        payload: String,
        sink: CharacterCardNoticeSink,
        limits: CharacterCardLimits,
    ): WorldBookDocument? {
        if (detection.format == CharacterCardFormat.V1) return null
        val element = detection.data[CharacterCardFieldNames.CHARACTER_BOOK] ?: return null
        if (element is JsonNull) {
            sink.add(
                CharacterCardNoticeCodes.CARD_FIELD_NULL,
                "Field 'character_book' was null; treated as absent.",
            )
            return null
        }
        val book = element as? JsonObject ?: throw CharacterCardImportException(
            CharacterCardErrorCodes.CHARACTER_BOOK_INVALID,
            "Field 'character_book' must be a JSON object.",
        )
        if (book[WorldBookFieldNames.ST_ENTRIES] !is JsonArray) {
            throw CharacterCardImportException(
                CharacterCardErrorCodes.CHARACTER_BOOK_INVALID,
                "Field 'character_book.entries' must be a JSON array.",
            )
        }
        val decoded = try {
            val wrapper = buildJsonObject {
                put("spec", if (detection.format == CharacterCardFormat.V3) "chara_card_v3" else "chara_card_v2")
                put("data", buildJsonObject { put("character_book", book) })
            }
            WorldBookCodec.decode(
                text = wrapper.toString(),
                fallbackName = "Imported character book",
                limits = limits.toWorldBookLimits(),
            )
        } catch (e: WorldBookImportException) {
            throw CharacterCardImportException(
                CharacterCardErrorCodes.WORLDBOOK_INVALID,
                "Embedded 'character_book' was rejected by the world book parser (${e.code}).",
            )
        }
        sink.add(
            CharacterCardNoticeCodes.CARD_WORLDBOOK_IMPORTED,
            "Embedded 'character_book' imported with ${decoded.document.entries.size} entries.",
        )
        return decoded.document
    }
}
