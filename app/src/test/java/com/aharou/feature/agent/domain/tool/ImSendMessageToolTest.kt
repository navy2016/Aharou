package com.aharou.feature.agent.domain.tool

import android.content.Context
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.ContainerProfileResolver
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.permission.PermissionRulesRepository
import com.aharou.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aharou.feature.im.data.repository.TelegramSettingsRepository
import com.aharou.feature.im.domain.ImChannelConfig
import com.aharou.feature.im.domain.ImDeliveryTarget
import com.aharou.feature.im.domain.ImDeliveryTargetStore
import com.aharou.feature.im.domain.TelegramBotClient
import com.aharou.feature.settings.data.repository.ToolSafetySettingsRepository
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.net.SocketTimeoutException

class ImSendMessageToolTest {
    @get:Rule val temporary = TemporaryFolder()

    private val store = mockk<ImDeliveryTargetStore>(relaxed = true)
    private val telegram = mockk<TelegramBotClient>(relaxed = true)
    private val settings = mockk<TelegramSettingsRepository>(relaxed = true)
    private val mapper = mockk<WorkspacePathMapper>()
    private lateinit var workspace: File
    private lateinit var privateFiles: File
    private lateinit var tool: ImSendMessageTool
    private lateinit var context: AgentContext

    @Before
    fun setUp() {
        workspace = temporary.newFolder("workspace")
        privateFiles = temporary.newFolder("private")
        val app = mockk<Context>()
        every { app.filesDir } returns privateFiles
        every { app.cacheDir } returns temporary.newFolder("cache")
        val profiles = mockk<ContainerProfileResolver>()
        every { profiles.forWorkspace(any()) } returns ContainerProfile.BUILTIN_ALPINE
        every { mapper.resolveFile(any(), any(), any()) } answers {
            File(secondArg<String>(), firstArg<String>().removePrefix("~/workspace/")).canonicalFile
        }
        tool = ImSendMessageTool(
            store, telegram, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), settings,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), app, mapper, profiles
        )
        context = AgentContext(null, null, workspace.absolutePath, null, sessionId = "session")
        coEvery { settings.currentConfig() } returns object : ImChannelConfig {
            override val enabled = true
            override val ready = true
            override val proxy = ""
        }
        coEvery { store.find(any()) } returns null
        coEvery { store.register("telegram", "123", any()) } returns
            ImDeliveryTarget("tgt-test", "telegram", "123")
        coEvery { telegram.send(any(), "123", any()) } returns "message-1"
    }

    private fun args(path: String? = null) = buildMap {
        put("target", JsonPrimitive("telegram:123"))
        put("text", JsonPrimitive("result"))
        if (path != null) put("filePath", JsonPrimitive(path))
    }

    @Test
    fun requiresNetworkWritePermissionAndIdempotency() = runTest {
        assertEquals(ToolPermissionPolicy.ASK, tool.permissionPolicy)
        assertTrue(ToolCapability.NETWORK_WRITE in tool.capabilities)
        assertTrue("send_im_message" in SIDE_EFFECT_TOOL_NAMES)
        val policy = ToolPermissionPolicyEngine(
            mockk<PermissionRulesRepository>(relaxed = true),
            mockk<ToolSafetySettingsRepository>(relaxed = true)
        )
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.DENY,
            policy.evaluate(tool, tool.name, args(), AgentMode.PLAN, workspace.path).verdict
        )
    }

    @Test
    fun containerWorkspaceFileIsResolvedForTheCallingSession() = runTest {
        File(workspace, "result.txt").writeText("result")
        assertTrue(tool.executeWithContext(args("~/workspace/result.txt"), context) is ToolResult.Partial)
        coVerify(exactly = 1) { telegram.send(any(), "123", "result") }
    }

    @Test
    fun acceptsPrivateApplicationFiles() = runTest {
        val file = File(privateFiles, "result.txt").apply { writeText("result") }
        assertTrue(tool.executeWithContext(args(file.path), context) is ToolResult.Partial)
    }

    @Test
    fun rejectsTraversalBeforeRegisteringOrSending() = runTest {
        val outside = temporary.newFile("outside.txt")
        val path = "~/workspace/../${outside.name}"
        val result = tool.executeWithContext(args(path), context)
        assertEquals("PATH_NOT_ALLOWED", (result as ToolResult.Error).code)
        coVerify(exactly = 0) { store.register(any(), any(), any()) }
        coVerify(exactly = 0) { telegram.send(any(), any(), any()) }
    }

    @Test
    fun rejectsWorkspaceLinkToAnOutsideFile() = runTest {
        val outside = temporary.newFile("outside.txt")
        Files.createSymbolicLink(File(workspace, "link.txt").toPath(), outside.toPath())
        val result = tool.executeWithContext(args("~/workspace/link.txt"), context)
        assertEquals("PATH_NOT_ALLOWED", (result as ToolResult.Error).code)
        coVerify(exactly = 0) { telegram.send(any(), any(), any()) }
    }

    @Test
    fun unknownDeliveryIsNotRetried() = runTest {
        coEvery { telegram.send(any(), any(), any()) } throws SocketTimeoutException("timeout")
        assertTrue(tool.executeWithContext(args(), context) is ToolResult.Partial)
        coVerify(exactly = 1) { telegram.send(any(), any(), any()) }
    }
}
