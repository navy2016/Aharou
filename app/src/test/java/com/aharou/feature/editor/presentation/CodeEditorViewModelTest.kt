package com.aharou.feature.editor.presentation

import android.app.Application
import android.content.Context
import com.aharou.feature.agent.domain.tool.FileVersionRegistry
import com.aharou.feature.editor.data.EditorSettings
import com.aharou.feature.editor.data.EditorSettingsRepository
import com.aharou.feature.editor.domain.TextMateSetup
import com.aharou.feature.editor.lsp.EditorLspManager
import com.aharou.feature.workspace.domain.FileAccessProvider
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
    private val versions = FileVersionRegistry()
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
        every { second.toDisplayPath(any()) } answers { arg(0) }
        every { identity.matches(any(), any()) } returns true
        every { settings.settingsFlow } returns MutableStateFlow(EditorSettings())
        mockkObject(TextMateSetup)
        every { TextMateSetup.ensureInitialized(any()) } returns Unit
        every { TextMateSetup.scopeNameFor(any()) } returns null
        model = CodeEditorViewModel(mockk<Context>(relaxed = true), files, settings,
            mockk<EditorLspManager>(relaxed = true), versions, identity)
    }

    @After
    fun tearDown() { unmockkObject(TextMateSetup); Dispatchers.resetMain() }

    private fun readableFile(size: Long) {
        val file = temporary.newFile().apply { writeText("full content") }
        every { first.exists(any()) } returns true
        every { first.isFile(any()) } returns true
        every { first.fileSize(any()) } returns size
        every { first.copyToLocal(any(), any()) } returns file
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
