package com.aharou.feature.editor.presentation

import android.app.Application
import android.content.Context
import com.aharou.feature.agent.domain.tool.FileVersionRegistry
import com.aharou.feature.editor.data.EditorSettings
import com.aharou.feature.editor.data.EditorSettingsRepository
import com.aharou.feature.editor.domain.TextMateSetup
import com.aharou.feature.editor.domain.EditorSessionManager
import com.aharou.feature.editor.lsp.EditorLspManager
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.FileSizeLimitExceededException
import com.aharou.feature.workspace.domain.sha256Fingerprint
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CodeEditorViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val files = mockk<FileAccessProvider>(relaxed = true)
    private val first = mockk<FileAccessProvider>(relaxed = true)
    private val second = mockk<FileAccessProvider>(relaxed = true)
    private val identity = mockk<EditorWorkspaceIdentity>(relaxed = true)
    private val versions = FileVersionRegistry(com.aharou.feature.agent.domain.notification.AgentNotificationCenter())
    private val settings = mockk<EditorSettingsRepository>()
    private lateinit var model: CodeEditorViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { files.forWorkspace("/one") } returns first
        every { files.forWorkspace("/two") } returns second
        every { first.forStrictRead() } returns first
        every { second.forStrictRead() } returns second
        every { first.fingerprint(any()) } returns "first"
        every { second.fingerprint(any()) } returns "second"
        every { first.toDisplayPath(any()) } answers { arg(0) }
        every { first.resourceKey(any()) } answers { arg(0) }
        every { second.resourceKey(any()) } answers { arg(0) }
        every { second.toDisplayPath(any()) } answers { arg(0) }
        every { identity.matches(any(), any()) } returns true
        every { settings.settingsFlow } returns MutableStateFlow(EditorSettings())
        mockkObject(TextMateSetup)
        every { TextMateSetup.ensureInitialized(any()) } returns Unit
        every { TextMateSetup.scopeNameFor(any()) } returns null
        model = CodeEditorViewModel(mockk<Context>(relaxed = true), files, settings,
            mockk<EditorLspManager>(relaxed = true), versions, identity, mockk<EditorFileObserver>(relaxed = true))
    }

    @After
    fun tearDown() { unmockkObject(TextMateSetup); Dispatchers.resetMain() }

    private fun readableFile(size: Long): java.io.File {
        val file = temporary.newFile().apply { writeText("full content") }
        every { first.exists(any()) } returns true
        every { first.isFile(any()) } returns true
        every { first.fileSize(any()) } returns size
        every { first.copyToLocal(any(), any()) } returns file
        return file
    }

    @Test
    fun openingEditorDoesNotRefreshAgentBaselineOrChangeLastWriter() = runTest(dispatcher) {
        val path = "~/workspace/a.txt"
        readableFile(12)
        versions.record("agent", path, "old")
        versions.recordWriter(path, "other-agent")
        model.bindWorkspace("/one", "version-read")
        model.load(path)
        model.uiState.first { it is EditorUiState.Success }
        assertEquals("old", versions.get("agent", path))
        assertEquals("other-agent", versions.lastWriter(path))
    }

    @Test
    fun savePreservesAgentBaselineAndRecordsExternalWriter() = runTest(dispatcher) {
        val path = "~/workspace/a.txt"
        versions.record("agent", path, "old")
        model.bindWorkspace("/one", "version-save")
        val request = model.createSaveRequest(path, "user content")!!
        val event = async(UnconfinedTestDispatcher(testScheduler)) { model.saveEvents.first() }
        model.save(request)
        assertEquals(SaveResult.Success(request), event.await())
        assertEquals("old", versions.get("agent", path))
        assertEquals("用户/编辑器", versions.lastWriter(path))
    }

    @Test
    fun failedSavePreservesVersionAndWriter() = runTest(dispatcher) {
        val path = "~/workspace/a.txt"
        versions.record("agent", path, "old")
        versions.recordWriter(path, "other-agent")
        every { first.writeFile(any(), any(), any(), any()) } throws java.io.IOException("fixture")
        model.bindWorkspace("/one", "version-save-error")
        val request = model.createSaveRequest(path, "user content")!!
        val event = async(UnconfinedTestDispatcher(testScheduler)) { model.saveEvents.first() }
        model.save(request)
        assertTrue(event.await() is SaveResult.Error)
        assertEquals("old", versions.get("agent", path))
        assertEquals("other-agent", versions.lastWriter(path))
    }

    @Test fun twentyMiBOpensWithoutConfirmation() = runTest(dispatcher) {
        readableFile(EDITOR_LARGE_FILE_WARNING_BYTES)
        model.bindWorkspace("/one", "limit20")
        model.load("~/workspace/a.txt")
        val state = model.uiState.first { it is EditorUiState.Success }
        assertEquals("full content", (state as EditorUiState.Success).content)
        verify(exactly = 1) { first.copyToLocal("~/workspace/a.txt", EDITOR_LARGE_FILE_WARNING_BYTES) }
    }

    @Test fun twentyMiBPlusOneWaitsForConfirmationWithoutDownloading() = runTest(dispatcher) {
        readableFile(EDITOR_LARGE_FILE_WARNING_BYTES + 1)
        model.bindWorkspace("/one", "limit20plus")
        model.load("~/workspace/a.txt")
        val warning = model.uiState.first { it is EditorUiState.LargeFileWarning } as EditorUiState.LargeFileWarning
        verify(exactly = 0) { first.copyToLocal(any(), any()) }
        model.confirmLargeFile(warning.loadId)
        assertEquals("full content", (model.uiState.first { it is EditorUiState.Success } as EditorUiState.Success).content)
        verify(exactly = 1) { first.copyToLocal("~/workspace/a.txt", EDITOR_MAX_FILE_BYTES) }
    }

    @Test fun oneHundredMiBIsAllowedAfterConfirmation() = runTest(dispatcher) {
        readableFile(EDITOR_MAX_FILE_BYTES)
        model.bindWorkspace("/one", "limit100")
        model.load("~/workspace/a.txt")
        val warning = model.uiState.first { it is EditorUiState.LargeFileWarning } as EditorUiState.LargeFileWarning
        model.confirmLargeFile(warning.loadId)
        model.uiState.first { it is EditorUiState.Success }
        verify(exactly = 1) { first.copyToLocal("~/workspace/a.txt", EDITOR_MAX_FILE_BYTES) }
    }

    @Test fun oneHundredMiBPlusOneIsRejectedBeforeDownload() = runTest(dispatcher) {
        readableFile(EDITOR_MAX_FILE_BYTES + 1)
        model.bindWorkspace("/one", "limit100plus")
        model.load("~/workspace/a.txt")
        val state = model.uiState.first { it is EditorUiState.TooLarge } as EditorUiState.TooLarge
        assertEquals(EDITOR_MAX_FILE_BYTES + 1, state.sizeBytes)
        model.confirmLargeFile(0)
        verify(exactly = 0) { first.copyToLocal(any(), any()) }
    }

    @Test fun previousFileConfirmationDoesNotApproveCurrentFile() = runTest(dispatcher) {
        readableFile(EDITOR_LARGE_FILE_WARNING_BYTES + 1)
        model.bindWorkspace("/one", "stale-file")
        model.load("~/workspace/a.txt")
        val firstWarning = model.uiState.first { it is EditorUiState.LargeFileWarning } as EditorUiState.LargeFileWarning
        model.load("~/workspace/b.txt")
        model.uiState.first { it is EditorUiState.LargeFileWarning }
        model.confirmLargeFile(firstWarning.loadId)
        runCurrent()
        verify(exactly = 0) { first.copyToLocal(any(), any()) }
    }

    @Test fun growthBeforeConfirmedDownloadIsStillRejected() = runTest(dispatcher) {
        readableFile(EDITOR_LARGE_FILE_WARNING_BYTES + 1)
        model.bindWorkspace("/one", "growth")
        model.load("~/workspace/a.txt")
        val warning = model.uiState.first { it is EditorUiState.LargeFileWarning } as EditorUiState.LargeFileWarning
        every { first.fileSize(any()) } returns EDITOR_MAX_FILE_BYTES + 1
        model.confirmLargeFile(warning.loadId)
        model.uiState.first { it is EditorUiState.TooLarge }
        verify(exactly = 0) { first.copyToLocal(any(), any()) }
    }

    @Test fun saveDoesNotApplyOpeningSizeLimit() = runTest(dispatcher) {
        every { first.fileSize(any()) } returns EDITOR_MAX_FILE_BYTES + 1
        model.bindWorkspace("/one", "save-unrestricted")
        val request = model.createSaveRequest("~/workspace/a.txt", "saved content")!!
        val result = async(UnconfinedTestDispatcher(testScheduler)) { model.saveEvents.first() }
        model.save(request)
        assertEquals(SaveResult.Success(request), result.await())
        verify(exactly = 1) { first.writeFile("~/workspace/a.txt", "saved content", any(), any()) }
        verify(exactly = 0) { first.fileSize(any()) }
        verify(exactly = 0) { first.copyToLocal(any(), any()) }
    }

    @Test
    fun lateSave_keepsOriginalBoundRootAndCannotAffectNewBinding() = runTest(dispatcher) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { first.writeFile(any(), any(), any(), any()) } answers {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        model.bindWorkspace("/one", "one")
        val request = model.createSaveRequest("~/workspace/a", "saved")!!
        val event = async(UnconfinedTestDispatcher(testScheduler)) { model.saveEvents.first() }
        model.save(request)
        runCurrent()
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            model.bindWorkspace("/two", "two")
        } finally { release.countDown() }
        assertEquals(SaveResult.Success(request), event.await())
        assertFalse(model.accepts(request))
        assertEquals(EditorUiState.Loading, model.uiState.value)
        verify(exactly = 1) { first.writeFile("~/workspace/a", "saved", any(), any()) }
        verify(exactly = 0) { second.writeFile(any(), any(), any(), any()) }
    }

    @Test
    fun switchingServerWithSameRoot_invalidatesOldGeneration() = runTest(dispatcher) {
        val generation = model.bindWorkspace("/one", "server-one")
        val request = model.createSaveRequest("~/workspace/a", "saved")!!
        model.bindWorkspace("/one", "server-two")
        model.unbindWorkspace(generation)
        assertFalse(model.accepts(request))
        assertEquals("server-two", model.createSaveRequest("~/workspace/a", "new")?.workspaceIdentity)
    }

    @Test
    fun staleEnvironmentRequest_failsWithoutWritingEitherRoot() = runTest(dispatcher) {
        model.bindWorkspace("/one", "one")
        val request = model.createSaveRequest("~/workspace/a", "saved")!!
        every { identity.matches("/one", "one") } returns false
        val event = async(UnconfinedTestDispatcher(testScheduler)) { model.saveEvents.first() }
        model.save(request)
        assertTrue(event.await() is SaveResult.Error)
        verify(exactly = 0) { first.writeFile(any(), any(), any(), any()) }
        verify(exactly = 0) { second.writeFile(any(), any(), any(), any()) }
    }

    @Test
    fun forceSave_afterChangingActivePath_writesOriginalRequest() = runTest(dispatcher) {
        model.bindWorkspace("/one", "one")
        val request = model.createSaveRequest("~/workspace/a", "original")!!
        every { first.exists(any()) } returns false
        model.load("~/workspace/b")
        val event = async(UnconfinedTestDispatcher(testScheduler)) { model.saveEvents.first() }
        model.saveForce(request)
        assertEquals(SaveResult.Success(request), event.await())
        verify(exactly = 1) { first.writeFile("~/workspace/a", "original", any(), any()) }
        verify(exactly = 0) { first.writeFile("~/workspace/b", any(), any(), any()) }
        assertTrue(model.isShowing("/one", "one", "~/workspace/b"))
    }

    private val refreshPath = "~/workspace/a.txt"

    private fun snapshot(content: String = "full content", baseline: String = "full content") =
        EditorSessionManager.Snapshot(content, content != baseline, 2, 3, 40, baseline)

    private suspend fun openRefreshFile(workspace: String = java.util.UUID.randomUUID().toString()): java.io.File {
        val file = readableFile(12)
        every { first.fingerprint(any()) } answers { sha256Fingerprint(file.readBytes()) }
        every { first.copyToLocal(any()) } returns file
        every { first.writeFile(any(), any(), any(), any()) } answers { file.writeText(arg(1)) }
        model.bindWorkspace("/one", workspace)
        EditorSessionManager.forWorkspace(workspace).open(refreshPath)
        model.load(refreshPath)
        model.uiState.first { it is EditorUiState.Success }
        return file
    }

    @Test
    fun refreshCleanFileUpdatesBufferSnapshotAndSaveBaselineWithoutChangingAgentReads() = runTest(dispatcher) {
        val workspace = java.util.UUID.randomUUID().toString()
        val file = openRefreshFile(workspace)
        val session = EditorSessionManager.forWorkspace(workspace)
        session.saveSnapshot(refreshPath, snapshot())
        val version = model.documentVersion.value
        versions.record("reader", refreshPath, "agent-baseline")
        file.writeText("external content")
        var visible = "full content"
        model.refresh(refreshPath, version, { snapshot(visible) }) { visible = it }
        assertEquals("external content", visible)
        assertEquals("external content", (model.uiState.value as EditorUiState.Success).content)
        assertEquals(snapshot("external content", "external content"), session.snapshot(refreshPath))
        assertEquals(version, model.documentVersion.value)
        assertNull(model.externalChange.value)
        assertEquals("agent-baseline", versions.get("reader", refreshPath))
        val request = model.createSaveRequest(refreshPath, "next edit")!!
        val event = async(UnconfinedTestDispatcher(testScheduler)) { model.saveEvents.first() }
        model.save(request)
        assertEquals(SaveResult.Success(request), event.await())
        assertEquals("next edit", file.readText())
    }

    @Test
    fun repeatedRefreshWithSameBytesDoesNotResetTheBuffer() = runTest(dispatcher) {
        val file = openRefreshFile()
        var baseline = "full content"
        var reloads = 0
        file.writeText("new content")
        repeat(2) {
            model.refresh(refreshPath, model.documentVersion.value, { snapshot(baseline, baseline) }) {
                baseline = it
                reloads++
            }
        }
        assertEquals(1, reloads)
    }

    @Test
    fun dirtyRefreshKeepsDraftAndOriginalSaveBaseline() = runTest(dispatcher) {
        val workspace = java.util.UUID.randomUUID().toString()
        val file = openRefreshFile(workspace)
        val session = EditorSessionManager.forWorkspace(workspace)
        val draft = snapshot("my draft")
        session.saveSnapshot(refreshPath, draft)
        file.writeText("external content")
        model.refresh(refreshPath, model.documentVersion.value, { draft }) { error("Draft was replaced") }
        assertEquals(EditorExternalChange.CHANGED, model.externalChange.value)
        assertEquals(draft, session.snapshot(refreshPath))
        assertEquals("full content", (model.uiState.value as EditorUiState.Success).content)
        val request = model.createSaveRequest(refreshPath, draft.content)!!
        val event = async(UnconfinedTestDispatcher(testScheduler)) { model.saveEvents.first() }
        model.save(request)
        assertEquals(SaveResult.Conflict(request, "external content"), event.await())
        assertEquals("external content", file.readText())
    }

    @Test
    fun undoingDraftToBaselineAllowsRefreshDespiteAnOlderDirtySnapshot() = runTest(dispatcher) {
        val workspace = java.util.UUID.randomUUID().toString()
        val file = openRefreshFile(workspace)
        val session = EditorSessionManager.forWorkspace(workspace)
        session.saveSnapshot(refreshPath, snapshot("old draft"))
        file.writeText("external content")
        var reloaded = false
        model.refresh(refreshPath, model.documentVersion.value, { snapshot() }) { reloaded = true }
        assertTrue(reloaded)
        assertEquals(false, session.snapshot(refreshPath)?.dirty)
        assertEquals("external content", session.snapshot(refreshPath)?.baselineContent)
    }

    @Test
    fun restoringDiskBaselineClearsTheNoticeWithoutDiscardingDraft() = runTest(dispatcher) {
        val file = openRefreshFile()
        file.writeText("external content")
        model.refresh(refreshPath, model.documentVersion.value, { snapshot("draft") }) { error("Draft was replaced") }
        assertEquals(EditorExternalChange.CHANGED, model.externalChange.value)
        file.writeText("full content")
        model.refresh(refreshPath, model.documentVersion.value, { snapshot("draft") }) { error("Draft was replaced") }
        assertNull(model.externalChange.value)
    }

    @Test
    fun returningToCachedTabRechecksDiskContent() = runTest(dispatcher) {
        val file = openRefreshFile()
        val generation = model.documentVersion.value
        every { first.exists("~/workspace/b.txt") } returns false
        model.load("~/workspace/b.txt")
        model.uiState.first { it is EditorUiState.Error }
        file.writeText("changed while hidden")
        model.load(refreshPath)
        assertTrue(model.documentVersion.value > generation)
        var visible = "full content"
        model.refresh(refreshPath, model.documentVersion.value, { snapshot() }) { visible = it }
        assertEquals("changed while hidden", visible)
    }

    @Test
    fun reloadingCleanSnapshotReplacesItsOldBaseline() = runTest(dispatcher) {
        val workspace = java.util.UUID.randomUUID().toString()
        val file = openRefreshFile(workspace)
        val session = EditorSessionManager.forWorkspace(workspace)
        session.saveSnapshot(refreshPath, snapshot())
        file.writeText("new disk version")
        model.reloadFromDisk(refreshPath)
        model.uiState.first { it is EditorUiState.Success }
        assertEquals("new disk version", session.snapshot(refreshPath)?.content)
        assertEquals("new disk version", session.snapshot(refreshPath)?.baselineContent)
    }

    @Test
    fun reopenedDirtySnapshotKeepsItsOriginalBaselineInTheCache() = runTest(dispatcher) {
        val workspace = java.util.UUID.randomUUID().toString()
        val session = EditorSessionManager.forWorkspace(workspace)
        session.saveSnapshot(refreshPath, snapshot("draft", "original disk"))
        openRefreshFile(workspace)
        assertEquals("original disk", (model.uiState.value as EditorUiState.Success).content)
        assertEquals("draft", session.snapshot(refreshPath)?.content)
    }

    @Test
    fun refreshFailureKeepsContentAndCanRecover() = runTest(dispatcher) {
        val file = openRefreshFile()
        every { first.copyToLocal(any(), any()) } throws java.io.IOException("disconnected")
        model.refresh(refreshPath, model.documentVersion.value, { snapshot() }) { error("Failed read was applied") }
        assertEquals("full content", (model.uiState.value as EditorUiState.Success).content)
        assertEquals(EditorExternalChange.UNAVAILABLE, model.externalChange.value)
        every { first.copyToLocal(any(), any()) } returns file
        file.writeText("reconnected")
        model.refresh(refreshPath, model.documentVersion.value, { snapshot() }) { }
        assertEquals("reconnected", (model.uiState.value as EditorUiState.Success).content)
        assertNull(model.externalChange.value)
    }

    @Test
    fun refreshOverTheApprovedSizeKeepsCurrentContent() = runTest(dispatcher) {
        openRefreshFile()
        every { first.copyToLocal(refreshPath, EDITOR_LARGE_FILE_WARNING_BYTES) } throws
            FileSizeLimitExceededException(EDITOR_LARGE_FILE_WARNING_BYTES, EDITOR_LARGE_FILE_WARNING_BYTES + 1)
        model.refresh(refreshPath, model.documentVersion.value, { snapshot() }) { error("Oversized file was applied") }
        assertEquals("full content", (model.uiState.value as EditorUiState.Success).content)
        assertEquals(EditorExternalChange.UNAVAILABLE, model.externalChange.value)
        verify(exactly = 0) { first.copyToLocal(refreshPath, EDITOR_MAX_FILE_BYTES) }
    }

    @Test
    fun oldUiBaselineAfterSavingCannotDiscardUserChanges() = runTest(dispatcher) {
        val file = openRefreshFile()
        val request = model.createSaveRequest(refreshPath, "saved content")!!
        val event = async(UnconfinedTestDispatcher(testScheduler)) { model.saveEvents.first() }
        model.save(request)
        assertEquals(SaveResult.Success(request), event.await())
        file.writeText("external content")
        model.refresh(refreshPath, model.documentVersion.value, { snapshot() }) { error("Unsaved undo was replaced") }
        assertEquals(EditorExternalChange.CHANGED, model.externalChange.value)
        assertEquals("saved content", (model.uiState.value as EditorUiState.Success).content)
    }

    @Test
    fun oldEditorInstanceCannotRefreshANewerDocument() = runTest(dispatcher) {
        openRefreshFile()
        model.refresh(refreshPath, model.documentVersion.value - 1, { snapshot() }) { error("Old editor was used") }
        verify(exactly = 1) { first.copyToLocal(refreshPath, any()) }
    }

    @Test
    fun typingDuringRefreshPreservesTheNewDraft() = runTest(dispatcher) {
        val file = openRefreshFile()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        file.writeText("external content")
        every { first.copyToLocal(any(), any()) } answers {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            file
        }
        var current = snapshot()
        val refresh = async { model.refresh(refreshPath, model.documentVersion.value, { current }) { error("Draft was replaced") } }
        runCurrent()
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            current = snapshot("typed during read")
        } finally { release.countDown() }
        refresh.await()
        assertEquals(EditorExternalChange.CHANGED, model.externalChange.value)
        assertEquals("typed during read", current.content)
    }

    @Test
    fun switchingWorkspaceDuringRefreshIgnoresTheOldResult() = runTest(dispatcher) {
        val file = openRefreshFile()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        file.writeText("external content")
        every { first.copyToLocal(any(), any()) } answers {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            file
        }
        val refresh = async { model.refresh(refreshPath, model.documentVersion.value, { snapshot() }) { error("Old workspace was applied") } }
        runCurrent()
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            model.bindWorkspace("/two", "new-workspace")
        } finally { release.countDown() }
        refresh.await()
        assertEquals(EditorUiState.Loading, model.uiState.value)
        assertNull(model.externalChange.value)
    }

    @Test
    fun switchingServerIdentityAtTheSameRootIgnoresTheOldRefresh() = runTest(dispatcher) {
        val file = openRefreshFile()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        file.writeText("old server")
        every { first.copyToLocal(any(), any()) } answers {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            file
        }
        val refresh = async { model.refresh(refreshPath, model.documentVersion.value, { snapshot() }) { error("Old server was applied") } }
        runCurrent()
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            model.bindWorkspace("/one", "different-server")
        } finally { release.countDown() }
        refresh.await()
        assertEquals(EditorUiState.Loading, model.uiState.value)
        assertNull(model.externalChange.value)
    }

    @Test
    fun olderRefreshCannotReplaceANewerRead() = runTest(dispatcher) {
        openRefreshFile()
        val older = temporary.newFile().apply { writeText("older external content") }
        val newer = temporary.newFile().apply { writeText("newer external content") }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        every { first.copyToLocal(any(), any()) } answers {
            if (reads.incrementAndGet() == 1) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                older
            } else newer
        }
        val version = model.documentVersion.value
        val refresh = async { model.refresh(refreshPath, version, { snapshot() }) { error("Older read was applied") } }
        runCurrent()
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            model.refresh(refreshPath, version, { snapshot() }) { }
        } finally { release.countDown() }
        refresh.await()
        assertEquals("newer external content", (model.uiState.value as EditorUiState.Success).content)
    }

    @Test
    fun saveStartingDuringRefreshInvalidatesTheRead() = runTest(dispatcher) {
        val file = openRefreshFile()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { first.copyToLocal(any(), any()) } answers {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            file
        }
        val refresh = async { model.refresh(refreshPath, model.documentVersion.value, { snapshot() }) { error("Old read was applied") } }
        runCurrent()
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            val request = model.createSaveRequest(refreshPath, "saved while checking")!!
            val event = async(UnconfinedTestDispatcher(testScheduler)) { model.saveEvents.first() }
            model.save(request)
            assertEquals(SaveResult.Success(request), event.await())
        } finally { release.countDown() }
        refresh.await()
        assertEquals("saved while checking", (model.uiState.value as EditorUiState.Success).content)
        assertNull(model.externalChange.value)
    }

    @Test
    fun lateLoad_doesNotReplaceNewWorkspaceState() = runTest(dispatcher) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { first.exists(any()) } answers {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            false
        }
        every { second.exists(any()) } returns false
        model.bindWorkspace("/one", "one")
        model.load("~/workspace/a")
        runCurrent()
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            model.bindWorkspace("/two", "two")
            model.load("~/workspace/a")
        } finally { release.countDown() }
        model.uiState.first { it is EditorUiState.Error }
        assertTrue(model.isShowing("/two", "two", "~/workspace/a"))
        verify(exactly = 1) { second.exists("~/workspace/a") }
    }
}
