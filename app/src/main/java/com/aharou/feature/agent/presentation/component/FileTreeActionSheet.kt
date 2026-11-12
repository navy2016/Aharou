package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.agent.presentation.BrowseClipboard
import com.aharou.feature.agent.presentation.FileTreeNode
import com.aharou.feature.agent.presentation.displayName
import compose.icons.FeatherIcons
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.FolderPlus
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Share2
import compose.icons.feathericons.Trash2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileTreeActionSheet(
    node: FileTreeNode,
    clipboard: BrowseClipboard?,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onAddToInput: () -> Unit,
    onShare: () -> Unit,
    onPasteHere: () -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Spacing.xl)) {
            Text(
                text = node.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.lg).padding(bottom = Spacing.md),
            )
            if (clipboard != null && node.entry.isDirectory) {
                SheetActionRow(
                    icon = FeatherIcons.Clipboard,
                    label = stringResource(R.string.file_browser_paste_here),
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onPasteHere,
                )
            }
            SheetActionRow(
                icon = FeatherIcons.Share2,
                label = stringResource(R.string.filepreview_share),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onShare,
            )
            if (node.entry.isDirectory) {
                SheetActionRow(
                    icon = FeatherIcons.FilePlus,
                    label = stringResource(R.string.file_browser_new_file),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onNewFile,
                )
                SheetActionRow(
                    icon = FeatherIcons.FolderPlus,
                    label = stringResource(R.string.file_browser_new_folder),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onNewFolder,
                )
            }
            if (node.root == null) {
                SheetActionRow(
                    icon = FeatherIcons.MessageSquare,
                    label = stringResource(R.string.common_add_to_input),
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onAddToInput,
                )
                SheetActionRow(
                    icon = FeatherIcons.Copy,
                    label = stringResource(R.string.common_copy),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onCopy,
                )
                SheetActionRow(
                    icon = Icons.Filled.ContentCut,
                    label = stringResource(R.string.common_cut),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onCut,
                )
                SheetActionRow(
                    icon = FeatherIcons.Edit2,
                    label = stringResource(R.string.common_rename),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onRename,
                )
                SheetActionRow(
                    icon = FeatherIcons.Trash2,
                    label = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
        }
    }
}
