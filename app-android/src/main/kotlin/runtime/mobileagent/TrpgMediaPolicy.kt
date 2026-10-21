// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

/**
 * Local-only media rules for the TRPG table.
 *
 * Selections are user preferences for one conversation. They are never
 * campaign state: the campaign export, model prompts and public player export
 * never contain these URIs or file contents. Only `content://` URIs granted by
 * the system document picker are accepted so untrusted text cannot point the
 * feature at a private file path.
 */
object TrpgMediaPolicy {
    const val MAX_IMAGE_BYTES = 8L * 1024L * 1024L
    const val MAX_IMAGE_EDGE = 2048
    const val MAX_IMAGE_PIXELS = 2048L * 2048L
    const val MAX_AUDIO_BYTES = 256L * 1024L * 1024L
    const val MAX_URI_CHARS = 2_048
    const val MAX_SPEECH_DRAFT_CHARS = 4_000
    const val MAX_READ_ALOUD_CHARS = 8_000
    const val MAX_CONVERSATION_ID_CHARS = 256
    private const val CONTENT_SCHEME = "content://"

    fun conversationIdIsValid(conversationId: String): Boolean {
        if (conversationId.isBlank()) return false
        if (conversationId.length > MAX_CONVERSATION_ID_CHARS) return false
        return conversationId.none { it.isISOControl() }
    }

    fun isAllowedContentUri(uri: String?): Boolean {
        if (uri == null) return false
        if (!uri.startsWith(CONTENT_SCHEME)) return false
        if (uri.length <= CONTENT_SCHEME.length) return false
        if (uri.length > MAX_URI_CHARS) return false
        return uri.none { it.isWhitespace() || it.isISOControl() }
    }
}

/** The two independent local picks. Either may be absent. */
data class TrpgMediaSelection(
    val audioUri: String? = null,
    val imageUri: String? = null,
) {
    fun withAudio(uri: String?): TrpgMediaSelection = copy(audioUri = uri)

    fun withImage(uri: String?): TrpgMediaSelection = copy(imageUri = uri)
}

object TrpgMediaSelections {
    fun sanitize(selection: TrpgMediaSelection): TrpgMediaSelection = TrpgMediaSelection(
        audioUri = selection.audioUri?.takeIf { TrpgMediaPolicy.isAllowedContentUri(it) },
        imageUri = selection.imageUri?.takeIf { TrpgMediaPolicy.isAllowedContentUri(it) },
    )

    fun requireValid(conversationId: String, selection: TrpgMediaSelection) {
        require(TrpgMediaPolicy.conversationIdIsValid(conversationId)) { "Invalid conversation id" }
        require(selection.audioUri == null || TrpgMediaPolicy.isAllowedContentUri(selection.audioUri)) {
            "Audio selection must be a content:// URI"
        }
        require(selection.imageUri == null || TrpgMediaPolicy.isAllowedContentUri(selection.imageUri)) {
            "Image selection must be a content:// URI"
        }
    }
}

enum class TrpgMediaAudioRejection { NOT_AUDIO, TOO_LARGE }

object TrpgMediaAudioFiles {
    private val ALSO_AUDIO = setOf("application/ogg", "application/x-ogg")

    fun rejection(mimeType: String?, sizeBytes: Long?): TrpgMediaAudioRejection? {
        val type = mimeType?.lowercase()
        if (!type.isNullOrBlank() && !type.startsWith("audio/") && type !in ALSO_AUDIO) {
            return TrpgMediaAudioRejection.NOT_AUDIO
        }
        if (sizeBytes != null && sizeBytes > TrpgMediaPolicy.MAX_AUDIO_BYTES) {
            return TrpgMediaAudioRejection.TOO_LARGE
        }
        return null
    }
}

internal fun trpgMediaText(chinese: Boolean, chineseText: String, englishText: String): String =
    if (chinese) chineseText else englishText
