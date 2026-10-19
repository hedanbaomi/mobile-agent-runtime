// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.worldbook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import runtime.mobileagent.domain.worldbook.WorldBookDocument
import runtime.mobileagent.domain.worldbook.WorldBookEntry
import runtime.mobileagent.domain.worldbook.WorldBookFieldNames
import runtime.mobileagent.domain.worldbook.WorldBookFormat
import runtime.mobileagent.domain.worldbook.WorldBookSettings

/**
 * 世界书编解码：完整无损导入 / 导出。
 *
 * 支持的载体（[WorldBookFormat]）与线格式：
 *  - [WorldBookFormat.NATIVE]
 *    `{"spec":"mobileagent.worldbook","version":1,"format":<来源格式>,"name","description",
 *      "settings","originalJson","entries":[<WorldBookEntry 逐字段序列化>]}`
 *    完全无损（含 `originalJson` / `originalKey` / `format` 来源）；仓储权威格式。
 *  - [WorldBookFormat.SILLY_TAVERN]（ST 1.19 原生文件 / 编辑器导出）
 *    `{"name"?,"description"?,"entries":{"<uid>":{...camelCase ST 字段...}}}`
 *  - [WorldBookFormat.CHARACTER_BOOK]（Character Card V2 `CharacterBook`，独立导出）
 *    `{"name"?,"description"?,"scan_depth","recursive_scanning","token_budget"?,"extensions",
 *      "entries":[{keys,content,enabled,insertion_order,case_sensitive,selective,constant,position,...}]}`
 *  - [WorldBookFormat.LOREBOOK_V3]（Character Card V3 独立世界书）
 *    `{"spec":"lorebook_v3","data":<CharacterBook 形状，entries 必须为数组>}`
 *  - [WorldBookFormat.CHARACTER_CARD_V2] / [WorldBookFormat.CHARACTER_CARD_V3]
 *    `{"spec":"chara_card_v2|chara_card_v3","spec_version":...,"data":{...,"character_book":<CharacterBook>}}`
 *    `originalJson` 保留整张卡；导出仅替换 `data.character_book`，其余字段（含未知字段）原样写回。
 *
 * 字段映射（CC 书级）：`scan_depth`↔settings.scanDepth、`recursive_scanning`↔settings.recursive、
 * `token_budget`↔settings.budgetCap（>0 时写出）。
 *
 * 无损承诺：
 *  - 条目级 `originalJson` + `originalKey`、文档级 `originalJson` 始终保留；
 *  - 导出先写回未知字段、再用已知字段覆盖 → “编辑过的字段生效、未知字段不丢”；
 *  - 重复键、重复 uid、超限、坏 JSON 一律抛 [WorldBookImportException]，失败零写入。
 */
object WorldBookCodec {

    /** 宽容读取用（原生格式可能来自更新版本）。 */
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = true
        allowSpecialFloatingPointValues = false
        isLenient = false
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 解析任意受支持载体。
     *
     * @param fallbackName 载体没有名字时的书名（例如上传文件名）。
     * @throws WorldBookImportException 有界校验失败、重复键/重复 uid、结构不可识别。
     */
    fun decode(
        text: String,
        fallbackName: String = "Imported world book",
        limits: WorldBookImportLimits = WorldBookImportLimits(),
    ): WorldBookImportResult {
        val sink = NoticeSink(limits)
        val guarded = WorldBookJsonGuard.parse(text, limits)
        guarded.notices.forEach { sink.add(it.entryUid, it.code, it.message) }
        val root = guarded.root
        if (isNative(root)) return decodeNative(root, sink, limits)

        val detected = detect(root, sink)
        val document = buildDocument(detected, root, fallbackName, sink, limits)
        sink.add(
            null,
            WorldBookNoticeCodes.FORMAT_DETECTED,
            "Detected ${detected.format} with ${document.entries.size} entries.",
        )
        return WorldBookImportResult(document = document, notices = sink.build())
    }

    /** 按 [format] 导出；缺省沿用文档的来源格式。 */
    fun encode(document: WorldBookDocument, format: WorldBookFormat = document.format): String = when (format) {
        WorldBookFormat.NATIVE -> encodeNative(document)
        WorldBookFormat.SILLY_TAVERN -> json.encodeToString(JsonObject.serializer(), stDocumentJson(document))
        WorldBookFormat.CHARACTER_BOOK -> json.encodeToString(JsonObject.serializer(), ccDocumentJson(document))
        WorldBookFormat.LOREBOOK_V3 -> json.encodeToString(JsonObject.serializer(), lorebookV3Json(document))
        WorldBookFormat.CHARACTER_CARD_V2 -> json.encodeToString(JsonObject.serializer(), cardJson(document, v3 = false))
        WorldBookFormat.CHARACTER_CARD_V3 -> json.encodeToString(JsonObject.serializer(), cardJson(document, v3 = true))
    }

    /** 原生无损格式（仓储权威格式）；往返后文档逐字段相等。 */
    fun encodeNative(document: WorldBookDocument): String =
        json.encodeToString(JsonObject.serializer(), nativeJson(document))

    // ------------------------------------------------------------------ 原生格式

    private fun isNative(root: JsonObject): Boolean =
        (root[WorldBookFieldNames.NATIVE_SPEC_KEY] as? JsonPrimitive)?.content == WorldBookFieldNames.NATIVE_SPEC

    private fun nativeJson(document: WorldBookDocument): JsonObject = buildJsonObject {
        put(WorldBookFieldNames.NATIVE_SPEC_KEY, JsonPrimitive(WorldBookFieldNames.NATIVE_SPEC))
        put(WorldBookFieldNames.NATIVE_VERSION_KEY, JsonPrimitive(WorldBookFieldNames.NATIVE_VERSION))
        put(WorldBookFieldNames.NATIVE_FORMAT_KEY, JsonPrimitive(document.format.name))
        put(WorldBookFieldNames.CC_NAME, JsonPrimitive(document.name))
        put(WorldBookFieldNames.CC_DESCRIPTION, JsonPrimitive(document.description))
        put(WorldBookFieldNames.NATIVE_SETTINGS_KEY, json.encodeToJsonElement(document.settings))
        put(WorldBookFieldNames.NATIVE_ORIGINAL_KEY, document.originalJson)
        put(
            WorldBookFieldNames.ST_ENTRIES,
            buildJsonArray { document.entries.forEach { add(json.encodeToJsonElement(it)) } },
        )
    }

    private fun decodeNative(
        root: JsonObject,
        sink: NoticeSink,
        limits: WorldBookImportLimits,
    ): WorldBookImportResult {
        val rawEntries = when (val container = root[WorldBookFieldNames.ST_ENTRIES]) {
            null, is JsonNull -> emptyList()
            is JsonArray -> container
            else -> throw WorldBookImportException(
                WorldBookNoticeCodes.JSON_ROOT_NOT_OBJECT,
                "Native world book 'entries' must be an array.",
            )
        }
        if (rawEntries.size > limits.maxEntries) {
            throw WorldBookImportException(
                WorldBookNoticeCodes.ENTRY_COUNT_LIMIT,
                "Book has ${rawEntries.size} entries, limit is ${limits.maxEntries}.",
            )
        }
        val entries = ArrayList<WorldBookEntry>(rawEntries.size)
        val seen = HashSet<String>(rawEntries.size * 2)
        rawEntries.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: throw WorldBookImportException(
                WorldBookNoticeCodes.ENTRY_NOT_OBJECT,
                "Native entry #$index is ${element::class.simpleName}, expected an object.",
            )
            val entry = try {
                json.decodeFromJsonElement(WorldBookEntry.serializer(), obj)
            } catch (e: Exception) {
                throw WorldBookImportException(
                    WorldBookNoticeCodes.ENTRY_FIELD_DROPPED,
                    "Native entry #$index could not be read: ${e.message ?: e::class.simpleName}",
                )
            }
            if (entry.keys.size > limits.maxKeysPerEntry || entry.secondaryKeys.size > limits.maxKeysPerEntry) {
                throw WorldBookImportException(
                    WorldBookNoticeCodes.ENTRY_KEY_COUNT_LIMIT,
                    "Entry '${entry.uid}' has more than ${limits.maxKeysPerEntry} keys.",
                )
            }
            if (!seen.add(entry.uid)) {
                throw WorldBookImportException(
                    WorldBookNoticeCodes.ENTRY_DUPLICATE_UID,
                    "Duplicate entry uid '${entry.uid}'.",
                )
            }
            entries += entry
        }
        val rawSettings = try {
            root[WorldBookFieldNames.NATIVE_SETTINGS_KEY]
                ?.let { json.decodeFromJsonElement(WorldBookSettings.serializer(), it) }
                ?: WorldBookSettings()
        } catch (e: Exception) {
            sink.add(null, WorldBookNoticeCodes.ENTRY_FIELD_COERCED, "Native settings could not be read; using defaults.")
            WorldBookSettings()
        }
        // 有界校验：非负/数值区间收敛到硬上限（导入值只能更严，不能提升）。
        val settings = rawSettings.normalized()
        if (settings != rawSettings) {
            sink.add(
                null,
                WorldBookNoticeCodes.SETTINGS_CLAMPED,
                "Native settings were outside the allowed range and were clamped (regexStepBudget<=${WorldBookSettings.HARD_MAX_REGEX_STEP_BUDGET}" +
                    ", maxRegexPatternLength<=${WorldBookSettings.HARD_MAX_REGEX_PATTERN_LENGTH}" +
                    ", maxMessageScanChars<=${WorldBookSettings.HARD_MAX_MESSAGE_SCAN_CHARS}).",
            )
        }
        val provenance = (root[WorldBookFieldNames.NATIVE_FORMAT_KEY] as? JsonPrimitive)
            ?.content
            ?.let { name -> WorldBookFormat.entries.firstOrNull { it.name == name } }
        val originalJson = root[WorldBookFieldNames.NATIVE_ORIGINAL_KEY] as? JsonObject ?: JsonObject(emptyMap())
        sink.add(null, WorldBookNoticeCodes.FORMAT_DETECTED, "Detected NATIVE with ${entries.size} entries.")
        return WorldBookImportResult(
            document = WorldBookDocument(
                name = JsonRead.stringField(root, WorldBookFieldNames.CC_NAME) ?: "Imported world book",
                entries = entries,
                description = JsonRead.stringField(root, WorldBookFieldNames.CC_DESCRIPTION) ?: "",
                settings = settings,
                originalJson = originalJson,
                format = provenance ?: WorldBookFormat.NATIVE,
            ),
            notices = sink.build(),
        )
    }

    // ------------------------------------------------------------------ 探测

    private class Detected(
        val format: WorldBookFormat,
        /** 承载条目与书级字段的对象。 */
        val book: JsonObject,
        /** 条目集合（对象映射或数组）。 */
        val entryContainer: JsonElement?,
        /** 完整角色卡的 `data` 对象（仅角色卡格式）。 */
        val cardData: JsonObject?,
    )

    private fun detect(root: JsonObject, sink: NoticeSink): Detected {
        val rootSpec = stringAt(root, WorldBookFieldNames.CC_SPEC)
        val data = root[WorldBookFieldNames.CC_DATA]

        // 1) 独立 Lorebook V3：{"spec":"lorebook_v3","data":{...}}
        if (rootSpec == WorldBookFieldNames.SPEC_LOREBOOK_V3) {
            val book = (data as? JsonObject) ?: root
            val container = book[WorldBookFieldNames.ST_ENTRIES] ?: root[WorldBookFieldNames.ST_ENTRIES]
            if (container !is JsonArray) {
                sink.add(
                    null,
                    WorldBookNoticeCodes.FORMAT_ASSUMED,
                    "lorebook_v3 without an entries array; imported as an empty book.",
                )
            }
            return Detected(WorldBookFormat.LOREBOOK_V3, book, container, null)
        }

        // 2) 完整角色卡 V2/V3
        val cardData = data as? JsonObject
        val spec = rootSpec ?: cardData?.let { stringAt(it, WorldBookFieldNames.CC_SPEC) }
        if (spec == WorldBookFieldNames.SPEC_CHARA_CARD_V2 || spec == WorldBookFieldNames.SPEC_CHARA_CARD_V3) {
            val format = if (spec == WorldBookFieldNames.SPEC_CHARA_CARD_V3) {
                WorldBookFormat.CHARACTER_CARD_V3
            } else {
                WorldBookFormat.CHARACTER_CARD_V2
            }
            val book = cardData?.get(WorldBookFieldNames.CC_CHARACTER_BOOK) as? JsonObject
            if (book == null) {
                sink.add(
                    null,
                    WorldBookNoticeCodes.FORMAT_CHARACTER_BOOK_MISSING,
                    "Character card has no data.character_book; imported as an empty book.",
                )
                return Detected(format, JsonObject(emptyMap()), null, cardData)
            }
            return Detected(format, book, book[WorldBookFieldNames.ST_ENTRIES], cardData)
        }

        // 3) 裸 character_book 包装
        val embedded = root[WorldBookFieldNames.CC_CHARACTER_BOOK]
        if (embedded is JsonObject) {
            sink.add(
                null,
                WorldBookNoticeCodes.FORMAT_ASSUMED,
                "Root has a bare character_book; treated as CHARACTER_BOOK.",
            )
            return Detected(WorldBookFormat.CHARACTER_BOOK, embedded, embedded[WorldBookFieldNames.ST_ENTRIES], null)
        }

        if (spec != null) {
            sink.add(
                null,
                WorldBookNoticeCodes.FORMAT_ASSUMED,
                "Unrecognized spec '$spec'; fell back to structural detection.",
            )
        }

        // 4) 结构探测：对象映射 = SillyTavern，数组 = Character Book
        return when (val container = root[WorldBookFieldNames.ST_ENTRIES]) {
            is JsonObject -> Detected(WorldBookFormat.SILLY_TAVERN, root, container, null)
            is JsonArray -> Detected(WorldBookFormat.CHARACTER_BOOK, root, container, null)
            null, is JsonNull -> {
                sink.add(
                    null,
                    WorldBookNoticeCodes.FORMAT_EMPTY_ENTRIES,
                    "Root has no entries; imported as an empty book.",
                )
                Detected(WorldBookFormat.SILLY_TAVERN, root, null, null)
            }

            else -> throw WorldBookImportException(
                WorldBookNoticeCodes.JSON_ROOT_NOT_OBJECT,
                "'${WorldBookFieldNames.ST_ENTRIES}' must be an object map or an array.",
            )
        }
    }

    // ------------------------------------------------------------------ 导入

    private fun buildDocument(
        detected: Detected,
        root: JsonObject,
        fallbackName: String,
        sink: NoticeSink,
        limits: WorldBookImportLimits,
    ): WorldBookDocument {
        val book = detected.book
        val rawEntries: List<Pair<String?, JsonObject>> = when (val container = detected.entryContainer) {
            null, is JsonNull -> emptyList()
            is JsonArray -> container.map { element ->
                val obj = element as? JsonObject ?: throw WorldBookImportException(
                    WorldBookNoticeCodes.ENTRY_NOT_OBJECT,
                    "Entry in array is ${element::class.simpleName}, expected an object.",
                )
                Pair(null, obj)
            }

            is JsonObject -> orderedMapEntries(container)
            else -> throw WorldBookImportException(
                WorldBookNoticeCodes.JSON_ROOT_NOT_OBJECT,
                "Unsupported entries container ${container::class.simpleName}.",
            )
        }

        if (rawEntries.size > limits.maxEntries) {
            throw WorldBookImportException(
                WorldBookNoticeCodes.ENTRY_COUNT_LIMIT,
                "Book has ${rawEntries.size} entries, limit is ${limits.maxEntries}.",
            )
        }

        val stShaped = detected.format == WorldBookFormat.SILLY_TAVERN
        val entries = ArrayList<WorldBookEntry>(rawEntries.size)
        val seen = HashSet<String>(rawEntries.size * 2)
        for ((mapKey, raw) in rawEntries) {
            val entry = if (stShaped) {
                WorldBookEntryReader.readStEntry(raw, mapKey ?: syntheticUid(raw, entries.size), mapKey, sink, limits)
            } else {
                WorldBookEntryReader.readCcEntry(raw, entries.size, sink, limits)
            }
            if (!seen.add(entry.uid)) {
                throw WorldBookImportException(
                    WorldBookNoticeCodes.ENTRY_DUPLICATE_UID,
                    "Duplicate entry uid '${entry.uid}'.",
                )
            }
            if (entry.uid.toULongOrNull() == null) {
                sink.add(
                    entry.uid,
                    WorldBookNoticeCodes.ENTRY_UID_NON_NUMERIC,
                    "Entry uid '${entry.uid}' is not numeric; kept as a string.",
                )
            }
            entries += entry
        }

        val settings = readSettings(book, detected.format)
        val name = readName(book, detected.cardData, detected.format) ?: fallbackName
        val description = JsonRead.stringField(book, WorldBookFieldNames.CC_DESCRIPTION)
            ?: JsonRead.stringField(root, WorldBookFieldNames.CC_DESCRIPTION)
            ?: ""

        return WorldBookDocument(
            name = name,
            entries = entries,
            description = description,
            settings = settings,
            originalJson = root,
            format = detected.format,
        )
    }

    /** ST 对象映射的条目顺序：整数型键升序优先，其余按出现顺序（等价 JS 对象键序）。 */
    private fun orderedMapEntries(container: JsonObject): List<Pair<String?, JsonObject>> {
        val numeric = ArrayList<Pair<Long, Pair<String?, JsonObject>>>(container.size)
        val others = ArrayList<Pair<String?, JsonObject>>()
        for ((key, value) in container) {
            val obj = value as? JsonObject ?: throw WorldBookImportException(
                WorldBookNoticeCodes.ENTRY_NOT_OBJECT,
                "Entry '$key' is ${value::class.simpleName}, expected an object.",
            )
            val pair: Pair<String?, JsonObject> = Pair(key, obj)
            val asLong = key.toLongOrNull()
            if (asLong != null) numeric += Pair(asLong, pair) else others += pair
        }
        numeric.sortBy { it.first }
        return numeric.map { it.second } + others
    }

    private fun syntheticUid(raw: JsonObject, index: Int): String {
        val declared = raw[WorldBookFieldNames.ST_UID]
        val text = (declared as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        return text ?: index.toString()
    }

    private fun readName(book: JsonObject, cardData: JsonObject?, format: WorldBookFormat): String? {
        JsonRead.stringField(book, WorldBookFieldNames.CC_NAME)?.let { if (it.isNotBlank()) return it }
        if (format == WorldBookFormat.CHARACTER_CARD_V2 || format == WorldBookFormat.CHARACTER_CARD_V3) {
            val characterName = cardData?.let { JsonRead.stringField(it, WorldBookFieldNames.CC_NAME) }
            if (!characterName.isNullOrBlank()) return "$characterName's Lorebook"
        }
        return null
    }

    /** CCv2/V3 书级字段映射：scan_depth→scanDepth、recursive_scanning→recursive、token_budget→budgetCap。 */
    private fun readSettings(book: JsonObject, format: WorldBookFormat): WorldBookSettings {
        if (format == WorldBookFormat.SILLY_TAVERN) return WorldBookSettings()
        val defaults = WorldBookSettings()
        val scanDepth = JsonRead.intField(book, WorldBookFieldNames.CC_SCAN_DEPTH)
        val tokenBudget = JsonRead.intField(book, WorldBookFieldNames.CC_TOKEN_BUDGET)
        val recursive = JsonRead.booleanField(book, WorldBookFieldNames.CC_RECURSIVE_SCANNING)
        return defaults.copy(
            scanDepth = scanDepth?.coerceIn(0, WorldBookSettings.MAX_SCAN_DEPTH) ?: defaults.scanDepth,
            budgetCap = tokenBudget?.coerceAtLeast(0) ?: defaults.budgetCap,
            recursive = recursive ?: defaults.recursive,
        )
    }

    // ------------------------------------------------------------------ 导出

    private fun stDocumentJson(document: WorldBookDocument): JsonObject = buildJsonObject {
        putBookUnknowns(this, document)
        put(WorldBookFieldNames.CC_NAME, JsonPrimitive(document.name))
        put(WorldBookFieldNames.CC_DESCRIPTION, JsonPrimitive(document.description))
        put(
            WorldBookFieldNames.ST_ENTRIES,
            buildJsonObject {
                val used = HashSet<String>()
                document.entries.forEachIndexed { index, entry ->
                    val preferred = entry.originalKey?.takeIf { it.isNotBlank() && used.add(it) }
                    val key = preferred ?: entry.uid.takeIf { used.add(it) } ?: "entry-$index"
                    put(key, WorldBookEntryWriter.stEntryJson(entry))
                }
            },
        )
    }

    private fun ccDocumentJson(document: WorldBookDocument): JsonObject = buildJsonObject {
        putBookUnknowns(this, document)
        put(WorldBookFieldNames.CC_NAME, JsonPrimitive(document.name))
        put(WorldBookFieldNames.CC_DESCRIPTION, JsonPrimitive(document.description))
        putCcBookFields(this, document)
        putCcExtensionsIfAbsent(this, document)
        put(
            WorldBookFieldNames.ST_ENTRIES,
            buildJsonArray { document.entries.forEach { add(WorldBookEntryWriter.ccEntryJson(it)) } },
        )
    }

    private fun lorebookV3Json(document: WorldBookDocument): JsonObject = buildJsonObject {
        // 只写规范形状 {spec, data}：不复制任何 carrier 判别/包装键（否则会“新 entries + 旧包装”双份）。
        put(WorldBookFieldNames.CC_SPEC, JsonPrimitive(WorldBookFieldNames.SPEC_LOREBOOK_V3))
        put(
            WorldBookFieldNames.CC_DATA,
            buildJsonObject {
                putBookUnknowns(this, document)
                put(WorldBookFieldNames.CC_NAME, JsonPrimitive(document.name))
                put(WorldBookFieldNames.CC_DESCRIPTION, JsonPrimitive(document.description))
                putCcBookFields(this, document)
                putCcExtensionsIfAbsent(this, document)
                put(
                    WorldBookFieldNames.ST_ENTRIES,
                    buildJsonArray { document.entries.forEach { add(WorldBookEntryWriter.ccEntryJson(it)) } },
                )
            },
        )
    }

    /** CC 书级字段：`scan_depth` / `recursive_scanning` / `token_budget`（编辑 settings 后导出即生效）。 */
    private fun putCcBookFields(builder: JsonObjectBuilder, document: WorldBookDocument) {
        builder.put(WorldBookFieldNames.CC_SCAN_DEPTH, JsonPrimitive(document.settings.scanDepth))
        builder.put(WorldBookFieldNames.CC_RECURSIVE_SCANNING, JsonPrimitive(document.settings.recursive))
        if (document.settings.budgetCap > 0) {
            builder.put(WorldBookFieldNames.CC_TOKEN_BUDGET, JsonPrimitive(document.settings.budgetCap))
        }
    }

    private fun cardJson(document: WorldBookDocument, v3: Boolean): JsonObject = buildJsonObject {
        // 根层未知字段只在“原始根不是书本身”时保留；书自身的未知字段一律进 data.character_book。
        if (!originalBookSubtreeIsRoot(document)) {
            putUnknownFields(document.originalJson, CARRIER_KEYS)
        }
        put(
            WorldBookFieldNames.CC_SPEC,
            JsonPrimitive(if (v3) WorldBookFieldNames.SPEC_CHARA_CARD_V3 else WorldBookFieldNames.SPEC_CHARA_CARD_V2),
        )
        put("spec_version", JsonPrimitive(if (v3) "3.0" else "2.0"))
        put(
            WorldBookFieldNames.CC_DATA,
            buildJsonObject {
                val originalData =
                    (document.originalJson[WorldBookFieldNames.CC_DATA] as? JsonObject) ?: JsonObject(emptyMap())
                putUnknownFields(originalData, CARD_DATA_MANAGED)
                if (WorldBookFieldNames.CC_NAME !in originalData) {
                    put(WorldBookFieldNames.CC_NAME, JsonPrimitive(document.name))
                }
                put(
                    WorldBookFieldNames.CC_CHARACTER_BOOK,
                    buildJsonObject {
                        putBookUnknowns(this, document)
                        put(WorldBookFieldNames.CC_NAME, JsonPrimitive(document.name))
                        put(WorldBookFieldNames.CC_DESCRIPTION, JsonPrimitive(document.description))
                        putCcBookFields(this, document)
                        putCcExtensionsIfAbsent(this, document)
                        put(
                            WorldBookFieldNames.ST_ENTRIES,
                            buildJsonArray { document.entries.forEach { add(WorldBookEntryWriter.ccEntryJson(it)) } },
                        )
                    },
                )
            },
        )
    }

    /**
     * 写出原始世界书子树的未知字段（排除书级管理键与载体判别/包装键）。
     * `extensions` 等书级扩展因此不会丢失，也不会把旧包装带进新格式。
     */
    private fun putBookUnknowns(builder: JsonObjectBuilder, document: WorldBookDocument) {
        builder.putUnknownFields(originalBookSubtree(document), CC_BOOK_MANAGED + CARRIER_KEYS)
    }

    private fun putCcExtensionsIfAbsent(builder: JsonObjectBuilder, document: WorldBookDocument) {
        if (WorldBookFieldNames.CC_EXTENSIONS !in originalBookSubtree(document)) {
            builder.put(WorldBookFieldNames.CC_EXTENSIONS, JsonObject(emptyMap()))
        }
    }

    /**
     * 原始 JSON 中真正承载世界书的子树：
     * 角色卡 → `data.character_book`；bare 包装 → `character_book`；Lorebook V3 → `data`；
     * SillyTavern / 扁平书 → 根（根必须自己有 `entries`）；都识别不到时返回空对象（不复制载体）。
     */
    private fun originalBookSubtree(document: WorldBookDocument): JsonObject {
        val root = document.originalJson
        val data = root[WorldBookFieldNames.CC_DATA] as? JsonObject
        return listOfNotNull(
            data?.get(WorldBookFieldNames.CC_CHARACTER_BOOK) as? JsonObject,
            root[WorldBookFieldNames.CC_CHARACTER_BOOK] as? JsonObject,
            root.takeIf { it[WorldBookFieldNames.ST_ENTRIES] != null },
            data?.takeIf { it[WorldBookFieldNames.ST_ENTRIES] != null },
        ).firstOrNull() ?: JsonObject(emptyMap())
    }

    private fun originalBookSubtreeIsRoot(document: WorldBookDocument): Boolean =
        originalBookSubtree(document) === document.originalJson

    /** 载体判别/包装键：跨格式导出时一律不复制。 */
    private val CARRIER_KEYS = setOf(
        "spec",
        "spec_version",
        "data",
        "character_book",
        "entries",
        "name",
        "description",
    )

    /** 角色卡 `data` 内由本编解码管理的键（`name`/`description` 是角色字段，必须保留）。 */
    private val CARD_DATA_MANAGED = setOf("character_book", "spec", "spec_version", "entries")

    /** 书级由 typed 字段管理的键（其余键视为未知字段原样保留）。 */
    private val CC_BOOK_MANAGED = setOf(
        WorldBookFieldNames.ST_ENTRIES,
        WorldBookFieldNames.CC_NAME,
        WorldBookFieldNames.CC_DESCRIPTION,
        WorldBookFieldNames.CC_SCAN_DEPTH,
        WorldBookFieldNames.CC_TOKEN_BUDGET,
        WorldBookFieldNames.CC_RECURSIVE_SCANNING,
    )

    private fun stringAt(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
