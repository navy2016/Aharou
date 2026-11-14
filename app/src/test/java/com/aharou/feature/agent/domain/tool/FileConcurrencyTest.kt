package com.aharou.feature.agent.domain.tool

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.inbox.SharedInboxEvents
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.subagent.SubAgentWriteLease
import com.aharou.feature.agent.domain.tool.editor.EditFileTool
import com.aharou.feature.agent.domain.tool.file.ReadFileTool
import com.aharou.feature.agent.domain.tool.file.WriteFileTool
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.sha256Fingerprint
import com.aharou.feature.workspace.domain.readLinesAndFingerprint
import com.aharou.feature.agent.domain.notification.AgentNotificationCenter
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileConcurrencyTest {
    private val path = "~/workspace/file.kt"
    private val files = mockk<FileAccessProvider>(relaxed = true)
    private val notifications = AgentNotificationCenter()
    private val versions = FileVersionRegistry(notifications)
    private val locks = WorkspaceFileLock()
    private val inbox = mockk<SharedInboxEvents>(relaxed = true)
    private val leases = SubAgentWriteLease()
    private val reader = ReadFileTool(files, versions)
    private val writer = WriteFileTool(files, locks, versions, inbox, leases)
    private val editor = EditFileTool(files, locks, versions, inbox, leases)
    private val context = AgentContext(null, null, "/project", null, sessionId = "session")
    private var content = "base"

    @Before
    fun setUp() {
        every { files.forWorkspace("/project") } returns files
        every { files.forStrictRead() } returns files
        every { files.resourceKey(path) } returns path
        every { files.readLinesWithFingerprint(path, any()) } answers {
            readLinesAndFingerprint(content.byteInputStream(), secondArg())
        }
        every { files.toDisplayPath(path) } returns path
        every { files.exists(path) } returns true
        every { files.readLines(path) } answers { content.lineSequence() }
        every { files.readFile(path) } answers { content }
        every { files.fileSize(path) } answers { content.toByteArray().size.toLong() }
        every { files.fingerprint(path) } answers { sha256Fingerprint(content.toByteArray()) }
        every { files.writeFile(path, any(), any(), any()) } answers { content = arg(1) }
        mockkObject(FileLogger)
        every { FileLogger.d(any(), any()) } returns Unit
        every { FileLogger.v(any(), any()) } returns Unit
        every { FileLogger.w(any(), any(), any()) } returns Unit
        every { FileLogger.e(any(), any(), any()) } returns Unit
    }

    @After
    fun tearDown() { unmockkObject(FileLogger) }

    private fun readArgs() = mapOf("path" to JsonPrimitive(path))
    private fun writeArgs(value: String) = mapOf("path" to JsonPrimitive(path), "content" to JsonPrimitive(value))
    private fun editArgs(old: String, replacement: String) = mapOf(
        "path" to JsonPrimitive(path),
        "edits" to JsonArray(listOf(JsonObject(mapOf(
            "old_string" to JsonPrimitive(old), "new_string" to JsonPrimitive(replacement)
        ))))
    )

    // 文件工具使用真实 IO；让超时也使用真实时钟，避免虚拟时钟在 IO 返回前推进到超时。
    @Test
    fun userWriteAfterReadIsRejectedByBothFileTools() = runTest {
        withContext(Dispatchers.IO) {
            val read = reader.executeWithContext(readArgs(), context)
            assertTrue(read.toString(), read is ToolResult.Success)
            content = "base\nuser change"
            versions.recordExternal(path, sha256Fingerprint(content.toByteArray()))
            val write = writer.executeWithContext(writeArgs("old overwrite"), context)
            val edit = editor.executeWithContext(editArgs("base", "outdated"), context)
            assertEquals(STALE_FILE_CODE, (write as ToolResult.Error).code)
            assertEquals(STALE_FILE_CODE, (edit as ToolResult.Error).code)
            assertEquals("base\nuser change", content)
            verify(exactly = 0) { files.writeFile(any(), any(), any(), any()) }
        }
    }

    @Test
    fun rereadAllowsEditsAndKeepsOtherSessionsStale() = runTest {
        withContext(Dispatchers.IO) {
            val other = context.copy(sessionId = "other")
            val read = reader.executeWithContext(readArgs(), context)
            val otherRead = reader.executeWithContext(readArgs(), other)
            assertTrue(read.toString(), read is ToolResult.Success)
            assertTrue(otherRead.toString(), otherRead is ToolResult.Success)
            content = "base\nuser change"
            versions.recordExternal(path, sha256Fingerprint(content.toByteArray()))
            val reread = reader.executeWithContext(readArgs(), context)
            assertTrue(reread.toString(), reread is ToolResult.Success)
            val edited = editor.executeWithContext(editArgs("base", "updated"), context)
            assertTrue(edited.toString(), edited is ToolResult.Success)
            assertEquals("updated\nuser change", content)
            val stale = writer.executeWithContext(writeArgs("other overwrite"), other)
            assertEquals(STALE_FILE_CODE, (stale as ToolResult.Error).code)
            assertEquals("updated\nuser change", content)
        }
    }

    @Test
    fun successfulWritesRefreshTheirOwnBaselineForFollowingEdits() = runTest {
        withContext(Dispatchers.IO) {
            val read = reader.executeWithContext(readArgs(), context)
            assertTrue(read.toString(), read is ToolResult.Success)
            val written = writer.executeWithContext(writeArgs("first"), context)
            assertTrue(written.toString(), written is ToolResult.Success)
            val edited = editor.executeWithContext(editArgs("first", "second"), context)
            assertTrue(edited.toString(), edited is ToolResult.Success)
            assertEquals("second", content)
            assertEquals(sha256Fingerprint(content.toByteArray()), versions.get("session", path))
        }
    }

    @Test
    fun readKeepsItsSnapshotWhenAnotherWriterReplacesTheFileBeforeRegistration() = runTest {
        withContext(Dispatchers.IO) {
            every { files.readLinesWithFingerprint(path, any()) } answers {
                val fingerprint = readLinesAndFingerprint(content.byteInputStream(), secondArg())
                content = "external"
                versions.recordExternal(path, sha256Fingerprint(content.toByteArray()))
                fingerprint
            }
            val read = reader.executeWithContext(readArgs(), context)
            assertTrue(read.toString(), read is ToolResult.Success)
            assertEquals(sha256Fingerprint("base".toByteArray()), versions.get("session", path))
            assertEquals(1, notifications.pendingCount("session"))
            val write = writer.executeWithContext(writeArgs("stale overwrite"), context)
            assertEquals(STALE_FILE_CODE, (write as ToolResult.Error).code)
            assertEquals("external", content)
        }
    }

    @Test
    fun writeRegistersItsSubmittedBytesRatherThanALaterExternalVersion() = runTest {
        withContext(Dispatchers.IO) {
            assertTrue(reader.executeWithContext(readArgs(), context) is ToolResult.Success)
            every { files.writeFile(path, any(), any(), any()) } answers { content = "external" }
            assertTrue(writer.executeWithContext(writeArgs("submitted"), context) is ToolResult.Success)
            assertEquals(sha256Fingerprint("submitted".toByteArray()), versions.get("session", path))
            val edit = editor.executeWithContext(editArgs("external", "old rewrite"), context)
            assertEquals(STALE_FILE_CODE, (edit as ToolResult.Error).code)
            assertEquals("external", content)
        }
    }

    @Test
    fun failedSnapshotDoesNotReplaceAnExistingBaseline() = runTest {
        withContext(Dispatchers.IO) {
            versions.record("session", path, "old")
            every { files.readLinesWithFingerprint(path, any()) } throws java.io.IOException("fixture")
            assertTrue(reader.executeWithContext(readArgs(), context) is ToolResult.Error)
            assertEquals("old", versions.get("session", path))
        }
    }

    @Test
    fun externalWriterDoesNotCreateAnUnreadSessionBaseline() = runTest {
        withContext(Dispatchers.IO) {
            versions.record("other", "other-path", "other-version")
            versions.recordExternal(path, sha256Fingerprint(content.toByteArray()))
            val written = writer.executeWithContext(writeArgs("new content"), context)
            assertTrue(written.toString(), written is ToolResult.Success)
            assertEquals("new content", content)
        }
    }
}
