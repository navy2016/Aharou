package com.aharou.feature.workspace.domain

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceShareFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val access = mockk<FileAccessProvider>()

    @Test fun singleFileUsesOriginalNameAndDoesNotModifySource() {
        val source = temporary.newFile("source").apply { writeText("shared content") }
        every { access.isDirectory("~/workspace/file.txt") } returns false
        every { access.copyToLocal("~/workspace/file.txt") } returns source
        val shared = prepareWorkspaceShare(access, "~/workspace/file.txt", temporary.newFolder("cache"))
        assertEquals("file.txt", shared.name)
        assertEquals("shared content", shared.readText())
        assertEquals("shared content", source.readText())
        assertTrue(shared.absolutePath != source.absolutePath)
    }

    @Test fun directoryZipKeepsNestedEmptyAndHiddenEntries() {
        val file = temporary.newFile("data").apply { writeText("payload") }
        every { access.isDirectory("~/workspace/project") } returns true
        every { access.listFiles("~/workspace/project") } returns listOf(
            FileEntry("nested", true, 0, 0), FileEntry("empty", true, 0, 0),
            FileEntry(".settings", false, file.length(), 0, localFile = file),
        )
        every { access.listFiles("~/workspace/project/nested") } returns listOf(FileEntry("文件.txt", false, file.length(), 0, localFile = file))
        every { access.listFiles("~/workspace/project/empty") } returns emptyList()
        every { access.isFile(any()) } returns true
        every { access.copyToLocal(any()) } returns file
        val shared = prepareWorkspaceShare(access, "~/workspace/project", temporary.newFolder("cache"))
        assertEquals("project.zip", shared.name)
        ZipFile(shared).use { zip ->
            assertEquals(setOf("project/", "project/nested/", "project/nested/文件.txt", "project/empty/", "project/.settings"), zip.entries().asSequence().map { it.name }.toSet())
            assertEquals("payload", zip.getInputStream(zip.getEntry("project/nested/文件.txt")).reader().readText())
        }
        assertTrue(file.isFile)
    }

    @Test fun symbolicLinksAndSpecialFilesAreNotTraversed() {
        every { access.isDirectory("folder") } returns true
        every { access.listFiles("folder") } returns listOf(
            FileEntry("loop", true, 0, 0, isSymbolicLink = true),
            FileEntry("outside", false, 0, 0, isSymbolicLink = true),
            FileEntry("pipe", false, 0, 0),
        )
        every { access.isFile("folder/pipe") } returns false
        val shared = prepareWorkspaceShare(access, "folder", temporary.newFolder("cache"))
        ZipFile(shared).use { assertEquals(listOf("folder/"), it.entries().asSequence().map { entry -> entry.name }.toList()) }
        verify(exactly = 0) { access.listFiles("folder/loop") }
        verify(exactly = 0) { access.copyToLocal(any()) }
    }

    @Test fun remoteCopyIsStreamedAndReleasedAfterZipEntry() {
        val copy = temporary.newFile("remote.copy").apply { writeText("r".repeat(9 * 1024 * 1024)) }
        every { access.isDirectory("folder") } returns true
        every { access.listFiles("folder") } returns listOf(FileEntry("large.txt", false, copy.length(), 0))
        every { access.isFile("folder/large.txt") } returns true
        every { access.copyToLocal("folder/large.txt") } returns copy
        val shared = prepareWorkspaceShare(access, "folder", temporary.newFolder("cache"))
        ZipFile(shared).use { assertEquals(9L * 1024 * 1024, it.getEntry("folder/large.txt").size) }
        assertFalse(copy.exists())
        verify(exactly = 0) { access.readBytes(any()) }
    }

    @Test fun failureDoesNotDeliverPartialArchive() {
        val cache = temporary.newFolder("cache")
        every { access.isDirectory("folder") } returns true
        every { access.listFiles("folder") } throws IOException("unreadable directory")
        assertThrows(IOException::class.java) { prepareWorkspaceShare(access, "folder", cache) }
        assertTrue(File(cache, "workspace_share").walkTopDown().none { it.isFile })
    }

    @Test fun invalidEntryCannotEscapeArchiveRoot() {
        every { access.isDirectory("folder") } returns true
        every { access.listFiles("folder") } returns listOf(FileEntry("../outside", false, 0, 0))
        assertThrows(IOException::class.java) { prepareWorkspaceShare(access, "folder", temporary.newFolder("cache")) }
        verify(exactly = 0) { access.copyToLocal(any()) }
    }
}
