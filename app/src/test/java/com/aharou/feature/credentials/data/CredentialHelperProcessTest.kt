package com.aharou.feature.credentials.data

import com.aharou.feature.credentials.data.CredentialRequestBridge.CredentialRequest
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CredentialHelperProcessTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun withHelper(block: (Process, File, CredentialRequest) -> Unit) {
        val directory = temporary.newFolder()
        FileSystems.getDefault().newWatchService().use { watcher ->
            directory.toPath().register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY)
            val process = ProcessBuilder("sh", File("src/main/assets/aharou/git-credential-aicode").absolutePath, "get")
                .apply {
                    environment()["AICODE_DIR"] = directory.absolutePath
                    environment().remove("AICODE_CRED_NO_PROMPT")
                    redirectErrorStream(true)
                }.start()
            try {
                process.outputStream.bufferedWriter().use { it.write("host=example.invalid\n\n") }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var file: File? = null
                while (file == null) {
                    file = directory.listFiles()?.firstOrNull { it.name.startsWith("cred-req-") && !it.name.endsWith(".tmp") }
                    if (file != null) break
                    val remaining = deadline - System.nanoTime()
                    check(remaining > 0) { "Helper did not publish a request" }
                    val key = checkNotNull(watcher.poll(remaining, TimeUnit.NANOSECONDS)) { "Helper request timed out" }
                    key.pollEvents()
                    key.reset()
                }
                val requestFile = requireNotNull(file)
                val fields = requestFile.readLines().associate { it.substringBefore('=') to it.substringAfter('=') }
                val request = CredentialRequest(requestFile.name.removePrefix("cred-req-"), fields.getValue("host"),
                    fields.getValue("pid").toInt(), fields.getValue("start_ticks").toLong(), fields.getValue("boot_id"))
                block(process, requestFile, request)
            } finally {
                if (process.isAlive) process.destroyForcibly()
                check(process.waitFor(5, TimeUnit.SECONDS)) { "Helper did not exit" }
            }
        }
    }

    @Test
    fun responseIsConsumedAndNormalExitRemovesIpcFiles() = withHelper { process, file, request ->
        assertTrue(CredentialRequestLifetime().isActive(file, request))
        val response = File(file.parentFile, "cred-resp-${request.requestId}")
        val staged = File(file.parentFile, "response.tmp").apply { writeText("username=test\npassword=fixture\n") }
        assertTrue(staged.renameTo(response))
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        assertEquals("username=test\npassword=fixture\n", process.inputStream.bufferedReader().readText())
        assertFalse(file.exists())
        assertFalse(response.exists())
    }

    @Test
    fun terminationSignalRemovesTheRequest() = withHelper { process, file, _ ->
        process.destroy()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertFalse(file.exists())
    }

    @Test
    fun forcedExitLeavesAFileThatTheLifetimeCheckRejects() = withHelper { process, file, request ->
        process.destroyForcibly()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertTrue(file.exists())
        assertFalse(CredentialRequestLifetime().isActive(file, request))
    }
}
