package com.aharou.feature.agent.domain.mcp

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.CommandResult
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.plugin.data.PluginRecord
import com.aharou.feature.plugin.data.PluginRecordStore
import com.aharou.feature.plugin.domain.PluginManager
import com.aharou.feature.plugin.domain.model.PluginManifest
import io.mockk.Called
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class McpPluginServersTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val containerInstaller = mockk<ContainerInstaller>()
    private val recordStore = mockk<PluginRecordStore>()
    private val engine = mockk<LinuxContainerEngine>()
    private val records = mutableMapOf<String, PluginRecord>()
    private lateinit var pluginServers: McpPluginServers
    private val manager by lazy {
        spyk(PluginManager(engine, containerInstaller, recordStore, mockk())).also {
            every { it.startHealthSupervisor() } just Runs
        }
    }

    @Before
    fun setUp() {
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } just Runs
        every { FileLogger.w(any(), any(), any()) } just Runs
        every { containerInstaller.aharouDir } returns temporary.root
        every { engine.isContainerInstalled() } returns true
        every { recordStore.all() } answers { records.toMap() }
        every { recordStore.remove(any()) } answers {
            records.remove(firstArg<String>())
            Unit
        }
        every { recordStore.save(any()) } answers {
            val record = firstArg<PluginRecord>()
            records[record.id] = record
        }
        pluginServers = McpPluginServers(containerInstaller, recordStore)
    }

    @After
    fun tearDown() {
        unmockkObject(FileLogger)
    }

    @Test
    fun servers_unrecordedManifest_isIgnored() = runTest {
        writeManifest(PluginManifest(id = "demo", mcpEntry = "bin/mcp"))

        assertTrue(pluginServers.servers().isEmpty())
    }

    @Test
    fun servers_recordWithoutManifest_isIgnored() = runTest {
        records["demo"] = PluginRecord(id = "demo")
        temporary.newFolder("plugins", "demo")

        assertTrue(pluginServers.servers().isEmpty())
    }

    @Test
    fun servers_recordWithoutDirectory_isIgnored() = runTest {
        records["demo"] = PluginRecord(id = "demo")

        assertTrue(pluginServers.servers().isEmpty())
    }

    @Test
    fun servers_manifestIdMustMatchRecordedDirectory() = runTest {
        records["demo"] = PluginRecord(id = "demo")
        writeManifest(PluginManifest(id = "other", mcpEntry = "bin/mcp"), directoryId = "demo")

        assertTrue(pluginServers.servers().isEmpty())
    }

    @Test
    fun servers_blankManifestId_isIgnored() = runTest {
        records["demo"] = PluginRecord(id = "demo")
        writeManifest(PluginManifest(mcpEntry = "bin/mcp"), directoryId = "demo")

        assertTrue(pluginServers.servers().isEmpty())
    }

    @Test
    fun servers_blankMcpEntry_isIgnored() = runTest {
        records["demo"] = PluginRecord(id = "demo")
        writeManifest(PluginManifest(id = "demo", mcpEntry = "  "))

        assertTrue(pluginServers.servers().isEmpty())
    }

    @Test
    fun servers_installedPlugin_preservesMcpDeclaration() = runTest {
        records["demo"] = PluginRecord(id = "demo", installedAt = 1L)
        writeManifest(
            PluginManifest(
                id = "demo",
                mcpEntry = " bin/mcp ",
                mcpArgs = listOf("--stdio"),
                environment = mapOf("MODE" to "test"),
            )
        )

        assertEquals(
            McpServerConfig(
                name = "plugin-demo",
                command = "/root/.aharou/plugins/demo/bin/mcp",
                args = listOf("--stdio"),
                env = mapOf("MODE" to "test"),
                keepAlive = true,
            ),
            pluginServers.servers().single()
        )
    }

    @Test
    fun servers_serviceNotStarted_doesNotDisableMcp() = runTest {
        records["demo"] = PluginRecord(id = "demo", enabled = false)
        writeManifest(
            PluginManifest(
                id = "demo",
                servicePort = 8080,
                launchCommand = "bin/http",
                mcpEntry = "/opt/demo/mcp",
            )
        )

        val server = pluginServers.servers().single()
        assertEquals("/opt/demo/mcp", server.command)
        assertTrue(server.enabled)
        assertTrue(server.isStdio)
        records["demo"] = records.getValue("demo").copy(enabled = true)
        assertEquals(server, pluginServers.servers().single())
        verify { engine wasNot Called }
    }

    @Test
    fun importReplacement_waitsForSuccessfulInstallationBeforeDeriving() = runTest {
        records["demo"] = PluginRecord(id = "demo", version = "1")
        writeManifest(PluginManifest(id = "demo", name = "Demo", version = "1", mcpEntry = "bin/old"))
        val replacement = PluginManifest(id = "demo", name = "Demo", version = "2", mcpEntry = "bin/new")

        assertTrue(manager.importPackage(pluginPackage(replacement)).isSuccess)

        assertNull(records["demo"])
        assertEquals("2", manager.readManifest("demo")?.version)
        assertTrue(pluginServers.servers().isEmpty())
        verify(exactly = 0) { recordStore.save(any()) }
    }

    @Test
    fun importReplacement_failureRemovesOldRecordBeforeChangingPayload() = runTest {
        records["demo"] = PluginRecord(id = "demo", version = "1")
        writeManifest(PluginManifest(id = "demo", name = "Demo", version = "1", mcpEntry = "bin/old"))
        val oldPayload = File(temporary.root, "plugins/demo/payload/old")
        oldPayload.parentFile!!.mkdirs()
        oldPayload.writeText("old")
        every { recordStore.remove("demo") } answers {
            assertTrue(oldPayload.isFile)
            records.remove("demo")
            Unit
        }
        val replacement = PluginManifest(id = "demo", name = "Demo", version = "2", mcpEntry = "bin/new")
        val zip = pluginPackage(replacement, payloadEntry = "payload/../../outside")

        val result = manager.importPackage(zip)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("包内路径越界"))
        assertFalse(oldPayload.exists())
        assertNull(records["demo"])
        assertEquals("1", manager.readManifest("demo")?.version)
        assertTrue(pluginServers.servers().isEmpty())
        verify(exactly = 0) { recordStore.save(any()) }
    }

    @Test
    fun install_successRegistersOnlyAfterStepsAndVerification() = runTest {
        records["demo"] = PluginRecord(id = "demo", version = "1")
        writeManifest(
            PluginManifest(
                id = "demo",
                name = "Demo",
                version = "2",
                mcpEntry = "bin/mcp",
                installSteps = listOf("install-demo"),
                verifyCommand = "verify-demo",
            )
        )
        val serversDuringInstall = mutableListOf<List<McpServerConfig>>()
        coEvery { engine.runCommandSyncWithExit(any(), null, any()) } coAnswers {
            serversDuringInstall += pluginServers.servers()
            CommandResult("", 0)
        }

        assertTrue(manager.install("demo").isSuccess)

        assertEquals(listOf(emptyList<McpServerConfig>(), emptyList<McpServerConfig>()), serversDuringInstall)
        assertEquals("2", records["demo"]?.version)
        assertEquals("plugin-demo", pluginServers.servers().single().name)
        verify(exactly = 1) { recordStore.save(match { it.id == "demo" && it.version == "2" }) }
        verify(exactly = 1) { manager.startHealthSupervisor() }
    }

    @Test
    fun reinstall_failedStepDoesNotKeepOldRecord() = runTest {
        records["demo"] = PluginRecord(id = "demo", version = "1")
        writeManifest(
            PluginManifest(
                id = "demo",
                mcpEntry = "bin/mcp",
                installSteps = listOf("install-demo"),
                verifyCommand = "verify-demo",
            )
        )
        coEvery { engine.runCommandSyncWithExit(any(), null, any()) } returns CommandResult("", 1)

        assertTrue(manager.install("demo").isFailure)

        assertNull(records["demo"])
        assertTrue(pluginServers.servers().isEmpty())
        coVerify(exactly = 1) { engine.runCommandSyncWithExit(any(), null, any()) }
        verify(exactly = 0) { recordStore.save(any()) }
        verify(exactly = 0) { manager.startHealthSupervisor() }
    }

    @Test
    fun reinstall_failedVerificationDoesNotKeepOldRecord() = runTest {
        records["demo"] = PluginRecord(id = "demo", version = "1")
        writeManifest(
            PluginManifest(
                id = "demo",
                mcpEntry = "bin/mcp",
                installSteps = listOf("install-demo"),
                verifyCommand = "verify-demo",
            )
        )
        coEvery { engine.runCommandSyncWithExit(any(), null, any()) } returnsMany listOf(
            CommandResult("", 0),
            CommandResult("", 1),
        )

        assertTrue(manager.install("demo").isFailure)

        assertNull(records["demo"])
        assertTrue(pluginServers.servers().isEmpty())
        coVerify(exactly = 2) { engine.runCommandSyncWithExit(any(), null, any()) }
        verify(exactly = 0) { recordStore.save(any()) }
        verify(exactly = 0) { manager.startHealthSupervisor() }
    }

    private fun writeManifest(manifest: PluginManifest, directoryId: String = manifest.id) {
        val dir = File(temporary.root, "plugins/$directoryId").apply { mkdirs() }
        File(dir, "manifest.json").writeText(Json.encodeToString(PluginManifest.serializer(), manifest))
    }

    private fun pluginPackage(manifest: PluginManifest, payloadEntry: String = "payload/entry"): File {
        val file = temporary.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(Json.encodeToString(PluginManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(payloadEntry))
            zip.write("payload".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return file
    }
}
