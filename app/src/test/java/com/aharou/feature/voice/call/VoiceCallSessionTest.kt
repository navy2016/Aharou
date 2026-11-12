package com.aharou.feature.voice.call

import android.content.Context
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.voice.data.RecordStartResult
import com.aharou.feature.voice.data.StreamingAsrEngine
import com.aharou.feature.voice.data.VoiceRecorder
import com.aharou.feature.voice.domain.VoiceModelManager
import com.aharou.feature.voice.playback.PlaybackSession
import com.aharou.feature.voice.playback.SpeechPlayback
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCallSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val recorder = mockk<VoiceRecorder>(relaxed = true)
    private val asr = mockk<StreamingAsrEngine>(relaxed = true)
    private lateinit var session: VoiceCallSession

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val playback = mockk<PlaybackSession>(relaxed = true)
        every { playback.currentText } returns MutableStateFlow("")
        every { playback.pendingCount } returns MutableStateFlow(0)
        every { playback.isSpeaking } returns MutableStateFlow(false)
        val hub = mockk<SpeechPlayback>()
        every { hub.newSession() } returns playback
        val models = mockk<VoiceModelManager>(relaxed = true)
        every { models.isReady(any()) } returns true
        every { models.modelDir(any()) } returns temporary.root
        coEvery { asr.startSession(any(), any()) } returns mockk(relaxed = true)
        every { recorder.start(any(), any()) } returns RecordStartResult.Started
        every { recorder.isRecording } returns false
        val context = mockk<Context>(relaxed = true)
        every { context.getString(any()) } returns "Still there?"
        session = VoiceCallSession(
            mockk<AgentTurnRunner>(relaxed = true), hub, asr, mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), models, recorder,
            mockk(relaxed = true), mockk(relaxed = true), context
        )
        state().value = VoiceCallSession.State.Speaking
    }

    @After
    fun tearDown() {
        session.stop()
        val field = VoiceCallSession::class.java.getDeclaredField("scope").apply { isAccessible = true }
        (field.get(session) as CoroutineScope).cancel()
        Dispatchers.resetMain()
    }

    @Suppress("UNCHECKED_CAST")
    private fun state(): MutableStateFlow<VoiceCallSession.State> {
        val field = VoiceCallSession::class.java.getDeclaredField("_state").apply { isAccessible = true }
        return field.get(session) as MutableStateFlow<VoiceCallSession.State>
    }

    private suspend fun listen(resetSilence: Boolean = true) = invokeSuspend("beginListening", resetSilence)

    private suspend fun invokeSuspend(name: String, vararg args: Any?): Unit =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = VoiceCallSession::class.java.declaredMethods.first { it.name == name }
                .apply { isAccessible = true }
            val result = try {
                method.invoke(session, *args, continuation)
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
            if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else Unit
        }

    @Test
    fun mutedReplyCompletionDoesNotRestartTheMicrophone() = runTest {
        session.setMuted(true)
        listen()
        assertEquals(VoiceCallSession.State.Listening, session.state.value)
        assertEquals(true, session.muted.value)
        verify(exactly = 0) { recorder.start(any(), any()) }
        session.setMuted(false)
        runCurrent()
        verify(exactly = 1) { recorder.start(any(), any()) }
    }

    @Test
    fun mutedWakeListeningDoesNotStartTheMicrophone() = runTest {
        session.setMuted(true)
        invokeSuspend("beginWakeListening")
        assertEquals(VoiceCallSession.State.WakeListening, session.state.value)
        verify(exactly = 0) { recorder.start(any(), any()) }
    }

    @Test
    fun silenceReminderKeepsItsHangUpTimer() = runTest {
        listen()
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(VoiceCallSession.State.Listening, session.state.value)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(VoiceCallSession.State.Idle, session.state.value)
    }

    @Test
    fun mutingCancelsTheSilenceTimer() = runTest {
        listen()
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        session.setMuted(true)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(VoiceCallSession.State.Listening, session.state.value)
        assertEquals(true, session.muted.value)
    }
}
