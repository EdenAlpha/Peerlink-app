package com.peerlink.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the public, fail-closed reader API.
 *
 * The prior F15 fixture targeted removed score-attribution fields and could
 * not compile against the current F26 reader. These tests cover the stable
 * safety guarantees: malformed telemetry is ignored, and an unverified
 * session never becomes settleable.
 */
class MatchProtocolReaderTest {
    private fun reader() = MatchProtocolReader.Reader(
        startedAtMs = 0L,
        baselineOutPackets = 0L,
        baselineInPackets = 0L,
        baselineTelemetryDrops = 0L,
        baselineParseFailures = 0L,
    )

    @Test
    fun emptySessionIsFailClosed() {
        val result = reader().segmentResult(
            endedBy = "test",
            completed = true,
            endedAtMs = MatchProtocolReader.MIN_SETTLEMENT_DURATION_MS,
        )

        assertFalse(result.gated)
        assertFalse(result.completed)
        assertFalse(result.settleable)
        assertTrue(result.events.isEmpty())
        assertTrue(result.settlementBlockers.isNotEmpty())
    }

    @Test
    fun malformedPacketIsIgnoredWithoutOpeningTheGate() {
        val reader = reader()
        val boundary = reader.onPacket(
            tMs = 100L,
            sentByMe = true,
            payloadLen = 7,
            head = ByteArray(7),
            headLen = 7,
        )

        assertNull(boundary)
        assertFalse(reader.gated)
        assertTrue(reader.goalEvents().isEmpty())
    }
}
