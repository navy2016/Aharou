package com.aharou.feature.agent.domain.checkpoint

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.core.util.LineDiff
import com.aharou.feature.agent.data.local.dao.CheckpointDao
import com.aharou.feature.agent.data.local.entity.CheckpointEntity
import com.aharou.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.sha256Fingerprint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
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
    class TargetUnavailable : IOException("Checkpoint or undo target is no longer available")

    data class RestoreResult(val restoredCount: Int, val conflicts: List<String>, val failures: List<String> = emptyList())

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
        val checkpointId: String,
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
     * 还原前的现场会另存一份，供 [undoRestoreTo] 反悔。
     */
    suspend fun restoreCodeToCheckpoint(sessionId: String, targetCheckpointId: String): RestoreResult =
        restoreLock(sessionId).withLock { restoreBoundCode(sessionId, targetCheckpointId) }

    private suspend fun restoreBoundCode(
        sessionId: String,
        targetCheckpointId: String
    ): RestoreResult = withContext(Dispatchers.IO) {
        val chains = snapshotChains(sessionId, targetCheckpointId)
        val conflicts = mutableListOf<String>()
        val failures = mutableListOf<String>()
        var restored = 0
        val journal = UndoJournal(sessionId, KIND_RESTORE)
        FileLogger.i(TAG, "还原开始: session=$sessionId target=$targetCheckpointId 涉及 ${chains.size} 个文件")
        for ((key, chain) in chains) {
            val target = chain.last()
            val path = target.snapshot.filePath
            try {
                fileLocks.withLock(key) {
                    val plan = planFile(key, chain)
                    if (plan == null) {
                        conflicts.add(path)
                    } else if (plan.applies) {
                        val applied = journal.write(requireNotNull(target.access), requireNotNull(target.executionId), key, path, plan.before, plan.after)
                        if (applied) restored++ else conflicts.add(path)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failures.add(path)
                FileLogger.w(TAG, "还原文件失败: $path", e)
            }
        }
        journal.trimHistory()
        FileLogger.i(TAG, "还原结束: session=$sessionId 共处理 $restored 个文件，冲突 ${conflicts.size} 个，失败 ${failures.size} 个")
        RestoreResult(restored, conflicts, failures)
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
        val chains = snapshotChains(sessionId, targetCheckpointId)
        val changed = ArrayList<RewindFileDiff>()
        val unchanged = ArrayList<RewindFileDiff>()
        val conflicts = ArrayList<RewindFileDiff>()
        for ((key, chain) in chains.entries.take(MAX_PREVIEW_FILE_COUNT)) {
            val entry = chain.last().snapshot
            val plan = try { fileLocks.withLock(key) { planFile(key, chain) } } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            if (plan == null) {
                conflicts.add(RewindFileDiff(entry.filePath, entry.changeType, RewindFileState.CONFLICT, 0, 0, emptyList(), false))
                continue
            }
            if (!plan.applies) continue
            val before = plan.before?.toString(Charsets.UTF_8).orEmpty()
            val after = plan.after?.toString(Charsets.UTF_8).orEmpty()
            if (plan.before.contentEquals(plan.after)) {
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
            checkpointId = targetCheckpointId,
            files = changed + unchanged + conflicts,
            restoreFileCount = changed.size + unchanged.size,
            changedFileCount = changed.size,
            conflictCount = conflicts.size,
            truncated = chains.size > MAX_PREVIEW_FILE_COUNT
        )
    }

    private data class SnapshotStep(val executionId: String?, val snapshot: CheckpointFileSnapshotEntity, val access: FileAccessProvider?)
    private data class FilePlan(val before: ByteArray?, val after: ByteArray?, val applies: Boolean)

    private suspend fun snapshotChains(sessionId: String, targetId: String): Map<String, List<SnapshotStep>> {
        val checkpoints = checkpointDao.getCheckpointsForSession(sessionId)
        val index = checkpoints.indexOfFirst { it.id == targetId }
        if (index < 0) throw TargetUnavailable()
        val chains = linkedMapOf<String, MutableList<SnapshotStep>>()
        val unbound = mutableListOf<SnapshotStep>()
        for (cp in checkpoints.subList(index, checkpoints.size).asReversed()) {
            val snapshots = checkpointDao.getFileSnapshotsForCheckpoint(cp.id)
            if (snapshots.isEmpty()) continue
            val access = try { accessFor(cp.id, sessionId) } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            for (snapshot in snapshots) {
                val step = SnapshotStep(cp.executionId, snapshot, access)
                val key = snapshot.resourceKey
                if (key == null) unbound.add(step) else chains.getOrPut(key) { mutableListOf() }.add(step)
            }
        }
        // 无资源标识的旧快照不能授权写入，也不能让同名的已登记快照绕过它。
        if (unbound.isNotEmpty()) {
            val byPath = chains.values.flatMap { chain ->
                chain.map { it.snapshot.filePath }.distinct().map { path -> path to chain }
            }.groupBy({ it.first }, { it.second })
            for (step in unbound) {
                val named = byPath[step.snapshot.filePath].orEmpty()
                if (named.isEmpty()) chains.getOrPut("unbound\u0000${step.snapshot.filePath}") { mutableListOf() }.add(step)
                else named.forEach { it.add(0, step) }
            }
        }
        return chains
    }

    private fun planFile(key: String, chain: List<SnapshotStep>): FilePlan? {
        val newest = chain.first()
        val access = newest.access ?: return null
        if (chain.any { it.snapshot.resourceKey != key || it.access?.resourceKey(it.snapshot.filePath) != key }) return null
        val path = newest.snapshot.filePath
        val before = if (access.exists(path)) access.copyToLocal(path).readBytes() else null
        var current = before
        var applies = false
        for ((_, snapshot) in chain) {
            val original = when (snapshot.changeType) {
                "MODIFY" -> File(baseCheckpointDir, snapshot.snapshotRelativePath).takeIf { it.isFile }?.readBytes() ?: return null
                "CREATE" -> null
                else -> return null
            }
            if (!checkpointCanRestore(snapshot.changeType, current, original, snapshot.writtenHash)) return null
            if (current != null || original != null) applies = true
            current = original
        }
        return FilePlan(before, current, applies)
    }

    /** 列出现场时间线，新的在前；界面据此让用户挑一个时间节点撤销。 */
    suspend fun listUndoRecords(sessionId: String): List<UndoRecordInfo> = restoreLock(sessionId).withLock {
        withContext(Dispatchers.IO) {
            undoRecords(sessionId).mapNotNull { file ->
                val record = readUndo(file)?.takeIf { it.files.isNotEmpty() } ?: return@mapNotNull null
                UndoRecordInfo(file.name, record.kind, record.createdAt, record.files.size)
            }
        }
    }

    suspend fun undoRestoreTo(sessionId: String, recordId: String): RestoreResult =
        restoreLock(sessionId).withLock { undoBoundRestore(sessionId, recordId) }

    private suspend fun undoBoundRestore(sessionId: String, recordId: String): RestoreResult = withContext(Dispatchers.IO) {
        val target = undoRecords(sessionId).firstOrNull { it.name == recordId } ?: throw TargetUnavailable()
        val record = readUndo(target)?.takeIf { it.files.isNotEmpty() } ?: throw TargetUnavailable()
        var count = 0
        val remaining = mutableListOf<UndoEntry>()
        val conflicts = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val journal = UndoJournal(sessionId, KIND_UNDO)
        for (entry in record.files) {
            try {
                val binding = executions.validate(executions.getExecution(entry.executionId ?: throw IOException("Undo execution binding is unknown")))
                val access = fileAccess.forExecution(binding).forStrictRead()
                val applied = fileLocks.withLock(entry.resourceKey) {
                    val key = access.resourceKey(entry.filePath)
                    if (entry.resourceKey != key) return@withLock false
                    val current = if (access.exists(entry.filePath)) access.copyToLocal(entry.filePath).readBytes() else null
                    if (current?.let { sha256Fingerprint(it) } != entry.expectedHash) return@withLock false
                    val restored = if (entry.existed) entry.bytes?.let { java.util.Base64.getDecoder().decode(it) }
                        ?: throw IOException("Undo snapshot is incomplete") else null
                    journal.write(access, binding.id, key, entry.filePath, current, restored)
                }
                if (applied) count++ else {
                    conflicts.add(entry.filePath)
                    remaining.add(entry)
                    FileLogger.w(TAG, "撤销恢复跳过冲突文件: ${entry.filePath}")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                remaining.add(entry)
                failures.add(entry.filePath)
                FileLogger.w(TAG, "撤销恢复失败: ${entry.filePath}", e)
            }
        }
        if (remaining.isEmpty()) Files.delete(target.toPath())
        else if (remaining.size != record.files.size) writeUndoRecord(target, record.copy(files = remaining))
        journal.trimHistory(target.takeIf { remaining.isNotEmpty() })
        FileLogger.i(TAG, "已撤销到现场 ${target.name}: session=$sessionId 处理 $count 个文件，跳过 ${conflicts.size} 个，失败 ${failures.size} 个，剩余备份节点 ${undoRecords(sessionId).size} 个")
        RestoreResult(count, conflicts, failures)
    }

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

    private inner class UndoJournal(private val sessionId: String, private val kind: String) {
        private val entries = mutableListOf<UndoEntry>()
        private var file: File? = null

        fun write(access: FileAccessProvider, executionId: String, key: String, path: String, before: ByteArray?, after: ByteArray?): Boolean {
            val beforeHash = before?.let { sha256Fingerprint(it) }
            val afterHash = after?.let { sha256Fingerprint(it) }
            fun matchesBefore() = access.resourceKey(path) == key &&
                (if (before == null) !access.exists(path) else access.fingerprint(path) == beforeHash)
            val entry = UndoEntry(path, null, before != null, executionId, key, afterHash, before?.let { java.util.Base64.getEncoder().encodeToString(it) })
            save(entries + entry)
            val applied = try {
                if (!matchesBefore()) false else {
                    if (after == null) access.delete(path) else access.writeBytes(path, after)
                    true
                }
            } catch (e: Exception) {
                // 连接中断时写入结果可能未知；只有能确认未变才撤掉这条写前记录。
                if (e !is CancellationException && runCatching { matchesBefore() }.getOrDefault(false)) save(entries) else entries.add(entry)
                throw e
            }
            if (!applied) {
                save(entries)
                return false
            }
            entries.add(entry)
            fileVersions.recordExternal(key, afterHash ?: "missing")
            return true
        }

        private fun save(snapshot: List<UndoEntry>) {
            if (snapshot.isEmpty()) {
                file?.let { Files.deleteIfExists(it.toPath()) }
                file = null
                return
            }
            val now = System.currentTimeMillis()
            val target = file ?: File(undoDir(sessionId).apply { mkdirs() }, "$now-${UUID.randomUUID().toString().take(6)}.json")
            writeUndoRecord(target, UndoRecord(kind, now, snapshot))
            file = target
        }

        fun trimHistory(remainingTarget: File? = null) {
            val written = file ?: return
            val keep = setOfNotNull(written, remainingTarget)
            undoRecords(sessionId).filterNot { it in keep }.drop(MAX_UNDO_RECORDS - keep.size).forEach { it.delete() }
        }
    }

    private fun writeUndoRecord(target: File, record: UndoRecord) {
        val tmp = File.createTempFile(".undo-", ".tmp", target.parentFile)
        try {
            tmp.outputStream().use { out ->
                out.write(Json.encodeToString(record).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
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
