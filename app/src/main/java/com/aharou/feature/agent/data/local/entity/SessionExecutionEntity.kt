package com.aharou.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aharou.feature.agent.domain.execution.ExecutionBinding

@Entity(tableName = "session_execution_bindings", indices = [Index(value = ["executionId"])])
data class SessionExecutionEntity(
    @PrimaryKey val sessionId: String,
    val executionId: String,
    val projectRoot: String,
    val executionRoot: String,
    val profileId: String,
    val mode: String,
    val sshIdentity: String?,
    val isolation: String,
    val baseCommit: String?,
    val branch: String?,
    val gitRepositoryRoot: String?,
    val gitCommonDir: String?,
    val mountRoot: String?,
    val projectExecutionRoot: String,
    val state: String,
    val createdAt: Long,
    val writePaths: String? = null
) {
    fun binding(): ExecutionBinding = ExecutionBinding(executionId, projectRoot, executionRoot, profileId,
        mode, sshIdentity, isolation, baseCommit, branch, gitRepositoryRoot, gitCommonDir, mountRoot, projectExecutionRoot, state == READY, state)

    companion object {
        const val PREPARING = "preparing"
        const val READY = "ready"
        const val FAILED = "failed"

        fun from(sessionId: String, binding: ExecutionBinding, state: String = READY): SessionExecutionEntity =
            SessionExecutionEntity(sessionId, binding.id, binding.projectRoot, binding.executionRoot,
                binding.profileId, binding.mode, binding.sshIdentity, binding.isolation, binding.baseCommit,
                binding.branch, binding.gitRepositoryRoot, binding.gitCommonDir, binding.mountRoot, binding.projectExecutionRoot, state, System.currentTimeMillis())
    }
}
