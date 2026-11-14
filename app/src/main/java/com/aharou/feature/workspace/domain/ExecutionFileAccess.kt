package com.aharou.feature.workspace.domain

import com.aharou.feature.agent.domain.model.AgentContext

fun FileAccessProvider.forAgent(context: AgentContext): FileAccessProvider =
    context.executionBinding?.let { forExecution(it) } ?: forWorkspace(context.projectRoot)
