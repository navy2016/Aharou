package com.aharou.feature.editor.presentation

import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.FileSizeLimitExceededException
import com.aharou.feature.workspace.domain.sha256Fingerprint
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EditorFileReaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val access = mockk<FileAccessProvider>()

    private fun bind(text: String): File {
        val file = temporary.newFile().apply { writeText(text) }
        every { access.copyToLocal(any()) } returns file
        every { access.copyToLocal(any(), any()) } answers {
            val limit = secondArg<Long>()
            if (file.length() > limit) throw FileSizeLimitExceededException(limit, file.length())
            file
        }
        return file
    }

    @Test fun largeFileIsReadInFullWithoutRemoteReadLimit() {
        val text = "x".repeat(9 * 1024 * 1024) + "\nend"
        bind(text)
        val result = readEditorFile(access, "~/workspace/large.txt")!!
        assertEquals(text, result.text)
        assertEquals(sha256Fingerprint(text.toByteArray()), result.fingerprint)
        verify(exactly = 0) { access.readBytes(any()) }
        verify(exactly = 0) { access.readFile(any()) }
        verify(exactly = 1) { access.copyToLocal("~/workspace/large.txt") }
        verify(exactly = 0) { access.copyToLocal(any(), any()) }
    }

    @Test fun unicodeAndCrLfArePreservedAndHashedOnce() {
        val text = "标题\r\n" + "数据".repeat(6000) + "\r\n末尾\r\n"
        bind(text)
        val result = readEditorFile(access, "~/workspace/unicode.txt")!!
        assertEquals(text, result.text)
        assertEquals(sha256Fingerprint(text.toByteArray()), result.fingerprint)
    }

    @Test fun emptyFileProducesValidContentAndFingerprint() {
        bind("")
        val result = readEditorFile(access, "~/workspace/empty.txt")!!
        assertEquals("", result.text)
        assertEquals(sha256Fingerprint(byteArrayOf()), result.fingerprint)
    }

    @Test fun binaryHeaderIsNotLoadedAsText() {
        val file = temporary.newFile().apply { writeBytes(byteArrayOf(65, 0, 66)) }
        every { access.copyToLocal(any()) } returns file
        assertNull(readEditorFile(access, "~/workspace/binary"))
    }

    @Test fun softLimitRejectsWholeDocumentInsteadOfReturningPrefix() {
        bind("entire text")
        assertThrows(FileSizeLimitExceededException::class.java) {
            readEditorFile(access, "~/workspace/file.txt", maxBytes = 5)
        }
    }

    @Test fun growthAfterCopyIsRejectedDuringReading() {
        val file = bind("grown file")
        every { access.copyToLocal(any(), any()) } returns file
        assertThrows(FileSizeLimitExceededException::class.java) {
            readEditorFile(access, "~/workspace/file.txt", maxBytes = 5)
        }
    }

    @Test fun callerCannotRaiseEditorHardLimit() {
        val file = bind("text")
        every { access.copyToLocal(any(), EDITOR_MAX_FILE_BYTES) } returns file
        assertEquals("text", readEditorFile(access, "~/workspace/file.txt", Long.MAX_VALUE)?.text)
        verify(exactly = 1) { access.copyToLocal("~/workspace/file.txt", EDITOR_MAX_FILE_BYTES) }
    }

    @Test fun saveConflictReadingUsesOrdinaryDownloadRegardlessOfOpeningLimit() {
        val file = spyk(temporary.newFile().apply { writeText("complete disk content") })
        every { file.length() } returns EDITOR_MAX_FILE_BYTES + 1
        every { access.copyToLocal("~/workspace/changed.txt") } returns file
        assertEquals("complete disk content", readEditorFile(access, "~/workspace/changed.txt")?.text)
        verify(exactly = 0) { access.copyToLocal(any(), any()) }
    }

    @Test fun readFailureIsNotConvertedToEmptyText() {
        every { access.copyToLocal(any()) } throws IOException("unreadable")
        assertThrows(IOException::class.java) { readEditorFile(access, "~/workspace/missing") }
    }
}
