// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.charactercard

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import runtime.mobileagent.domain.charactercard.CharacterCardFieldNames
import runtime.mobileagent.domain.charactercard.CharacterCardFormat

/**
 * 探测结果：[format] 为命中的载体；[data] 为已知字段所在对象（V2/V3 是 `data`，V1 是根）。
 */
internal class CharacterCardDetection(
    val format: CharacterCardFormat,
    val data: JsonObject,
    val specVersion: String?,
)

/**
 * 载体探测：只接受有明确证据的 V1/V2/V3，**不把任意世界书或 `{}` 当角色卡**。
 *
 * 规则：
 *  - 有 `spec`：必须是字符串且等于 `chara_card_v2` / `chara_card_v3`；其它值（含 `lorebook_v3`）明确拒绝；
 *    随后 `data` 必须是对象。
 *  - 无 `spec`：必须存在 V1 特有的 `first_mes` / `mes_example` 之一（世界书不会有），否则拒绝。
 */
internal object CharacterCardDetector {

    /** 已支持的最高 spec major：更高版本明确拒绝，不猜测字段语义。 */
    private const val MAX_KNOWN_SPEC_MAJOR = 3

    fun detect(root: JsonObject, sink: CharacterCardNoticeSink): CharacterCardDetection {
        val specElement = root[CharacterCardFieldNames.SPEC]
        if (specElement != null && specElement !is JsonNull) {
            val spec = StrictField.stringValue(specElement, CharacterCardFieldNames.SPEC)
            if (spec == CharacterCardFieldNames.SPEC_CHARA_CARD_V2) {
                return CharacterCardDetection(CharacterCardFormat.V2, requireData(root), readSpecVersion(root, 2, sink))
            }
            if (spec == CharacterCardFieldNames.SPEC_CHARA_CARD_V3) {
                return CharacterCardDetection(CharacterCardFormat.V3, requireData(root), readSpecVersion(root, 3, sink))
            }
            throw CharacterCardImportException(
                CharacterCardErrorCodes.UNKNOWN_SPEC,
                "Unsupported 'spec' value; expected '${CharacterCardFieldNames.SPEC_CHARA_CARD_V2}' " +
                    "or '${CharacterCardFieldNames.SPEC_CHARA_CARD_V3}'.",
            )
        }
        if (!hasV1Evidence(root)) {
            throw CharacterCardImportException(
                CharacterCardErrorCodes.UNRECOGNIZED,
                "Input has no character card spec and no V1 greeting fields; refusing to import it as a character card.",
            )
        }
        return CharacterCardDetection(CharacterCardFormat.V1, root, null)
    }

    private fun requireData(root: JsonObject): JsonObject =
        root[CharacterCardFieldNames.DATA] as? JsonObject ?: throw CharacterCardImportException(
            CharacterCardErrorCodes.DATA_NOT_OBJECT,
            "Field 'data' must be a JSON object.",
        )

    /** V1 证据：`first_mes` 或 `mes_example` 存在且不为 null。 */
    private fun hasV1Evidence(root: JsonObject): Boolean =
        CharacterCardFieldNames.V1_GREETING_KEYS.any { key ->
            val value = root[key]
            value != null && value !is JsonNull
        }

    /**
     * 校验 `spec_version`：缺失只告警；非字符串/非版本形态明确失败；major 高于 3 明确拒绝；
     * major 与 spec 不一致只告警（V3 规范要求向后兼容，不因 spec_version 拒绝导入）。
     */
    private fun readSpecVersion(
        root: JsonObject,
        expectedMajor: Int,
        sink: CharacterCardNoticeSink,
    ): String? {
        val element = root[CharacterCardFieldNames.SPEC_VERSION]
        if (element == null || element is JsonNull) {
            sink.add(
                CharacterCardNoticeCodes.CARD_SPEC_VERSION_MISSING,
                "Missing 'spec_version'; imported according to the declared spec.",
            )
            return null
        }
        val text = StrictField.stringValue(element, CharacterCardFieldNames.SPEC_VERSION)
        val major = text.substringBefore('.').toIntOrNull() ?: throw CharacterCardImportException(
            CharacterCardErrorCodes.INVALID_SPEC_VERSION,
            "'spec_version' is not a numeric version string.",
        )
        if (major > MAX_KNOWN_SPEC_MAJOR) {
            throw CharacterCardImportException(
                CharacterCardErrorCodes.UNSUPPORTED_SPEC_VERSION,
                "'spec_version' major $major is newer than the supported major $MAX_KNOWN_SPEC_MAJOR.",
            )
        }
        if (major != expectedMajor) {
            sink.add(
                CharacterCardNoticeCodes.CARD_SPEC_VERSION_MISMATCH,
                "'spec_version' major $major does not match the declared spec major $expectedMajor; imported as declared.",
            )
        }
        return text
    }
}
