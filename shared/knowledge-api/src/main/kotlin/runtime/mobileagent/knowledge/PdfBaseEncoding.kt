// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

internal enum class PdfBaseEncoding { STANDARD, WIN_ANSI, MAC_ROMAN, SYMBOL }

internal fun decodePdfBaseByte(base: PdfBaseEncoding?, code: Int): String? {
    if (base == null) return null
    if (base == PdfBaseEncoding.SYMBOL) return symbolChar(code)
    if (code in 0x20..0x7E) return code.toChar().toString()
    return when (base) {
        PdfBaseEncoding.STANDARD -> null
        PdfBaseEncoding.WIN_ANSI -> winAnsiChar(code)
        PdfBaseEncoding.MAC_ROMAN -> macRomanChar(code)
        PdfBaseEncoding.SYMBOL -> null
    }
}

private fun winAnsiChar(code: Int): String? {
    if (code in 0xA0..0xFF) return String(byteArrayOf(code.toByte()), Charsets.ISO_8859_1)
    return when (code) {
        0x80 -> "€"
        0x82 -> "‚"
        0x83 -> "ƒ"
        0x84 -> "„"
        0x85 -> "…"
        0x86 -> "†"
        0x87 -> "‡"
        0x88 -> "ˆ"
        0x89 -> "‰"
        0x8A -> "Š"
        0x8B -> "‹"
        0x8C -> "Œ"
        0x8E -> "Ž"
        0x91 -> "‘"
        0x92 -> "’"
        0x93 -> "“"
        0x94 -> "”"
        0x95 -> "•"
        0x96 -> "–"
        0x97 -> "—"
        0x98 -> "˜"
        0x99 -> "™"
        0x9A -> "š"
        0x9B -> "›"
        0x9C -> "œ"
        0x9E -> "ž"
        0x9F -> "Ÿ"
        else -> null
    }
}

private fun macRomanChar(code: Int): String? {
    val mapped = MAC_ROMAN_80.getOrNull(code - 0x80) ?: return null
    return mapped.takeIf { it.isNotEmpty() }
}

// PDF MacRomanEncoding 0x80–0xFF. Empty slots are undefined and fail closed.
private val MAC_ROMAN_80 = arrayOf(
    "Ä", "Å", "Ç", "É", "Ñ", "Ö", "Ü", "á",
    "à", "â", "ä", "ã", "å", "ç", "é", "è",
    "ê", "ë", "í", "ì", "î", "ï", "ñ", "ó",
    "ò", "ô", "ö", "õ", "ú", "ù", "û", "ü",
    "†", "°", "¢", "£", "§", "•", "¶", "ß",
    "®", "©", "™", "´", "¨", "≠", "Æ", "Ø",
    "∞", "±", "≤", "≥", "¥", "µ", "∂", "∑",
    "∏", "π", "∫", "ª", "º", "Ω", "æ", "ø",
    "¿", "¡", "¬", "√", "ƒ", "≈", "∆", "«",
    "»", "…", "\u00A0", "À", "Ã", "Õ", "Œ", "œ",
    "–", "—", "“", "”", "‘", "’", "÷", "◊",
    "ÿ", "Ÿ", "⁄", "€", "‹", "›", "ﬁ", "ﬂ",
    "‡", "·", "‚", "„", "‰", "Â", "Ê", "Á",
    "Ë", "È", "Í", "Î", "Ï", "Ì", "Ó", "Ô",
    "", "Ò", "Ú", "Û", "Ù", "ı", "ˆ", "˜",
    "¯", "˘", "˙", "˚", "¸", "˝", "˛", "ˇ",
)

// Adobe Symbol built-in encoding (PDF 32000-1 D.5). Codes absent from these
// tables are undefined in the set or have no dependable Unicode text mapping
// (for example the bounding-box glyph fragments) and fail closed.
private fun symbolChar(code: Int): String? {
    SYMBOL_ASCII_OVERRIDES[code]?.let { return it }
    if (code in 0x20..0x7E) return code.toChar().toString()
    val mapped = SYMBOL_HIGH_80.getOrNull(code - 0x80) ?: return null
    return mapped.takeIf { it.isNotEmpty() }
}

// Symbol reassigns part of 0x20-0x7E to Greek letters and math operators.
private val SYMBOL_ASCII_OVERRIDES: Map<Int, String> = mapOf(
    0x22 to "\u2200", // universal
    0x24 to "\u2203", // existential
    0x27 to "\u220B", // suchthat
    0x2A to "\u2217", // asteriskmath
    0x2D to "\u2212", // minus
    0x40 to "\u2245", // congruent
    0x41 to "\u0391", 0x42 to "\u0392", 0x43 to "\u03A7", 0x44 to "\u0394",
    0x45 to "\u0395", 0x46 to "\u03A6", 0x47 to "\u0393", 0x48 to "\u0397",
    0x49 to "\u0399", 0x4A to "\u03D1", 0x4B to "\u039A", 0x4C to "\u039B",
    0x4D to "\u039C", 0x4E to "\u039D", 0x4F to "\u039F", 0x50 to "\u03A0",
    0x51 to "\u0398", 0x52 to "\u03A1", 0x53 to "\u03A3", 0x54 to "\u03A4",
    0x55 to "\u03A5", 0x56 to "\u03C2", 0x57 to "\u03A9", 0x58 to "\u039E",
    0x59 to "\u03A8", 0x5A to "\u0396",
    0x5C to "\u2234", // therefore
    0x5E to "\u22A5", // perpendicular
    0x60 to "\u203E", // radicalex (overline)
    0x61 to "\u03B1", 0x62 to "\u03B2", 0x63 to "\u03C7", 0x64 to "\u03B4",
    0x65 to "\u03B5", 0x66 to "\u03C6", 0x67 to "\u03B3", 0x68 to "\u03B7",
    0x69 to "\u03B9", 0x6A to "\u03D5", 0x6B to "\u03BA", 0x6C to "\u03BB",
    0x6D to "\u03BC", 0x6E to "\u03BD", 0x6F to "\u03BF", 0x70 to "\u03C0",
    0x71 to "\u03B8", 0x72 to "\u03C1", 0x73 to "\u03C3", 0x74 to "\u03C4",
    0x75 to "\u03C5", 0x76 to "\u03D6", 0x77 to "\u03C9", 0x78 to "\u03BE",
    0x79 to "\u03C8", 0x7A to "\u03B6",
    0x7E to "\u223C", // similar
)

// Symbol 0x80-0xFF. Empty slots fail closed, matching the MacRoman table style.
private val SYMBOL_HIGH_80 = arrayOf(
    "", "", "", "", "", "", "", "",
    "", "", "", "", "", "", "", "",
    "", "", "", "", "", "", "", "",
    "", "", "", "", "", "", "", "",
    "\u20AC", "\u03D2", "\u2032", "\u2264", "\u2044", "\u221E", "\u0192", "\u2663",
    "\u2666", "\u2665", "\u2660", "\u2194", "\u2190", "\u2191", "\u2192", "\u2193",
    "\u00B0", "\u00B1", "\u2033", "\u2265", "\u00D7", "\u221D", "\u2202", "\u2022",
    "\u00F7", "\u2260", "\u2261", "\u2248", "\u2026", "", "", "\u21B5",
    "\u2135", "\u2111", "\u211C", "\u2118", "\u2297", "\u2295", "\u2205", "\u2229",
    "\u222A", "\u2283", "\u2287", "\u2284", "\u2282", "\u2286", "\u2208", "\u2209",
    "\u2220", "\u2207", "\u00AE", "\u00A9", "\u2122", "\u220F", "\u221A", "\u22C5",
    "\u00AC", "\u2227", "\u2228", "\u21D4", "\u21D0", "\u21D1", "\u21D2", "\u21D3",
    "\u25CA", "\u2329", "\u00AE", "\u00A9", "\u2122", "\u2211", "\u239B", "\u239C",
    "\u239D", "\u23A1", "\u23A2", "\u23A3", "\u23A7", "\u23A8", "\u23A9", "\u23AA",
    "", "\u232A", "\u222B", "\u2320", "\u23AE", "\u2321", "\u239E", "\u239F",
    "\u23A0", "\u23A4", "\u23A5", "\u23A6", "\u23AB", "\u23AC", "\u23AD", "",
)
