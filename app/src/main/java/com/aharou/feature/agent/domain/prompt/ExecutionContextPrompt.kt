package com.aharou.feature.agent.domain.prompt

import com.aharou.feature.agent.domain.model.AgentContext

internal fun executionContextPrompt(context: AgentContext, instructions: String): String = buildString {
    val binding = context.executionBinding
    append("\n- 项目归属路径: ").append(context.projectRoot)
    append("\n- 实际执行目录: ").append(context.executionRoot)
    if (binding?.isWorktree == true) {
        append("\n- 隔离方式: worktree")
        append("\n- 执行分支: ").append(binding.branch)
        append("\n- 基准提交: ").append(binding.baseCommit)
        append('\n').append(instructions.trim())
    } else {
        append("\n说明：文件与命令按本会话执行绑定定位；不要根据界面当前选中的项目改写执行目录。")
    }
}
