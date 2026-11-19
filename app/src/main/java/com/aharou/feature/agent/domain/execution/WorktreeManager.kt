package com.aharou.feature.agent.domain.execution

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.core.util.shellQuote
import com.aharou.feature.agent.data.local.entity.SessionExecutionEntity
import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.ContainerProfileResolver
import com.aharou.feature.agent.domain.tool.WorkspaceFileLock
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Singleton
class WorktreeManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val executions: SessionExecutionRepository,
    private val engine: CommandEngine,
    private val profiles: ContainerProfileResolver,
    private val paths: WorkspacePathMapper,
    private val locks: WorkspaceFileLock
) {
    data class Prepared(val binding: ExecutionBinding, val parentDirty: Boolean)

    class PreparationException(val stage: String, cause: Throwable) : IOException(
        "Worktree preparation failed at $stage; the original workspace was not used. ${cause.javaClass.simpleName}: ${cause.message.orEmpty()}", cause)

    suspend fun prepare(ownerId: String, parent: ExecutionBinding): Prepared = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val isLocal = parent.mode != ExecutionMode.REMOTE_SSH.name
        val provisionalPool = if (isLocal) File(context.filesDir, "agent-worktrees").absolutePath
            else parent.executionRoot.trimEnd('/') + "-aharou-worktrees"
        val branch = "aharou/task-$id"
        var binding = parent.copy(id = id, executionRoot = "$provisionalPool/$id", isolation = ExecutionBinding.WORKTREE,
            branch = branch, baseCommit = null, gitRepositoryRoot = null, gitCommonDir = null,
            mountRoot = if (isLocal) provisionalPool else null)
        var stage = "record_preparing"
        try {
            executions.attach(ownerId, binding, SessionExecutionEntity.PREPARING)
            stage = "validate_parent"
            executions.validate(parent)
            stage = "resolve_repository"
            val profile = profiles.forExecution(parent)
            val metadata = command("git rev-parse --show-toplevel && git rev-parse --path-format=absolute --git-common-dir && git rev-parse HEAD", parent)
                .lineSequence().filter { it.isNotBlank() }.toList()
            if (metadata.size != 3 || !metadata[2].matches(Regex("[0-9a-fA-F]{40,64}"))) throw IOException("Cannot resolve repository HEAD")
            stage = "resolve_paths"
            val mapper = if (isLocal) paths.forExecution(parent, profile) else null
            val repo = mapper?.resolveFile(metadata[0], parent.executionRoot)?.canonicalPath ?: metadata[0]
            val common = mapper?.resolveFile(metadata[1], parent.executionRoot)?.canonicalPath ?: metadata[1]
            val pool = if (isLocal) File(context.filesDir, "agent-worktrees").apply { mkdirs() }.canonicalPath
                else repo.trimEnd('/') + "-aharou-worktrees"
            val destination = "$pool/$id"
            binding = binding.copy(executionRoot = destination, baseCommit = metadata[2], gitRepositoryRoot = repo,
                gitCommonDir = common, mountRoot = if (isLocal) pool else null)
            stage = "record_paths"
            executions.updatePreparation(ownerId, binding)
            val preparing = binding.copy(executionRoot = repo, ready = false, preparationState = SessionExecutionEntity.PREPARING)
            val dirty = locks.withLock("git\u0000${parent.sshIdentity.orEmpty()}\u0000$common") {
                stage = "parent_status"
                val dirty = command("git status --porcelain", parent).isNotBlank()
                stage = "create_worktree"
                command("git -C ${shellQuote(repo)} worktree add -b ${shellQuote(branch)} ${shellQuote(destination)} ${shellQuote(metadata[2])}", preparing)
                stage = "verify_head"
                val checkout = command("git rev-parse --path-format=absolute --show-toplevel --git-common-dir HEAD && git symbolic-ref HEAD", binding).lines()
                if (checkout.size != 4) throw IOException("Cannot resolve prepared worktree Git metadata")
                if (checkout[2] != metadata[2]) throw IOException("Prepared worktree HEAD mismatch: actual=${checkout[2]} expected=${metadata[2]}")
                if (checkout[3] != "refs/heads/$branch") throw IOException("Prepared worktree branch mismatch: actual=${checkout[3]} expected=refs/heads/$branch")
                val childMapper = if (isLocal) paths.forExecution(binding, profile) else null
                val actualRoot = childMapper?.resolveFile(checkout[0], destination)?.canonicalPath ?: checkout[0]
                val actualCommon = childMapper?.resolveFile(checkout[1], destination)?.canonicalPath ?: checkout[1]
                if (actualRoot != destination || actualCommon != common)
                    throw IOException("Prepared worktree path mismatch: root=$actualRoot expectedRoot=$destination common=$actualCommon expectedCommon=$common")
                dirty
            }
            stage = "validate_execution"
            executions.validate(binding)
            stage = "record_ready"
            executions.ready(ownerId, binding)
            FileLogger.i("WorktreeManager", "ready owner=$ownerId execution=$id root=${binding.executionRoot} branch=$branch")
            Prepared(binding, dirty)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                try { executions.failed(ownerId, binding) } catch (recordError: Exception) { error.addSuppressed(recordError) }
            }
            if (error is CancellationException) throw error
            FileLogger.e("WorktreeManager", "failed stage=$stage owner=$ownerId execution=$id mode=${binding.mode} profile=${binding.profileId} root=${binding.executionRoot} repository=${binding.gitRepositoryRoot} common=${binding.gitCommonDir} branch=$branch base=${binding.baseCommit}", error)
            throw PreparationException(stage, error)
        }
    }

    private suspend fun command(command: String, binding: ExecutionBinding): String {
        val result = engine.runCommandSyncUnbounded(command, binding.executionRoot, 120_000, binding)
        if (result.exitCode != 0 || result.outputTruncated) throw IOException(result.output.ifBlank { "Git worktree command failed" })
        return result.output.trim()
    }
}
