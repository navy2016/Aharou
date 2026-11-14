package com.aharou.feature.agent.domain.execution

import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.dao.SessionExecutionDao
import com.aharou.feature.agent.data.local.entity.SessionExecutionEntity
import com.aharou.feature.agent.domain.container.ActiveRemoteConnectionResolver
import com.aharou.feature.agent.domain.container.ContainerProfileResolver
import com.aharou.feature.settings.data.repository.ExecutionMode
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class SessionExecutionRepository @Inject constructor(
    private val dao: SessionExecutionDao,
    private val sessions: ChatSessionDao,
    private val profiles: ContainerProfileResolver,
    private val connections: ActiveRemoteConnectionResolver,
    private val remoteConnection: com.aharou.feature.agent.domain.container.RemoteSshConnection,
    private val files: dagger.Lazy<com.aharou.feature.workspace.domain.FileAccessProvider>,
    private val leases: com.aharou.feature.agent.domain.subagent.SubAgentWriteLease
) {
    private val lock = Mutex()

    fun observe(sessionId: String) = dao.observe(sessionId).map { entity ->
        val binding = try { entity?.binding() ?: resolve(sessionId) } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
        if (binding != null) try { profiles.bindExecution(binding) } catch (error: Exception) {
            if (error is CancellationException) throw error
            com.aharou.core.util.FileLogger.w("SessionExecution", "执行环境不可用: ${binding.executionRoot}", error)
        }
        binding
    }

    suspend fun resolve(sessionId: String, requestedRoot: String = ""): ExecutionBinding = lock.withLock {
        val session = sessions.getById(sessionId) ?: throw IOException("Session no longer exists")
        dao.get(sessionId)?.let { entity ->
            if (session.workspacePath.isNotBlank() && session.workspacePath != entity.projectRoot) throw IOException("Execution project path changed; start a new task")
            if (entity.state != SessionExecutionEntity.READY) throw IOException("Execution workspace is not ready: ${entity.state}")
            val binding = validate(entity.binding())
            entity.writePaths?.let { serialized ->
                val paths = Json.decodeFromString<Set<String>>(serialized)
                val access = files.get().forExecution(binding)
                val resources = paths.mapTo(linkedSetOf()) { access.resourceKey(if (it == "*") "." else it) }
                leases.register(sessionId, paths, resources)
            }
            return@withLock binding
        }
        if (session.executionBindingRequired) throw IOException("Execution preparation is missing; the original workspace was not used")
        val root = session.workspacePath.ifBlank { requestedRoot }
        if (root.isBlank()) throw IOException("No workspace is bound to this session")
        val profile = profiles.workspaceProfile(root)
        val remote = if (profile.mode == ExecutionMode.REMOTE_SSH) connections.configForProfile(profile.id)
            ?: throw IOException("SSH profile is not configured") else null
        val executionRoot = remote?.let {
            val base = it.remoteWorkspacePath.trimEnd('/')
            if (root == base || root.startsWith("$base/")) root else "$base/${root.trimEnd('/').substringAfterLast('/')}"
        } ?: root
        val binding = ExecutionBinding(UUID.randomUUID().toString(), root, executionRoot, profile.id,
            profile.mode.name, remote?.executionIdentity(), projectExecutionRoot = executionRoot)
        check(dao.insert(SessionExecutionEntity.from(sessionId, binding)) >= 0)
        validate(binding)
    }

    suspend fun validate(binding: ExecutionBinding): ExecutionBinding {
        if (!binding.ready) throw IOException("Execution workspace is still preparing")
        val profile = profiles.bindExecution(binding)
        if (profile.mode == ExecutionMode.REMOTE_SSH) {
            val remote = connections.configForProfile(profile.id) ?: throw IOException("SSH profile is not configured")
            if (remote.executionIdentity() != binding.sshIdentity) throw IOException("SSH execution identity changed; start a new task")
            if (!remoteConnection.isConnected() || remoteConnection.config != remote) remoteConnection.connect(remote)
        }
        if (!files.get().forExecution(binding).forStrictRead().isDirectory("~/workspace")) throw IOException("Execution workspace was removed")
        if (binding.isWorktree) validateWorktree(binding)
        return binding
    }

    private fun validateWorktree(binding: ExecutionBinding) {
        val access = files.get().forExecution(binding).forStrictRead()
        if (!access.isDirectory(binding.executionRoot)) throw IOException("Execution worktree was removed")
        val pointer = access.readFile(binding.executionRoot.trimEnd('/') + "/.git").trim()
        if (!pointer.startsWith("gitdir: ")) throw IOException("Execution worktree Git metadata changed")
        val root = java.nio.file.Paths.get(binding.executionRoot)
        val gitDir = root.resolve(pointer.removePrefix("gitdir: ")).normalize()
        val common = binding.gitCommonDir ?: throw IOException("Execution worktree Git metadata is missing")
        if (!gitDir.toString().startsWith(common.trimEnd('/') + "/worktrees/")) throw IOException("Execution worktree belongs to another repository")
        if (access.readFile(gitDir.resolve("HEAD").toString()).trim() != "ref: refs/heads/${binding.branch}")
            throw IOException("Execution worktree branch changed")
        val relative = access.readFile(gitDir.resolve("commondir").toString()).trim()
        if (access.resourceKey(gitDir.resolve(relative).normalize().toString()) != access.resourceKey(common))
            throw IOException("Execution worktree common directory changed")
    }

    suspend fun attach(sessionId: String, binding: ExecutionBinding, state: String = SessionExecutionEntity.READY) = lock.withLock {
        val existing = dao.get(sessionId)
        if (existing != null) {
            check(existing.binding() == binding && existing.state == state) { "Session execution binding cannot be replaced" }
        } else check(dao.insert(SessionExecutionEntity.from(sessionId, binding, state)) >= 0)
    }

    suspend fun updatePreparation(sessionId: String, binding: ExecutionBinding) = lock.withLock {
        val existing = dao.get(sessionId) ?: throw IOException("Execution preparation was removed")
        check(existing.executionId == binding.id && existing.state == SessionExecutionEntity.PREPARING)
        check(dao.update(SessionExecutionEntity.from(sessionId, binding, SessionExecutionEntity.PREPARING)
            .copy(createdAt = existing.createdAt, writePaths = existing.writePaths)) == 1)
    }

    suspend fun ready(sessionId: String, binding: ExecutionBinding) {
        check(dao.updateState(sessionId, binding.id, SessionExecutionEntity.READY) == 1)
    }

    suspend fun failed(sessionId: String, binding: ExecutionBinding) {
        dao.updateState(sessionId, binding.id, SessionExecutionEntity.FAILED)
    }

    suspend fun get(sessionId: String): ExecutionBinding? = dao.get(sessionId)
        ?.takeIf { it.state == SessionExecutionEntity.READY }?.binding()

    suspend fun getExecution(id: String): ExecutionBinding = dao.getExecution(id)?.binding()
        ?: throw IOException("Execution workspace is unavailable")

    suspend fun saveWritePaths(sessionId: String, paths: Set<String>) = lock.withLock {
        check(dao.updateWritePaths(sessionId, Json.encodeToString(paths)) == 1)
    }

    suspend fun forget(sessionId: String) { dao.delete(sessionId); leases.release(sessionId) }
}
