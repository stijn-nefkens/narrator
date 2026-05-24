package com.example.narrator.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.util.Locale

object EpubParser {

    fun parse(file: File, locale: Locale = Locale.US): Book =
        FileInputStream(file).use { parse(it, locale) }

    fun parse(input: InputStream, locale: Locale = Locale.US): Book {
        val zip = try {
            ZipReader.from(input)
        } catch (e: Exception) {
            throw EpubParseException("Failed to read EPUB archive", e)
        }

        val opfPath = Container.rootfilePath(zip)
        val opfDir = Paths.parentDir(opfPath)
        val opfXml = zip.readText(opfPath)
            ?: throw EpubParseException("OPF not found at $opfPath")
        val opf = Opf.parse(opfXml)

        val tocResult = readToc(zip, opf, opfDir)

        val tocBySpineHref = buildSpineToTocMap(tocResult.entries, tocResult.tocDir, opf, opfDir)

        val raw = mutableListOf<RawChapter>()
        opf.spine.forEachIndexed { spineIndex, idref ->
            val item = opf.items[idref] ?: return@forEachIndexed
            val href = item.href
            val path = Paths.resolve(opfDir, href)
            val xhtml = zip.readText(path) ?: return@forEachIndexed
            val fragmentsForFile = tocBySpineHref[href].orEmpty()
            raw.addAll(
                chaptersForFile(
                    spineHref = href,
                    spineAbsPath = path,
                    spineIndex = spineIndex,
                    xhtml = xhtml,
                    tocByFragment = fragmentsForFile,
                    locale = locale,
                )
            )
        }

        val filtered = applyBodyMatterAndHeuristics(raw, tocResult.bodyMatterAbsPath)
        val chapters = finalizeChapters(filtered)

        val cover = opf.coverImage()?.let { item ->
            val path = Paths.resolve(opfDir, item.href)
            zip.read(path)?.let { it to item.mediaType.ifEmpty { guessMime(item.href) } }
        }

        return Book(
            title = opf.title,
            author = opf.author,
            chapters = chapters,
            coverImage = cover?.first,
            coverMimeType = cover?.second,
        )
    }

    private data class TocResult(
        val entries: List<TocEntry>,
        val tocDir: String,
        val bodyMatterAbsPath: String?,
    )

    private data class RawChapter(
        val title: String?,
        val spineIndex: Int,
        val spineAbsPath: String,
        val chunks: List<String>,
    )

    private fun readToc(zip: ZipReader, opf: OpfDocument, opfDir: String): TocResult {
        opf.navItem()?.let { item ->
            val path = Paths.resolve(opfDir, item.href)
            zip.readText(path)?.let { xml ->
                val tocDir = Paths.parentDir(path)
                val bmHref = Toc.parseBodyMatterHref(xml)
                val bmAbs = bmHref?.let { Paths.resolve(tocDir, Paths.splitFragment(decode(it)).first) }
                return TocResult(Toc.parseNavXhtml(xml), tocDir, bmAbs)
            }
        }
        opf.ncxItem()?.let { item ->
            val path = Paths.resolve(opfDir, item.href)
            zip.readText(path)?.let {
                return TocResult(Toc.parseNcx(it), Paths.parentDir(path), null)
            }
        }
        return TocResult(emptyList(), opfDir, null)
    }

    /** href (relative to opfDir) -> ordered list of (fragment, title) pairs pointing into that file. */
    private fun buildSpineToTocMap(
        tocEntries: List<TocEntry>,
        tocDir: String,
        opf: OpfDocument,
        opfDir: String,
    ): Map<String, MutableList<Pair<String?, String>>> {
        val spineHrefs = opf.spine.mapNotNull { opf.items[it]?.href }.toSet()
        val result = LinkedHashMap<String, MutableList<Pair<String?, String>>>()
        for (entry in tocEntries) {
            val (hrefNoFrag, fragment) = Paths.splitFragment(decode(entry.href))
            val absolute = Paths.resolve(tocDir, hrefNoFrag)
            val match = spineHrefs.firstOrNull { Paths.resolve(opfDir, it) == absolute } ?: continue
            result.getOrPut(match) { mutableListOf() }.add(fragment to entry.title)
        }
        return result
    }

    private fun chaptersForFile(
        spineHref: String,
        spineAbsPath: String,
        spineIndex: Int,
        xhtml: String,
        tocByFragment: List<Pair<String?, String>>,
        locale: Locale,
    ): List<RawChapter> {
        val fragmentsOnly = tocByFragment.filter { it.first != null }
        val noFragTitle = tocByFragment.firstOrNull { it.first == null }?.second

        if (fragmentsOnly.isEmpty()) {
            val chunks = paragraphsToChunks(HtmlToText.extractParagraphs(xhtml), locale)
            if (chunks.isEmpty()) return emptyList()
            return listOf(RawChapter(noFragTitle, spineIndex, spineAbsPath, chunks))
        }

        return splitByFragments(
            xhtml = xhtml,
            preFragmentTitle = noFragTitle,
            fragments = fragmentsOnly.associate { it.first!! to it.second },
            spineIndex = spineIndex,
            spineAbsPath = spineAbsPath,
            locale = locale,
        )
    }

    private fun splitByFragments(
        xhtml: String,
        preFragmentTitle: String?,
        fragments: Map<String, String>,
        spineIndex: Int,
        spineAbsPath: String,
        locale: Locale,
    ): List<RawChapter> {
        val doc = Jsoup.parse(xhtml)
        val body = doc.body() ?: return emptyList()
        val result = mutableListOf<RawChapter>()
        var currentTitle: String? = preFragmentTitle
        var accumulator: Element = body.shallowClone()

        fun flushAccumulator() {
            val chunks = paragraphsToChunks(HtmlToText.extractParagraphs(accumulator.outerHtml()), locale)
            if (chunks.isNotEmpty()) {
                result.add(RawChapter(currentTitle, spineIndex, spineAbsPath, chunks))
            }
            accumulator = body.shallowClone()
        }

        for (child in body.children()) {
            val hit = findFragmentInOrUnder(child, fragments.keys)
            if (hit != null) {
                flushAccumulator()
                currentTitle = fragments[hit] ?: currentTitle
            }
            accumulator.appendChild(child.clone())
        }
        flushAccumulator()
        return result
    }

    private fun findFragmentInOrUnder(el: Element, fragments: Set<String>): String? {
        if (el.id() in fragments) return el.id()
        for (descendant in el.allElements) {
            if (descendant.id() in fragments) return descendant.id()
        }
        return null
    }

    private fun applyBodyMatterAndHeuristics(
        chapters: List<RawChapter>,
        bodyMatterAbsPath: String?,
    ): List<RawChapter> {
        val afterBody: List<RawChapter> = if (bodyMatterAbsPath != null) {
            val startIndex = chapters.indexOfFirst { it.spineAbsPath == bodyMatterAbsPath }
            if (startIndex >= 0) chapters.subList(startIndex, chapters.size) else chapters
        } else {
            chapters
        }
        // Always drop TOC-shaped chapters too — even after a landmarks cut, an embedded
        // TOC can still sneak in (and most Gutenberg-style books have no landmarks at all).
        return afterBody.filterNot { looksLikeToc(it) }
    }

    private fun looksLikeToc(chapter: RawChapter): Boolean {
        if (chapter.chunks.isEmpty()) return false
        val t = chapter.title?.trim()?.lowercase().orEmpty()
        if (t == "contents" || t == "table of contents" || t == "toc") return true
        if (chapter.chunks.size < 3) return false
        val shortRatio = chapter.chunks.count { it.length < 40 }.toDouble() / chapter.chunks.size
        val chapterMarker = Regex("(?i)\\bchapter\\s+[\\dIVXLCivxlc]+\\b")
        val hasMarkers = chapter.chunks.count { chapterMarker.containsMatchIn(it) } >= 2
        return shortRatio >= 0.7 && hasMarkers
    }

    private fun finalizeChapters(raw: List<RawChapter>): List<Chapter> {
        return raw.mapIndexed { idx, c ->
            val title = c.title?.takeIf { it.isNotBlank() } ?: "Chapter ${idx + 1}"
            Chapter(title = title, chunks = c.chunks)
        }
    }

    private fun paragraphsToChunks(paragraphs: List<String>, locale: Locale): List<String> =
        paragraphs.flatMap { Sentences.split(it, locale) }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }

    private fun guessMime(href: String): String = when (href.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }
}
