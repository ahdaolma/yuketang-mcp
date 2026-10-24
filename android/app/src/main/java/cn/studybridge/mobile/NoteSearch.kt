package cn.studybridge.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.Color

val LocalNoteSearchQuery = staticCompositionLocalOf { "" }
data class NoteSearchTarget(val offset: Int, val query: String, val revision: Int)
val LocalNoteSearchTarget = staticCompositionLocalOf<NoteSearchTarget?> { null }
val LocalNoteSearchReveal = staticCompositionLocalOf<((android.widget.TextView, Int, Int) -> Unit)?> { null }
fun searchHighlight(text: CharSequence, query: String): CharSequence {
    if (query.isBlank()) return text
    val result = SpannableStringBuilder(text)
    val plain = text.toString()
    var start = 0
    while (start < text.length) {
        val found = plain.indexOf(query, start, true); if (found < 0) break
        result.setSpan(BackgroundColorSpan(0xFFFFD45A.toInt()), found, found + query.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        start = found + query.length
    }
    return result
}
fun searchSnippet(text: String, query: String): AnnotatedString {
    val builder = AnnotatedString.Builder(text)
    if (query.isNotBlank()) { var start = 0; while (start < text.length) { val found = text.indexOf(query, start, true); if (found < 0) break; builder.addStyle(SpanStyle(background = Color(0xFFFFD45A), color = Color.Black), found, found + query.length); start = found + query.length } }
    return builder.toAnnotatedString()
}

data class NoteSearchHit(val segment: Int, val offset: Int, val snippet: String)
private class NoteSearchResults(private val content: String, private val wordLength: Int, private val segments: List<NoteReadingSegment>, private val offsets: IntArray) : AbstractList<NoteSearchHit>() {
    override val size get() = offsets.size
    override fun get(index: Int): NoteSearchHit {
        val found = offsets[index]
        var low = 0; var high = segments.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (segments[middle].start <= found) low = middle + 1 else high = middle
        }
        return NoteSearchHit((low - 1).coerceAtLeast(0), found,
            content.substring(maxOf(0, found - 32), minOf(content.length, found + wordLength + 70)).replace('\r', ' ').replace('\n', ' '))
    }
}

internal fun noteSearchHitIndex(hits: List<NoteSearchHit>, offset: Int): Int {
    var low = 0; var high = hits.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (hits[middle].offset < offset) low = middle + 1 else high = middle
    }
    return low.takeIf { it < hits.size && hits[it].offset == offset } ?: -1
}

fun searchNoteContent(content: String, query: String, segments: List<NoteReadingSegment> = noteReadingSegments(content)): List<NoteSearchHit> {
    val word = query.trim(); if (word.isEmpty()) return emptyList()
    // Keep all destinations as compact offsets; only visible list rows and the
    // chosen result allocate snippets. A common word must not create hundreds
    // of thousands of duplicated preview strings or lose later destinations.
    var offsets = IntArray(32)
    var count = 0
    val hidden = noteHiddenSearchCharacters(content)
    var offset = 0
    while (offset < content.length) {
        val found = content.indexOf(word, offset, ignoreCase = true); if (found < 0) break
        offset = found + word.length
        if ((found until offset).any { hidden[it] }) continue
        if (count == offsets.size) offsets = offsets.copyOf(offsets.size * 2)
        offsets[count++] = found
    }
    return NoteSearchResults(content, word.length, segments, offsets.copyOf(count))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NoteSearchDialog(note: Note, onDismiss: () -> Unit, onHit: ((NoteSearchHit, String) -> Unit)? = null, onSelect: (Int) -> Unit) {
    var query by remember(note.id) { mutableStateOf("") }
    val results by produceState<List<NoteSearchHit>?>(emptyList(), note.content, query) {
        value = null
        value = withContext(Dispatchers.Default) { searchNoteContent(note.content, query) }
    }
    StudyModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("笔记内查找", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                FeedbackTextButton(onClick = onDismiss) { Text("关闭") }
            }
            OutlinedTextField(query, { query = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("查找完整正文") }, singleLine = true)
            Text(when { query.isBlank() -> "输入关键词，点击结果定位到正文命中处。"; results == null -> "正在查找…"; results!!.isEmpty() -> "没有匹配的内容"; else -> "找到 ${results!!.size} 处" }, style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = expandedListHeight(360.dp)).testTag("查找结果列表"), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(results.orEmpty(), key = { it.offset }) { hit ->
                    StudyChoiceRow(onClick = { if (onHit == null) onSelect(hit.segment) else onHit(hit, query.trim()) }) {
                        Column(Modifier.fillMaxWidth()) { Text("第 ${hit.segment + 1} 段", fontWeight = FontWeight.SemiBold); Text(searchSnippet(hit.snippet, query.trim()), style = MaterialTheme.typography.bodySmall, maxLines = 3) }
                    }
                }
            }
        }
    }
}
