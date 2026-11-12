// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

internal data class ClosedMatch(val rank: Int, val end: Int)

internal object StreamingSecretRedactorPatterns {
    private const val MIN_TAIL_KEEP = 32
    private const val COMPACT_HEADROOM = 48
    private const val SK_ALPHABET = "_-ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    private val bearerOpenPattern =
        Regex("""(?i)b(?:e(?:a(?:r(?:e(?:r(?:\s+(?:\S+)?)?)?)?)?)?)?\z""")
    private val apiKeyOpenPattern = Regex(
        """(?i)(?:x(?:-(?:a(?:p(?:i(?:[_-]?(?:k(?:e(?:y)?)?)?)?)?)?)?)?|a(?:p(?:i(?:[_-]?(?:k(?:e(?:y)?)?)?)?)?)?|(?:x-)?api[_-]?key["']?(?:\s*|\s*[:=]\s*(?:|"[^"\r\n]*|'[^'\r\n]*|[^\s,;"'}][^\s,;}]*)))\z""",
    )
    private val cookieOpenPattern =
        Regex("""(?i)(?:c(?:o(?:o(?:k(?:i(?:e)?)?)?)?)?|cookie\s*(?::\s*(?:\S[^\r\n]*)?)?)\z""")
    private val setCookieOpenPattern = Regex(
        """(?i)(?:s(?:e(?:t(?:-(?:c(?:o(?:o(?:k(?:i(?:e)?)?)?)?)?)?)?)?)?|set-cookie\s*(?::\s*(?:\S[^\r\n]*)?)?)\z""",
    )
    private val jsonCookieOpenPattern = Regex(
        """(?i)["'](?:|s(?:e(?:t(?:-(?:c(?:o(?:o(?:k(?:i(?:e)?)?)?)?)?)?)?)?)?|c(?:o(?:o(?:k(?:i(?:e)?)?)?)?)?|(?:set-)?cookie["']\s*(?::\s*(?:|"[^"\r\n]*|'[^'\r\n]*))?)\z""",
    )
    private val skOpenPattern = Regex("""(?i)s(?:k(?:-[A-Za-z0-9_-]*)?)?\z""")

    private val closedBearer = Regex("""(?i)bearer\s+\S+""")
    private val closedApiKey =
        Regex("""(?i)(?:x-)?api[_-]?key["']?\s*[:=]\s*(?:"[^"\r\n]*"|'[^'\r\n]*'|[^\s,;}]+)""")
    private val closedCookie = Regex("""(?i)(?:set-)?cookie\s*:\s*[^\r\n]+""")
    private val closedJsonCookie =
        Regex("""(?i)["'](?:set-)?cookie["']\s*:\s*(?:"[^"\r\n]*"|'[^'\r\n]*')""")
    private val closedSk = Regex("""(?i)sk-[A-Za-z0-9_-]{10,}""")

    private val closedFromBearer = listOf(1 to closedBearer)
    private val closedFromApiKey = listOf(2 to closedApiKey)
    private val closedFromCookie = listOf(3 to closedCookie)
    private val closedFromJsonCookie = listOf(4 to closedJsonCookie)
    private val closedFromS = listOf(3 to closedCookie, 5 to closedSk)

    private val openDoubleQuotedApiKey = Regex("""(?i)^(?:x-)?api[_-]?key["']?\s*[:=]\s*"[^"\r\n]*\z""")
    private val openSingleQuotedApiKey = Regex("""(?i)^(?:x-)?api[_-]?key["']?\s*[:=]\s*'[^'\r\n]*\z""")
    private val openDoubleQuotedJsonCookie = Regex("""(?i)^["'](?:set-)?cookie["']\s*:\s*"[^"\r\n]*\z""")
    private val openSingleQuotedJsonCookie = Regex("""(?i)^["'](?:set-)?cookie["']\s*:\s*'[^'\r\n]*\z""")

    private val bearerHeadRegex = Regex("""(?i)^bearer\s+""")
    private val unquotedApiKeyOpenRegex = Regex("""(?i)^(?:x-)?api[_-]?key["']?\s*[:=]\s*[^\s,;"'}][^\s,;}]*\z""")
    private val apiKeyHeadRegex = Regex("""(?i)^(?:x-)?api[_-]?key["']?\s*[:=]\s*""")
    private val cookieValueOpenRegex = Regex("""(?i)^(?:set-)?cookie\s*:\s*\S[^\r\n]*\z""")
    private val cookieHeadRegex = Regex("""(?i)^(?:set-)?cookie\s*:\s*""")

    fun isOpenPatternPrefix(text: CharSequence, cursor: Int, maxRank: Int = 5): Boolean =
        when (text[cursor].lowercaseChar()) {
            'b' -> maxRank >= 1 && bearerOpenPattern.matchAt(text, cursor) != null
            'a', 'x' -> maxRank >= 2 && apiKeyOpenPattern.matchAt(text, cursor) != null
            'c' -> maxRank >= 3 && cookieOpenPattern.matchAt(text, cursor) != null
            '"', '\'' -> maxRank >= 4 && jsonCookieOpenPattern.matchAt(text, cursor) != null
            's' -> (maxRank >= 3 && setCookieOpenPattern.matchAt(text, cursor) != null) ||
                (maxRank >= 5 && skOpenPattern.matchAt(text, cursor) != null)
            else -> false
        }

    fun closedPatternMatch(text: CharSequence, cursor: Int): ClosedMatch? {
        val candidates = when (text[cursor].lowercaseChar()) {
            'b' -> closedFromBearer
            'a', 'x' -> closedFromApiKey
            'c' -> closedFromCookie
            '"', '\'' -> closedFromJsonCookie
            's' -> closedFromS
            else -> return null
        }
        for ((rank, regex) in candidates) {
            val match = regex.matchAt(text, cursor)
            if (match != null) return ClosedMatch(rank, match.range.last + 1)
        }
        return null
    }

    fun detectOpenQuoteChar(buf: CharSequence): Char {
        if (buf.isEmpty()) return '\u0000'
        return when (buf[0].lowercaseChar()) {
            'a', 'x' -> when {
                openDoubleQuotedApiKey.matchAt(buf, 0) != null -> '"'
                openSingleQuotedApiKey.matchAt(buf, 0) != null -> '\''
                else -> '\u0000'
            }
            '"', '\'' -> when {
                openDoubleQuotedJsonCookie.matchAt(buf, 0) != null -> '"'
                openSingleQuotedJsonCookie.matchAt(buf, 0) != null -> '\''
                else -> '\u0000'
            }
            else -> '\u0000'
        }
    }

    fun selectGuardChar(secrets: List<String>): Char {
        if (secrets.none { '\u001F' in it }) return '\u001F'
        for (code in 0x0001..0x001E) {
            val ch = code.toChar()
            if (!ch.isWhitespace() && secrets.none { ch in it }) return ch
        }
        for (code in 0xE000..0xF8FF) {
            val ch = code.toChar()
            if (secrets.none { ch in it }) return ch
        }
        return '\u001F'
    }

    fun selectSkGuardChar(secrets: List<String>): Char? {
        if (secrets.isEmpty()) return '_'
        return SK_ALPHABET.firstOrNull { ch -> secrets.none { ch in it } }
    }

    fun compactOpenTailInPlace(
        buf: StringBuilder,
        secrets: List<String>,
        guardToken: String,
        cookieGuardToken: String,
        skGuardToken: String?,
    ) {
        val maxSecretLen = secrets.firstOrNull()?.length ?: 0
        val tailKeep = maxSecretLen + MIN_TAIL_KEEP
        if (buf.length <= tailKeep + COMPACT_HEADROOM) return
        if (secrets.any { buf.indexOf(it) >= 0 }) return
        when (buf[0].lowercaseChar()) {
            'b' -> if (bearerOpenPattern.matchAt(buf, 0) != null) {
                replaceAfterHead(buf, bearerHeadRegex, tailKeep, guardToken)
            }
            'a', 'x' -> if (unquotedApiKeyOpenRegex.matchAt(buf, 0) != null) {
                replaceAfterHead(buf, apiKeyHeadRegex, tailKeep, guardToken)
            }
            'c' -> if (cookieValueOpenRegex.matchAt(buf, 0) != null) {
                replaceAfterHead(buf, cookieHeadRegex, tailKeep, cookieGuardToken)
            }
            's' -> compactFromS(buf, secrets, tailKeep, cookieGuardToken, skGuardToken)
        }
    }

    private fun compactFromS(
        buf: StringBuilder,
        secrets: List<String>,
        tailKeep: Int,
        cookieGuardToken: String,
        skGuardToken: String?,
    ) {
        if (cookieValueOpenRegex.matchAt(buf, 0) != null) {
            replaceAfterHead(buf, cookieHeadRegex, tailKeep, cookieGuardToken)
            return
        }
        if (skGuardToken != null && closedSk.matchAt(buf, 0)?.range?.last == buf.length - 1) {
            if (secrets.none { buf.indexOf(it) >= 0 }) {
                val deleteEnd = buf.length - tailKeep
                if (deleteEnd > 13) buf.replace(13, deleteEnd, skGuardToken)
            }
        }
    }

    private fun replaceAfterHead(buf: StringBuilder, headRegex: Regex, tailKeep: Int, replacement: String) {
        val head = headRegex.find(buf) ?: return
        val valStart = head.range.last + 1
        val deleteEnd = buf.length - tailKeep
        if (valStart < buf.length && deleteEnd > valStart + 1) {
            buf.replace(valStart + 1, deleteEnd, replacement)
        }
    }
}
