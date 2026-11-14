package com.aharou.feature.workspace.domain

import com.aharou.core.util.LINE_TRUNCATED_NOTE
import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileReadSnapshotTest {
    private class TrackedStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closes = 0
        override fun close() { closes++; super.close() }
    }

    @Test
    fun emptyAndMixedLineEndingsKeepTheirRawByteFingerprint() {
        for (text in listOf("", "one\r\ntwo\rthree\n", "中文\r\n𠮷")) {
            val bytes = text.toByteArray()
            val stream = TrackedStream(bytes)
            assertEquals(sha256Fingerprint(bytes), readLinesAndFingerprint(stream) { true })
            assertEquals(1, stream.closes)
        }
    }

    @Test
    fun oversizedLinesAreClippedButAllBytesStillContributeToTheFingerprint() {
        val text = "a".repeat(70_000) + "\r\nend"
        val stream = TrackedStream(text.toByteArray())
        val lines = mutableListOf<String>()
        assertEquals(sha256Fingerprint(text.toByteArray()), readLinesAndFingerprint(stream) { lines += it; true })
        assertEquals(2, lines.size)
        assertTrue(lines.first().endsWith(LINE_TRUNCATED_NOTE))
        assertEquals("end", lines.last())
        assertEquals(1, stream.closes)
    }

    @Test
    fun earlyStopClosesTheStreamWithoutReturningACompleteFingerprint() {
        val stream = TrackedStream("first\nsecond".toByteArray())
        assertNull(readLinesAndFingerprint(stream) { false })
        assertEquals(1, stream.closes)
    }

    @Test
    fun cancellationClosesTheStreamAndPropagatesTheSameFailure() {
        val stream = TrackedStream("first\nsecond".toByteArray())
        val expected = CancellationException("fixture")
        val actual = assertThrows(CancellationException::class.java) {
            readLinesAndFingerprint(stream) { throw expected }
        }
        assertSame(expected, actual)
        assertEquals(1, stream.closes)
    }
}
