package com.example.narrator.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

internal object HtmlToText {
    private val BLOCK_TAGS = setOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6",
        "li", "ul", "ol", "blockquote", "pre",
        "div", "section", "article", "aside", "figure", "figcaption", "table", "tr",
        "hr", "br",
    )

    private val WHITESPACE = Regex("\\s+")
    private val PAGE_NUMBER = Regex("^(?:p(?:age|g)?\\.?\\s*)?\\d+$", RegexOption.IGNORE_CASE)

    private val FOOTNOTE_EPUB_TYPES = setOf("noteref", "footnote", "endnote", "rearnote")

    fun extractParagraphs(xhtml: String): List<String> {
        val doc: Document = Jsoup.parse(xhtml)
        cleanup(doc)
        val out = mutableListOf<String>()
        val body: Element = doc.body() ?: return emptyList()
        emit(body, out)
        return out
    }

    private fun cleanup(doc: Document) {
        doc.select("script, style, noscript, nav, header, footer, aside").remove()

        val drops = mutableListOf<Element>()
        for (el in doc.allElements) {
            if (isPageBreak(el) || isPageNumber(el) || isFootnoteMarker(el)) {
                drops.add(el)
            }
        }
        drops.forEach { it.remove() }
    }

    private fun emit(el: Element, out: MutableList<String>) {
        val buf = StringBuilder()
        for (node in el.childNodes()) {
            when (node) {
                is TextNode -> buf.append(node.text())
                is Element -> {
                    if (node.tagName() == "br") {
                        flush(buf, out)
                    } else if (isBlock(node.tagName())) {
                        flush(buf, out)
                        emit(node, out)
                    } else {
                        buf.append(node.text())
                    }
                }
            }
        }
        flush(buf, out)
    }

    private fun flush(buf: StringBuilder, out: MutableList<String>) {
        val text = buf.toString().replace(WHITESPACE, " ").trim()
        buf.setLength(0)
        if (text.isEmpty()) return
        if (PAGE_NUMBER.matches(text)) return
        out.add(text)
    }

    private fun isBlock(tag: String): Boolean = tag.lowercase() in BLOCK_TAGS

    private fun isPageBreak(el: Element): Boolean {
        val role = el.attr("role")
        val type = el.attr("epub:type")
        return role == "doc-pagebreak" || type == "pagebreak"
    }

    private fun isPageNumber(el: Element): Boolean {
        val cls = el.className().lowercase()
        return cls.split(WHITESPACE).any { it.startsWith("page") && it.contains("num") || it == "page" || it == "pagenumber" }
    }

    private fun isFootnoteMarker(el: Element): Boolean {
        val tag = el.tagName().lowercase()
        if (tag != "sup" && tag != "a") return false
        if (el.attr("epub:type") in FOOTNOTE_EPUB_TYPES) return true
        val cls = el.className().lowercase()
        return cls.contains("footnote") || cls.contains("noteref")
    }
}
