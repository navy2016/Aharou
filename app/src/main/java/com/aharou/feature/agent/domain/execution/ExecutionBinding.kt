package com.aharou.feature.agent.domain.execution

import com.aharou.feature.agent.domain.container.RemoteConnectionConfig
import kotlinx.serialization.Serializable

@Serializable
data class ExecutionBinding(
    val id: String,
    val projectRoot: String,
    val executionRoot: String,
    val profileId: String,
    val mode: String,
    val sshIdentity: String? = null,
    val isolation: String = SHARED,
    val baseCommit: String? = null,
    val branch: String? = null,
    val gitRepositoryRoot: String? = null,
    val gitCommonDir: String? = null,
    val mountRoot: String? = null,
    val projectExecutionRoot: String = projectRoot,
    val ready: Boolean = true,
    val preparationState: String = "ready"
) {
    val isWorktree: Boolean get() = isolation == WORKTREE

    fun workspaceArgument(path: String, home: String): String {
        if (!isWorktree && mode != "REMOTE_SSH") return path
        val aliases = listOf("~/workspace", home.trimEnd('/') + "/workspace", "/root/workspace")
        val prefix = aliases.firstOrNull { path == it || path.startsWith("$it/") } ?: return path
        return executionRoot.trimEnd('/') + path.removePrefix(prefix)
    }

    companion object {
        const val SHARED = "shared"
        const val WORKTREE = "worktree"

        fun validateIsolation(value: String): String {
            require(value == SHARED || value == WORKTREE) { "isolation must be shared or worktree" }
            return value
        }
    }
}

fun RemoteConnectionConfig.executionIdentity(): String =
    listOf(host.lowercase(), port.toString(), username, remoteWorkspacePath.trimEnd('/'))
        .joinToString("|") { "${it.length}:$it" }
