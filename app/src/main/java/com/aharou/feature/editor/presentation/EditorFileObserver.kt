package com.aharou.feature.editor.presentation

import com.aharou.core.util.FileLogger
import com.aharou.core.watch.FileChangeHub
import com.aharou.core.watch.WatchFilter
import com.aharou.feature.agent.domain.container.ContainerProfileResolver
import com.aharou.feature.agent.domain.tool.FileVersionRegistry
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription

class EditorFileObserver @Inject constructor(
    private val hub: FileChangeHub,
    private val versions: FileVersionRegistry,
    private val profiles: ContainerProfileResolver,
    private val paths: WorkspacePathMapper
) {
    fun changes(root: String, path: String, access: FileAccessProvider): Flow<Unit> = flow {
        val key = access.resourceKey(path)
        val writes = versions.changes.onSubscription { emit(key) }.filter { it == key }.map { }
        val local = if (profiles.forWorkspace(root).mode == ExecutionMode.REMOTE_SSH) emptyFlow() else flow watch@ {
            val file = paths.resolveFile(path, root).canonicalFile
            val parent = file.parentFile ?: return@watch
            emitAll(hub.watchHostDir(parent, filter = WatchFilter(followGitignore = false))
                .filter { batch -> batch.truncated || batch.changes.any { it.hostPath == file.path || it.hostPath == parent.path } }
                .map { })
        }.catch { FileLogger.w("EditorFileObserver", "文件监听失败: $path", it) }
        emitAll(merge(writes, local))
    }.catch {
        FileLogger.w("EditorFileObserver", "无法监听文件: $path", it)
        emit(Unit)
    }.flowOn(Dispatchers.IO).conflate()
}
