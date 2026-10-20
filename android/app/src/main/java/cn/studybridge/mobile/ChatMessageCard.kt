package cn.studybridge.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable fun ChatMessageCard(message: Message, identity: String, provider: String, streaming: Boolean) {
    val user = message.role == "user"
    val reference = user && (message.text.length > 900 || message.text.startsWith("用户选择的笔记原文："))
    var expanded by rememberSaveable(identity) { mutableStateOf(false) }
    val text = message.text.removePrefix("用户选择的笔记原文：\n")
    Surface(
        color = when { reference -> MaterialTheme.colorScheme.surfaceContainerLow; user -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f); else -> Color.Transparent },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(start = if (user && !reference) 28.dp else 0.dp)
    ) {
        Column(Modifier.padding(horizontal = if (user) 14.dp else 4.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (reference && (message.source != null || message.text.startsWith("用户选择的笔记原文："))) "参考笔记" else if (user) "你" else provider, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            message.source?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            message.image?.let { DataImage(it, message.source ?: "课件") }
            if (reference && !expanded) Text(remember(text) { notePreview(text).take(600) }, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            else if (streaming) Text(text.ifBlank { "正在思考…" }, style = MaterialTheme.typography.bodyLarge)
            else Markdown(text.ifBlank { "正在思考…" })
            if (reference) FeedbackTextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起原文" else "展开原文") }
        }
    }
}
