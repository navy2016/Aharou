package com.aharou.feature.editor.presentation

import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.SizeLimitedInputStream
import com.aharou.feature.workspace.domain.formatSha256
import java.security.DigestInputStream
import java.security.MessageDigest

internal data class EditorFileContent(val text: String, val fingerprint: String)

internal fun readEditorFile(access: FileAccessProvider, path: String, maxBytes: Long? = null): EditorFileContent? {
    val limit = maxBytes?.let { minOf(it, EDITOR_MAX_FILE_BYTES) }
    val file = if (limit != null) access.copyToLocal(path, limit) else access.copyToLocal(path)
    val source = if (limit != null) SizeLimitedInputStream(file.inputStream(), limit) else file.inputStream()
    val digest = MessageDigest.getInstance("SHA-256")
    return DigestInputStream(source, digest).buffered().use { input ->
        input.mark(BINARY_SNIFF_BYTES)
        val head = ByteArray(BINARY_SNIFF_BYTES)
        var read = 0
        while (read < head.size) {
            val count = input.read(head, read, head.size - read)
            if (count < 0) break
            if (count > 0) read += count
        }
        if ((0 until read).any { head[it] == 0.toByte() }) return@use null
        input.reset()
        val text = input.reader(Charsets.UTF_8).readText()
        EditorFileContent(text, formatSha256(digest.digest()))
    }
}

internal const val EDITOR_LARGE_FILE_WARNING_BYTES = 20L * 1024 * 1024
internal const val EDITOR_MAX_FILE_BYTES = 100L * 1024 * 1024
private const val BINARY_SNIFF_BYTES = 8000
