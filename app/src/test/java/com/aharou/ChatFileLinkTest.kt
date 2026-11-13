package com.aharou

import androidx.compose.ui.platform.UriHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天区 Markdown 链接目标的「文件路径 vs 网址」判定与行号拆分（[asChatFilePath] / [splitPathAndLine]）。
 *
 * 不覆盖 `file://`：其解码走 android.net.Uri，JVM 单测下被桩成默认返回值。
 */
class ChatFileLinkTest {
    private var workspaceRoot: String? = "/data/user/0/com.aharou.agent.debug/files/projects/default"
    private val openedFiles = mutableListOf<Pair<String, Int>>()
    private val openedUrls = mutableListOf<String>()
    private val externalUris = mutableListOf<String>()
    private val htmlPaths = mutableListOf<String>()
    private var htmlPreviewUrl: String? = "file:///tmp/preview.html"
    private val handler = FileAwareUriHandler(
        delegate = object : UriHandler {
            override fun openUri(uri: String) { externalUris += uri }
        },
        onOpenUrl = { openedUrls += it },
        toFileUrl = { path -> htmlPaths += path; htmlPreviewUrl },
        workspaceRoot = { workspaceRoot },
        onOpenFile = { path, line -> openedFiles += path to line },
    )

    // ---------- asChatFilePath：判定为文件路径 ----------

    @Test
    fun bare_file_name_with_line_is_file_path() {
        assertEquals("example.py:1", asChatFilePath("example.py:1"))
        assertEquals("config.json:1", asChatFilePath("config.json:1"))
        assertEquals("notes.md:12", asChatFilePath("notes.md:12"))
    }

    @Test
    fun relative_and_container_paths_are_file_paths() {
        assertEquals("notes.md", asChatFilePath("notes.md"))
        assertEquals("app/src/main/java/com/aicode/MainActivity.kt:42", asChatFilePath("app/src/main/java/com/aicode/MainActivity.kt:42"))
        assertEquals("~/workspace/example.py:1", asChatFilePath("~/workspace/example.py:1"))
        assertEquals("/etc/apk/repositories", asChatFilePath("/etc/apk/repositories"))
    }

    // ---------- asChatFilePath：判定为网址 ----------

    @Test
    fun web_urls_go_to_browser() {
        assertNull(asChatFilePath("https://github.com/a/b"))
        assertNull(asChatFilePath("http://example.com:8080/p"))
        assertNull(asChatFilePath("content://com.aharou.files/x"))
    }

    @Test
    fun opaque_url_schemes_go_to_browser() {
        assertNull(asChatFilePath("mailto:dev@example.com"))
        assertNull(asChatFilePath("tel:12345"))
        assertNull(asChatFilePath("MAILTO:dev@example.com"))
    }

    // ---------- splitPathAndLine ----------

    @Test
    fun trailing_line_number_is_split_off() {
        assertEquals("example.py" to 1, splitPathAndLine("example.py:1"))
        assertEquals("~/workspace/a.kt" to 42, splitPathAndLine("~/workspace/a.kt:42"))
    }

    @Test
    fun path_without_line_number_keeps_zero() {
        assertEquals("notes.md" to 0, splitPathAndLine("notes.md"))
        assertEquals("~/workspace/notes.md" to 0, splitPathAndLine("~/workspace/notes.md"))
    }

    @Test
    fun oversized_line_number_falls_back_to_zero() {
        assertEquals("a.kt" to 0, splitPathAndLine("a.kt:99999999999999"))
    }

    @Test
    fun host_workspace_svg_link_opens_source_editor() {
        handler.openUri("/data/user/0/com.aharou.agent.debug/files/projects/default/pelican-on-bike.svg")
        assertEquals(listOf("~/workspace/pelican-on-bike.svg" to 0), openedFiles)
        assertTrue(openedUrls.isEmpty())
        assertTrue(externalUris.isEmpty())
        assertTrue(htmlPaths.isEmpty())
    }

    @Test
    fun relative_and_container_svg_links_keep_editor_routing() {
        val paths = listOf("pelican-on-bike.svg", "~/workspace/pelican-on-bike.svg", "/root/workspace/pelican-on-bike.svg", "/etc/icons/pelican.svg")
        paths.forEach(handler::openUri)
        assertEquals(paths.map { it to 0 }, openedFiles)
        assertTrue(openedUrls.isEmpty())
        assertTrue(externalUris.isEmpty())
        assertTrue(htmlPaths.isEmpty())
    }

    @Test
    fun host_svg_link_preserves_line_number_and_file_name() {
        handler.openUri("$workspaceRoot/diagrams/Pelican On Bike.SVG:42")
        assertEquals(listOf("~/workspace/diagrams/Pelican On Bike.SVG" to 42), openedFiles)
        assertTrue(openedUrls.isEmpty())
        assertTrue(htmlPaths.isEmpty())
    }

    @Test
    fun workspace_path_matching_requires_directory_boundary() {
        val paths = listOf("${workspaceRoot}-other/pelican.svg", "$workspaceRoot.svg", "/etc/pelican.svg")
        paths.forEach(handler::openUri)
        assertEquals(paths.map { it to 0 }, openedFiles)
    }

    @Test
    fun workspace_switch_uses_latest_root_without_recreating_handler() {
        val oldRoot = workspaceRoot
        workspaceRoot = "/data/user/0/com.aharou.agent.debug/files/projects/second"
        handler.openUri("$workspaceRoot/pelican.svg")
        handler.openUri("$oldRoot/pelican.svg")
        assertEquals(listOf("~/workspace/pelican.svg" to 0, "$oldRoot/pelican.svg" to 0), openedFiles)
    }

    @Test
    fun workspace_root_with_trailing_slash_maps_files_and_directory() {
        workspaceRoot = "$workspaceRoot/"
        handler.openUri("${workspaceRoot}pelican.svg")
        handler.openUri(workspaceRoot!!.trimEnd('/'))
        assertEquals(listOf("~/workspace/pelican.svg" to 0, "~/workspace" to 0), openedFiles)
    }

    @Test
    fun missing_or_root_workspace_does_not_rewrite_absolute_paths() {
        listOf(null, "", "/").forEach { root ->
            workspaceRoot = root
            handler.openUri("/etc/pelican.svg")
        }
        assertEquals(List(3) { "/etc/pelican.svg" to 0 }, openedFiles)
    }

    @Test
    fun remote_workspace_absolute_svg_link_keeps_same_file_target() {
        workspaceRoot = "/srv/project"
        handler.openUri("/srv/project/pelican.svg:8")
        assertEquals(listOf("~/workspace/pelican.svg" to 8), openedFiles)
        assertTrue(openedUrls.isEmpty())
        assertTrue(htmlPaths.isEmpty())
    }

    @Test
    fun web_svg_and_external_schemes_keep_their_handlers() {
        handler.openUri("https://example.com/pelican.svg")
        handler.openUri("mailto:dev@example.com")
        assertEquals(listOf("https://example.com/pelican.svg"), openedUrls)
        assertEquals(listOf("mailto:dev@example.com"), externalUris)
        assertTrue(openedFiles.isEmpty())
        assertTrue(htmlPaths.isEmpty())
    }

    @Test
    fun workspace_html_link_keeps_browser_preview_and_editor_fallback() {
        handler.openUri("$workspaceRoot/index.html")
        htmlPreviewUrl = null
        handler.openUri("$workspaceRoot/index.html:12")
        assertEquals(listOf("~/workspace/index.html", "~/workspace/index.html"), htmlPaths)
        assertEquals(listOf("file:///tmp/preview.html"), openedUrls)
        assertEquals(listOf("~/workspace/index.html" to 12), openedFiles)
    }
}
