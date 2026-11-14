package com.aharou.feature.agent.domain.execution

import com.aharou.feature.agent.domain.container.ContainerProfile
import java.io.IOException

fun executionMounts(binding: ExecutionBinding?, profile: ContainerProfile): List<String> {
    if (binding?.isWorktree != true) return emptyList()
    if (profile.extraBindings.any { it.substringAfter(':').trimEnd('/') == "/root/workspace" }) {
        throw IOException("Container mount overrides the isolated workspace")
    }
    val paths = listOfNotNull(binding.mountRoot, binding.executionRoot, binding.gitRepositoryRoot, binding.gitCommonDir).distinct()
    return paths.flatMap { path ->
        if (!path.startsWith('/') || path.contains(':') || path.contains('\u0000')) throw IOException("Invalid Git metadata mount path")
        listOf("-b", "$path:$path")
    }
}
