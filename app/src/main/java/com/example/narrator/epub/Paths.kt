package com.example.narrator.epub

internal object Paths {
    fun parentDir(path: String): String {
        val normalized = path.replace('\\', '/')
        val idx = normalized.lastIndexOf('/')
        return if (idx >= 0) normalized.substring(0, idx + 1) else ""
    }

    fun resolve(baseDir: String, relative: String): String {
        if (relative.isEmpty()) return baseDir.trimEnd('/')
        val ref = relative.replace('\\', '/')
        if (ref.startsWith('/')) return ref.removePrefix("/")
        val base = baseDir.replace('\\', '/')
        val combined = if (base.isEmpty() || base.endsWith('/')) base + ref else "$base/$ref"
        val parts = ArrayDeque<String>()
        for (segment in combined.split('/')) {
            when (segment) {
                "", "." -> continue
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }

    /**
     * Decodes `%XX` escapes (as UTF-8) in an href/path. Unlike URLDecoder this leaves `+` alone —
     * in a URL path `+` is a literal plus, and form-decoding it to a space broke file names
     * containing one. Malformed escapes are kept verbatim.
     */
    fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val hi = if (s[i] == '%' && i + 2 < s.length) hexValue(s[i + 1]) else -1
            val lo = if (hi >= 0) hexValue(s[i + 2]) else -1
            if (lo >= 0) {
                out.write(hi * HEX_DIGITS.length + lo)
                i += ESCAPE_LENGTH
            } else {
                // Copy a whole code point so a surrogate pair isn't split into two '?'s.
                val end = i + Character.charCount(s.codePointAt(i))
                out.write(s.substring(i, end).toByteArray(Charsets.UTF_8))
                i = end
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private const val HEX_DIGITS = "0123456789abcdef"
    private const val ESCAPE_LENGTH = 3  // "%XX"

    /** Value of hex digit [c], or -1 (no sign / Unicode-digit leniency, unlike toIntOrNull). */
    private fun hexValue(c: Char): Int = HEX_DIGITS.indexOf(c.lowercaseChar())

    fun splitFragment(href: String): Pair<String, String?> {
        val idx = href.indexOf('#')
        return if (idx < 0) href to null else href.substring(0, idx) to href.substring(idx + 1)
    }
}
