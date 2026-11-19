package com.aharou.feature.agent.domain.workflow

import android.os.SystemClock
import com.aharou.core.privacy.OutboundMasker
import com.aharou.core.util.FileLogger
import com.aharou.core.util.runCancellationSafeTail
import com.aharou.feature.agent.data.local.dao.LlmCallRecordDao
import com.aharou.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.privacy.restoredFromOutbound
import com.aharou.feature.agent.domain.provider.AIProvider
import com.aharou.feature.agent.domain.provider.AIResponse
import com.aharou.feature.agent.domain.provider.AIStreamChunk
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.workflow.StatefulAgentWorkflow.Companion.LIVE_TAIL_CHARS
import com.aharou.feature.agent.domain.workflow.StatefulAgentWorkflow.Companion.PERSIST_WAIT_MS
import com.aharou.feature.agent.domain.workflow.StatefulAgentWorkflow.Companion.TAG
import com.aharou.feature.agent.domain.workflow.StatefulAgentWorkflow.Companion.hasSnapshotData
import com.aharou.feature.agent.domain.workflow.StatefulAgentWorkflow.Companion.persistenceConfirmed
import com.aharou.feature.agent.presentation.MessageRole
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull

internal class LlmStreamCall(
    private val sessionId: String?,
    private val reasoningEffort: String?,
    private val provider: AIProvider,
    private val telemetry: AgentTurnTelemetry,
    private val imagePersister: GeneratedImagePersister,
    private val persistence: MessagePersistenceUseCase,
    private val records: LlmCallRecordDao,
    private val emit: suspend (AgentEvent) -> Unit,
) {
    val messageId = UUID.randomUUID().toString()
    private val startedElapsed = SystemClock.elapsedRealtime()
    private val startedWall = System.currentTimeMillis()
    private var firstByteMs: Long? = null
    private var callId = -1
    private var retries = 0
    private var completed = false
    private var errorMessage: String? = null
    private var failedElapsed: Long? = null

    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private var finalResponse: AIResponse? = null
    val hasOutput: Boolean get() = finalResponse != null || text.isNotEmpty() || reasoning.isNotEmpty()
    private var cachedSnapshot: AgentEvent.AssistantText? = null
    private var snapshotSent = false
    private var persistedImages = emptyList<AgentImage>()
    private var textPending = false
    private var reasoningPending = false
    private var lastTextAt = 0L
    private var lastReasoningAt = 0L
    private var cutStream = false
    private var stoppedDuringStream = false

    // 先保存原结果或异常，在异常处理区之外只挂起收尾一次，再传播原异常（包括取消）。
    suspend fun <T> run(block: suspend () -> T): T {
        val outcome = runCatching { block() }
        finish()
        return outcome.getOrThrow()
    }

    suspend fun collectResponse(
        systemPrompt: String, messages: List<AgentMessage>, tools: List<AgentTool>, predictedInput: Int,
        masker: OutboundMasker?, stopRequested: () -> Boolean,
    ): AIResponse {
        callId = telemetry.llmRequest(provider.providerId, provider.model, messages.size, tools.size, predictedInput)
        provider.completeStream(systemPrompt, messages, tools, reasoningEffort)
            .takeWhile { !cutStream }
            .collect { chunk ->
                if (stopRequested()) {
                    stoppedDuringStream = true
                    cutStream = true
                }
                accept(chunk, masker)
            }
        flushText()
        flushReasoning()
        stoppedDuringStream = stoppedDuringStream || stopRequested()
        val response = finalResponse ?: AIResponse(content = text.toString())
        val snapshot = snapshot()
        telemetry.llmStreamEnd(callId, text.length, reasoning.length, snapshot.toolCalls.size, response.stopReason,
            response.inputTokens, response.outputTokens, retries, response.cachedInputTokens)
        completed = finalResponse != null || !stoppedDuringStream
        return response.copy(reasoning = snapshot.reasoning, images = persistedImages.ifEmpty { response.images })
    }

    private suspend fun accept(chunk: AIStreamChunk, masker: OutboundMasker?) {
        when (chunk) {
            is AIStreamChunk.TextDelta -> {
                markFirstByte()
                text.append(chunk.text)
                val loop = samplingLoopStart(text)
                if (loop >= 0) {
                    cutStream = true
                    cutSamplingLoop(text, loop, inReasoning = false, emit = emit)
                }
                textPending = true
                val now = SystemClock.elapsedRealtime()
                if (now - lastTextAt >= DELTA_THROTTLE_MS) {
                    flushText()
                    lastTextAt = now
                }
            }
            is AIStreamChunk.ReasoningDelta -> {
                markFirstByte()
                reasoning.append(chunk.text)
                val loop = samplingLoopStart(reasoning)
                if (loop >= 0) {
                    cutStream = true
                    cutSamplingLoop(reasoning, loop, inReasoning = true, emit = emit)
                }
                reasoningPending = true
                val now = SystemClock.elapsedRealtime()
                if (now - lastReasoningAt >= DELTA_THROTTLE_MS) {
                    flushReasoning()
                    lastReasoningAt = now
                }
            }
            is AIStreamChunk.Retrying -> {
                retries = maxOf(retries, chunk.attempt)
                resetDeltas()
                emit(AgentEvent.Retrying(chunk.attempt, chunk.maxRetries, chunk.error))
            }
            is AIStreamChunk.KeySwitched -> {
                resetDeltas()
                emit(AgentEvent.KeySwitched(chunk.newIndex, chunk.total))
            }
            is AIStreamChunk.Final -> {
                markFirstByte()
                finalResponse = if (masker == null) chunk.response else chunk.response.restoredFromOutbound(masker)
            }
            is AIStreamChunk.ToolCallDeclared -> {
                markFirstByte()
                if (!stoppedDuringStream) emit(AgentEvent.ToolCallPreparing(chunk.name))
            }
            is AIStreamChunk.ToolCallArgumentProgress -> if (!stoppedDuringStream) emit(toolCallProgressEvent(chunk))
        }
    }

    private fun markFirstByte() {
        if (firstByteMs != null) return
        firstByteMs = SystemClock.elapsedRealtime() - startedElapsed
        telemetry.llmFirstByte(callId)
    }

    private fun resetDeltas() {
        text.setLength(0)
        reasoning.setLength(0)
        textPending = false
        reasoningPending = false
        lastTextAt = 0L
        lastReasoningAt = 0L
    }

    private suspend fun flushText() {
        if (!textPending) return
        textPending = false
        emit(AgentEvent.AssistantDelta(livePreview(text)))
    }

    private suspend fun flushReasoning() {
        if (!reasoningPending) return
        reasoningPending = false
        emit(AgentEvent.ReasoningDelta(livePreview(reasoning)))
    }

    private suspend fun snapshot(): AgentEvent.AssistantText {
        cachedSnapshot?.let { return it }
        val response = finalResponse ?: AIResponse(text.toString(), reasoning = reasoning.toString())
        val attachments = if (response.images.isEmpty()) emptyList() else {
            val (images, files) = imagePersister.persist(response.images)
            persistedImages = images
            files
        }
        // 原生响应块可能包含未分发的工具调用；不能把它们作为可回放的对话历史保存。
        val omitCalls = stoppedDuringStream && response.toolCalls.isNotEmpty()
        return AgentEvent.AssistantText(response.content, if (omitCalls) emptyList() else response.toolCalls,
            response.reasoning.orEmpty().ifEmpty { reasoning.toString() }, response.signature.orEmpty(), response.inputTokens,
            response.outputTokens, response.cachedInputTokens, if (omitCalls) "" else response.thinkingBlocksJson.orEmpty(), attachments,
            messageId, CompletableDeferred()).also { cachedSnapshot = it }
    }

    suspend fun publishSnapshot() {
        val snapshot = snapshot()
        if (!snapshot.hasSnapshotData()) return
        if (!snapshotSent) {
            emit(snapshot)
            snapshotSent = true
        }
        withTimeoutOrNull(PERSIST_WAIT_MS) { snapshot.persisted?.await() }
    }

    fun markRecovered(error: Exception) { errorMessage = error.message }

    fun markFailed(error: Exception) {
        val message = error.message ?: error.javaClass.simpleName
        errorMessage = message
        failedElapsed = SystemClock.elapsedRealtime()
        telemetry.llmStreamFailed(callId, message, text.length)
    }

    private suspend fun finish() {
        try {
            runCancellationSafeTail {
                if (sessionId != null && hasOutput) {
                    runCatching { persistUnconfirmed(sessionId) }.onFailure { warn("保存回复快照失败", it) }
                }
                runCatching { recordCall() }.onFailure { warn("写入调用记录失败", it) }
            }
        } catch (t: Throwable) {
            warn("收尾落库异常", t)
        }
    }

    private suspend fun persistUnconfirmed(sessionId: String) {
        val snapshot = snapshot()
        if (!snapshot.hasSnapshotData() || snapshot.persistenceConfirmed()) return
        persistence.persist(sessionId, MessageRole.ASSISTANT, snapshot.content, id = messageId,
            reasoning = snapshot.reasoning, toolCalls = snapshot.toolCalls, signature = snapshot.signature,
            thinkingBlocksJson = snapshot.thinkingBlocksJson, attachments = snapshot.attachments,
            inputTokens = snapshot.inputTokens, outputTokens = snapshot.outputTokens, cachedInputTokens = snapshot.cachedInputTokens)
        snapshot.persisted?.complete(Unit)
    }

    private suspend fun recordCall() {
        val usage = finalResponse
        records.insert(LlmCallRecordEntity(sessionId = sessionId, providerId = provider.providerId.ifBlank { null },
            model = provider.model, reasoningEffort = reasoningEffort, kind = "chat", inputTokens = usage?.inputTokens ?: 0,
            outputTokens = usage?.outputTokens ?: 0, cachedInputTokens = usage?.cachedInputTokens ?: 0,
            cacheCreationTokens = usage?.cacheCreationTokens ?: 0, ttfbMillis = firstByteMs?.toInt(),
            durationMillis = ((failedElapsed ?: SystemClock.elapsedRealtime()) - startedElapsed).toInt(),
            status = when {
                completed -> "success"
                errorMessage != null -> "error"
                else -> "cancelled"
            },
            errorMessage = errorMessage, stopReason = usage?.stopReason, retryCount = retries, createdAt = startedWall))
    }

    private companion object {
        const val DELTA_THROTTLE_MS = 60L

        fun livePreview(text: StringBuilder): String {
            var start = (text.length - LIVE_TAIL_CHARS).coerceAtLeast(0)
            if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start++
            return text.substring(start)
        }

        fun warn(message: String, t: Throwable) {
            runCatching { FileLogger.w(TAG, "$message: ${t.javaClass.simpleName}") }
        }
    }
}
