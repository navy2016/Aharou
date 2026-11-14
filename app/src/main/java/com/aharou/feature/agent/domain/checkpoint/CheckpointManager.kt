package com.aharou.feature.agent.domain.checkpoint

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.core.util.LineDiff
import com.aharou.core.util.writeTextSafely
import com.aharou.feature.agent.data.local.dao.CheckpointDao
import com.aharou.feature.agent.data.local.entity.CheckpointEntity
import com.aharou.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.aharou.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Singleton
class CheckpointManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val checkpointDao: CheckpointDao,
    private val fileAccess: FileAccessProvider,
    private val executions: com.aharou.feature.agent.domain.execution.SessionExecutionRepository,
    private val fileLocks: com.aharou.feature.agent.domain.tool.WorkspaceFileLock,
    private val fileVersions: com.aharou.feature.agent.domain.tool.FileVersionRegistry
) {
    /** 回退结果：实际还原的文件数，以及因「被检查点之外的操作改过」而跳过的文件。 */
    data class RestoreResult(val restoredCount: Int, val conflicts: List<String>) {
        val hasConflict: Boolean get() = conflicts.isNotEmpty()
    }

    /** 回退预览里单个文件的去向，三态与 [restoreCodeToCheckpoint] 的实际行为一一对应。 */
    enum class RewindFileState {
        /** 会被还原，且内容与磁盘当前不同（[RewindFileDiff.lines] 有内容）。 */
        RESTORED_WITH_CHANGES,

        /** 会被还原，但快照与当前内容一致，落盘动作不改任何一行。 */
        RESTORED_UNCHANGED,

        /** 被检查点之外的操作改过，回退会跳过它（不覆盖别人的改动）。 */
        CONFLICT
    }

    /**
     * 单个文件的回退预览。
     *
     * [lines] 是「磁盘当前内容 → 回退后内容」的行级差异；超过 [MAX_PREVIEW_DIFF_LINES] 截断，
     * 由 [truncated] 标记。新增/删除行数是完整差异的计数，不受截断影响（否则界面上的 +N 会少报）。
     */
    data class RewindFileDiff(
        val filePath: String,
        val changeType: String,
        val state: RewindFileState,
        val addedLines: Int,
        val removedLines: Int,
        val lines: List<LineDiff.DiffLine>,
        val truncated: Boolean
    )

    /**
     * 一次回退预览：会被还原的文件（有改动的排在前面，其次是无改动的）、冲突文件排在最后，
     * 冲突不该夹在「能还原」的文件中间，界面要成组提示这些文件回退不会动。
     *
     * [files] 最多 [MAX_PREVIEW_FILE_COUNT] 条，被截断时 [restoreFileCount] 等计数也只覆盖
     * 已扫描的那批——超上限时不再逐文件读内容推演，否则大工作区会同时打爆内存与 IO。
     */
    data class RewindDiff(
        val files: List<RewindFileDiff>,
        val restoreFileCount: Int,
        val changedFileCount: Int,
        val conflictCount: Int,
        val truncated: Boolean
    )

    /** 撤销恢复所需的现场记录：单个文件在那一刻的样子。 */
    @Serializable
    private data class UndoEntry(val filePath: String, val content: String?, val existed: Boolean,
        val executionId: String? = null, val resourceKey: String? = null, val expectedHash: String? = null, val bytes: String? = null)

    /**
     * 一份可回滚现场：哪次操作留下的、什么时候、涉及哪些文件。
     *
     * 带上 kind 与时间，界面才能像配置审计那样列出时间线、按节点挑一个撤销，
     * 而不是只留一份「最近一次」。
     */
    @Serializable
    private data class UndoRecord(
        val kind: String,
        val createdAt: Long,
        val files: List<UndoEntry>
    )

    /** 现场摘要，供界面列时间线（不含文件内容）。 */
    data class UndoRecordInfo(
        /** 现场文件在磁盘上的名字，撤销时按它定位。 */
        val id: String,
        /** [KIND_RESTORE]（一次回退留下的）或 [KIND_UNDO]（一次撤销留下的）。 */
        val kind: String,
        val createdAt: Long,
        val fileCount: Int
    )

    // 检查点备份根路径: <filesDir>/checkpoints/<sessionId>/<checkpointId>/
    private val baseCheckpointDir: File
        get() = File(context.filesDir, "checkpoints")

    // 活动 checkpoint 必须按会话隔离：多个会话（含父会话与其子代理）会并行跑 workflow，
    // 用单一字段会被后创建的会话覆盖，导致文件快照挂到别的会话名下，
    // 那个会话一撤销就会把本会话刚写好的文件还原/删除。
    private val activeCheckpointIds = ConcurrentHashMap<String, String>()
    private val restoreLocks = ConcurrentHashMap<String, Mutex>()

    private fun restoreLock(sessionId: String): Mutex = restoreLocks.computeIfAbsent(sessionId) { Mutex() }

    /**
     * 在用户发送新消息时调用，创建一个新的 Checkpoint 节点
     */
    suspend fun createCheckpoint(
        sessionId: String,
        userMessageId: String,
        prompt: String,
        execution: com.aharou.feature.agent.domain.execution.ExecutionBinding? = null
    ): CheckpointEntity = withContext(Dispatchers.IO) {
        val checkpointId = UUID.randomUUID().toString()
        val snippet = if (prompt.length > 60) prompt.take(60) + "..." else prompt
        val entity = CheckpointEntity(
            id = checkpointId,
            sessionId = sessionId,
            userMessageId = userMessageId,
            promptSnippet = snippet,
            createdAt = System.currentTimeMillis(),
            executionId = execution?.id
        )
        checkpointDao.insertCheckpoint(entity)
        activeCheckpointIds[sessionId] = checkpointId
        entity
    }

    fun setActiveCheckpointId(sessionId: String, checkpointId: String?) {
        if (checkpointId == null) {
            activeCheckpointIds.remove(sessionId)
        } else {
            activeCheckpointIds[sessionId] = checkpointId
        }
    }

    /**
     * 在工具（editFile/writeFile）将要修改或新建文件前调用。
     * 若该文件在当前 Checkpoint 周期内未快照过，则保存其原始状态。
     */
    suspend fun beforeFileModified(
        sessionId: String,
        filePath: String,
        access: FileAccessProvider? = null
    ): String? = withContext(Dispatchers.IO) {
        val checkpointId = activeCheckpointIds[sessionId] ?: return@withContext null
        val owner = checkpointDao.getCheckpointById(checkpointId) ?: throw java.io.IOException("Checkpoint no longer exists")
        if (owner.sessionId != sessionId || owner.executionId == null) throw java.io.IOException("Checkpoint execution binding is unknown")
        val fileAccess = access ?: accessFor(checkpointId, sessionId)

        // 查重：同一个 checkpointId 内对同一文件只保留最原始的一次快照
        if (checkpointDao.countSnapshot(checkpointId, filePath) > 0) return@withContext checkpointId

        val snapshotDir = File(baseCheckpointDir, "$sessionId/$checkpointId")
        if (!snapshotDir.exists()) {
            snapshotDir.mkdirs()
        }

        val exists = fileAccess.exists(filePath)
        val changeType = if (exists) "MODIFY" else "CREATE"
        val snapshotFileName = "${UUID.randomUUID()}_${File(filePath).name}"
        val snapshotFile = File(snapshotDir, snapshotFileName)

        if (changeType == "MODIFY") {
            val source = fileAccess.copyToLocal(filePath)
            source.inputStream().use { input -> snapshotFile.outputStream().use { output -> input.copyTo(output) } }
        } else {
            snapshotFile.writeText("") // 标示创建空记录
        }

        val snapshotEntity = CheckpointFileSnapshotEntity(
            id = UUID.randomUUID().toString(),
            checkpointId = checkpointId,
            filePath = filePath,
            snapshotRelativePath = "$sessionId/$checkpointId/$snapshotFileName",
            changeType = changeType,
            resourceKey = fileAccess.resourceKey(filePath)
        )
        checkpointDao.insertFileSnapshot(snapshotEntity)
        FileLogger.i(TAG, "快照: session=$sessionId cp=$checkpointId type=$changeType path=$filePath")
        checkpointId
    }

    /**
     * 文件被 AI **成功写入后**调用：记下写入后的内容摘要。
     * 回退时用它区分「文件仍是 AI 写下的样子」与「已被检查点之外的操作改过」。
     */
    suspend fun afterFileModified(sessionId: String, filePath: String, access: FileAccessProvider? = null,
        checkpointId: String? = activeCheckpointIds[sessionId], committedFingerprint: String? = null) = withContext(Dispatchers.IO) {
        checkpointId ?: return@withContext
        val selected = access ?: accessFor(checkpointId, sessionId)
        val fingerprint = committedFingerprint ?: selected.fingerprint(filePath) ?: throw java.io.IOException("Cannot fingerprint the written file")
        checkpointDao.updateWrittenHash(checkpointId, filePath, fingerprint.removePrefix("sha256:"))
    }

    private suspend fun accessFor(checkpointId: String, sessionId: String): FileAccessProvider {
        val checkpoint = checkpointDao.getCheckpointById(checkpointId) ?: throw java.io.IOException("Checkpoint no longer exists")
        if (checkpoint.sessionId != sessionId) throw java.io.IOException("Checkpoint belongs to another session")
        val id = checkpoint.executionId ?: throw java.io.IOException("Checkpoint execution binding is unknown")
        val binding = executions.validate(executions.getExecution(id))
        return fileAccess.forExecution(binding).forStrictRead()
    }

    /**
     * 将代码回滚到指定 Checkpoint 节点的初始状态。
     *
     * 每个文件在还原前比对当前内容摘要与快照记录的写入摘要：不一致说明它被检查点之外的操作改过，
     * 这时**不覆盖**它，而是记进 [RestoreResult.conflicts] 交由上层提示用户（避免把人家的改动冲掉）。
     * 还原前的现场会另存一份，供 [undoLastRestore] 反悔。
     */
    suspend fun restoreCodeToCheckpoint(sessionId: String, targetCheckpointId: String): RestoreResult =
        restoreLock(sessionId).withLock { restoreBoundCode(sessionId, targetCheckpointId) }

    private suspend fun restoreBoundCode(
        sessionId: String,
        targetCheckpointId: String
    ): RestoreResult = withContext(Dispatchers.IO) {
        val allCheckpoints = checkpointDao.getCheckpointsForSession(sessionId)
        val targetIndex = allCheckpoints.indexOfFirst { it.id == targetCheckpointId }
        if (targetIndex == -1) return@withContext RestoreResult(0, emptyList())

        // 收集 targetCheckpointId 及其之后所有 Checkpoint 的快照，倒序还原
        val checkpointsToRollback = allCheckpoints.subList(targetIndex, allCheckpoints.size).reversed()
        var restoredFileCount = 0
        val conflicts = mutableListOf<String>()
        val undoEntries = linkedMapOf<String, UndoEntry>()
        var undoFile: File? = null
        FileLogger.i(
            TAG,
            "还原开始: session=$sessionId target=$targetCheckpointId 涉及 ${checkpointsToRollback.size} 个检查点"
        )

        for (cp in checkpointsToRollback) {
            val snapshots = checkpointDao.getFileSnapshotsForCheckpoint(cp.id)
            val access = try { accessFor(cp.id, sessionId) } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                conflicts.addAll(snapshots.map { it.filePath })
                continue
            }
            for (snapshot in snapshots) {
                try {
                val key = access.resourceKey(snapshot.filePath)
                fileLocks.withLock(listOf(key, snapshot.resourceKey)) {
                    if (snapshot.resourceKey != key || access.resourceKey(snapshot.filePath) != key) {
                        conflicts.add(snapshot.filePath)
                        return@withLock
                    }
                    val originalFile = File(baseCheckpointDir, snapshot.snapshotRelativePath)
                    val original = if (snapshot.changeType == "MODIFY") originalFile.takeIf { it.isFile }?.readBytes() else null
                    if (snapshot.changeType == "MODIFY" && original == null) { conflicts.add(snapshot.filePath); return@withLock }
                    val current = if (access.exists(snapshot.filePath)) access.copyToLocal(snapshot.filePath).readBytes() else null
                    if (!checkpointCanRestore(snapshot.changeType, current, original, snapshot.writtenHash)) {
                        conflicts.add(snapshot.filePath)
                        return@withLock
                    }
                    if (snapshot.changeType == "CREATE" && current == null) return@withLock
                    val before = undoEntries[key] ?: UndoEntry(snapshot.filePath, null, current != null,
                        cp.executionId, key, bytes = current?.let { java.util.Base64.getEncoder().encodeToString(it) })
                    val after = original?.let { com.aharou.feature.workspace.domain.sha256Fingerprint(it) }
                    undoEntries[key] = before.copy(expectedHash = after)
                    undoFile = saveUndo(sessionId, KIND_RESTORE, undoEntries.values.toList(), undoFile)
                    if (original != null) access.writeBytes(snapshot.filePath, original) else access.delete(snapshot.filePath)
                    fileVersions.recordExternal(key, after ?: "missing")
                    restoredFileCount++
                }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    conflicts.add(snapshot.filePath)
                    FileLogger.w(TAG, "还原文件失败，保留当前内容: ${snapshot.filePath}", error)
                }
            }
        }
        FileLogger.i(TAG, "还原结束: session=$sessionId 共处理 $restoredFileCount 个文件，冲突 ${conflicts.size} 个")
        RestoreResult(restoredFileCount, conflicts)
    }

    /**
     * 预览还原到 [targetCheckpointId] 会涉及多少个文件快照，供回退前的确认提示展示。
     *
     * 只数不动文件；范围与 [restoreCodeToCheckpoint] 同源，但它是上限——被判定为冲突的文件会被跳过。
     */
    suspend fun countFilesToRestore(sessionId: String, targetCheckpointId: String): Int =
        withContext(Dispatchers.IO) {
            val allCheckpoints = checkpointDao.getCheckpointsForSession(sessionId)
            val targetIndex = allCheckpoints.indexOfFirst { it.id == targetCheckpointId }
            if (targetIndex == -1) return@withContext 0
            allCheckpoints.subList(targetIndex, allCheckpoints.size)
                .sumOf { checkpointDao.getFileSnapshotsForCheckpoint(it.id).size }
        }

    /**
     * 预览回退到 [targetCheckpointId] 会发生什么：哪些文件会被还原、哪些本来就没变、哪些会被跳过。
     *
     * 只读：不写文件、不改摘要。范围与判定口径都跟 [restoreCodeToCheckpoint] 同源——同样从目标检查点
     * 往后（含目标）逐份倒着走，同样按写入摘要判断冲突。两者一旦分叉，预览说「能还原」而真正回退时被跳过，
     * 用户会以为回退失败。
     */
    suspend fun previewRewindDiff(
        sessionId: String,
        targetCheckpointId: String
    ): RewindDiff = withContext(Dispatchers.IO) {
        val allCheckpoints = checkpointDao.getCheckpointsForSession(sessionId)
        val targetIndex = allCheckpoints.indexOfFirst { it.id == targetCheckpointId }
        if (targetIndex == -1) return@withContext RewindDiff(emptyList(), 0, 0, 0, false)

        val entries = LinkedHashMap<String, PreviewEntry>()
        var truncated = false

        // 倒序（新→旧）与 restore 一致：同一个文件在多个检查点各有快照时，回退是逐份往回写的，
        // 冲突判定必须跟着这条链走，只拿最早那份去比会把能干净回退的文件误报成冲突。
        for (cp in allCheckpoints.subList(targetIndex, allCheckpoints.size).reversed()) {
            val access = try { accessFor(cp.id, sessionId) } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                null
            }
            for (snapshot in checkpointDao.getFileSnapshotsForCheckpoint(cp.id)) {
                val key = snapshot.resourceKey ?: "${cp.id}:${snapshot.filePath}"
                val entry = entries[key]
                if (entry == null) {
                    if (entries.size >= MAX_PREVIEW_FILE_COUNT) {
                        truncated = true
                        continue
                    }
                    entries[key] = PreviewEntry(snapshot.filePath)
                        .also { applyPreviewSnapshot(it, snapshot, access) }
                } else {
                    applyPreviewSnapshot(entry, snapshot, access)
                }
            }
        }

        val changed = ArrayList<RewindFileDiff>()
        val unchanged = ArrayList<RewindFileDiff>()
        val conflicts = ArrayList<RewindFileDiff>()
        var restoreCount = 0
        for (entry in entries.values) {
            if (entry.lastOutcomeConflict) {
                conflicts.add(
                    RewindFileDiff(entry.filePath, entry.changeType, RewindFileState.CONFLICT, 0, 0, emptyList(), false)
                )
                continue
            }
            if (!entry.restored) continue
            restoreCount++
            val before = entry.diskContent?.toString(Charsets.UTF_8) ?: ""
            val after = entry.virtualContent?.toString(Charsets.UTF_8) ?: ""
            if (before == after) {
                unchanged.add(
                    RewindFileDiff(
                        entry.filePath,
                        entry.changeType,
                        RewindFileState.RESTORED_UNCHANGED,
                        0,
                        0,
                        emptyList(),
                        false
                    )
                )
                continue
            }
            val rawDiff = LineDiff.diff(before, after)
            // 空文件在 split("\n") 后被当成「一个空行」，空串那一侧会多冒一条假的新增/删除；
            // 只在整份内容为空的那一侧过滤，行数统计才与界面上看到的行一致。
            val diff = rawDiff.filterNot { line ->
                (before.isEmpty() && line.type == LineDiff.LineType.REMOVE && line.text.isEmpty()) ||
                    (after.isEmpty() && line.type == LineDiff.LineType.ADD && line.text.isEmpty())
            }
            val diffTruncated = diff.size > MAX_PREVIEW_DIFF_LINES
            changed.add(
                RewindFileDiff(
                    filePath = entry.filePath,
                    changeType = entry.changeType,
                    state = RewindFileState.RESTORED_WITH_CHANGES,
                    addedLines = diff.count { it.type == LineDiff.LineType.ADD },
                    removedLines = diff.count { it.type == LineDiff.LineType.REMOVE },
                    lines = if (diffTruncated) diff.take(MAX_PREVIEW_DIFF_LINES) else diff,
                    truncated = diffTruncated
                )
            )
        }

        RewindDiff(
            files = changed + unchanged + conflicts,
            restoreFileCount = restoreCount,
            changedFileCount = changed.size,
            conflictCount = conflicts.size,
            truncated = truncated
        )
    }

    /**
     * 把一份快照「施加」到内存里的模拟状态上：还原会真的写盘，这里只记账。
     *
     * 冲突判定条件逐字对齐 [restoreCodeToCheckpoint]，不另造规则；快照文件丢失时与还原一样直接跳过。
     */
    private suspend fun applyPreviewSnapshot(entry: PreviewEntry, snapshot: CheckpointFileSnapshotEntity, access: FileAccessProvider?) {
        entry.changeType = snapshot.changeType
        if (access == null || snapshot.resourceKey == null) { entry.lastOutcomeConflict = true; return }
        fileLocks.withLock(snapshot.resourceKey) {
            try {
                if (access.resourceKey(snapshot.filePath) != snapshot.resourceKey) { entry.lastOutcomeConflict = true; return@withLock }
                val current = if (entry.virtualLoaded) entry.virtualContent else {
                    val bytes = if (access.exists(snapshot.filePath)) access.copyToLocal(snapshot.filePath).readBytes() else null
                    entry.diskContent = bytes
                    entry.virtualContent = bytes
                    entry.virtualLoaded = true
                    bytes
                }
                val originalFile = File(baseCheckpointDir, snapshot.snapshotRelativePath)
                val original = if (snapshot.changeType == "MODIFY") originalFile.takeIf { it.isFile }?.readBytes() else null
                if (snapshot.changeType == "MODIFY" && original == null ||
                    !checkpointCanRestore(snapshot.changeType, current, original, snapshot.writtenHash)) {
                    entry.lastOutcomeConflict = true
                } else if (snapshot.changeType != "CREATE" || current != null) {
                    entry.virtualContent = original
                    entry.restored = true
                    entry.lastOutcomeConflict = false
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                entry.lastOutcomeConflict = true
            }
        }
    }

    /** 预览时单个文件的模拟状态。 */
    private class PreviewEntry(val filePath: String) {
        var changeType: String = "MODIFY"

        /** 磁盘上此刻的真实内容，首次触碰时读一次；null 表示文件不存在。 */
        var diskContent: ByteArray? = null

        /** 推演到当前位置时的内容：每还原一份快照它就变成那份快照的原文，null 表示将被删除。 */
        var virtualContent: ByteArray? = null
        var virtualLoaded = false

        /** 至少发生过一次还原。 */
        var restored = false

        /** 最后一笔是冲突跳过——还原里冲突的 continue 会让文件停在这一刻，net 效果由它决定。 */
        var lastOutcomeConflict = false
    }

    /** 列出现场时间线，新的在前；界面据此让用户挑一个时间节点撤销。 */
    fun listUndoRecords(sessionId: String): List<UndoRecordInfo> =
        undoRecords(sessionId).mapNotNull { file ->
            val record = readUndo(file) ?: return@mapNotNull null
            UndoRecordInfo(file.name, record.kind, record.createdAt, record.files.size)
        }

    /** 撤销最近一次恢复。 */
    suspend fun undoLastRestore(sessionId: String): Int = undoRestoreTo(sessionId, null)

    /**
     * 撤销到指定现场（[recordId] 取自 [listUndoRecords]；为空即最新一份），返回还原的文件数。
     *
     * 撤销前先把当前现场也存一份——撤销本身同样会盖文件，得给它留个反向的后悔键，
     * 于是来回跳都不丢东西。目标现场用过即删，不会再出现在时间线里。
     */
    suspend fun undoRestoreTo(sessionId: String, recordId: String?): Int =
        restoreLock(sessionId).withLock { undoBoundRestore(sessionId, recordId) }

    private suspend fun undoBoundRestore(sessionId: String, recordId: String?): Int = withContext(Dispatchers.IO) {
        val records = undoRecords(sessionId)
        val target = (if (recordId != null) records.firstOrNull { it.name == recordId } else records.firstOrNull())
            ?: return@withContext 0
        val record = readUndo(target) ?: return@withContext 0

        var count = 0
        val remaining = mutableListOf<UndoEntry>()
        val inverse = mutableListOf<UndoEntry>()
        var inverseFile: File? = null
        for (entry in record.files) {
            try {
                val binding = executions.validate(executions.getExecution(entry.executionId
                    ?: throw java.io.IOException("Undo execution binding is unknown")))
                val access = fileAccess.forExecution(binding).forStrictRead()
                val key = access.resourceKey(entry.filePath)
                fileLocks.withLock(listOf(key, entry.resourceKey)) {
                    if (entry.resourceKey != key || access.resourceKey(entry.filePath) != key || access.fingerprint(entry.filePath) != entry.expectedHash)
                        throw java.io.IOException("Undo target changed externally")
                    val current = if (access.exists(entry.filePath)) access.copyToLocal(entry.filePath).readBytes() else null
                    val restored = if (entry.existed) entry.bytes?.let { java.util.Base64.getDecoder().decode(it) }
                        ?: throw java.io.IOException("Undo snapshot is incomplete") else null
                    val after = restored?.let { com.aharou.feature.workspace.domain.sha256Fingerprint(it) }
                    inverse.add(UndoEntry(entry.filePath, null, current != null, binding.id, key, after,
                        current?.let { java.util.Base64.getEncoder().encodeToString(it) }))
                    inverseFile = saveUndo(sessionId, KIND_UNDO, inverse, inverseFile)
                    if (restored != null) access.writeBytes(entry.filePath, restored) else access.delete(entry.filePath)
                    fileVersions.recordExternal(key, after ?: "missing")
                    count++
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                remaining.add(entry)
                FileLogger.w(TAG, "撤销恢复跳过冲突文件: ${entry.filePath}", error)
            }
        }
        if (remaining.isEmpty()) target.delete() else
            check(target.writeTextSafely(Json.encodeToString(record.copy(files = remaining)), TAG))
        FileLogger.i(TAG, "已撤销到现场 ${target.name}: session=$sessionId 处理 $count 个文件，剩余 ${undoRecordCount(sessionId)} 个")
        count
    }

    /** 还能撤销几次（现场条数），供界面提示。 */
    fun undoRecordCount(sessionId: String): Int = undoRecords(sessionId).size

    /** 是否还有可撤销的现场。 */
    fun hasUndoRecord(sessionId: String): Boolean = undoRecords(sessionId).isNotEmpty()

    /**
     * 删除 Session 关联的所有 Checkpoint 快照与记录
     */
    suspend fun clearSessionCheckpoints(sessionId: String) =
        restoreLock(sessionId).withLock { clearBoundCheckpoints(sessionId) }

    private suspend fun clearBoundCheckpoints(sessionId: String) = withContext(Dispatchers.IO) {
        activeCheckpointIds.remove(sessionId)
        checkpointDao.deleteFileSnapshotsForSession(sessionId)
        checkpointDao.deleteCheckpointsForSession(sessionId)
        val sessionDir = File(baseCheckpointDir, sessionId)
        if (sessionDir.exists()) {
            sessionDir.deleteRecursively()
        }
    }

    /**
     * 存一份可回滚现场。每次恢复/撤销各存一份、各自成文件，于是能像时间线一样往回选，
     * 而不是只能撤最近一次。
     *
     * 超过 [MAX_UNDO_RECORDS] 就删最旧的：现场里放的是整份文件内容，不设上限会把磁盘吃掉。
     */
    private fun saveUndo(sessionId: String, kind: String, entries: List<UndoEntry>, target: File? = null): File? {
        if (entries.isEmpty()) return target
        val dir = undoDir(sessionId).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val file = target ?: File(dir, "$now-${UUID.randomUUID().toString().take(6)}.json")
        if (!file.writeTextSafely(Json.encodeToString(UndoRecord(kind, now, entries)), TAG))
            throw java.io.IOException("Cannot persist checkpoint undo journal")
        undoRecords(sessionId).drop(MAX_UNDO_RECORDS).forEach { it.delete() }
        return file
    }

    /** 现场目录：一条一次，文件名以时间戳开头。 */
    private fun undoDir(sessionId: String): File = File(baseCheckpointDir, "$sessionId/undo")

    /** 可回滚现场，新的在前。[legacyUndoFile] 是升级前留下的单份现场，排到最后。 */
    private fun undoRecords(sessionId: String): List<File> {
        val records = undoDir(sessionId).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            .orEmpty()
        val legacy = legacyUndoFile(sessionId)
        return if (legacy.isFile) records + legacy else records
    }

    /** 读一份现场；升级前的老格式是裸的 `List<UndoEntry>`，没有 kind/时间，用文件修改时间兜底。 */
    private fun readUndo(file: File): UndoRecord? = runCatching {
        val text = file.readText()
        runCatching { Json.decodeFromString<UndoRecord>(text) }.getOrElse {
            UndoRecord(KIND_RESTORE, file.lastModified(), Json.decodeFromString<List<UndoEntry>>(text))
        }
    }.getOrElse { e ->
        FileLogger.w(TAG, "读取撤销记录失败: ${file.name}", e)
        null
    }

    /** 升级前的老格式：单份现场直接放在会话目录下。 */
    private fun legacyUndoFile(sessionId: String): File =
        File(baseCheckpointDir, "$sessionId/.undo.json")

    private companion object {
        const val TAG = "CheckpointManager"

        /** 最多保留多少份可回滚现场：每份装着整批文件的原文，放开会吃磁盘。 */
        const val MAX_UNDO_RECORDS = 20

        /** 回退预览最多推演多少个文件：每个都要读一次当前内容，大工作区放开会把内存和 IO 一起打爆。 */
        const val MAX_PREVIEW_FILE_COUNT = 200

        /** 单个文件最多带回多少行差异，超出截断并标记，避免一次展开把整段对话卡住。 */
        const val MAX_PREVIEW_DIFF_LINES = 200

        /** 现场来源：一次回退留下的。 */
        const val KIND_RESTORE = "restore"

        /** 现场来源：一次撤销留下的。 */
        const val KIND_UNDO = "undo"
    }
}
