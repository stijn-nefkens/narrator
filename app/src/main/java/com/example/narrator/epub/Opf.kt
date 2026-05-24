package com.example.narrator.epub

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

internal data class OpfManifestItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val properties: String,
) {
    fun hasProperty(token: String): Boolean =
        properties.split(WHITESPACE).any { it == token }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

internal data class OpfDocument(
    val title: String,
    val author: String,
    val items: Map<String, OpfManifestItem>,
    val spine: List<String>,
    val coverImageId: String?,
) {
    fun coverImage(): OpfManifestItem? = coverImageId?.let { items[it] }
    fun navItem(): OpfManifestItem? = items.values.firstOrNull { it.hasProperty("nav") }
    fun ncxItem(): OpfManifestItem? = items.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
}

internal object Opf {
    fun parse(xml: String): OpfDocument {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())

        val title = firstText(doc, "dc:title", "title")
        val author = firstText(doc, "dc:creator", "creator")

        val items = doc.getElementsByTag("item")
            .filter { it.parent()?.tagName() == "manifest" }
            .associate { el ->
                val id = el.attr("id")
                id to OpfManifestItem(
                    id = id,
                    href = el.attr("href"),
                    mediaType = el.attr("media-type"),
                    properties = el.attr("properties"),
                )
            }

        val spine = doc.getElementsByTag("itemref")
            .filter { it.parent()?.tagName() == "spine" }
            .map { it.attr("idref") }
            .filter { it.isNotEmpty() }

        val cover3 = items.values.firstOrNull { it.hasProperty("cover-image") }?.id
        val cover2 = doc.getElementsByTag("meta")
            .firstOrNull { it.attr("name") == "cover" }
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
        val coverImageId = cover3 ?: cover2

        return OpfDocument(
            title = title.ifEmpty { "Unknown title" },
            author = author.ifEmpty { "Unknown author" },
            items = items,
            spine = spine,
            coverImageId = coverImageId,
        )
    }

    private fun firstText(doc: org.jsoup.nodes.Document, vararg tags: String): String {
        for (tag in tags) {
            val el = doc.getElementsByTag(tag).firstOrNull()
            if (el != null) return el.text().trim()
        }
        return ""
    }
}
