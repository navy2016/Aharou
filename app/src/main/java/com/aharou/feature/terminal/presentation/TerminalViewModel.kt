package com.aharou.feature.terminal.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerInitState
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import com.aharou.feature.terminal.domain.RemoteTerminalSessionManager
import com.aharou.feature.terminal.domain.TerminalSessionManager
import com.aharou.feature.terminal.presentation.component.TerminalKeyModifiers
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aharou.feature.terminal.data.repository.TerminalSettings
import com.aharou.feature.terminal.data.repository.TerminalSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 终端页的薄观察层。
 *
 * 会话的所有权在 [TerminalSessionManager]（Singleton），本 ViewModel 只转发 UI 操作并暴露其状态流，
 * **不持有也不销毁任何会话**——这正是「常驻后台」的关键：离开终端页导致本 VM onCleared 时，
 * 会话仍由管理器持有、继续在后台运行，下次回到终端页直接复用。
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val localManager: TerminalSessionManager,
    private val remoteManager: RemoteTerminalSessionManager,
    private val modeHolder: ExecutionModeHolder,
    private val containerEngine: LinuxContainerEngine,
    private val terminalSettingsRepository: TerminalSettingsRepository,
    private val executions: com.aharou.feature.agent.domain.execution.SessionExecutionRepository
) : ViewModel() {

    private companion object { const val TAG = "TerminalViewModel" }

    private val execution = MutableStateFlow<com.aharou.feature.agent.domain.execution.ExecutionBinding?>(null)
    private var boundOnce = false
    private var preparationVersion = 0L
    private fun isRemote() = (execution.value?.mode ?: modeHolder.currentMode().name) == ExecutionMode.REMOTE_SSH.name

    fun bindExecution(binding: com.aharou.feature.agent.domain.execution.ExecutionBinding?) {
        if (boundOnce && execution.value == binding) return
        execution.value = binding
        boundOnce = true
        prepare()
    }

    /** 当前是否远程 SSH 模式（UI 据此隐藏本地容器专属入口，如环境工具）。 */
    val isRemoteMode: Boolean get() = isRemote()

    /** 容器准备阶段的整体状态：仅用于首个标签创建前的 Loading/Error 提示。 */
    sealed interface PrepareState {
        data object Loading : PrepareState
        data object Ready : PrepareState
        data class Error(val message: String) : PrepareState
    }

    private val _prepareState = MutableStateFlow<PrepareState>(PrepareState.Loading)
    val prepareState: StateFlow<PrepareState> = _prepareState.asStateFlow()

    /** 正在新建标签：SSH 模式下建立 shell channel 需等服务器响应，UI 据此显示等待动画并拦截重复点击。 */
    private val _creatingTab = MutableStateFlow(false)
    val creatingTab: StateFlow<Boolean> = _creatingTab.asStateFlow()

    /** 新建标签失败的原因；UI 弹完提示后调 [clearTabError] 清掉，避免重组时重复弹。 */
    private val _tabError = MutableStateFlow<String?>(null)
    val tabError: StateFlow<String?> = _tabError.asStateFlow()

    fun clearTabError() {
        _tabError.value = null
    }

    /** 容器初始化实时进度（解压/部署/装包），Loading 阶段用它展示细粒度文案。 */
    val containerInit: StateFlow<ContainerInitState> = containerEngine.initProgress

    val tabs = combine(localManager.tabs, remoteManager.tabs, modeHolder.mode, execution) { local, remote, mode, binding ->
        val selected = if ((binding?.mode ?: mode.name) == ExecutionMode.REMOTE_SSH.name) remote else local
        if (binding == null) selected else selected.filter { it.workspacePath == binding.executionRoot }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeTabId = combine(localManager.activeTabId, remoteManager.activeTabId, tabs, execution, modeHolder.mode) { local, remote, visible, binding, mode ->
        val id = if ((binding?.mode ?: mode.name) == ExecutionMode.REMOTE_SSH.name) remote else local
        if (visible.any { it.id == id }) id else visible.firstOrNull()?.id
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val revision = combine(localManager.revision, remoteManager.revision) { a, b -> a + b }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** 额外按键行驱动的虚拟修饰键，供 TerminalView 读取。 */
    val modifiers = TerminalKeyModifiers()

    val terminalSettings: StateFlow<TerminalSettings> = terminalSettingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TerminalSettings())

    fun setTheme(themeId: String) {
        viewModelScope.launch { terminalSettingsRepository.setThemeId(themeId) }
    }

    fun setFontSize(sizeSp: Int) {
        viewModelScope.launch { terminalSettingsRepository.setFontSizeSp(sizeSp) }
    }

    fun setCursorStyle(style: Int) {
        viewModelScope.launch { terminalSettingsRepository.setCursorStyle(style) }
    }

    fun setFontPath(path: String) {
        viewModelScope.launch { terminalSettingsRepository.setFontPath(path) }
    }

    /** 进入终端页：确保至少有一个标签（首次会解压容器或连 SSH）。 */
    fun prepare() {
        val version = ++preparationVersion
        val binding = execution.value
        viewModelScope.launch {
            _prepareState.value = PrepareState.Loading
            if (binding?.ready == false) {
                if (binding.preparationState != "preparing") _prepareState.value = PrepareState.Error(context.getString(R.string.execution_prepare_failed))
                return@launch
            }
            try {
                if (binding != null) {
                    executions.validate(binding)
                    val existing = (if (isRemote()) remoteManager.tabs.value else localManager.tabs.value)
                        .firstOrNull { it.workspacePath == binding.executionRoot }
                    if (existing != null) activate(existing.id) else {
                        if (isRemote()) remoteManager.createInteractiveTab(binding) else localManager.createInteractiveTab(binding)
                    }
                } else if (isRemote()) remoteManager.ensureInitialTab() else localManager.ensureInitialTab()
                if (version == preparationVersion && binding == execution.value) _prepareState.value = PrepareState.Ready
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "终端准备失败", e)
                if (version == preparationVersion && binding == execution.value)
                    _prepareState.value = PrepareState.Error(e.message ?: context.getString(R.string.terminal_prepare_error_unknown))
            }
        }
    }

    fun newTab() {
        if (_creatingTab.value) return
        _creatingTab.value = true
        viewModelScope.launch {
            try {
                val binding = execution.value
                binding?.let { executions.validate(it) }
                if (isRemote()) remoteManager.createInteractiveTab(binding) else localManager.createInteractiveTab(binding)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "新建标签失败", e)
                _tabError.value = context.getString(
                    R.string.terminal_new_tab_failed,
                    e.message ?: context.getString(R.string.terminal_prepare_error_unknown)
                )
            } finally {
                _creatingTab.value = false
            }
        }
    }

    fun activate(id: String) = if (id.startsWith("ssh-term-")) remoteManager.activate(id) else localManager.activate(id)
    fun closeTab(id: String) = if (id.startsWith("ssh-term-")) remoteManager.closeTab(id) else localManager.closeTab(id)

    fun write(text: String) {
        activeTabId.value?.let { id -> if (id.startsWith("ssh-term-")) remoteManager.writeToTab(id, text) else localManager.writeToTab(id, text) }
    }

    fun writeBytes(vararg bytes: Int) {
        activeTabId.value?.let { id -> if (id.startsWith("ssh-term-")) remoteManager.writeBytesToTab(id, *bytes) else localManager.writeBytesToTab(id, *bytes) }
    }

    // 注意：故意不在 onCleared 里销毁会话——会话归 Singleton 管理器所有，需常驻后台。
}
