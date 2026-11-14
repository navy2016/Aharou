package com.aharou.feature.agent.domain.execution

import android.content.Context
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

    suspend fun prepare(ownerId: String, parent: ExecutionBinding): Prepared = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val isLocal = parent.mode != ExecutionMode.REMOTE_SSH.name
        val provisionalPool = if (isLocal) File(context.filesDir, "agent-worktrees").absolutePath
            else parent.executionRoot.trimEnd('/') + "-aharou-worktrees"
        val branch = "aharou/task-$id"
        var binding = parent.copy(id = id, executionRoot = "$provisionalPool/$id", isolation = ExecutionBinding.WORKTREE,
            branch = branch, baseCommit = null, gitRepositoryRoot = null, gitCommonDir = null,
            mountRoot = if (isLocal) provisionalPool else null)
        executions.attach(ownerId, binding, SessionExecutionEntity.PREPARING)
        try {
            executions.validate(parent)
            val profile = profiles.forExecution(parent)
            val metadata = command("git rev-parse --show-toplevel && git rev-parse --path-format=absolute --git-common-dir && git rev-parse HEAD", parent)
                .lineSequence().filter { it.isNotBlank() }.toList()
            if (metadata.size != 3 || !metadata[2].matches(Regex("[0-9a-fA-F]{40,64}"))) throw IOException("Cannot resolve repository HEAD")
            val mapper = if (isLocal) paths.forExecution(parent, profile) else null
            val repo = mapper?.resolveFile(metadata[0], parent.executionRoot)?.canonicalPath ?: metadata[0]
            val common = mapper?.resolveFile(metadata[1], parent.executionRoot)?.canonicalPath ?: metadata[1]
            val pool = if (isLocal) File(context.filesDir, "agent-worktrees").apply { mkdirs() }.canonicalPath
                else repo.trimEnd('/') + "-aharou-worktrees"
            val destination = "$pool/$id"
            binding = binding.copy(executionRoot = destination, baseCommit = metadata[2], gitRepositoryRoot = repo,
                gitCommonDir = common, mountRoot = if (isLocal) pool else null)
            executions.updatePreparation(ownerId, binding)
            val preparing = binding.copy(executionRoot = parent.executionRoot)
            val dirty = locks.withLock("git\u0000${parent.sshIdentity.orEmpty()}\u0000$common") {
                val dirty = command("git status --porcelain", parent).isNotBlank()
                command("git -C ${shellQuote(repo)} worktree add -b ${shellQuote(branch)} ${shellQuote(destination)} ${shellQuote(metadata[2])}", preparing)
                val actual = command("git -C ${shellQuote(destination)} rev-parse HEAD", preparing).trim()
                if (actual != metadata[2]) throw IOException("Prepared worktree does not match the selected commit")
                dirty
            }
            executions.validate(binding)
            executions.ready(ownerId, binding)
            Prepared(binding, dirty)
        } catch (error: Throwable) {
            withContext(NonCancellable) { executions.failed(ownerId, binding) }
            if (error is CancellationException) throw error
            throw IOException("Worktree preparation failed; the original workspace was not used", error)
        }
    }

    private suspend fun command(command: String, binding: ExecutionBinding): String {
        val result = engine.runCommandSyncUnbounded(command, binding.executionRoot, 120_000, binding)
        if (result.exitCode != 0 || result.outputTruncated) throw IOException(result.output.ifBlank { "Git worktree command failed" })
        return result.output.trim()
    }
}
