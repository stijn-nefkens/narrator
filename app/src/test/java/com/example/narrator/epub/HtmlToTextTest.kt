package com.example.narrator.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlToTextTest {

    @Test
    fun `extracts paragraphs in document order`() {
        val html = """
            <html><body>
              <h1>Title</h1>
              <p>First.</p>
              <p>Second.</p>
            </body></html>
        """.trimIndent()
        val out = HtmlToText.extractParagraphs(html)
        assertEquals(listOf("Title", "First.", "Second."), out)
    }

    @Test
    fun `drops scripts and styles`() {
        val html = """
            <html><body>
              <script>alert('hi')</script>
              <style>.x { color: red; }</style>
              <p>Real content.</p>
            </body></html>
        """.trimIndent()
        val out = HtmlToText.extractParagraphs(html)
        assertEquals(listOf("Real content."), out)
    }

    @Test
    fun `treats br as paragraph break`() {
        val html = "<html><body><p>One<br/>Two<br/>Three</p></body></html>"
        val out = HtmlToText.extractParagraphs(html)
        assertEquals(listOf("One", "Two", "Three"), out)
    }

    @Test
    fun `drops bare page-number paragraphs`() {
        val html = """
            <html><body>
              <p>Real text.</p>
              <p>17</p>
              <p>Page 18</p>
              <p>More text.</p>
            </body></html>
        """.trimIndent()
        val out = HtmlToText.extractParagraphs(html)
        assertTrue(out.contains("Real text."))
        assertTrue(out.contains("More text."))
        assertTrue("page number stripped", out.none { it == "17" || it == "Page 18" })
    }

    @Test
    fun `removes pagebreak elements`() {
        val html = """
            <html><body>
              <p>Before.</p>
              <span epub:type="pagebreak">23</span>
              <p>After.</p>
            </body></html>
        """.trimIndent()
        val out = HtmlToText.extractParagraphs(html)
        assertEquals(listOf("Before.", "After."), out)
    }
}
