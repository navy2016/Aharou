package com.aharou.feature.agent.domain.checkpoint

import android.content.ContextWrapper
import com.aharou.feature.agent.data.local.dao.CheckpointDao
import com.aharou.feature.agent.data.local.entity.CheckpointEntity
import com.aharou.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.FileEntry
import com.aharou.feature.workspace.domain.sha256Fingerprint
import kotlinx.coroutines.test.runTest
import io.mockk.mockk
import io.mockk.coEvery
import com.aharou.feature.agent.domain.execution.ExecutionBinding
import com.aharou.feature.agent.domain.execution.SessionExecutionRepository
import com.aharou.feature.agent.domain.tool.WorkspaceFileLock
import com.aharou.feature.agent.domain.tool.FileVersionRegistry
import com.aharou.feature.agent.domain.notification.AgentNotificationCenter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

/**
 * 活动 checkpoint 的会话归属。
 *
 * 回归点：活动 checkpoint 曾是全局单字段，会话 B 发消息会覆盖会话 A 的活动 checkpoint，
 * A 之后写文件产生的快照被挂到 B 名下；B 一撤销就把 A 已写盘的文件还原或删除
 * （表现为「写盘成功但文件消失」的幽灵文件）。
 */
class CheckpointManagerSessionIsolationTest {

    private val root: File = File(System.getProperty("java.io.tmpdir"), "cp-test-${System.nanoTime()}").apply { mkdirs() }
    private val filesDir = File(root, "files").apply { mkdirs() }
    private val workspace = File(root, "workspace").apply { mkdirs() }

    private val dao = FakeCheckpointDao()
    private val fileAccess = LocalFileAccess()
    private val executions = mockk<SessionExecutionRepository>().also { repository ->
        coEvery { repository.getExecution(any()) } answers { binding(arg(0)) }
        coEvery { repository.validate(any()) } answers { arg(0) }
    }
    private val manager = CheckpointManager(FakeContext(filesDir), dao, fileAccess, executions,
        WorkspaceFileLock(), FileVersionRegistry(AgentNotificationCenter()))

    private fun binding(id: String) = ExecutionBinding(id, workspace.path, workspace.path, "builtin-alpine", "LOCAL_PROOT")

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun restoringOtherSession_doesNotRevertModifiedFile() = runTest {
        val path = File(workspace, "a.txt").apply { writeText("original") }.absolutePath

        manager.createCheckpoint("sessionA", "msgA", "A 的请求", binding("executionA"))
        val cpB = manager.createCheckpoint("sessionB", "msgB", "B 的请求", binding("executionB"))

        // A 的 Agent 在 B 发过消息之后才动文件
        manager.beforeFileModified("sessionA", path)
        fileAccess.writeFile(path, "A 的新内容", overwrite = true)

        val restored = manager.restoreCodeToCheckpoint("sessionB", cpB.id)

        assertEquals(0, restored.restoredCount)
        assertEquals("A 的新内容", File(path).readText())
    }

    @Test
    fun restoringOtherSession_doesNotDeleteCreatedFile() = runTest {
        val path = File(workspace, "new.txt").absolutePath

        manager.createCheckpoint("sessionA", "msgA", "A 的请求", binding("executionA"))
        val cpB = manager.createCheckpoint("sessionB", "msgB", "B 的请求", binding("executionB"))

        manager.beforeFileModified("sessionA", path)
        fileAccess.writeFile(path, "A 新建的文件", overwrite = true)

        manager.restoreCodeToCheckpoint("sessionB", cpB.id)

        assertTrue(File(path).exists())
        assertEquals("A 新建的文件", File(path).readText())
    }

    @Test
    fun restoringOwnSession_revertsAndDeletes() = runTest {
        val modified = File(workspace, "m.txt").apply { writeText("original") }.absolutePath
        val created = File(workspace, "c.txt").absolutePath

        val cpA = manager.createCheckpoint("sessionA", "msgA", "A 的请求", binding("executionA"))
        manager.createCheckpoint("sessionB", "msgB", "B 的请求", binding("executionB"))

        manager.beforeFileModified("sessionA", modified)
        fileAccess.writeFile(modified, "改过了", overwrite = true)
        // 真实链路里工具写完必记写入摘要（afterFileModified）；缺了它，还原时会因「无法判断来历」
        // 被当成外部改动跳过——那是有意的保护，不是这里要测的行为。
        manager.afterFileModified("sessionA", modified)
        manager.beforeFileModified("sessionA", created)
        fileAccess.writeFile(created, "新建的", overwrite = true)
        manager.afterFileModified("sessionA", created)

        val restored = manager.restoreCodeToCheckpoint("sessionA", cpA.id)

        assertEquals(2, restored.restoredCount)
        assertEquals("original", File(modified).readText())
        assertFalse(File(created).exists())
    }

    @Test
    fun externallyModifiedFile_isSkippedNotOverwritten() = runTest {
        val modified = File(workspace, "x.txt").apply { writeText("original") }.absolutePath

        val cpA = manager.createCheckpoint("sessionA", "msgA", "A 的请求", binding("executionA"))
        manager.beforeFileModified("sessionA", modified)
        fileAccess.writeFile(modified, "AI 写的", overwrite = true)
        manager.afterFileModified("sessionA", modified)
        // 检查点之外的操作（手改 / 终端命令）又改了一手
        fileAccess.writeFile(modified, "我自己改的", overwrite = true)

        val restored = manager.restoreCodeToCheckpoint("sessionA", cpA.id)

        assertEquals(0, restored.restoredCount)
        assertTrue(restored.conflicts.contains(modified))
        assertEquals("我自己改的", File(modified).readText())
    }

    @Test
    fun snapshotsAreScopedToOwningSession() = runTest {
        val pathA = File(workspace, "a.txt").apply { writeText("a") }.absolutePath
        val pathB = File(workspace, "b.txt").apply { writeText("b") }.absolutePath

        val cpA = manager.createCheckpoint("sessionA", "msgA", "A 的请求", binding("executionA"))
        val cpB = manager.createCheckpoint("sessionB", "msgB", "B 的请求", binding("executionB"))

        manager.beforeFileModified("sessionA", pathA)
        manager.beforeFileModified("sessionB", pathB)

        assertEquals(listOf(pathA), dao.getFileSnapshotsForCheckpoint(cpA.id).map { it.filePath })
        assertEquals(listOf(pathB), dao.getFileSnapshotsForCheckpoint(cpB.id).map { it.filePath })
    }

    @Test
    fun clearingSessionDropsItsActiveCheckpoint() = runTest {
        val path = File(workspace, "a.txt").apply { writeText("original") }.absolutePath
        val cpA = manager.createCheckpoint("sessionA", "msgA", "A 的请求", binding("executionA"))

        manager.clearSessionCheckpoints("sessionA")
        manager.beforeFileModified("sessionA", path)

        assertTrue(dao.getFileSnapshotsForCheckpoint(cpA.id).isEmpty())
    }

    @Test
    fun externallyChangedCreatedFileIsNeitherPreviewedForDeletionNorDeleted() = runTest {
        val path = File(workspace, "created.txt").path
        val cp = manager.createCheckpoint("sessionA", "create", "create", binding("executionA"))
        manager.beforeFileModified("sessionA", path)
        fileAccess.writeFile(path, "created", true)
        manager.afterFileModified("sessionA", path)
        File(path).writeText("user content")
        assertEquals(1, manager.previewRewindDiff("sessionA", cp.id).conflictCount)
        assertEquals(listOf(path), manager.restoreCodeToCheckpoint("sessionA", cp.id).conflicts)
        assertEquals("user content", File(path).readText())
    }

    @Test
    fun restoreKeepsTheOriginalCrLfBytes() = runTest {
        val file = File(workspace, "crlf.txt").apply { writeText("a\r\nb\r\n") }
        val cp = manager.createCheckpoint("sessionA", "bytes", "bytes", binding("executionA"))
        manager.beforeFileModified("sessionA", file.path)
        file.writeText("changed\n")
        manager.afterFileModified("sessionA", file.path)
        manager.restoreCodeToCheckpoint("sessionA", cp.id)
        assertEquals("a\r\nb\r\n", file.readText())
    }

    @Test
    fun undoDoesNotOverwriteEditsMadeAfterRestore() = runTest {
        val file = File(workspace, "undo.txt").apply { writeText("before") }
        val cp = manager.createCheckpoint("sessionA", "undo", "undo", binding("executionA"))
        manager.beforeFileModified("sessionA", file.path)
        file.writeText("agent")
        manager.afterFileModified("sessionA", file.path)
        manager.restoreCodeToCheckpoint("sessionA", cp.id)
        file.writeText("external after restore")
        assertEquals(0, manager.undoLastRestore("sessionA"))
        assertEquals("external after restore", file.readText())
        assertTrue(manager.hasUndoRecord("sessionA"))
    }

    @Test
    fun undoRestoresThePreRestoreBytes() = runTest {
        val file = File(workspace, "undo-bytes.txt").apply { writeText("before") }
        val cp = manager.createCheckpoint("sessionA", "undo-bytes", "undo", binding("executionA"))
        manager.beforeFileModified("sessionA", file.path)
        file.writeText("agent\r\n")
        manager.afterFileModified("sessionA", file.path)
        manager.restoreCodeToCheckpoint("sessionA", cp.id)
        assertEquals(1, manager.undoLastRestore("sessionA"))
        assertEquals("agent\r\n", file.readText())
    }

    @Test
    fun aCapturedCheckpointReceivesTheHashEvenIfTheActiveCheckpointChanges() = runTest {
        val file = File(workspace, "checkpoint.txt").apply { writeText("before") }
        val first = manager.createCheckpoint("sessionA", "first", "first", binding("executionA"))
        val token = manager.beforeFileModified("sessionA", file.path)
        manager.createCheckpoint("sessionA", "second", "second", binding("executionA"))
        file.writeText("written")
        manager.afterFileModified("sessionA", file.path, checkpointId = token, committedFingerprint = sha256Fingerprint(file.readBytes()))
        assertEquals(sha256Fingerprint(file.readBytes()).removePrefix("sha256:"), dao.getFileSnapshotsForCheckpoint(first.id).single().writtenHash)
    }

    @Test
    fun aLegacyCheckpointWithoutAnExecutionBindingDoesNotTouchTheSelectedWorkspace() = runTest {
        val file = File(workspace, "legacy.txt").apply { writeText("current") }
        val cp = manager.createCheckpoint("sessionA", "legacy", "legacy")
        dao.insertFileSnapshot(CheckpointFileSnapshotEntity("legacy-snapshot", cp.id, file.path, "missing", "CREATE"))
        assertEquals(listOf(file.path), manager.restoreCodeToCheckpoint("sessionA", cp.id).conflicts)
        assertEquals("current", file.readText())
    }

    private class FakeContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }

    private class FakeCheckpointDao : CheckpointDao {
        private val checkpoints = mutableListOf<CheckpointEntity>()
        private val snapshots = mutableListOf<CheckpointFileSnapshotEntity>()

        override suspend fun insertCheckpoint(checkpoint: CheckpointEntity) {
            checkpoints.removeAll { it.id == checkpoint.id }
            checkpoints += checkpoint
        }

        override suspend fun insertFileSnapshot(snapshot: CheckpointFileSnapshotEntity) {
            snapshots.removeAll { it.id == snapshot.id }
            snapshots += snapshot
        }

        // 同一毫秒创建的 checkpoint 按插入顺序稳定排序，对齐 Room 的 createdAt ASC
        override suspend fun getCheckpointsForSession(sessionId: String): List<CheckpointEntity> =
            checkpoints.filter { it.sessionId == sessionId }.sortedBy { it.createdAt }

        override suspend fun getCheckpointBySessionAndMessage(sessionId: String, messageId: String): CheckpointEntity? =
            checkpoints.firstOrNull { it.sessionId == sessionId && it.userMessageId == messageId }

        override suspend fun getCheckpointById(checkpointId: String): CheckpointEntity? =
            checkpoints.firstOrNull { it.id == checkpointId }

        override suspend fun getFileSnapshotsForCheckpoint(checkpointId: String): List<CheckpointFileSnapshotEntity> =
            snapshots.filter { it.checkpointId == checkpointId }

        override suspend fun countSnapshot(checkpointId: String, filePath: String): Int =
            snapshots.count { it.checkpointId == checkpointId && it.filePath == filePath }

        override suspend fun updateWrittenHash(checkpointId: String, filePath: String, hash: String) {
            val index = snapshots.indexOfFirst { it.checkpointId == checkpointId && it.filePath == filePath }
            if (index >= 0) {
                snapshots[index] = snapshots[index].copy(writtenHash = hash)
            }
        }

        override suspend fun deleteCheckpointsForSession(sessionId: String) {
            checkpoints.removeAll { it.sessionId == sessionId }
        }

        override suspend fun deleteFileSnapshotsForSession(sessionId: String) {
            val ids = checkpoints.filter { it.sessionId == sessionId }.map { it.id }.toSet()
            snapshots.removeAll { it.checkpointId in ids }
        }

        override suspend fun deleteCheckpointsBefore(cutoffTimestamp: Long) {
            checkpoints.removeAll { it.createdAt < cutoffTimestamp }
        }
    }

    private class LocalFileAccess : FileAccessProvider {
        override fun readFile(path: String): String = File(path).readText()
        override fun readLinesWithFingerprint(path: String, consumeLine: (String) -> Boolean): String? =
            com.aharou.feature.workspace.domain.readLinesAndFingerprint(File(path).inputStream(), consumeLine)
        override fun resourceKey(path: String): String = "local\u0000${File(path).canonicalPath}"
        override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) {
            File(path).parentFile?.mkdirs()
            File(path).writeText(content, encoding)
        }

        override fun exists(path: String): Boolean = File(path).exists()
        override fun delete(path: String) {
            File(path).delete()
        }

        override fun readLines(path: String): Sequence<String> = throw UnsupportedOperationException()
        override fun isDirectory(path: String): Boolean = throw UnsupportedOperationException()
        override fun isFile(path: String): Boolean = throw UnsupportedOperationException()
        override fun fileSize(path: String): Long = throw UnsupportedOperationException()
        override fun lastModified(path: String): Long = throw UnsupportedOperationException()
        override fun fingerprint(path: String): String? {
            val file = File(path)
            if (!file.isFile) return null
            return sha256Fingerprint(file.readBytes())
        }

        override fun permissions(path: String): String = throw UnsupportedOperationException()
        override fun listFiles(path: String): List<FileEntry> = throw UnsupportedOperationException()
        override fun readBytes(path: String): ByteArray = throw UnsupportedOperationException()
        override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
            File(path).parentFile?.mkdirs()
            File(path).writeBytes(bytes)
        }
        override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long = throw UnsupportedOperationException()
        override fun listFilesRecursive(path: String, maxDepth: Int): List<String> = emptyList()
        override fun copyToLocal(path: String): File = File(path)
        override fun deleteRecursively(path: String) = throw UnsupportedOperationException()
        override fun rename(path: String, newPath: String) = throw UnsupportedOperationException()
        override fun copy(path: String, newPath: String, overwrite: Boolean) = throw UnsupportedOperationException()
        override fun move(path: String, newPath: String, overwrite: Boolean) = throw UnsupportedOperationException()
        override fun mkdirs(path: String) = throw UnsupportedOperationException()
        override fun parentPath(path: String): String? = throw UnsupportedOperationException()
        override fun toDisplayPath(path: String): String = path
    }
}
