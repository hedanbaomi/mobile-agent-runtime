// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.charactercard

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * 严格 UTF-8 解码：非法字节序列（含过长编码、孤立代理）一律失败，不做替换字符降级。
 *
 * BOM 在这里**不**剥离：`U+FEFF` 是合法 UTF-8 码位，交给 JSON 层统一处理并告警。
 */
internal object StrictText {

    fun decodeUtf8(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw CharacterCardImportException(
                CharacterCardErrorCodes.ENCODING_INVALID,
                "Input is not valid UTF-8.",
            )
        }
    }
}
