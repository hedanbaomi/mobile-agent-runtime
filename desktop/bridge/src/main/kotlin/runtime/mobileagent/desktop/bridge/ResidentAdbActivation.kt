// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.desktop.bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Wire constants shared with the APK's fixed resident activation entrypoint. */
internal object ResidentAdbActivation {
    const val ACTIVATION_TOKEN_BYTES = 32
    const val BOOTSTRAP_SECRET_BYTES = 32
    const val BOOTSTRAP_BYTES = 4 + ACTIVATION_TOKEN_BYTES + BOOTSTRAP_SECRET_BYTES
    const val BOOTSTRAP_MAGIC = 0x4d415231 // MAR1
    const val PROVIDER_URI = "content://runtime.mobileagent.resident-adb"

    fun decodeActivationTokenHex(chars: CharArray): ByteArray {
        require(chars.size == ACTIVATION_TOKEN_BYTES * 2) {
            "activation token must be exactly 64 hexadecimal characters"
        }
        val result = ByteArray(ACTIVATION_TOKEN_BYTES)
        for (index in result.indices) {
            val high = chars[index * 2].hexValue()
            val low = chars[index * 2 + 1].hexValue()
            require(high >= 0 && low >= 0) { "activation token must be hexadecimal" }
            result[index] = ((high shl 4) or low).toByte()
        }
        return result
    }

    fun encodeBootstrapFrame(token: ByteArray, secret: ByteArray): ByteArray {
        require(token.size == ACTIVATION_TOKEN_BYTES)
        require(secret.size == BOOTSTRAP_SECRET_BYTES)
        return ByteBuffer.allocate(BOOTSTRAP_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(BOOTSTRAP_MAGIC)
            .put(token)
            .put(secret)
            .array()
    }

    fun parseCurrentAndroidUser(output: ByteArray): Int {
        val text = output.decodeUtf8Strict().trim()
        require(text.matches(Regex("[0-9]{1,10}"))) {
            "Android did not return a valid current user id"
        }
        return text.toInt().also { require(it >= 0) }
    }

    fun providerReportsReady(output: ByteArray): Boolean {
        val text = output.decodeUtf8Strict()
        val bundleStart = text.indexOf("Bundle")
        if (bundleStart < 0) return false
        val open = text.indexOf('{', bundleStart)
        if (open < 0) return false
        val close = text.indexOf('}', open + 1)
        if (close < 0) return false
        val readyValues = text.substring(open + 1, close).split(',').mapNotNull { field ->
            val separator = field.indexOf('=')
            if (separator < 0 || field.substring(0, separator).trim() != "ready") null
            else field.substring(separator + 1).trim()
        }
        return readyValues.singleOrNull() == "true"
    }

    /** Only the validated Android user id is interpolated into this fixed device-side script. */
    fun launchScript(userId: Int): String {
        require(userId >= 0)
        return "exec 3<&0; " +
            "apk=\$(pm path --user $userId runtime.mobileagent </dev/null | sed -n 's/^package://p' | head -n 1); " +
            "[ -n \"\$apk\" ] || exit 127; " +
            "CLASSPATH=\"\$apk\" app_process /system/bin " +
            "runtime.mobileagent.resident.ResidentAdbMain $userId <&3 3<&- >/dev/null 2>&1 &"
    }

    private fun Char.hexValue(): Int = when (this) {
        in '0'..'9' -> code - '0'.code
        in 'a'..'f' -> code - 'a'.code + 10
        in 'A'..'F' -> code - 'A'.code + 10
        else -> -1
    }

    private fun ByteArray.decodeUtf8Strict(): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()
}
