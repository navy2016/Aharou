package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.subagent.WriteLease

internal data class PipelineTask(
    val id: String,
    val prompt: String,
    val description: String,
    val dependsOn: Set<String>,
    val writePaths: Set<String>?,
    val agent: String?,
    val department: String?,
    val agentQuery: String?,
    val model: String?,
    val provider: String?
)

internal object TaskPipelinePlan {
    fun layers(tasks: List<PipelineTask>): List<List<PipelineTask>> {
        val byId = tasks.associateBy { it.id }
        val depth = mutableMapOf<String, Int>()
        val visiting = mutableSetOf<String>()
        fun depthOf(id: String): Int {
            depth[id]?.let { return it }
            if (!visiting.add(id)) throw IllegalStateException("任务「$id」形成依赖环")
            val task = byId.getValue(id)
            val value = if (task.dependsOn.isEmpty()) 0 else task.dependsOn.maxOf { depthOf(it) } + 1
            visiting.remove(id)
            depth[id] = value
            return value
        }
        return tasks.groupBy { depthOf(it.id) }.toSortedMap().values.toList()
    }

    fun overlapping(layer: List<PipelineTask>): Pair<PipelineTask, PipelineTask>? {
        for (i in layer.indices) {
            val a = layer[i].writePaths ?: continue
            for (j in i + 1 until layer.size) {
                val b = layer[j].writePaths ?: continue
                if (WriteLease.overlaps(a, b)) return layer[i] to layer[j]
            }
        }
        return null
    }
}
