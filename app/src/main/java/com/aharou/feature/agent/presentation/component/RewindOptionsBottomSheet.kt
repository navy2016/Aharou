package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.aharou.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.domain.checkpoint.CheckpointManager
import com.aharou.feature.agent.presentation.RewindMenuTarget
import compose.icons.FeatherIcons
import compose.icons.feathericons.FileText
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.RotateCcw

enum class RewindOption {
    RESTORE_CODE_AND_CONVERSATION,
    RESTORE_CONVERSATION,
    RESTORE_CODE,

    /** 撤销最近一次代码恢复，把当时的现场写回去。 */
    UNDO_LAST_RESTORE;

    val requiresCheckpoint: Boolean get() = this == RESTORE_CODE || this == RESTORE_CODE_AND_CONVERSATION
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RewindOptionsBottomSheet(
    promptSnippet: String,
    undoRecordCount: Int,
    target: RewindMenuTarget,
    loadDiff: suspend (RewindMenuTarget) -> CheckpointManager.RewindDiff?,
    onShowTimeline: () -> Unit,
    onOptionSelected: (RewindOption, CheckpointManager.RewindDiff?) -> Unit,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var rewindDiff by remember(target) { mutableStateOf<CheckpointManager.RewindDiff?>(null) }
    var loading by remember(target) { mutableStateOf(true) }
    LaunchedEffect(target) {
        rewindDiff = loadDiff(target)
        loading = false
    }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.checkpoint_rewind_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            if (promptSnippet.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.checkpoint_target_prompt, promptSnippet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            if (rewindDiff == null) Text(stringResource(if (loading) R.string.checkpoint_preview_loading else R.string.checkpoint_preview_unavailable))
            rewindDiff?.let { diff ->
                if (diff.files.isNotEmpty() || diff.truncated) {
                    RewindDiffPreview(
                        diff = diff,
                        texts = RewindDiffPreviewTexts(
                            summary = stringResource(if (diff.truncated) R.string.checkpoint_rewind_diff_partial else R.string.checkpoint_rewind_diff_summary, diff.restoreFileCount, diff.changedFileCount),
                            conflictNotice = stringResource(R.string.checkpoint_rewind_diff_conflict, diff.conflictCount),
                            truncatedNotice = stringResource(R.string.checkpoint_rewind_diff_truncated),
                            conflictBadge = stringResource(R.string.checkpoint_rewind_diff_conflict_badge),
                            unchanged = stringResource(R.string.checkpoint_rewind_diff_unchanged),
                            expand = stringResource(R.string.checkpoint_rewind_diff_expand),
                            collapse = stringResource(R.string.checkpoint_rewind_diff_collapse)
                        )
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                }
            }

            OptionRow(
                icon = FeatherIcons.RotateCcw,
                title = stringResource(R.string.checkpoint_restore_code_and_conversation),
                description = stringResource(R.string.checkpoint_restore_code_and_conversation_desc),
                enabled = rewindDiff != null,
                onClick = {
                    onOptionSelected(RewindOption.RESTORE_CODE_AND_CONVERSATION, rewindDiff)
                    onDismissRequest()
                }
            )

            OptionRow(
                icon = FeatherIcons.MessageSquare,
                title = stringResource(R.string.checkpoint_restore_conversation),
                description = stringResource(R.string.checkpoint_restore_conversation_desc),
                onClick = {
                    onOptionSelected(RewindOption.RESTORE_CONVERSATION, null)
                    onDismissRequest()
                }
            )

            OptionRow(
                icon = FeatherIcons.FileText,
                title = stringResource(R.string.checkpoint_restore_code),
                description = stringResource(R.string.checkpoint_restore_code_desc),
                enabled = rewindDiff != null,
                onClick = {
                    onOptionSelected(RewindOption.RESTORE_CODE, rewindDiff)
                    onDismissRequest()
                }
            )

            OptionRow(
                icon = FeatherIcons.RotateCcw,
                title = stringResource(R.string.checkpoint_undo_last_restore),
                description = stringResource(if (undoRecordCount > 0) R.string.checkpoint_undo_last_restore_desc else R.string.checkpoint_undo_timeline_empty),
                enabled = undoRecordCount > 0,
                onClick = {
                    onOptionSelected(RewindOption.UNDO_LAST_RESTORE, null)
                    onDismissRequest()
                }
            )

            OptionRow(
                icon = FeatherIcons.RotateCcw,
                title = stringResource(R.string.checkpoint_undo_timeline_title),
                description = if (undoRecordCount > 0) {
                    stringResource(R.string.checkpoint_undo_timeline_desc, undoRecordCount)
                } else {
                    stringResource(R.string.checkpoint_undo_timeline_empty)
                },
                onClick = {
                    onShowTimeline()
                    onDismissRequest()
                }
            )

            Spacer(modifier = Modifier.height(Spacing.lg))
        }
    }
}

/** 回退确认里展示的回退点时间；时间戳不可用时回退为空串。 */
internal fun formatRewindTime(millis: Long): String =
    if (millis <= 0L) ""
    else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(millis))

@Composable
private fun OptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = Spacing.xs, horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = Spacing.sm)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
