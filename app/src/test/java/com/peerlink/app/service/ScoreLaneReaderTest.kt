package com.peerlink.app.service

import org.junit.Assert.*
import org.junit.Test

class ScoreLaneReaderTest {
    private fun token(text: String, x: Float, y: Float, width: Float = 24f, height: Float = 28f) =
        ScoreLaneReader.Token(text, ScoreLaneReader.Box(x, y, x + width, y + height))
    private fun read(vararg tokens: ScoreLaneReader.Token) = ScoreLaneReader.read(tokens.toList(), 400, 130, 5, 500)
    @Test fun largeScoreDoesNotRequireSmallWords() {
        val score = read(token("2-2", 140f, 30f, 120f))!!
        assertEquals(2, score.home); assertEquals(2, score.away)
    }
    @Test fun compactDigitTokenCanRepresentBothScoreSides() {
        val score = read(token("01", 140f, 30f, 120f))!!
        assertEquals(0, score.home)
        assertEquals(1, score.away)
        val twoTwo = read(token("22", 140f, 30f, 120f))!!
        assertEquals(2, twoTwo.home)
        assertEquals(2, twoTwo.away)
    }

    @Test fun narrowSCanBeTheOcrVersionOfOne() {
        val score = read(token("0", 122f, 30f), token("S", 254f, 30f, width = 12f, height = 28f))!!
        assertEquals(0, score.home)
        assertEquals(1, score.away)
    }

    @Test fun splitDigitsAndOcrGlyphsWorkInBothLanes() {
        for (y in listOf(30f, 155f)) {
            val score = read(token("O", 122f, y), token("I", 254f, y))!!
            assertEquals(0, score.home); assertEquals(1, score.away)
        }
    }
    @Test fun pairGlyphCorrectionIsRestrictedToScoreSeparators() {
        val score = read(token("O – I", 140f, 30f, 120f))!!
        assertEquals(0, score.home); assertEquals(1, score.away)
        for (text in listOf("12:15", "2.2", "2/2", "2 v 2", "2 points 2", "21-0")) {
            assertNull(text, read(token(text, 140f, 30f, 120f)))
        }
    }
    @Test fun splitClockDigitsAreNotCombinedIntoAScore() {
        assertNull(read(token("12", 122f, 30f), token(":", 192f, 30f), token("15", 254f, 30f)))
        assertNull(read(token("12:15", 122f, 30f, 156f), token("12", 122f, 30f), token("15", 254f, 30f)))
    }
    @Test fun signsWordsAndOutOfRangeValuesAreRejected() {
        for (text in listOf("-1", "+1", "1%", "21", "100", "WIN", "", "1 0")) {
            assertNull(text, ScoreLaneReader.normalizeToken(text))
        }
        assertEquals(20, ScoreLaneReader.normalizeToken("20"))
        assertEquals(10, ScoreLaneReader.normalizeToken("IO"))
    }
    @Test fun statisticsOutsideScoreColumnsAreRejected() {
        assertNull(read(token("5", 5f, 30f), token("8", 371f, 30f)))
        assertNull(read(token("5", 125f, 30f)))
    }
    @Test fun unrelatedRowsAndCompositeJoinAreRejected() {
        assertNull(read(token("1", 125f, 20f), token("2", 255f, 80f)))
        assertNull(read(token("2-2", 140f, 120f, 120f)))
    }
    @Test fun largerUnalignedNumeralDoesNotHideTheAlignedScore() {
        val score = read(token("3", 122f, 20f), token("1", 254f, 20f), token("8", 122f, 70f, height = 55f))!!
        assertEquals(3, score.home); assertEquals(1, score.away)
    }
    @Test fun conflictingEquallyPlausibleScoresAreNotGuessed() {
        assertNull(read(token("2-2", 140f, 20f, 120f), token("3-1", 140f, 80f, 120f)))
    }
    @Test fun duplicateLineAndElementReadingsAreHarmless() {
        val item = token("2-2", 140f, 30f, 120f)
        assertEquals(2, read(item, item)?.home)
    }
    @Test fun scoreAndFinalityAreDifferentEvidence() {
        assertNotNull(read(token("2-2", 140f, 30f, 120f)))
        assertFalse(FinalScoreEvidence.isFinal("2-2 Online Match"))
        assertTrue(FinalScoreEvidence.isNonFinal("HALF-TIME 2-2"))
        assertTrue(FinalScoreEvidence.isNonFinal("Resume Match"))
        assertTrue(FinalScoreEvidence.isFinal("FULL TIME 2-2"))
    }
}
