package com.aharou.feature.agent.domain.execution

import com.aharou.feature.agent.domain.container.ContainerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionBindingTest {
    private val binding = ExecutionBinding("id", "/project", "/worktrees/id", "profile", "LOCAL_PROOT",
        isolation = ExecutionBinding.WORKTREE, gitRepositoryRoot = "/project", gitCommonDir = "/project/.git", mountRoot = "/worktrees")

    @Test fun worktreeArgumentsResolveOnlyWorkspaceAliases() {
        assertEquals("/worktrees/id/src/a.kt", binding.workspaceArgument("~/workspace/src/a.kt", "/root"))
        assertEquals("/worktrees/id", binding.workspaceArgument("/root/workspace", "/root"))
        assertEquals("/etc/hosts", binding.workspaceArgument("/etc/hosts", "/root"))
        assertEquals("~/workspace-other/a", binding.workspaceArgument("~/workspace-other/a", "/root"))
    }

    @Test fun sharedLocalArgumentsKeepTheContainerAlias() {
        assertEquals("~/workspace/a", binding.copy(isolation = "shared").workspaceArgument("~/workspace/a", "/root"))
    }

    @Test fun remoteArgumentsUseTheRealBoundDirectory() {
        assertEquals("/worktrees/id/a", binding.copy(mode = "REMOTE_SSH", isolation = "shared")
            .workspaceArgument("/home/user/workspace/a", "/home/user"))
    }

    @Test fun stableMountsIncludeGitCommonDirectoryAndWorktreePool() {
        val mounts = executionMounts(binding, ContainerProfile.BUILTIN_ALPINE)
        assertTrue(mounts.contains("/project/.git:/project/.git"))
        assertTrue(mounts.contains("/worktrees:/worktrees"))
        assertTrue(mounts.contains("/worktrees/id:/worktrees/id"))
    }

    @Test(expected = java.io.IOException::class)
    fun aProfileCannotOverrideTheIsolatedWorkspaceMount() {
        executionMounts(binding, ContainerProfile.BUILTIN_ALPINE.copy(extraBindings = listOf("/other:/root/workspace")))
    }

    @Test fun worktreeGitEnvironmentKeepsExistingConfigurationAndAddsScopedCredentials() {
        val environment = mapOf("GIT_CONFIG_COUNT" to "1", "GIT_CONFIG_KEY_0" to "safe.directory", "GIT_CONFIG_VALUE_0" to "*")
        val result = executionGitEnvironment(environment, binding)
        assertEquals("2", result["GIT_CONFIG_COUNT"])
        assertEquals("safe.directory", result["GIT_CONFIG_KEY_0"])
        assertEquals("includeIf.gitdir:/project/.git/worktrees/.path", result["GIT_CONFIG_KEY_1"])
        assertEquals("/root/.aharou/gitconfig.credential", result["GIT_CONFIG_VALUE_1"])
    }

    @Test fun sharedExecutionDoesNotBroadenGitCredentialScope() {
        val environment = mapOf("GIT_CONFIG_COUNT" to "1")
        assertEquals(environment, executionGitEnvironment(environment, binding.copy(isolation = "shared")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedIsolationDoesNotBecomeShared() {
        ExecutionBinding.validateIsolation("unknown")
    }
}
