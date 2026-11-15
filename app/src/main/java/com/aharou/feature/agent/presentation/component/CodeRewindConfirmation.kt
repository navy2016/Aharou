package com.aharou.feature.agent.presentation.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aharou.R

internal data class CodeRewindRequest(
    val sessionId: String,
    val messageId: String,
    val timestamp: Long,
    val option: RewindOption,
    val fileCount: Int,
) {
    fun belongsTo(currentSessionId: String?) = sessionId == currentSessionId
}

@Composable
internal fun CodeRewindConfirmation(
    request: CodeRewindRequest,
    onDismiss: () -> Unit,
    onConfirm: (CodeRewindRequest) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.checkpoint_confirm_rewind_title)) },
        text = {
            Text(stringResource(R.string.checkpoint_confirm_rewind_body,
                formatRewindTime(request.timestamp), request.fileCount))
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(request) }) {
                Text(stringResource(R.string.checkpoint_confirm_rewind_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
