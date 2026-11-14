package com.aharou.feature.terminal.domain

import com.aharou.feature.agent.domain.execution.ExecutionBinding
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DelegatingTerminalSessionProvider @Inject constructor(
    private val modeHolder: ExecutionModeHolder,
    private val local: TerminalSessionManager,
    private val remote: RemoteTerminalSessionManager
) : TerminalSessionProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun forTab(id: String): TerminalSessionProvider = if (id.startsWith("ssh-term-")) remote else local

    override val tabFinishedEvents: SharedFlow<TabFinishedEvent> =
        merge(local.tabFinishedEvents, remote.tabFinishedEvents).shareIn(scope, SharingStarted.Eagerly, replay = 0)

    override val runningBackgroundTabs: Flow<List<TabInfo>> =
        combine(local.runningBackgroundTabs, remote.runningBackgroundTabs) { a, b -> a + b }.distinctUntilChanged()

    override suspend fun startBackgroundCommand(command: String, title: String?, notify: Boolean,
        sourceSessionId: String?, workspacePath: String?, execution: ExecutionBinding?): String {
        val mode = execution?.mode ?: modeHolder.currentMode().name
        val backend = if (mode == ExecutionMode.REMOTE_SSH.name) remote else local
        return backend.startBackgroundCommand(command, title, notify, sourceSessionId, workspacePath, execution)
    }

    override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean = forTab(id).sendInput(id, input, appendNewline)
    override fun writeToTab(id: String, text: String): Boolean = forTab(id).writeToTab(id, text)
    override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean = forTab(id).writeBytesToTab(id, *bytes)
    override fun getTabOutput(id: String): String? = forTab(id).getTabOutput(id)
    override fun listTabs(): List<TabInfo> = local.listTabs() + remote.listTabs()
    override fun closeTab(id: String): Boolean = forTab(id).closeTab(id)
    override fun closeFinishedAgentTabs(): Int = local.closeFinishedAgentTabs() + remote.closeFinishedAgentTabs()
}
