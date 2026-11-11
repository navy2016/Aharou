package com.aharou.feature.agent.domain.provider

import com.aharou.feature.agent.data.remote.anthropic.AnthropicApi
import com.aharou.feature.agent.domain.model.AgentMessage
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anthropic 流式断流：`message_stop` 或已有 `stop_reason` 的 EOF 均正常收尾。
 *
 * 只验证 adapter 的解析与异常语义。「断流后已累积文本是否作为快照落库」在
 * `StatefulAgentWorkflow` 的 finally 里，不属于本通道的职责，故这里不覆盖。
 */
class AnthropicStreamInterruptionTest {

    private fun adapterFor(body: () -> ResponseBody): AnthropicAdapter {
        val api = mockk<AnthropicApi>()
        every { api.streamMessageCustom(any(), any(), any(), any(), any()) } answers { FakeStreamCall(body) }
        return AnthropicAdapter(api).apply {
            apiKey = "k"
            maxNetworkRetries = 0
        }
    }

    private val messages = listOf(AgentMessage.UserMessage(content = "hi"))

    /** 收 chunk 并返回收集到的异常：正常完成时为 null，断流时为对应 Throwable。 */
    private suspend fun collectInto(
        sink: MutableList<AIStreamChunk>,
        body: () -> ResponseBody
    ): Throwable? = runCatching {
        adapterFor(body).completeStream("", messages).collect { sink += it }
    }.exceptionOrNull()

    private fun textOf(chunks: List<AIStreamChunk>): String =
        chunks.filterIsInstance<AIStreamChunk.TextDelta>().joinToString("") { it.text }

    private fun List<AIStreamChunk>.singleFinal(): AIStreamChunk.Final =
        filterIsInstance<AIStreamChunk.Final>().single()

    private fun sse(vararg args: String) = FakeSseStream.body(*args)

    private val messageStart =
        """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-3-5-sonnet-20241022","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}}"""

    private val blockStart = """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""

    private val blockStop = """{"type":"content_block_stop","index":0}"""

    private val messageDelta =
        """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":2}}"""

    private val messageStop = """{"type":"message_stop"}"""

    private fun textDelta(text: String) =
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}"""

    private fun fullStreamBody() = sse(messageStart, blockStart, textDelta("你"), textDelta("好"), blockStop, messageDelta, messageStop)

    @Test
    fun message_stop_present_completes_with_final() = runTest {
        val chunks = mutableListOf<AIStreamChunk>()
        val error = collectInto(chunks) { fullStreamBody() }

        assertNull("收到 message_stop 的流不该报错，实际：$error", error)
        assertEquals("你好", textOf(chunks))
        val response = chunks.singleFinal().response
        assertEquals("你好", response.content)
        assertEquals("end_turn", response.stopReason)
    }

    @Test
    fun missing_message_stop_is_reported_as_interrupted() = runTest {
        val chunks = mutableListOf<AIStreamChunk>()
        // 故意去掉 message_stop 与 message_delta：连接正常关闭但没有任何终止信号，
        // 等价于线上「AI 说到一半突然没了」。
        val error = collectInto(chunks) {
            sse(messageStart, blockStart, textDelta("你"), textDelta("好"), blockStop)
        }

        assertTrue("缺终止事件必须抛 IOException，实际：$error", error is IOException)
        assertTrue(
            "断流文案应由 adapter 统一给出，实际：${error?.message}",
            error!!.message.orEmpty().startsWith(FakeSseStream.INTERRUPTED_PREFIX)
        )
        // 不断言「已收到的增量被收集到」：流以异常结束时 channel 随之关闭/取消，
        // 已 send 但尚未被收集端取走的增量可能丢失，这是 flow 的语义，不是本通道可保证的契约。
        // 「在收到部分 delta 之后才断」由假流本身保证：数据行全部投递完毕后才轮到 EOF。
        assertTrue(
            "断流的流不许产出 Final，否则上层会把残缺内容当完整回复",
            chunks.filterIsInstance<AIStreamChunk.Final>().isEmpty()
        )
    }

    @Test
    fun streamApiErrorRetainsCodeForEnhancedRetry() = runTest {
        val chunks = mutableListOf<AIStreamChunk>()
        val error = collectInto(chunks) {
            sse("""{"type":"error","error":{"type":"overloaded_error","message":"overloaded"}}""")
        }
        assertTrue(error is StreamApiException)
        assertEquals("overloaded_error", (error as StreamApiException).code)
        assertEquals("overloaded", error.message)
        assertTrue(chunks.none { it is AIStreamChunk.Final })
    }

    @Test
    fun stop_reason_without_message_stop_completes() = runTest {
        val chunks = mutableListOf<AIStreamChunk>()
        // 个别中转在 message_delta 给出 stop_reason 后直接关流。此时本轮生成已完整，
        // 按正常结束收尾，不能判成断流去整轮重试。
        val error = collectInto(chunks) {
            sse(messageStart, blockStart, textDelta("你"), textDelta("好"), blockStop, messageDelta)
        }

        assertNull("stop_reason 已到的流不该报错，实际：$error", error)
        assertEquals("你好", textOf(chunks))
        val response = chunks.singleFinal().response
        assertEquals("你好", response.content)
        assertEquals("end_turn", response.stopReason)
        assertEquals(10, response.inputTokens)
        assertEquals(2, response.outputTokens)
        assertTrue(chunks.none { it is AIStreamChunk.Retrying })
    }

    @Test
    fun message_delta_without_delta_preserves_stop_reason_and_final_usage() = runTest {
        val chunks = mutableListOf<AIStreamChunk>()
        val error = collectInto(chunks) {
            sse(
                messageStart, blockStart, textDelta("好"), blockStop, messageDelta,
                """{"type":"message_delta","usage":{"input_tokens":12,"output_tokens":7,"cache_read_input_tokens":3,"cache_creation_input_tokens":2}}"""
            )
        }

        assertNull(error)
        val response = chunks.singleFinal().response
        assertEquals("好", response.content)
        assertEquals("end_turn", response.stopReason)
        assertEquals(17, response.inputTokens)
        assertEquals(7, response.outputTokens)
        assertEquals(3, response.cachedInputTokens)
        assertEquals(2, response.cacheCreationTokens)
    }

    @Test
    fun io_failure_after_stop_reason_is_not_treated_as_normal_eof() = runTest {
        val chunks = mutableListOf<AIStreamChunk>()
        val error = collectInto(chunks) {
            FakeSseStream.interruptingBody(listOf(messageStart, blockStart, textDelta("好"), messageDelta))
        }

        assertTrue(error is IOException)
        assertEquals("模拟连接被重置", error?.message)
        assertTrue(chunks.none { it is AIStreamChunk.Final })
    }

    @Test
    fun io_failure_midway_propagates_without_being_swallowed() = runTest {
        val chunks = mutableListOf<AIStreamChunk>()
        val error = collectInto(chunks) {
            FakeSseStream.interruptingBody(listOf(messageStart, blockStart, textDelta("你"), textDelta("好")))
        }

        assertTrue("底层读取失败必须以 IOException 形式冒给上层，实际：$error", error is IOException)
        // 只在 EOF 时由 adapter 自己造统一文案；底层异常必须保留原始 message，
        // 否则用户与日志都看不到「连接被重置」这类真实原因。
        assertEquals("模拟连接被重置", error!!.message)
        // 「读到一半」是假流保证的：先逐行投递 delta，全部投递完才在 read 里抛错。
        // 不在此断言收集到的增量——流异常结束时缓存中的增量可能尚未被收集端取走。
        assertTrue(
            "读取失败的流同样不许产出 Final",
            chunks.filterIsInstance<AIStreamChunk.Final>().isEmpty()
        )
    }
}
