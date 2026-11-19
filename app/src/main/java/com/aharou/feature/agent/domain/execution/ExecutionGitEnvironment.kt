package com.aharou.feature.agent.domain.execution

import java.io.IOException

fun executionGitEnvironment(environment: Map<String, String>, binding: ExecutionBinding?): Map<String, String> {
    if (binding?.isWorktree != true) return environment
    val count = environment["GIT_CONFIG_COUNT"]?.let { it.toIntOrNull() ?: throw IOException("Invalid Git configuration count") } ?: 0
    if (count < 0) throw IOException("Invalid Git configuration count")
    val common = binding.gitCommonDir ?: throw IOException("Git worktree metadata is missing")
    return environment + mapOf("GIT_CONFIG_COUNT" to (count + 1).toString(),
        "GIT_CONFIG_KEY_$count" to "includeIf.gitdir:${common.trimEnd('/')}/worktrees/.path",
        "GIT_CONFIG_VALUE_$count" to "/root/.aharou/gitconfig.credential")
}
