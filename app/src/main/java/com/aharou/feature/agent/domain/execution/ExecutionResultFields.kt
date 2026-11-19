package com.aharou.feature.agent.domain.execution

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

fun JsonObjectBuilder.putExecution(binding: ExecutionBinding?) {
    binding ?: return
    put("executionId", binding.id)
    put("executionReady", binding.ready)
    put("executionState", binding.preparationState)
    put("isolation", binding.isolation)
    put("projectRoot", binding.projectRoot)
    put("executionRoot", binding.executionRoot)
    binding.branch?.let { put("branch", it) }
    binding.baseCommit?.let { put("baseCommit", it) }
}
