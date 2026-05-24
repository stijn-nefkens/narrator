package com.example.narrator.epub

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

internal object Container {
    const val PATH = "META-INF/container.xml"

    fun rootfilePath(zip: ZipReader): String {
        val xml = zip.readText(PATH)
            ?: throw EpubParseException("Missing $PATH — not a valid EPUB")
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val rootfile = doc.getElementsByTag("rootfile").firstOrNull()
            ?: throw EpubParseException("$PATH has no <rootfile>")
        val path = rootfile.attr("full-path").trim()
        if (path.isEmpty()) throw EpubParseException("rootfile has empty full-path")
        return path
    }
}
