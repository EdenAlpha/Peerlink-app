package com.peerlink.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SideSuggestionTest {

    private val t0 = 1_760_000_000_000L

    @Test fun noTimestampsMeansNoSuggestion() {
        assertNull(SideSuggestion.compute(0L, 0L, swapped = false))
        assertNull(SideSuggestion.compute(t0, 0L, swapped = false))
        assertNull(SideSuggestion.compute(0L, t0, swapped = false))
    }

    @Test fun earlierCallerSuggestsHome() {
        // Local called 2 minutes before the peer (the recorded evening case):
        // local created the room -> Home.
        assertEquals(
            MatchControlChannel.Side.HOME,
            SideSuggestion.compute(t0, t0 + 127_000L, swapped = false),
        )
        // Peer called 5 minutes first (the recorded morning case): local joined -> Away.
        assertEquals(
            MatchControlChannel.Side.AWAY,
            SideSuggestion.compute(t0 + 322_000L, t0, swapped = false),
        )
    }

    @Test fun bothPhonesComputeTheSameAnswer() {
        // Phone A local=earlier, phone B local=later, same two timestamps:
        // complementary sides, never a conflict.
        val a = SideSuggestion.compute(t0, t0 + 60_000L, swapped = false)
        val b = SideSuggestion.compute(t0 + 60_000L, t0, swapped = false)
        assertEquals(MatchControlChannel.Side.HOME, a)
        assertEquals(MatchControlChannel.Side.AWAY, b)
        assertEquals(a!!.opposite(), b)
    }

    @Test fun tinyGapIsClockSkewAndStaysSilent() {
        // 10 seconds apart: could be skew or a simultaneous start — no guess.
        assertNull(SideSuggestion.compute(t0, t0 + 10_000L, swapped = false))
        // One millisecond below the threshold stays silent...
        assertNull(
            SideSuggestion.compute(t0, t0 + SideSuggestion.MIN_GAP_MS - 1L, swapped = false),
        )
        // ...the threshold itself is the first gap that counts.
        assertEquals(
            MatchControlChannel.Side.HOME,
            SideSuggestion.compute(t0, t0 + SideSuggestion.MIN_GAP_MS, swapped = false),
        )
    }

    @Test fun hugeGapIsStaleAndStaysSilent() {
        assertNull(
            SideSuggestion.compute(t0, t0 + SideSuggestion.MAX_GAP_MS + 1L, swapped = false),
        )
    }

    @Test fun holdFlipsExactlyOnce() {
        val original = SideSuggestion.compute(t0, t0 + 120_000L, swapped = false)
        val flipped = SideSuggestion.compute(t0, t0 + 120_000L, swapped = true)
        assertEquals(MatchControlChannel.Side.HOME, original)
        assertEquals(MatchControlChannel.Side.AWAY, flipped)
        assertEquals(original!!.opposite(), flipped)
    }

    @Test fun swappedKeepsBothPhonesComplementary() {
        // Both phones flip (either or both players held): still complementary.
        val a = SideSuggestion.compute(t0, t0 + 60_000L, swapped = true)
        val b = SideSuggestion.compute(t0 + 60_000L, t0, swapped = true)
        assertEquals(a!!.opposite(), b)
    }
}
