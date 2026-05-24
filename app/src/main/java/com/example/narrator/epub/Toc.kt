package com.example.narrator.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

internal data class TocEntry(
    val title: String,
    val href: String,
)

internal object Toc {
    fun parseNavXhtml(xml: String): List<TocEntry> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val tocNav = doc.getElementsByTag("nav").firstOrNull { it.attr("epub:type") == "toc" }
        val root: Element = tocNav ?: doc.getElementsByTag("nav").firstOrNull() ?: doc
        return root.getElementsByTag("a")
            .mapNotNull { a ->
                val title = a.text().trim()
                val href = a.attr("href").trim()
                if (title.isEmpty() || href.isEmpty()) null else TocEntry(title, href)
            }
    }

    /** Returns the href of the EPUB3 landmark marked epub:type="bodymatter", or null. */
    fun parseBodyMatterHref(xml: String): String? {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val landmarks = doc.getElementsByTag("nav")
            .firstOrNull { it.attr("epub:type") == "landmarks" } ?: return null
        return landmarks.getElementsByTag("a")
            .firstOrNull { it.attr("epub:type").split(Regex("\\s+")).any { t -> t == "bodymatter" } }
            ?.attr("href")?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun parseNcx(xml: String): List<TocEntry> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return doc.getElementsByTag("navPoint").mapNotNull { np ->
            val label = np.getElementsByTag("navLabel").firstOrNull()
                ?.getElementsByTag("text")?.firstOrNull()?.text()?.trim().orEmpty()
            val src = np.getElementsByTag("content").firstOrNull()
                ?.attr("src")?.trim().orEmpty()
            if (label.isEmpty() || src.isEmpty()) null else TocEntry(label, src)
        }
    }
}
