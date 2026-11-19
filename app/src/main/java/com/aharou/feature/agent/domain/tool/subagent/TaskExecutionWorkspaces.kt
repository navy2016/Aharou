package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.execution.ExecutionBinding
import com.aharou.feature.agent.domain.execution.SessionExecutionRepository
import com.aharou.feature.agent.domain.execution.WorktreeManager
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.subagent.SubAgentEventBus
import com.aharou.feature.workspace.domain.FileAccessProvider
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class TaskExecutionWorkspaces @Inject constructor(
    private val executions: SessionExecutionRepository,
    private val worktrees: WorktreeManager,
    private val events: SubAgentEventBus,
    private val files: FileAccessProvider
) {
    private suspend fun parent(context: AgentContext): ExecutionBinding = context.executionBinding
        ?: executions.resolve(requireNotNull(context.sessionId), context.projectRoot)

    private fun isolation(args: Map<String, JsonElement>, context: AgentContext): String {
        val value = (args["isolation"] as? JsonPrimitive)?.contentOrNull ?: ExecutionBinding.SHARED
        ExecutionBinding.validateIsolation(value)
        if (value == ExecutionBinding.WORKTREE && context.mode == AgentMode.PLAN) throw IOException("Worktree preparation requires a writable mode")
        if (!events.enabled) throw IOException("Subagents are disabled")
        return value
    }

    suspend fun child(sessionId: String, args: Map<String, JsonElement>, context: AgentContext): WorktreeManager.Prepared {
        val selected = isolation(args, context)
        val parent = parent(context)
        val prepared = if (selected == ExecutionBinding.WORKTREE) worktrees.prepare(sessionId, parent)
            else WorktreeManager.Prepared(parent, context.executionParentDirty).also { executions.attach(sessionId, parent) }
        try {
            if (!events.enabled) throw IOException("Subagents were disabled while preparing the workspace")
            executions.validate(prepared.binding)
            return prepared
        } catch (error: Exception) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { executions.failed(sessionId, prepared.binding) }
            throw error
        }
    }

    suspend fun pipeline(args: Map<String, JsonElement>, context: AgentContext): AgentContext {
        if (isolation(args, context) == ExecutionBinding.SHARED) return context
        if (events.isFull) throw IOException("Subagent concurrency limit reached")
        val owner = "pipeline:${UUID.randomUUID()}"
        val prepared = worktrees.prepare(owner, parent(context))
        if (!events.enabled) {
            executions.failed(owner, prepared.binding)
            throw IOException("Subagents were disabled while preparing the workspace")
        }
        return context.copy(executionBinding = prepared.binding, executionParentDirty = prepared.parentDirty)
    }

    fun resources(paths: Set<String>, binding: ExecutionBinding): Set<String> {
        val access = files.forExecution(binding).forStrictRead()
        return paths.mapTo(linkedSetOf()) { access.resourceKey(if (it == "*") "." else it) }
    }
}
