// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.charactercard

import kotlinx.serialization.json.JsonObject
import runtime.mobileagent.serialization.worldbook.WorldBookImportException
import runtime.mobileagent.serialization.worldbook.WorldBookJsonGuard
import runtime.mobileagent.serialization.worldbook.WorldBookNoticeCodes

/**
 * 角色卡 JSON 的有界前置体检。
 *
 * 直接复用同 module 的 [WorldBookJsonGuard]（不改动它）：重复键拒绝、嵌套/节点/对象键/单字段长度上限、
 * 语法错误统一映射。体检通过后输入规模已被证明有界，解析不会失控。
 *
 * 只把 guard 的 worldbook 错误码映射成角色卡的稳定错误码，消息不携带任何输入内容。
 */
internal object CharacterCardJson {

    /** 体检结果：根对象 + 实际参与解析的文本（BOM 已剥离）+ BOM 说明。 */
    class Parsed(
        val root: JsonObject,
        val payload: String,
        val notices: List<CharacterCardImportNotice>,
    )

    /**
     * @throws CharacterCardImportException 越界、重复键、语法错误或根不是对象。
     */
    fun parse(text: String, limits: CharacterCardLimits): Parsed {
        val notices = ArrayList<CharacterCardImportNotice>(1)
        var payload = text
        if (payload.startsWith(BOM)) {
            payload = payload.substring(1)
            notices += CharacterCardImportNotice(
                CharacterCardNoticeCodes.JSON_BOM,
                "Stripped UTF-8 BOM before parsing.",
            )
        }
        val guarded = try {
            WorldBookJsonGuard.parse(payload, limits.toWorldBookLimits())
        } catch (e: WorldBookImportException) {
            throw mapGuardFailure(e.code)
        }
        return Parsed(guarded.root, payload, notices)
    }

    /** guard 错误码 → 角色卡错误码（一一对应，不拼接 guard 的原始消息）。 */
    private fun mapGuardFailure(code: String): CharacterCardImportException = when (code) {
        WorldBookNoticeCodes.JSON_SIZE_LIMIT -> CharacterCardImportException(
            CharacterCardErrorCodes.JSON_SIZE_LIMIT,
            "JSON text exceeds the configured character limit.",
        )

        WorldBookNoticeCodes.JSON_DEPTH_LIMIT -> CharacterCardImportException(
            CharacterCardErrorCodes.JSON_DEPTH_LIMIT,
            "JSON nesting is deeper than the configured limit.",
        )

        WorldBookNoticeCodes.JSON_NODE_LIMIT -> CharacterCardImportException(
            CharacterCardErrorCodes.JSON_NODE_LIMIT,
            "JSON contains more values than the configured limit.",
        )

        WorldBookNoticeCodes.JSON_OBJECT_KEYS_LIMIT -> CharacterCardImportException(
            CharacterCardErrorCodes.JSON_OBJECT_KEYS_LIMIT,
            "A JSON object has more keys than the configured limit.",
        )

        WorldBookNoticeCodes.JSON_STRING_LIMIT -> CharacterCardImportException(
            CharacterCardErrorCodes.JSON_STRING_LIMIT,
            "A JSON string is longer than the configured field limit.",
        )

        WorldBookNoticeCodes.JSON_DUPLICATE_KEY -> CharacterCardImportException(
            CharacterCardErrorCodes.JSON_DUPLICATE_KEY,
            "Duplicate object key at the same nesting level.",
        )

        WorldBookNoticeCodes.JSON_ROOT_NOT_OBJECT -> CharacterCardImportException(
            CharacterCardErrorCodes.ROOT_NOT_OBJECT,
            "Character card root must be a JSON object.",
        )

        else -> CharacterCardImportException(
            CharacterCardErrorCodes.JSON_SYNTAX,
            "Malformed or unsupported JSON input.",
        )
    }

    private const val BOM = '\uFEFF'
}
