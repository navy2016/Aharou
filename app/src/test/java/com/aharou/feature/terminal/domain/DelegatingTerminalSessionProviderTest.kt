package com.aharou.feature.terminal.domain

import com.aharou.feature.agent.domain.execution.ExecutionBinding
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegatingTerminalSessionProviderTest {
    private val modeHolder = ExecutionModeHolder()
    private val local = mockk<TerminalSessionManager>(relaxed = true)
    private val remote = mockk<RemoteTerminalSessionManager>(relaxed = true)

    private fun tabInfo(id: String) = TabInfo(id = id, title = id, isBackground = true, running = true, command = "npm run dev")

    private fun newProvider(localTabs: List<TabInfo> = emptyList(), remoteTabs: List<TabInfo> = emptyList()): DelegatingTerminalSessionProvider {
        every { local.runningBackgroundTabs } returns flowOf(localTabs)
        every { remote.runningBackgroundTabs } returns flowOf(remoteTabs)
        every { local.tabFinishedEvents } returns MutableSharedFlow()
        every { remote.tabFinishedEvents } returns MutableSharedFlow()
        return DelegatingTerminalSessionProvider(modeHolder, local, remote)
    }

    @Test
    fun listTabsIncludesBothBackends() {
        val provider = newProvider()
        every { local.listTabs() } returns listOf(tabInfo("term-1"))
        every { remote.listTabs() } returns listOf(tabInfo("ssh-term-1"))
        assertEquals(listOf("term-1", "ssh-term-1"), provider.listTabs().map { it.id })
    }

    @Test
    fun localInputAndOutputRemainBoundAfterSwitchingGlobalMode() {
        val provider = newProvider()
        every { local.sendInput(any(), any(), any()) } returns true
        every { local.getTabOutput("term-1") } returns "hello"
        modeHolder.setMode(ExecutionMode.REMOTE_SSH)
        assertTrue(provider.sendInput("term-1", "ls", true))
        assertEquals("hello", provider.getTabOutput("term-1"))
        verify(exactly = 0) { remote.sendInput(any(), any(), any()) }
        verify(exactly = 0) { remote.getTabOutput(any()) }
    }

    @Test
    fun remoteOutputDoesNotDependOnTheGlobalMode() {
        val provider = newProvider()
        every { remote.getTabOutput("ssh-term-9") } returns "remote-out"
        assertEquals("remote-out", provider.getTabOutput("ssh-term-9"))
        verify(exactly = 0) { local.getTabOutput(any()) }
    }

    @Test
    fun closeUsesTheTabBackendAcrossModeSwitches() {
        val provider = newProvider()
        every { local.closeTab(any()) } returns true
        every { remote.closeTab(any()) } returns true
        provider.closeTab("term-1")
        modeHolder.setMode(ExecutionMode.REMOTE_SSH)
        provider.closeTab("term-2")
        modeHolder.setMode(ExecutionMode.LOCAL_PROOT)
        provider.closeTab("ssh-term-1")
        verify(exactly = 2) { local.closeTab(any()) }
        verify(exactly = 1) { remote.closeTab(any()) }
    }

    @Test
    fun writeBytesUsesTheTabBackend() {
        val provider = newProvider()
        provider.writeBytesToTab("ssh-term-1", 3, 4)
        verify { remote.writeBytesToTab("ssh-term-1", 3, 4) }
    }

    @Test
    fun ordinaryBackgroundCommandUsesTheSelectedMode() = runTest {
        val provider = newProvider()
        coEvery { local.startBackgroundCommand("npm run dev", "dev", true, "sess-1", "/ws", null) } returns "term-5"
        assertEquals("term-5", provider.startBackgroundCommand("npm run dev", "dev", true, "sess-1", "/ws"))
        coVerify(exactly = 0) { remote.startBackgroundCommand(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun executionBindingSelectsRemoteEvenWhenTheGlobalModeIsLocal() = runTest {
        val provider = newProvider()
        val binding = ExecutionBinding("execution", "/project", "/remote/worktree", "ssh", "REMOTE_SSH", "server")
        coEvery { remote.startBackgroundCommand("pwd", null, true, "child", "/remote/worktree", binding) } returns "ssh-term-1"
        assertEquals("ssh-term-1", provider.startBackgroundCommand("pwd", null, true, "child", "/remote/worktree", binding))
        coVerify(exactly = 0) { local.startBackgroundCommand(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun finishedEventsFlowIsStableAcrossModeSwitches() {
        val provider = newProvider()
        val events = provider.tabFinishedEvents
        modeHolder.setMode(ExecutionMode.REMOTE_SSH)
        assertSame(events, provider.tabFinishedEvents)
    }

    @Test
    fun runningBackgroundTabsMergeBothBackends() = runTest {
        val provider = newProvider(listOf(tabInfo("term-1")), listOf(tabInfo("ssh-term-1")))
        assertEquals(listOf("term-1", "ssh-term-1"), provider.runningBackgroundTabs.first().map { it.id })
    }
}
