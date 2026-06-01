package com.example.narrator.tts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure tests for FilePipeline.stripTrailingNoise — the set of trailing characters sherpa-onnx
 * renders as an audible click ("ktsh"). The sentence terminator must survive; only the trailing
 * noise is removed. Regression net for the recurring "weird sound at the end with X" reports
 * (quotes in 0.x, then brackets ".)" / ".]" here).
 */
class TrailingNoiseTest {

    @Test fun `strips trailing closing brackets but keeps the terminator`() {
        assertEquals("(an aside).", FilePipeline.stripTrailingNoise("(an aside).)"))
        assertEquals("a list item.", FilePipeline.stripTrailingNoise("a list item.]"))
        assertEquals("a block.", FilePipeline.stripTrailingNoise("a block.}"))
    }

    @Test fun `strips trailing quotes (straight and curly)`() {
        assertEquals("she said.", FilePipeline.stripTrailingNoise("she said.\""))
        assertEquals("he replied.", FilePipeline.stripTrailingNoise("he replied.”"))
        assertEquals("oui.", FilePipeline.stripTrailingNoise("oui.»"))
    }

    @Test fun `strips a run of mixed trailing noise`() {
        // Nested close: end of a quoted parenthetical, e.g. ...(“done.”) plus space.
        assertEquals("done.", FilePipeline.stripTrailingNoise("done.”) "))
    }

    @Test fun `leaves brackets and quotes that are mid-text alone`() {
        // Only TRAILING chars are touched; internal punctuation is preserved.
        assertEquals(
            "something (an aside) and more.",
            FilePipeline.stripTrailingNoise("something (an aside) and more."),
        )
        assertEquals(
            "he said \"hi\" to me.",
            FilePipeline.stripTrailingNoise("he said \"hi\" to me."),
        )
    }

    @Test fun `collapses a bracket sandwiched between two terminators`() {
        // The [.).] family: a parenthetical that itself ends in a terminator, then the sentence
        // terminator — the ")" sits between two periods and clicks. trimEnd can't reach it because
        // the string still ends in a real terminator. Keep one terminator, drop the bracket.
        assertEquals("(He left.", FilePipeline.stripTrailingNoise("(He left.)."))
        assertEquals("the end.", FilePipeline.stripTrailingNoise("the end.).]"))
        assertEquals("(Really?", FilePipeline.stripTrailingNoise("(Really?)."))
        assertEquals("done.", FilePipeline.stripTrailingNoise("done.”)."))
    }

    @Test fun `keeps a normal closing bracket before the terminator`() {
        // Here the ")" closes ordinary content (preceded by a letter, not a terminator), so it's
        // NOT the sandwiched case — leave it intact, just like mid-text brackets.
        assertEquals("(world).", FilePipeline.stripTrailingNoise("(world)."))
        assertEquals("see (above).", FilePipeline.stripTrailingNoise("see (above)."))
    }

    @Test fun `every declared noise char is stripped when trailing`() {
        for (c in FilePipeline.TRAILING_NOISE_CHARS) {
            val s = "word$c"
            assertEquals("char U+%04X should be stripped".format(c.code), "word", FilePipeline.stripTrailingNoise(s))
        }
    }
}
