package com.aharou.feature.workspace.domain

import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun prepareWorkspaceShare(access: FileAccessProvider, path: String, cacheDir: File): File {
    val name = path.trimEnd('/').substringAfterLast('/').ifEmpty { "folder" }
    if (!isValidFileEntryName(name)) throw IOException("Invalid file name")
    val directory = File(cacheDir, "workspace_share/${UUID.randomUUID()}")
    if (!directory.mkdirs()) throw IOException("Could not create share cache")
    val isDirectory = access.isDirectory(path)
    val target = File(directory, if (isDirectory) "$name.zip" else name)
    try {
        if (isDirectory) {
            target.outputStream().buffered().use { out ->
                ZipOutputStream(out).use { zip ->
                    addDirectoryToShare(access, path, name, zip)
                }
            }
        } else {
            access.copyToLocal(path).inputStream().buffered().use { input ->
                target.outputStream().buffered().use { output -> input.copyTo(output) }
            }
        }
        return target
    } catch (e: Exception) {
        target.delete()
        directory.delete()
        throw e
    }
}

private fun addDirectoryToShare(access: FileAccessProvider, path: String, relative: String, zip: ZipOutputStream) {
    zip.putNextEntry(ZipEntry("$relative/"))
    zip.closeEntry()
    for (entry in access.listFiles(path)) {
        if (entry.isSymbolicLink) continue
        if (!isValidFileEntryName(entry.name)) throw IOException("Invalid directory entry name")
        val child = path.trimEnd('/') + "/" + entry.name
        val childRelative = "$relative/${entry.name}"
        if (!entry.isDirectory && !access.isFile(child)) continue
        if (entry.isDirectory) {
            addDirectoryToShare(access, child, childRelative, zip)
        } else {
            val file = access.copyToLocal(child)
            try {
                zip.putNextEntry(ZipEntry(childRelative))
                file.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            } finally {
                // 远程条目没有宿主文件，copyToLocal 产物是一次性下载副本。
                if (entry.localFile == null) file.delete()
            }
        }
    }
}
