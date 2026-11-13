package com.aharou.feature.workspace.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SizeLimitedInputStreamTest {
    @Test fun exactLimitReadsFullContentAndEof() {
        val source = byteArrayOf(1, 2, 3, 4)
        SizeLimitedInputStream(source.inputStream(), 4).use { input ->
            assertArrayEquals(source, input.readBytes())
            assertEquals(-1, input.read())
        }
    }

    @Test fun bulkReadBeyondLimitThrowsInsteadOfReturningPrefix() {
        SizeLimitedInputStream(byteArrayOf(1, 2, 3, 4, 5).inputStream(), 4).use { input ->
            val error = assertThrows(FileSizeLimitExceededException::class.java) { input.read(ByteArray(8)) }
            assertEquals(4L, error.maxBytes)
            assertEquals(5L, error.sizeBytes)
        }
    }

    @Test fun singleByteReadUsesSameLimit() {
        SizeLimitedInputStream(byteArrayOf(1, 2).inputStream(), 1).use { input ->
            assertEquals(1, input.read())
            assertThrows(FileSizeLimitExceededException::class.java) { input.read() }
        }
    }

    @Test fun emptySourceAtZeroLimitIsValid() {
        SizeLimitedInputStream(byteArrayOf().inputStream(), 0).use { input ->
            assertEquals(-1, input.read())
        }
    }
}
