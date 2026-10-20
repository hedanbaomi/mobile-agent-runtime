// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.charactercard

import runtime.mobileagent.domain.charactercard.CharacterCardDocument
import runtime.mobileagent.domain.charactercard.CharacterCardFormat

/**
 * 角色卡导入编解码入口。
 *
 * 支持载体：
 *  - **JSON**：legacy Tavern V1（六个根字段）、Character Card V2、Character Card V3；
 *  - **PNG**：标准 tEXt chunk `chara`（V1/V2）或 `ccv3`（V3），值为 base64(UTF-8 JSON)；
 *    两者同时存在时按规范优先 `ccv3`，`ccv3` 损坏时**不**降级到 `chara`。
 *
 * 安全边界（全部为硬约束，输入无法放宽）：
 *  - 文件 ≤ [MAX_FILE_BYTES]；JSON ≤ 8 MiB 字符；单字段 ≤ 1 MiB 字符；嵌套/节点/对象键/列表数有界；
 *  - UTF-8 严格解码（非法字节序列失败），支持 BOM；重复 JSON 键直接拒绝；
 *  - 未知 spec / 更高 major / 错误字段类型 / 重复或冲突 PNG 元数据 / CRC 失败 / 截断明确失败；
 *  - 不解码 PNG 像素、不执行卡内脚本/模板/正则、不联网、不读取远程 assets。
 *
 * 保真承诺：返回文档的 `originalJson` 是完整原始 JSON 根对象，未知字段、`extensions`、`assets`
 * 一律原样保留；保留字段只是数据，不代表权限、工具授权或模型配置。
 */
object CharacterCardCodec {

    /** 允许的最大输入文件字节数（32 MiB）。超出即失败，不截断。 */
    const val MAX_FILE_BYTES: Int = 32 * 1024 * 1024

    /** 载体没有可用名字时使用的回退名。 */
    const val DEFAULT_FALLBACK_NAME: String = "Imported character"

    /**
     * 从文件字节导入：按 PNG 签名自动选择 PNG 元数据或 UTF-8 JSON。
     *
     * @throws CharacterCardImportException 越界、结构错误、无法识别或内嵌世界书损坏。
     */
    fun decode(bytes: ByteArray, fallbackName: String = DEFAULT_FALLBACK_NAME): CharacterCardImportResult =
        decodeBytes(bytes, fallbackName, CharacterCardLimits())

    /**
     * 从 JSON 文本导入（不含 PNG 容器）。
     *
     * @throws CharacterCardImportException 越界、结构错误、无法识别或内嵌世界书损坏。
     */
    fun decodeJson(text: String, fallbackName: String = DEFAULT_FALLBACK_NAME): CharacterCardImportResult =
        decodeText(text, fallbackName, CharacterCardLimits(), CharacterCardContainers.JSON, emptyList(), null)

    /** 测试入口：JSON 文本 + 有界限额。 */
    internal fun decodeJson(
        text: String,
        fallbackName: String,
        limits: CharacterCardLimits,
    ): CharacterCardImportResult =
        decodeText(text, fallbackName, limits, CharacterCardContainers.JSON, emptyList(), null)

    /** 测试/内部入口：可注入有界限额（限额只由调用方降低，输入不可提高）。 */
    internal fun decodeBytes(
        bytes: ByteArray,
        fallbackName: String,
        limits: CharacterCardLimits,
    ): CharacterCardImportResult {
        if (bytes.size > limits.maxFileBytes) {
            throw CharacterCardImportException(
                CharacterCardErrorCodes.FILE_TOO_LARGE,
                "Input has ${bytes.size} bytes, limit is ${limits.maxFileBytes}.",
            )
        }
        if (PngChunkScanner.hasPngSignature(bytes)) {
            val metadata = PngTextMetadata.extract(bytes, limits)
            return decodeText(
                text = metadata.text,
                fallbackName = fallbackName,
                limits = limits,
                container = CharacterCardContainers.PNG,
                initialNotices = metadata.notices,
                pngKeyword = metadata.keyword,
            )
        }
        return decodeText(
            text = StrictText.decodeUtf8(bytes),
            fallbackName = fallbackName,
            limits = limits,
            container = CharacterCardContainers.JSON,
            initialNotices = emptyList(),
            pngKeyword = null,
        )
    }

    /** 共享实现：JSON 体检 → 载体探测 → 字段读取 → 内嵌世界书 → 组装结果。 */
    internal fun decodeText(
        text: String,
        fallbackName: String,
        limits: CharacterCardLimits,
        container: String,
        initialNotices: List<CharacterCardImportNotice>,
        pngKeyword: String?,
    ): CharacterCardImportResult {
        val sink = CharacterCardNoticeSink(limits, initialNotices)
        val json = CharacterCardJson.parse(text, limits)
        json.notices.forEach { sink.add(it.code, it.message) }
        val detection = CharacterCardDetector.detect(json.root, sink)
        val fields = CharacterCardFieldReader.read(detection, limits, sink)
        val name = resolveName(fields.name, fallbackName, limits, sink)
        val worldBook = CharacterCardWorldBook.read(detection, json.payload, sink, limits)
        val document = CharacterCardDocument(
            name = name,
            description = fields.description,
            personality = fields.personality,
            scenario = fields.scenario,
            firstMessage = fields.firstMessage,
            exampleMessages = fields.exampleMessages,
            systemPrompt = fields.systemPrompt,
            postHistoryInstructions = fields.postHistoryInstructions,
            alternateGreetings = fields.alternateGreetings,
            creatorNotes = fields.creatorNotes,
            creator = fields.creator,
            characterVersion = fields.characterVersion,
            tags = fields.tags,
            nickname = fields.nickname,
            groupOnlyGreetings = fields.groupOnlyGreetings,
            format = detection.format,
            originalJson = json.root,
        )
        addContainerNotices(sink, container, detection.format, pngKeyword)
        addPreservationNotices(sink)
        return CharacterCardImportResult(
            document = document,
            worldBook = worldBook,
            notices = sink.build(),
            container = container,
        )
    }

    private fun resolveName(
        raw: String?,
        fallbackName: String,
        limits: CharacterCardLimits,
        sink: CharacterCardNoticeSink,
    ): String {
        if (raw == null || raw.isBlank()) {
            sink.add(
                CharacterCardNoticeCodes.CARD_NAME_FALLBACK,
                "Character card has no usable 'name'; fallback name applied.",
            )
            return fallbackName.ifBlank { DEFAULT_FALLBACK_NAME }
        }
        if (raw.length > limits.maxNameChars) {
            sink.add(
                CharacterCardNoticeCodes.CARD_NAME_TRUNCATED,
                "'name' exceeds ${limits.maxNameChars} characters and was truncated; the original value stays in originalJson.",
            )
            return raw.substring(0, limits.maxNameChars)
        }
        return raw
    }

    /** PNG 专属说明：头像像素与内嵌资源不导入；chunk 名与 spec 不一致时提示。 */
    private fun addContainerNotices(
        sink: CharacterCardNoticeSink,
        container: String,
        format: CharacterCardFormat,
        pngKeyword: String?,
    ) {
        if (container != CharacterCardContainers.PNG) return
        if (pngKeyword == PngTextMetadata.KEYWORD_CCV3 && format != CharacterCardFormat.V3) {
            sink.add(
                CharacterCardNoticeCodes.PNG_CONTAINER_SPEC_MISMATCH,
                "PNG 'ccv3' metadata did not contain a V3 card; imported as detected ${format.name}.",
            )
        }
        if (pngKeyword == PngTextMetadata.KEYWORD_CHARA && format == CharacterCardFormat.V3) {
            sink.add(
                CharacterCardNoticeCodes.PNG_CONTAINER_SPEC_MISMATCH,
                "PNG 'chara' metadata contained a V3 card; imported as V3.",
            )
        }
        sink.add(
            CharacterCardNoticeCodes.PNG_AVATAR_NOT_IMPORTED,
            "PNG avatar pixels are not extracted or saved; the PNG is used as a metadata container only.",
        )
        sink.add(
            CharacterCardNoticeCodes.PNG_ASSETS_NOT_IMPORTED,
            "PNG embedded assets (for example 'chara-ext-asset_*') are not imported.",
        )
    }

    /** 保留字段说明：它们只是数据，导入阶段不执行、不联网、不解释为权限。 */
    private fun addPreservationNotices(sink: CharacterCardNoticeSink) {
        sink.add(
            CharacterCardNoticeCodes.CARD_SCRIPTS_NOT_EXECUTED,
            "Card text is preserved only; scripts, templates and regex are never executed during import.",
        )
        sink.add(
            CharacterCardNoticeCodes.CARD_ASSETS_NOT_FETCHED,
            "Assets referenced by 'uri' are preserved in originalJson and were not fetched.",
        )
        sink.add(
            CharacterCardNoticeCodes.CARD_UNKNOWN_FIELDS_PRESERVED,
            "Unknown fields are preserved in originalJson as data only; they are not permissions, tools or model configuration.",
        )
    }
}
