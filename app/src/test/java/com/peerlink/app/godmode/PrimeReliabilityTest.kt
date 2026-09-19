package com.peerlink.app.godmode

import com.peerlink.app.service.FinalScoreEvidence
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

class PrimeReliabilityTest {
    @Test fun shellFinishesAtStatusEvenIfRemoteNeverClosesPipe() {
        val source = object : StringReader("started\n__exit__0\n") {
            override fun read(): Int {
                val ch = super.read()
                check(ch != -1) { "Reader waited beyond command completion" }
                return ch
            }
        }
        assertEquals("started", PrimeAdbShellReader.read(source, "__exit__"))
    }
    @Test fun shellRejectsNonzeroMissingStatusAndOversizedOutput() {
        for (output in listOf("denied\n__exit__1\n", "no status", "x".repeat(200))) {
            try {
                PrimeAdbShellReader.read(StringReader(output), "__exit__", 100)
                fail("Expected shell failure")
            } catch (_: IOException) { }
        }
    }
    @Test fun stalledAdbReadCanBeCancelled() = runBlocking {
        val reader = object : java.io.Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                Thread.sleep(10_000)
                return -1
            }
            override fun close() = Unit
        }
        val start = System.nanoTime()
        val result = withTimeoutOrNull(100L) {
            runInterruptible(Dispatchers.IO) { PrimeAdbShellReader.read(reader, "__exit__") }
        }
        assertNull(result)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000)
    }
    @Test fun processDeadlineStillAppliesAfterStdoutCloses() {
        val start = System.nanoTime()
        try {
            PrimeShellRunner.run(listOf("sh", "-c", "exec 1>&- 2>&-; sleep 4"), 150L, 1024)
            fail("Expected process timeout")
        } catch (_: IOException) { }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000)
    }
    @Test fun shellCollectsOutputAndRejectsExcess() {
        assertEquals("ready", PrimeShellRunner.run(listOf("sh", "-c", "printf ready"), 2_000, 100))
        try {
            PrimeShellRunner.run(listOf("sh", "-c", "printf 1234567890"), 2_000, 4)
            fail("Expected output limit")
        } catch (_: Exception) { }
    }
    @Test fun gameModeUsesAvailableModesRatherThanHelpOrCurrentMode() {
        assertTrue(PrimePerformanceModeParser.hasPerformanceMode("jp.konami.pesam current mode: 1, available game modes: [1,2,3]"))
        assertEquals(1, PrimePerformanceModeParser.currentMode("current mode: 1, available game modes: [1,2,3]"))
        assertTrue(PrimePerformanceModeParser.hasPerformanceMode("Available modes: [standard, performance]"))
        assertFalse(PrimePerformanceModeParser.hasPerformanceMode("Performance mode not supported"))
        assertFalse(PrimePerformanceModeParser.hasPerformanceMode("current mode: 2, available game modes: [1,3]"))
    }
    @Test fun unknownForegroundDoesNotBecomeAGuiltyVerdict() {
        assertNull(PrimeForegroundParser.isForeground("", "jp.konami.pesam"))
        assertNull(PrimeForegroundParser.isForeground("Permission denied", "jp.konami.pesam"))
        assertEquals(true, PrimeForegroundParser.isForeground("mTopResumedActivity=ActivityRecord{123 u0 jp.konami.pesam/.GameActivity t1}", "jp.konami.pesam"))
        assertEquals(false, PrimeForegroundParser.isForeground("mResumedActivity=ActivityRecord{123 u0 jp.konami.pesam.fake/.Main t1}", "jp.konami.pesam"))
        assertEquals(
            listOf("com.peerlink.app"),
            PrimeForegroundParser.resumedPackages("mResumedActivity=ActivityRecord{123 u0 com.peerlink.app/.ui.MainActivity t1}"),
        )
    }
    @Test fun nonFinalScreensCannotFinalizeScore() {
        for (text in listOf("Online Match 0-0", "eFootball Stadium 2-1", "Half Time 1-0", "90:00 2-1")) {
            assertFalse(text, FinalScoreEvidence.isFinal(text))
        }
        for (text in listOf("Full Time", "FULL-TIME", "Fulltime", "Match Ended", "Final Result")) {
            assertTrue(text, FinalScoreEvidence.isFinal(text))
        }
    }
}
