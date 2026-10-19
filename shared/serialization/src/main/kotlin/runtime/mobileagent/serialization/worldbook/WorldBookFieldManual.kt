// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.worldbook

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import runtime.mobileagent.domain.worldbook.WorldBookEntry
import runtime.mobileagent.domain.worldbook.WorldBookFieldNames
import runtime.mobileagent.domain.worldbook.WorldBookPosition
import runtime.mobileagent.domain.worldbook.WorldBookRole
import runtime.mobileagent.domain.worldbook.WorldBookSelectiveLogic

/**
 * 类型化字段的容错读取（越界/类型不符 → 记录 notice 并取安全默认值）。
 *
 * 字段名与默认值对照 SillyTavern 1.19.0 `newWorldInfoEntryDefinition` / `convertCharacterBook`
 * 与 Character Card V2/V3 规范。
 */
internal object WorldBookFieldManual {

    /** `position`：CC 的 `before_char`/`after_char` 字符串或 ST 的 0..7 数字。 */
    fun position(
        raw: JsonObject,
        extensions: JsonObject,
        uid: String,
        fromExtensions: Boolean,
        sink: NoticeSink,
    ): WorldBookPosition {
        val candidates = buildList {
            if (fromExtensions) add(extensions["position"])
            add(raw[WorldBookFieldNames.ST_POSITION])
            if (!fromExtensions) add(extensions["position"])
            add(raw["position"])
        }
        for (element in candidates) {
            if (element == null || element is JsonNull) continue
            if (element is JsonPrimitive && element.isString) {
                when (element.content) {
                    "before_char" -> return WorldBookPosition.BEFORE_CHAR
                    "after_char" -> return WorldBookPosition.AFTER_CHAR
                }
            }
            val code = JsonRead.int(element)
            if (code != null) {
                WorldBookPosition.fromCode(code)?.let { return it }
                sink.add(uid, WorldBookNoticeCodes.FIELD_POSITION_OUT_OF_RANGE, "position $code outside 0..7; using 0.")
                return WorldBookPosition.BEFORE_CHAR
            }
        }
        return WorldBookPosition.BEFORE_CHAR
    }

    /** `role`（0..2）。 */
    fun role(
        raw: JsonObject,
        extensions: JsonObject,
        uid: String,
        fromExtensions: Boolean,
        sink: NoticeSink,
    ): WorldBookRole {
        for (code in codes(raw, extensions, WorldBookFieldNames.ST_ROLE, fromExtensions)) {
            WorldBookRole.fromCode(code)?.let { return it }
            sink.add(uid, WorldBookNoticeCodes.FIELD_ROLE_OUT_OF_RANGE, "role $code outside 0..2; using 0.")
            return WorldBookRole.SYSTEM
        }
        return WorldBookRole.SYSTEM
    }

    /** `selectiveLogic`（0..3）。 */
    fun selectiveLogic(
        raw: JsonObject,
        extensions: JsonObject,
        uid: String,
        fromExtensions: Boolean,
        sink: NoticeSink,
    ): WorldBookSelectiveLogic {
        for (code in codes(raw, extensions, WorldBookFieldNames.ST_SELECTIVE_LOGIC, fromExtensions)) {
            WorldBookSelectiveLogic.fromCode(code)?.let { return it }
            sink.add(uid, WorldBookNoticeCodes.ENTRY_FIELD_COERCED, "selectiveLogic $code outside 0..3; using 0.")
            return WorldBookSelectiveLogic.AND_ANY
        }
        return WorldBookSelectiveLogic.AND_ANY
    }

    /** `delayUntilRecursion`：false→0，true→1，数字原样（负数归 0）。 */
    fun delayUntilRecursion(element: JsonElement?, uid: String, sink: NoticeSink): Int {
        if (element == null || element is JsonNull) return 0
        val asBoolean = if (element is JsonPrimitive && element.intOrNull == null) JsonRead.boolean(element) else null
        if (asBoolean != null) return if (asBoolean) 1 else 0
        val number = JsonRead.int(element)
        if (number == null) {
            sink.add(
                uid,
                WorldBookNoticeCodes.ENTRY_FIELD_COERCED,
                "delayUntilRecursion is neither boolean nor number; using 0.",
            )
            return 0
        }
        if (number < 0) {
            sink.add(uid, WorldBookNoticeCodes.FIELD_NEGATIVE_COUNTER, "delayUntilRecursion $number < 0; using 0.")
            return 0
        }
        return number
    }

    /** `probability`：clamp 到 0..100。 */
    fun probability(element: JsonElement?, uid: String, sink: NoticeSink): Int {
        val raw = JsonRead.int(element) ?: 100
        val clamped = raw.coerceIn(0, 100)
        if (clamped != raw) {
            sink.add(uid, WorldBookNoticeCodes.FIELD_PROBABILITY_OUT_OF_RANGE, "probability $raw clamped to $clamped.")
        }
        return clamped
    }

    /** `groupWeight`：负数归 0。 */
    fun groupWeight(element: JsonElement?, uid: String, sink: NoticeSink): Int {
        val raw = JsonRead.int(element) ?: WorldBookEntry.DEFAULT_GROUP_WEIGHT
        if (raw < 0) {
            sink.add(uid, WorldBookNoticeCodes.FIELD_GROUP_WEIGHT_OUT_OF_RANGE, "groupWeight $raw < 0; using 0.")
            return 0
        }
        return raw
    }

    /** `depth`：负数归 0。 */
    fun depth(element: JsonElement?, uid: String, sink: NoticeSink): Int {
        val raw = JsonRead.int(element) ?: WorldBookEntry.DEFAULT_DEPTH
        if (raw < 0) {
            sink.add(uid, WorldBookNoticeCodes.FIELD_DEPTH_OUT_OF_RANGE, "depth $raw < 0; using 0.")
            return 0
        }
        return raw
    }

    private fun codes(
        raw: JsonObject,
        extensions: JsonObject,
        key: String,
        fromExtensions: Boolean,
    ): List<Int> {
        val candidates = buildList {
            if (fromExtensions) add(extensions[key])
            add(raw[key])
            if (!fromExtensions) add(extensions[key])
        }
        return candidates.mapNotNull { JsonRead.int(it) }
    }
}
