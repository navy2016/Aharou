package com.aharou.feature.agent.presentation.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.feature.agent.domain.checkpoint.CheckpointManager

internal data class CodeRewindRequest(
    val sessionId: String,
    val messageId: String,
    val timestamp: Long,
    val option: RewindOption,
    val checkpointId: String,
    val restoreCount: Int,
    val skipCount: Int,
    val partial: Boolean,
) {
    constructor(sessionId: String, messageId: String, timestamp: Long, option: RewindOption, diff: CheckpointManager.RewindDiff) :
        this(sessionId, messageId, timestamp, option, diff.checkpointId, diff.restoreFileCount, diff.conflictCount, diff.truncated)

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
            Text(stringResource(if (request.partial) R.string.checkpoint_confirm_rewind_partial else R.string.checkpoint_confirm_rewind_body,
                formatRewindTime(request.timestamp), request.restoreCount, request.skipCount))
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
