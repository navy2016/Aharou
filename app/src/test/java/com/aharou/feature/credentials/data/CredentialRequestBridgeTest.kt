package com.aharou.feature.credentials.data

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.core.watch.ChangeKind
import com.aharou.core.watch.ChangeRoot
import com.aharou.core.watch.FileChange
import com.aharou.core.watch.FileChangeBatch
import com.aharou.core.watch.FileChangeHub
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.credentials.domain.model.GitCredential
import com.aharou.feature.credentials.domain.repository.CredentialRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CredentialRequestBridgeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = mockk<Context>()
    private val credentials = mockk<CredentialRepository>(relaxed = true)
    private val engine = mockk<LinuxContainerEngine>(relaxed = true)
    private val hub = mockk<FileChangeHub>()
    private val events = MutableSharedFlow<FileChangeBatch>()
    private val bridge = CredentialRequestBridge(context, credentials, engine, hub)
    private val directory: File get() = File(temporary.root, "aharou")

    @Before
    fun setUp() {
        every { context.filesDir } returns temporary.root
        every { hub.watchAicode() } returns events
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } returns Unit
        every { FileLogger.w(any(), any(), any()) } returns Unit
        every { FileLogger.e(any(), any(), any()) } returns Unit
    }

    @After
    fun tearDown() { unmockkObject(FileLogger) }

    private fun request(id: String, host: String = "github.com"): File =
        File(directory.apply { mkdirs() }, "cred-req-$id").apply { writeText("host=$host\n") }

    private fun response(id: String): File = File(directory, "cred-resp-$id")

    private suspend fun TestScope.changed(file: File, kind: ChangeKind = ChangeKind.CREATED) {
        events.emit(FileChangeBatch(listOf(FileChange(ChangeRoot.AICODE, file.path, file.name, kind))))
        runCurrent()
    }

    @Test
    fun concurrentRequestsAreAnsweredInOrderWithTheirOwnCredentials() = runTest {
        request("first")
        bridge.start(backgroundScope)
        runCurrent()
        changed(request("second"))
        assertEquals("first", bridge.request.value?.requestId)
        bridge.respond("first", "alice", "fixture-first")
        runCurrent()
        assertEquals("second", bridge.request.value?.requestId)
        assertEquals("username=alice\npassword=fixture-first\n", response("first").readText())
        bridge.respond("second", "bob", "fixture-second")
        runCurrent()
        assertNull(bridge.request.value)
        assertEquals("username=bob\npassword=fixture-second\n", response("second").readText())
        coVerify(exactly = 1) { credentials.save(GitCredential("github.com", "github.com", "alice", "fixture-first")) }
        coVerify(exactly = 1) { credentials.save(GitCredential("github.com", "github.com", "bob", "fixture-second")) }
        verify(exactly = 2) { engine.incPromptInFlight() }
        verify(exactly = 2) { engine.decPromptInFlight() }
    }

    @Test
    fun repeatedEventsAndAnswersAreHandledOnlyOnce() = runTest {
        val file = request("first")
        bridge.start(backgroundScope)
        runCurrent()
        changed(file)
        changed(file)
        bridge.respond("first", "alice", "fixture")
        bridge.respond("first", "bob", "other-fixture")
        bridge.cancel("first")
        runCurrent()
        assertEquals("username=alice\npassword=fixture\n", response("first").readText())
        coVerify(exactly = 1) { credentials.save(any()) }
        verify(exactly = 1) { engine.incPromptInFlight() }
        verify(exactly = 1) { engine.decPromptInFlight() }
    }

    @Test
    fun delayedCallbackCannotAnswerTheNextRequest() = runTest {
        request("first")
        bridge.start(backgroundScope)
        runCurrent()
        changed(request("second", "gitlab.com"))
        bridge.cancel("first")
        runCurrent()
        bridge.respond("first", "late", "fixture")
        bridge.cancel("first")
        runCurrent()
        assertEquals("second", bridge.request.value?.requestId)
        assertFalse(response("second").exists())
        assertEquals("cancel=1\n", response("first").readText())
        coVerify(exactly = 0) { credentials.save(any()) }
        verify(exactly = 1) { engine.decPromptInFlight() }
    }

    @Test
    fun failedResponseRemainsPendingAndCanBeRetried() = runTest {
        request("first")
        bridge.start(backgroundScope)
        runCurrent()
        val blocked = File(directory, "cred-resp-first.tmp").apply { mkdirs() }
        val child = File(blocked, "keep").apply { writeText("fixture") }
        bridge.respond("first", "alice", "fixture")
        runCurrent()
        assertEquals("first", bridge.request.value?.requestId)
        assertFalse(response("first").exists())
        coVerify(exactly = 0) { credentials.save(any()) }
        verify(exactly = 0) { engine.decPromptInFlight() }
        assertTrue(child.delete())
        assertTrue(blocked.delete())
        bridge.respond("first", "alice", "retry-fixture")
        runCurrent()
        assertNull(bridge.request.value)
        assertEquals("username=alice\npassword=retry-fixture\n", response("first").readText())
        coVerify(exactly = 1) { credentials.save(any()) }
        verify(exactly = 1) { engine.decPromptInFlight() }
    }

    @Test
    fun helperExitReclaimsItsRequestAndAdvancesTheQueue() = runTest {
        val first = request("first")
        bridge.start(backgroundScope)
        runCurrent()
        changed(request("second"))
        assertTrue(first.delete())
        changed(first, ChangeKind.DELETED)
        assertEquals("second", bridge.request.value?.requestId)
        bridge.respond("first", "late", "fixture")
        bridge.cancel("second")
        runCurrent()
        assertNull(bridge.request.value)
        assertFalse(response("first").exists())
        assertEquals("cancel=1\n", response("second").readText())
        verify(exactly = 2) { engine.decPromptInFlight() }
    }

    @Test
    fun repeatedStartPreservesTheLiveRequestAndOnlyStartsOneListener() = runTest {
        val file = request("first")
        bridge.start(backgroundScope)
        runCurrent()
        bridge.start(backgroundScope)
        runCurrent()
        assertTrue(file.exists())
        assertEquals("first", bridge.request.value?.requestId)
        verify(exactly = 1) { hub.watchAicode() }
        verify(exactly = 1) { engine.incPromptInFlight() }
    }

    @Test
    fun incompleteRequestCanBeReadAfterItsContentArrives() = runTest {
        val file = request("first", "")
        bridge.start(backgroundScope)
        runCurrent()
        assertNull(bridge.request.value)
        file.writeText("host=github.com\n")
        changed(file, ChangeKind.MODIFIED)
        assertEquals("first", bridge.request.value?.requestId)
        verify(exactly = 1) { engine.incPromptInFlight() }
    }

    @Test
    fun startupKeepsRecentRequestsAndRemovesOnlyExpiredFiles() = runTest {
        val stale = request("stale")
        val staleResponse = response("stale").apply { writeText("cancel=1\n") }
        val old = System.currentTimeMillis() - 31L * 60L * 1000L
        assertTrue(stale.setLastModified(old))
        assertTrue(staleResponse.setLastModified(old))
        val fresh = request("fresh")
        bridge.start(backgroundScope)
        runCurrent()
        assertFalse(stale.exists())
        assertFalse(staleResponse.exists())
        assertTrue(fresh.exists())
        assertEquals("fresh", bridge.request.value?.requestId)
        verify(exactly = 1) { engine.incPromptInFlight() }
    }

    @Test
    fun watcherFailureDoesNotStopFallbackRequestProcessing() = runTest {
        every { hub.watchAicode() } returns flow { throw java.io.IOException("fixture") }
        bridge.start(backgroundScope)
        runCurrent()
        request("first")
        advanceTimeBy(1000)
        runCurrent()
        assertEquals("first", bridge.request.value?.requestId)
        bridge.cancel("first")
        runCurrent()
        assertNull(bridge.request.value)
        assertEquals("cancel=1\n", response("first").readText())
        verify(exactly = 1) { engine.incPromptInFlight() }
        verify(exactly = 1) { engine.decPromptInFlight() }
    }

    @Test
    fun existingResponseDoesNotProduceAnotherPrompt() = runTest {
        request("answered")
        response("answered").writeText("cancel=1\n")
        bridge.start(backgroundScope)
        runCurrent()
        assertNull(bridge.request.value)
        verify(exactly = 0) { engine.incPromptInFlight() }
    }

    @Test
    fun helperDeletionDuringAnAnswerDoesNotDoubleReleaseTheRequest() = runTest {
        val first = request("first")
        val saving = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { credentials.save(any()) } coAnswers { saving.complete(Unit); release.await() }
        bridge.start(backgroundScope)
        runCurrent()
        bridge.respond("first", "alice", "fixture")
        runCurrent()
        saving.await()
        assertTrue(first.delete())
        changed(first, ChangeKind.DELETED)
        bridge.cancel("first")
        runCurrent()
        verify(exactly = 0) { engine.decPromptInFlight() }
        release.complete(Unit)
        runCurrent()
        assertNull(bridge.request.value)
        coVerify(exactly = 1) { credentials.save(any()) }
        verify(exactly = 1) { engine.decPromptInFlight() }
    }
}
