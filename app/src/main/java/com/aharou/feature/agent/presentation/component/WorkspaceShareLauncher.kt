package com.aharou.feature.agent.presentation.component

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.aharou.R
import java.io.File

internal fun launchWorkspaceShare(context: Context, result: Result<File>) {
    val shared = result.getOrNull()
    if (shared == null) {
        Toast.makeText(context, context.getString(R.string.file_share_failed_toast, result.exceptionOrNull()?.message.orEmpty()), Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shared)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(shared.extension.lowercase()) ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, shared.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.file_share_chooser_title)))
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.file_share_failed_toast, e.message.orEmpty()), Toast.LENGTH_SHORT).show()
    }
}
