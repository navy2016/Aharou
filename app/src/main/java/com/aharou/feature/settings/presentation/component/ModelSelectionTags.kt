package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.R
import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.agent.presentation.component.reasoningEffortLabel
import com.aharou.feature.settings.domain.model.ModelMetadata
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowDown
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.Zap

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelSelectionTags(
    metadata: ModelMetadata?,
    effort: ReasoningEffort?,
    onReasoningClick: (() -> Unit)?,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (metadata?.supportsVision == true) SelectionTag("Image", highlight = true)
        if (metadata?.supportsTools == true) SelectionTag("Tools")
        val input = metadata?.inputTokens?.takeIf { it > 0 } ?: metadata?.contextTokens?.takeIf { it > 0 }
        if (input != null) SelectionTag(formatLimit(input), icon = FeatherIcons.ArrowUp)
        metadata?.outputTokens?.takeIf { it > 0 }?.let { SelectionTag(formatLimit(it), icon = FeatherIcons.ArrowDown) }
        if (effort != null && onReasoningClick != null) {
            SelectionTag(
                stringResource(R.string.model_reasoning_effort_tag, reasoningEffortLabel(effort)),
                icon = FeatherIcons.Zap,
                onClick = onReasoningClick,
            )
        }
    }
}

@Composable
private fun SelectionTag(text: String, highlight: Boolean = false, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    val bg = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg)
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = foreground)
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = foreground, maxLines = 1)
    }
}

private fun formatLimit(tokens: Int): String = when {
    tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
    tokens >= 1_000_000 -> "${tokens / 1_000_000.0}".trimDecimal() + "M"
    tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}K"
    tokens >= 1_000 -> "${tokens / 1_000.0}".trimDecimal() + "K"
    else -> tokens.toString()
}
