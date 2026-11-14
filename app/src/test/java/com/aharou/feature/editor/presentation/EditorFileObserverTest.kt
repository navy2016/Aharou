package com.aharou.feature.editor.presentation

import android.app.Application
import com.aharou.core.watch.ChangeKind
import com.aharou.core.watch.ChangeRoot
import com.aharou.core.watch.FileChange
import com.aharou.core.watch.FileChangeBatch
import com.aharou.core.watch.FileChangeHub
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.ContainerProfileResolver
import com.aharou.feature.agent.domain.notification.AgentNotificationCenter
import com.aharou.feature.agent.domain.tool.FileVersionRegistry
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EditorFileObserverTest {
    @get:Rule val temporary = TemporaryFolder()
    private val hub = mockk<FileChangeHub>()
    private val profiles = mockk<ContainerProfileResolver>()
    private val paths = mockk<WorkspacePathMapper>()
    private val access = mockk<FileAccessProvider>()
    private val versions = FileVersionRegistry(AgentNotificationCenter())
    private val observer = EditorFileObserver(hub, versions, profiles, paths)
    private val path = "~/workspace/a.txt"

    @Test
    fun remoteSubscriptionChecksInitiallyAndAfterMatchingToolWrites() = runTest {
        every { access.resourceKey(path) } returns "remote-resource"
        every { profiles.forWorkspace("/one") } returns ContainerProfile.BUILTIN_ALPINE.copy(mode = ExecutionMode.REMOTE_SSH)
        val signals = Channel<Unit>(Channel.UNLIMITED)
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observer.changes("/one", path, access).collect { signals.send(it) }
        }
        signals.receive()
        versions.recordExternal("other-resource", "ignored")
        versions.recordWrite("agent", "remote-resource", "new", path)
        signals.receive()
        job.cancelAndJoin()
        assertTrue(signals.tryReceive().isFailure)
        verify { hub wasNot Called; paths wasNot Called }
    }

    @Test
    fun localChangesWatchResolvedParentAndFilterUnrelatedFiles() = runTest {
        val file = temporary.newFile("target.txt").canonicalFile
        val batches = MutableSharedFlow<FileChangeBatch>(extraBufferCapacity = 4)
        every { access.resourceKey(path) } returns "local-resource"
        every { profiles.forWorkspace("/one") } returns ContainerProfile.BUILTIN_ALPINE
        every { paths.resolveFile(path, "/one", true) } returns file
        every { hub.watchHostDir(any(), any(), any(), any(), any(), any(), any()) } returns batches
        val signals = Channel<Unit>(Channel.UNLIMITED)
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observer.changes("/one", path, access).collect { signals.send(it) }
        }
        signals.receive()
        batches.subscriptionCount.first { it > 0 }
        batches.emit(FileChangeBatch(listOf(FileChange(ChangeRoot.OTHER, file.path + ".other", path, ChangeKind.MODIFIED))))
        batches.emit(FileChangeBatch(listOf(FileChange(ChangeRoot.OTHER, file.path, path, ChangeKind.MODIFIED))))
        signals.receive()
        job.cancelAndJoin()
        assertTrue(signals.tryReceive().isFailure)
        verify(exactly = 1) {
            hub.watchHostDir(file.parentFile!!, null, ChangeRoot.OTHER, false,
                match { !it.followGitignore }, true, FileChangeHub.DEFAULT_BATCH_WINDOW_MS)
        }
    }

    @Test
    fun truncatedLocalBatchStillRequestsARecheck() = runTest {
        val file = temporary.newFile().canonicalFile
        val batches = MutableSharedFlow<FileChangeBatch>(extraBufferCapacity = 1)
        every { access.resourceKey(path) } returns "key"
        every { profiles.forWorkspace("/one") } returns ContainerProfile.BUILTIN_ALPINE
        every { paths.resolveFile(path, "/one", true) } returns file
        every { hub.watchHostDir(any(), any(), any(), any(), any(), any(), any()) } returns batches
        val signals = Channel<Unit>(Channel.UNLIMITED)
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observer.changes("/one", path, access).collect { signals.send(it) }
        }
        signals.receive()
        batches.subscriptionCount.first { it > 0 }
        batches.emit(FileChangeBatch(emptyList(), truncated = true))
        signals.receive()
        job.cancelAndJoin()
    }

    @Test
    fun unavailableResourceStillRequestsOneCheckWithoutObservingTheWrongBackend() = runTest {
        every { access.resourceKey(path) } throws java.io.IOException("offline")
        var checks = 0
        observer.changes("/one", path, access).collect { checks++ }
        assertEquals(1, checks)
        verify { hub wasNot Called; paths wasNot Called; profiles wasNot Called }
    }
}
