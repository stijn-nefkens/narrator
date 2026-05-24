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

    fun splitFragment(href: String): Pair<String, String?> {
        val idx = href.indexOf('#')
        return if (idx < 0) href to null else href.substring(0, idx) to href.substring(idx + 1)
    }
}
