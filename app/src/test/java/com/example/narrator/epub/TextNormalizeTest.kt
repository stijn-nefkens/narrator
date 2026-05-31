package com.example.narrator.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizeTest {

    // --- restoreSentenceSpaces -------------------------------------------

    @Test fun `inserts space after period between glued sentences`() {
        assertEquals("scale. Quite", TextNormalize.restoreSentenceSpaces("scale.Quite"))
        assertEquals("abroad. As", TextNormalize.restoreSentenceSpaces("abroad.As"))
    }

    @Test fun `splits sentences glued after a digit`() {
        assertEquals(
            "It was 1990. The next year came.",
            TextNormalize.restoreSentenceSpaces("It was 1990.The next year came."),
        )
    }

    @Test fun `keeps a closing quote on the left of the inserted space`() {
        assertEquals(
            "he said.\" Then she left.",
            TextNormalize.restoreSentenceSpaces("he said.\"Then she left."),
        )
    }

    @Test fun `does not break acronyms or all-caps runs`() {
        assertEquals("The U.S. economy", TextNormalize.restoreSentenceSpaces("The U.S. economy"))
        assertEquals("U.S.A. today", TextNormalize.restoreSentenceSpaces("U.S.A. today"))
    }

    // --- stripGroupingCommas ---------------------------------------------

    @Test fun `strips grouping commas from large numbers`() {
        assertEquals("200000", TextNormalize.stripGroupingCommas("200,000"))
        assertEquals("1234567", TextNormalize.stripGroupingCommas("1,234,567"))
        assertEquals("about 12345 people", TextNormalize.stripGroupingCommas("about 12,345 people"))
    }

    @Test fun `leaves list commas alone`() {
        assertEquals("eggs, milk, bread", TextNormalize.stripGroupingCommas("eggs, milk, bread"))
        assertEquals("1, 2, 3", TextNormalize.stripGroupingCommas("1, 2, 3"))
    }

    @Test fun `leaves european-style decimals alone`() {
        // Two-digit fractional part is not a thousands group, so it is preserved.
        assertEquals("3,14", TextNormalize.stripGroupingCommas("3,14"))
    }

    // --- splitGluedWords -------------------------------------------------

    @Test fun `splits words glued at a camelCase boundary`() {
        assertEquals("happening Then", TextNormalize.splitGluedWords("happeningThen"))
        assertEquals("the day The next", TextNormalize.splitGluedWords("the dayThe next"))
    }

    @Test fun `does not split single-lowercase brand or name forms`() {
        // Only one lowercase letter before the internal capital — left intact.
        assertEquals("iPhone", TextNormalize.splitGluedWords("iPhone"))
        assertEquals("eBook", TextNormalize.splitGluedWords("eBook"))
        assertEquals("McDonald", TextNormalize.splitGluedWords("McDonald"))
        assertEquals("DeForest", TextNormalize.splitGluedWords("DeForest"))
        assertEquals("LaSalle", TextNormalize.splitGluedWords("LaSalle"))
    }

    @Test fun `does not split the Mac name prefix`() {
        assertEquals("MacArthur", TextNormalize.splitGluedWords("MacArthur"))
        assertEquals("General MacArthur won", TextNormalize.splitGluedWords("General MacArthur won"))
    }

    @Test fun `does not break acronym runs`() {
        // No uppercase-then-lowercase second word, and no two lowercase before the boundary.
        assertEquals("USData", TextNormalize.splitGluedWords("USData"))
    }

    @Test fun `normalize chains number, dot-glue and glued-word fixes`() {
        assertEquals(
            "We raised 200000. Then morning Came.",
            TextNormalize.normalize("We raised 200,000.Then morningCame."),
        )
    }
}
