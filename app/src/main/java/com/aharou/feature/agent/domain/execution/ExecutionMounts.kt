package com.aharou.feature.agent.domain.execution

import com.aharou.feature.agent.data.local.entity.SessionExecutionEntity
import com.aharou.feature.agent.domain.container.ContainerProfile
import java.io.IOException

internal fun workspaceProotArgs(projectPath: String?, binding: ExecutionBinding?, profile: ContainerProfile): List<String> = buildList {
    if (projectPath != null) {
        if (binding?.isWorktree == true && binding.preparationState == SessionExecutionEntity.PREPARING) {
            // Git 会把创建时可见的绝对路径写进登记；/root/workspace 在下一次执行时会改指子树。
            addAll(listOf("-w", projectPath))
        } else {
            addAll(listOf("-b", "$projectPath:/root/workspace", "-w", "/root/workspace"))
        }
    }
    if (binding?.isWorktree != true) return@buildList
    if (profile.extraBindings.any { it.substringAfter(':').trimEnd('/') == "/root/workspace" }) {
        throw IOException("Container mount overrides the isolated workspace")
    }
    val paths = listOfNotNull(binding.mountRoot, binding.executionRoot, binding.gitRepositoryRoot, binding.gitCommonDir).distinct()
    for (path in paths) {
        if (!path.startsWith('/') || path.contains(':') || path.contains('\u0000')) throw IOException("Invalid Git metadata mount path")
        addAll(listOf("-b", "$path:$path"))
    }
}
