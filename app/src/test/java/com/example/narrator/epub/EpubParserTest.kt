package com.example.narrator.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class EpubParserTest {

    @Test
    fun `parses title author chapters and cover from a minimal EPUB3`() {
        val bytes = simpleEpub3()
        val book = EpubParser.parse(ByteArrayInputStream(bytes))

        assertEquals("A Tale", book.title)
        assertEquals("Jane Doe", book.author)
        assertEquals(2, book.chapters.size)

        val ch1 = book.chapters[0]
        assertEquals("Chapter One", ch1.title)
        assertEquals(2, ch1.chunks.size)
        assertTrue(ch1.chunks[0].startsWith("It was the best of times"))
        assertTrue(ch1.chunks[1].startsWith("Hello world"))

        val ch2 = book.chapters[1]
        assertEquals("Chapter Two", ch2.title)
        assertTrue(ch2.chunks.any { it.contains("second chapter") })

        assertNotNull(book.coverImage)
        assertEquals("image/png", book.coverMimeType)
    }

    @Test
    fun `strips page-number spans and footnote markers`() {
        val bytes = EpubBuilder()
            .add("META-INF/container.xml", containerXml("OEBPS/content.opf"))
            .add(
                "OEBPS/content.opf",
                packageXml(
                    title = "Cleaned",
                    creator = "Author",
                    manifest = """
                        <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                    """.trimIndent(),
                    spine = """<itemref idref="c1"/>""",
                ),
            )
            .add(
                "OEBPS/c1.xhtml",
                xhtml(
                    """
                    <p>First sentence. <span class="pagenum">12</span> Second sentence.</p>
                    <p>Read on<sup class="footnote">1</sup> please.</p>
                    <span role="doc-pagebreak">13</span>
                    <p>Final line.</p>
                    """.trimIndent(),
                ),
            )
            .build()

        val book = EpubParser.parse(ByteArrayInputStream(bytes))
        val chunks = book.chapters.flatMap { it.chunks }.joinToString(" ")
        assertTrue("page number stripped", !chunks.contains("12"))
        assertTrue("footnote marker stripped", !chunks.contains("Read on1"))
        assertTrue(chunks.contains("First sentence"))
        assertTrue(chunks.contains("Final line"))
    }

    @Test
    fun `falls back to Unknown for missing metadata`() {
        val bytes = EpubBuilder()
            .add("META-INF/container.xml", containerXml("OEBPS/content.opf"))
            .add(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
                   <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                     <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"></metadata>
                     <manifest>
                       <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                     </manifest>
                     <spine><itemref idref="c1"/></spine>
                   </package>""".trimIndent(),
            )
            .add("OEBPS/c1.xhtml", xhtml("<p>Hello.</p>"))
            .build()

        val book = EpubParser.parse(ByteArrayInputStream(bytes))
        assertEquals("Unknown title", book.title)
        assertEquals("Unknown author", book.author)
    }

    @Test
    fun `splits a single spine file into chapters by TOC fragments`() {
        val nav = """<?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body>
                <nav epub:type="toc">
                  <ol>
                    <li><a href="all.xhtml#one">First Part</a></li>
                    <li><a href="all.xhtml#two">Second Part</a></li>
                  </ol>
                </nav>
              </body>
            </html>""".trimIndent()

        val content = xhtml(
            """
            <section id="one"><p>Opening of part one.</p></section>
            <section id="two"><p>Opening of part two.</p></section>
            """.trimIndent(),
        )

        val bytes = EpubBuilder()
            .add("META-INF/container.xml", containerXml("OEBPS/content.opf"))
            .add(
                "OEBPS/content.opf",
                packageXml(
                    title = "Split Test",
                    creator = "Author",
                    manifest = """
                        <item id="all" href="all.xhtml" media-type="application/xhtml+xml"/>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    """.trimIndent(),
                    spine = """<itemref idref="all"/>""",
                ),
            )
            .add("OEBPS/nav.xhtml", nav)
            .add("OEBPS/all.xhtml", content)
            .build()

        val book = EpubParser.parse(ByteArrayInputStream(bytes))
        assertEquals(2, book.chapters.size)
        assertEquals("First Part", book.chapters[0].title)
        assertEquals("Second Part", book.chapters[1].title)
        assertTrue(book.chapters[0].chunks.first().contains("part one"))
        assertTrue(book.chapters[1].chunks.first().contains("part two"))
    }

    @Test
    fun `reads EPUB2 NCX TOC and cover meta`() {
        val ncx = """<?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint><navLabel><text>Intro</text></navLabel><content src="ch1.xhtml"/></navPoint>
                <navPoint><navLabel><text>Outro</text></navLabel><content src="ch2.xhtml"/></navPoint>
              </navMap>
            </ncx>""".trimIndent()

        val bytes = EpubBuilder()
            .add("META-INF/container.xml", containerXml("OEBPS/content.opf"))
            .add(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
                   <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                     <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                       <dc:title>Old Book</dc:title>
                       <dc:creator>Author</dc:creator>
                       <meta name="cover" content="cover-id"/>
                     </metadata>
                     <manifest>
                       <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                       <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                       <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                       <item id="cover-id" href="cover.png" media-type="image/png"/>
                     </manifest>
                     <spine toc="ncx">
                       <itemref idref="ch1"/>
                       <itemref idref="ch2"/>
                     </spine>
                   </package>""".trimIndent(),
            )
            .add("OEBPS/toc.ncx", ncx)
            .add("OEBPS/ch1.xhtml", xhtml("<p>Intro line.</p>"))
            .add("OEBPS/ch2.xhtml", xhtml("<p>Outro line.</p>"))
            .add("OEBPS/cover.png", fakePng())
            .build()

        val book = EpubParser.parse(ByteArrayInputStream(bytes))
        assertEquals("Old Book", book.title)
        assertEquals(2, book.chapters.size)
        assertEquals("Intro", book.chapters[0].title)
        assertEquals("Outro", book.chapters[1].title)
        assertNotNull(book.coverImage)
        assertEquals("image/png", book.coverMimeType)
    }

    // --- fixture helpers --------------------------------------------------

    private fun simpleEpub3(): ByteArray {
        val nav = """<?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body>
                <nav epub:type="toc">
                  <ol>
                    <li><a href="ch1.xhtml">Chapter One</a></li>
                    <li><a href="ch2.xhtml">Chapter Two</a></li>
                  </ol>
                </nav>
              </body>
            </html>""".trimIndent()

        return EpubBuilder()
            .add("META-INF/container.xml", containerXml("OEBPS/content.opf"))
            .add(
                "OEBPS/content.opf",
                packageXml(
                    title = "A Tale",
                    creator = "Jane Doe",
                    manifest = """
                        <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="cover-img" href="cover.png" media-type="image/png" properties="cover-image"/>
                    """.trimIndent(),
                    spine = """
                        <itemref idref="ch1"/>
                        <itemref idref="ch2"/>
                    """.trimIndent(),
                ),
            )
            .add("OEBPS/nav.xhtml", nav)
            .add(
                "OEBPS/ch1.xhtml",
                xhtml("<p>It was the best of times, it was the worst of times.</p><p>Hello world.</p>"),
            )
            .add("OEBPS/ch2.xhtml", xhtml("<p>This is the second chapter.</p>"))
            .add("OEBPS/cover.png", fakePng())
            .build()
    }

    private fun containerXml(opfPath: String) = """<?xml version="1.0"?>
        <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>""".trimIndent()

    private fun packageXml(
        title: String,
        creator: String,
        manifest: String,
        spine: String,
    ) = """<?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>$title</dc:title>
            <dc:creator>$creator</dc:creator>
          </metadata>
          <manifest>
            $manifest
          </manifest>
          <spine>
            $spine
          </spine>
        </package>""".trimIndent()

    private fun xhtml(body: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml">
          <head><title>Doc</title></head>
          <body>
            $body
          </body>
        </html>""".trimIndent()
}
