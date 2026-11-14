package com.aharou.feature.editor.presentation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.editor.domain.EditorSessionManager
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.flow.collect

@Composable
internal fun ObserveEditorFileChanges(
    viewModel: CodeEditorViewModel,
    identity: String,
    path: String,
    documentVersion: Int,
    editor: CodeEditor?,
    baseline: MutableState<String>,
    dirty: Boolean,
    saving: Boolean
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, identity, path, documentVersion, editor, dirty, saving, lifecycle) {
        if (editor == null || saving) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.fileChanges(path).collect {
                viewModel.refresh(path, documentVersion, { editor.snapshot(baseline.value) }) { content ->
                    baseline.value = content
                    editor.replaceFromDisk(content)
                }
            }
        }
    }
}

@Composable
internal fun EditorExternalChangeNotice(viewModel: CodeEditorViewModel) {
    val change by viewModel.externalChange.collectAsStateWithLifecycle()
    change?.let {
        Text(
            text = stringResource(if (it == EditorExternalChange.CHANGED) R.string.editor_external_change
                else R.string.editor_external_unavailable),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs)
        )
    }
}

internal fun CodeEditor.snapshot(baseline: String): EditorSessionManager.Snapshot {
    val content = text.toString()
    return EditorSessionManager.Snapshot(content, content != baseline,
        cursor.leftLine + 1, cursor.leftColumn + 1, scrollY, baseline)
}

private fun CodeEditor.replaceFromDisk(content: String) {
    val leftLine = cursor.leftLine
    val leftColumn = cursor.leftColumn
    val rightLine = cursor.rightLine
    val rightColumn = cursor.rightColumn
    val x = offsetX
    val y = offsetY
    setText(content)
    val lastLine = lineCount - 1
    val left = leftLine.coerceAtMost(lastLine)
    val right = rightLine.coerceAtMost(lastLine)
    val startColumn = leftColumn.coerceAtMost(text.getColumnCount(left))
    val endColumn = rightColumn.coerceAtMost(text.getColumnCount(right))
        .coerceAtLeast(if (left == right) startColumn else 0)
    setSelectionRegion(left, startColumn, right, endColumn)
    scroller.startScroll(offsetX, offsetY, x.coerceIn(0, scrollMaxX.coerceAtLeast(0)) - offsetX,
        y.coerceIn(0, scrollMaxY.coerceAtLeast(0)) - offsetY, 0)
    scroller.abortAnimation()
    invalidate()
}
