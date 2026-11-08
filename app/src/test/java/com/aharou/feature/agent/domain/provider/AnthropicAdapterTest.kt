package com.aharou.feature.agent.domain.provider

import com.aharou.feature.agent.data.remote.anthropic.AnthropicApi
import com.aharou.feature.agent.data.remote.anthropic.AnthropicContentBlock
import com.aharou.feature.agent.data.remote.anthropic.AnthropicMessage
import com.aharou.feature.agent.data.remote.anthropic.AnthropicMessageRequest
import com.aharou.feature.agent.data.remote.anthropic.AnthropicMessageResponse
import com.aharou.feature.agent.data.remote.anthropic.AnthropicStopDetails
import com.aharou.feature.agent.data.remote.anthropic.AnthropicUsage
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.agent.domain.tool.ToolCall
import com.aharou.feature.settings.domain.model.ReasoningConfig
import com.aharou.feature.settings.domain.model.ReasoningDialect
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Call

class AnthropicAdapterTest {

    /** 捕获实际上送的请求体，并返回可配置的响应。 */
    private class FakeApi(var response: AnthropicMessageResponse) : AnthropicApi {
        var lastRequest: AnthropicMessageRequest? = null
        var lastBody: com.google.gson.JsonObject? = null

        override suspend fun createMessage(
            url: String,
            apiKey: String?,
            version: String?,
            extraHeaders: Map<String, String>,
            request: AnthropicMessageRequest
        ): AnthropicMessageResponse {
            lastRequest = request
            return response
        }

        override suspend fun createMessageCustom(
            url: String,
            apiKey: String?,
            version: String?,
            extraHeaders: Map<String, String>,
            request: okhttp3.RequestBody
        ): AnthropicMessageResponse {
            val body = request.decodeJsonObject()
            lastBody = body
            return createMessage(url, apiKey, version, extraHeaders, Gson().fromJson(body, AnthropicMessageRequest::class.java))
        }

        override fun streamMessageCustom(
            url: String,
            apiKey: String?,
            version: String?,
            extraHeaders: Map<String, String>,
            request: okhttp3.RequestBody
        ): Call<ResponseBody> {
            val body = request.decodeJsonObject()
            lastBody = body
            return streamMessage(url, apiKey, version, extraHeaders, Gson().fromJson(body, AnthropicMessageRequest::class.java))
        }

        override fun streamMessage(
            url: String,
            apiKey: String?,
            version: String?,
            extraHeaders: Map<String, String>,
            request: AnthropicMessageRequest
        ): Call<ResponseBody> {
            lastRequest = request
            return FakeStreamCall {
                FakeSseStream.body(
                    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
                    """{"type":"message_stop"}"""
                )
            }
        }
    }

    private fun response(
        content: List<AnthropicContentBlock> = listOf(AnthropicContentBlock(type = "text", text = "ok")),
        stopReason: String? = "end_turn",
        stopDetails: AnthropicStopDetails? = null,
        usage: AnthropicUsage = AnthropicUsage(input_tokens = 10, output_tokens = 5)
    ) = AnthropicMessageResponse(
        id = "msg_1",
        type = "message",
        role = "assistant",
        content = content,
        model = "claude-3-5-sonnet-20241022",
        stop_reason = stopReason,
        stop_sequence = null,
        stop_details = stopDetails,
        usage = usage
    )

    private fun adapter(api: FakeApi, maxOutput: Int? = null): AnthropicAdapter =
        AnthropicAdapter(api).apply {
            apiKey = "k"
            maxOutputTokens = maxOutput
        }

    private fun blocksOf(request: AnthropicMessageRequest?, role: String): List<AnthropicContentBlock> {
        val message = request?.messages?.first { it.role == role } ?: return emptyList()
        return contentBlocks(message.content)
    }

    private fun contentBlocks(content: Any): List<AnthropicContentBlock> =
        Gson().fromJson(Gson().toJsonTree(content), Array<AnthropicContentBlock>::class.java).toList()

    private fun toolCall(id: String) = ToolCall(id = id, name = "readFile", arguments = JsonObject(emptyMap()))

    @Test
    fun max_tokens_uses_model_metadata_output_limit() = runTest {
        val api = FakeApi(response())
        adapter(api, maxOutput = 64000).complete("sys", listOf(AgentMessage.UserMessage(content = "hi")))

        assertEquals(64000, api.lastRequest?.max_tokens)
    }

    @Test
    fun max_tokens_falls_back_when_metadata_missing() = runTest {
        val api = FakeApi(response())
        adapter(api, maxOutput = null).complete("sys", listOf(AgentMessage.UserMessage(content = "hi")))

        assertEquals(16384, api.lastRequest?.max_tokens)
    }

    @Test
    fun max_tokens_leaves_room_for_content_above_thinking_budget() = runTest {
        val api = FakeApi(response())
        // 元数据上限比思考预算还小时不能直接用：max_tokens 必须大于 budget_tokens，否则服务端 400。
        adapter(api, maxOutput = 2048).complete(
            "sys",
            listOf(AgentMessage.UserMessage(content = "hi")),
            reasoningEffort = "high"
        )

        assertEquals(8192, api.lastRequest?.thinking?.budget_tokens)
        assertEquals(8192 + 4096, api.lastRequest?.max_tokens)
    }

    @Test
    fun reasoning_budget_patch_and_custom_body_reach_sync_and_stream_requests() = runTest {
        for (stream in listOf(false, true)) {
            val api = FakeApi(response())
            val adapter = adapter(api, maxOutput = 2048).apply {
                maxNetworkRetries = 0
                temperature = 0.7f
                reasoningConfig = ReasoningConfig(
                    dialect = ReasoningDialect.ANTHROPIC_BUDGET,
                    budgets = mapOf(ReasoningEffort.HIGH to 6144),
                    patches = mapOf(ReasoningEffort.HIGH to JsonParser.parseString(
                        """{"nullable":null}"""
                    ).asJsonObject)
                )
            }
            val messages = listOf(AgentMessage.UserMessage(content = "hi"))

            if (stream) adapter.completeStream("sys", messages, reasoningEffort = "high").toList()
            else adapter.complete("sys", messages, reasoningEffort = "high")
            val generated = api.lastBody!!
            assertEquals("enabled", generated.getAsJsonObject("thinking").get("type").asString)
            assertEquals(6144, generated.getAsJsonObject("thinking").get("budget_tokens").asInt)
            assertFalse(generated.getAsJsonObject("thinking").has("display"))
            assertEquals(10240, generated.get("max_tokens").asInt)
            assertFalse(generated.has("temperature"))
            assertTrue(generated.get("nullable").isJsonNull)
            assertEquals(stream, generated.get("stream").asBoolean)
            assertEquals(1, generated.getAsJsonArray("messages").size())

            adapter.customBody = JsonParser.parseString(
                """{"thinking":{"budget_tokens":2048,"display":null},"max_tokens":1024,"temperature":0.2,"nullable":null}"""
            ).asJsonObject
            if (stream) adapter.completeStream("sys", messages, reasoningEffort = "high").toList()
            else adapter.complete("sys", messages, reasoningEffort = "high")
            val overridden = api.lastBody!!
            assertFalse(overridden.getAsJsonObject("thinking").has("type"))
            assertEquals(2048, overridden.getAsJsonObject("thinking").get("budget_tokens").asInt)
            assertTrue(overridden.getAsJsonObject("thinking").get("display").isJsonNull)
            assertEquals(1024, overridden.get("max_tokens").asInt)
            assertEquals(0.2, overridden.get("temperature").asDouble, 0.0001)
            assertTrue(overridden.get("nullable").isJsonNull)
            assertEquals(stream, overridden.get("stream").asBoolean)
        }
    }

    @Test
    fun adaptive_default_and_none_keep_base_temperature_in_sync_and_stream_requests() = runTest {
        for (stream in listOf(false, true)) {
            val api = FakeApi(response())
            val adapter = adapter(api, maxOutput = 4096).apply {
                model = "claude-sonnet-4-6"
                maxNetworkRetries = 0
                temperature = 0.7f
            }
            val messages = listOf(AgentMessage.UserMessage(content = "hi"))

            if (stream) adapter.completeStream("sys", messages).toList()
            else adapter.complete("sys", messages)
            val defaultBody = api.lastBody!!
            assertFalse(defaultBody.has("thinking"))
            assertFalse(defaultBody.has("output_config"))
            assertEquals(4096, defaultBody.get("max_tokens").asInt)
            assertEquals(0.7, defaultBody.get("temperature").asDouble, 0.0001)

            if (stream) adapter.completeStream("sys", messages, reasoningEffort = "none").toList()
            else adapter.complete("sys", messages, reasoningEffort = "none")
            val disabled = api.lastBody!!
            assertEquals("disabled", disabled.getAsJsonObject("thinking").get("type").asString)
            assertFalse(disabled.has("output_config"))
            assertEquals(4096, disabled.get("max_tokens").asInt)
            assertEquals(0.7, disabled.get("temperature").asDouble, 0.0001)

            if (stream) adapter.completeStream("sys", messages, reasoningEffort = "high").toList()
            else adapter.complete("sys", messages, reasoningEffort = "high")
            val adaptive = api.lastBody!!
            assertEquals("adaptive", adaptive.getAsJsonObject("thinking").get("type").asString)
            assertEquals("high", adaptive.getAsJsonObject("output_config").get("effort").asString)
            assertFalse(adaptive.has("temperature"))
            assertEquals(4096, adaptive.get("max_tokens").asInt)
        }
    }

    @Test
    fun thinking_and_redacted_blocks_are_snapshotted_in_order() = runTest {
        val api = FakeApi(
            response(
                content = listOf(
                    AnthropicContentBlock(type = "thinking", thinking = "先想想", signature = "sig-a"),
                    AnthropicContentBlock(type = "redacted_thinking", data = "opaque-blob"),
                    AnthropicContentBlock(type = "thinking", thinking = "再想想", signature = "sig-b"),
                    AnthropicContentBlock(type = "text", text = "答案")
                )
            )
        )
        val result = adapter(api).complete("sys", listOf(AgentMessage.UserMessage(content = "hi")))

        assertEquals("答案", result.content)
        // 思考文本仍拼接给 UI 展示，但回传用的快照必须保留三个原始块及其顺序。
        assertEquals("先想想再想想", result.reasoning)
        val snapshot = result.thinkingBlocksJson
        assertTrue(snapshot != null && snapshot.contains("opaque-blob"))
        assertEquals(
            listOf("thinking", "redacted_thinking", "thinking"),
            Regex("\"type\":\"(\\w+)\"").findAll(snapshot!!).map { it.groupValues[1] }.toList()
        )
    }

    @Test
    fun stop_details_and_cache_creation_are_surfaced() = runTest {
        val api = FakeApi(
            response(
                content = emptyList(),
                stopReason = "refusal",
                stopDetails = AnthropicStopDetails(type = "refusal", explanation = "违反使用政策"),
                usage = AnthropicUsage(
                    input_tokens = 10,
                    output_tokens = 0,
                    cache_read_input_tokens = 7,
                    cache_creation_input_tokens = 3
                )
            )
        )
        val result = adapter(api).complete("sys", listOf(AgentMessage.UserMessage(content = "hi")))

        // 拒答时正文为空，只有 stop_details 里有可展示的理由。
        assertEquals("违反使用政策", result.stopDetail)
        assertTrue(result.isAborted)
        assertEquals(7, result.cachedInputTokens)
        assertEquals(3, result.cacheCreationTokens)
    }

    @Test
    fun assistant_history_replays_thinking_snapshot_verbatim() = runTest {
        val api = FakeApi(response())
        val snapshot = """[{"type":"thinking","thinking":"上轮思考","signature":"sig-a"},""" +
            """{"type":"redacted_thinking","data":"opaque-blob"}]"""
        adapter(api).complete(
            "sys",
            listOf(
                AgentMessage.UserMessage(content = "hi"),
                AgentMessage.AssistantMessage(
                    content = "上轮回答",
                    reasoning = "上轮思考",
                    signature = "sig-a",
                    thinkingBlocksJson = snapshot
                )
            )
        )

        val blocks = blocksOf(api.lastRequest, "assistant")
        assertEquals(listOf("thinking", "redacted_thinking", "text"), blocks.map { it.type })
        assertEquals("opaque-blob", blocks[1].data)
        assertEquals("sig-a", blocks[0].signature)
    }

    @Test
    fun assistant_history_falls_back_to_signature_block_without_snapshot() = runTest {
        val api = FakeApi(response())
        adapter(api).complete(
            "sys",
            listOf(
                AgentMessage.UserMessage(content = "hi"),
                // 旧数据/备份恢复只有 signature，没有原生快照。
                AgentMessage.AssistantMessage(
                    content = "上轮回答",
                    reasoning = "上轮思考",
                    signature = "sig-a"
                )
            )
        )

        val blocks = blocksOf(api.lastRequest, "assistant")
        assertEquals(listOf("thinking", "text"), blocks.map { it.type })
        assertEquals("上轮思考", blocks[0].thinking)
        assertNull(blocks[0].data)
    }

    @Test
    fun assistant_history_sends_no_thinking_block_when_nothing_stored() = runTest {
        val api = FakeApi(response())
        adapter(api).complete(
            "sys",
            listOf(
                AgentMessage.UserMessage(content = "hi"),
                AgentMessage.AssistantMessage(content = "上轮回答")
            )
        )

        val blocks = blocksOf(api.lastRequest, "assistant")
        assertEquals(listOf("text"), blocks.map { it.type })
    }

    @Test
    fun parallel_tool_results_go_into_one_user_message() = runTest {
        val api = FakeApi(response())
        adapter(api).complete(
            "sys",
            listOf(
                AgentMessage.UserMessage(content = "hi"),
                AgentMessage.AssistantMessage(
                    content = "并行读两个文件",
                    toolCalls = listOf(toolCall("c1"), toolCall("c2"))
                ),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "readFile", result = "a"),
                AgentMessage.ToolResultMessage(id = "c2", toolName = "readFile", result = "b")
            )
        )

        // 两个 tool_result 拆成两条 user 消息时，不合并连续 user 轮的网关会报
        // "tool_use ids were found without tool_result blocks immediately after"。
        val messages = api.lastRequest!!.messages
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.role })
        val resultBlocks = contentBlocks(messages.last().content)
        assertEquals(listOf("tool_result", "tool_result"), resultBlocks.map { it.type })
        assertEquals(listOf("c1", "c2"), resultBlocks.map { it.tool_use_id })
    }

    @Test
    fun user_message_keeps_cache_breakpoint_on_last_text_block() = runTest {
        val api = FakeApi(response())
        adapter(api).complete("sys", listOf(AgentMessage.UserMessage(content = "hi")))

        val user: AnthropicMessage = api.lastRequest!!.messages.last { it.role == "user" }
        val blocks = contentBlocks(user.content)
        assertEquals("ephemeral", blocks.last().cache_control?.get("type"))
    }
}
